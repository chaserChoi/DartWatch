package com.dartwatch.discord;

import com.dartwatch.notification.NotificationSender.SendResult;
import com.dartwatch.notification.NotificationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

public class DiscordWebhookClient {
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final RestClient http;
    private final DiscordProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private Instant blockedUntil = Instant.MIN;

    public DiscordWebhookClient(RestClient http, DiscordProperties properties, ObjectMapper mapper, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** One attempt only. Callers own retry counts; this client only enforces a 429 not-before time. */
    public synchronized SendResult execute(Map<String, Object> payload) {
        if (!properties.isEnabled()) return SendResult.failed("DISCORD_DISABLED", false);
        URI endpoint = endpoint();
        if (endpoint == null) return SendResult.failed("CONFIGURATION", false);
        Instant now = clock.instant();
        if (now.isBefore(blockedUntil)) return rateLimited(Duration.between(now, blockedUntil));
        try {
            return http.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(payload).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            Duration delay = retryDelay(response.getHeaders().getFirst("Retry-After"), response.getBody());
                            blockedUntil = clock.instant().plus(delay);
                            return rateLimited(delay);
                        }
                        if (status == 400) return SendResult.failed("DISCORD_BAD_REQUEST", false);
                        if (status == 401 || status == 403) return SendResult.failed("DISCORD_AUTHENTICATION", false);
                        if (status == 404) return SendResult.failed("DISCORD_WEBHOOK_NOT_FOUND", false);
                        if (status >= 400 && status < 500) return SendResult.failed("DISCORD_REJECTED", false);
                        if (status >= 500) return new SendResult(NotificationStatus.UNKNOWN, "DISCORD_SERVER_UNCERTAIN", false);
                        // A POST may have been committed before a 5xx, redirect, empty or malformed reply.
                        if (status != 200) return SendResult.unknown();
                        byte[] body = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                        if (body.length == 0 || body.length > MAX_RESPONSE_BYTES) return SendResult.unknown();
                        var json = mapper.readTree(body);
                        var id = json == null ? null : json.get("id");
                        if (id == null || !id.isTextual() || !id.asText().matches("[1-9][0-9]{0,19}")) return SendResult.unknown();
                        return new SendResult(NotificationStatus.SENT, null, false, id.asText(), null);
                    });
        } catch (ResourceAccessException ex) {
            Throwable cause = ex;
            boolean timeout = false;
            for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
                if (cause instanceof ConnectException || cause instanceof UnknownHostException || cause instanceof HttpConnectTimeoutException)
                    return SendResult.failed("CONNECTION_FAILED", true);
                timeout |= cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException;
            }
            return timeout ? new SendResult(NotificationStatus.UNKNOWN, "DISCORD_TIMEOUT_UNCERTAIN", false) : SendResult.unknown();
        } catch (RuntimeException ex) {
            // Never attach or log the original exception: the URI path contains the webhook token.
            return SendResult.unknown();
        }
    }

    public String targetIdentifier() {
        URI uri = endpoint();
        if (uri == null) return null;
        try {
            return "discord:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(uri.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("Digest unavailable"); }
    }

    private URI endpoint() {
        try {
            String value = properties.getWebhookUrl();
            if (value == null || !value.equals(value.strip())) return null;
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !("discord.com".equalsIgnoreCase(uri.getHost()) || "discordapp.com".equalsIgnoreCase(uri.getHost()))
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/api/(?:v10/)?webhooks/[1-9][0-9]{0,19}/[A-Za-z0-9_-]{1,200}")) return null;
            return URI.create(value + "?wait=true");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private Duration retryDelay(String header, java.io.InputStream body) {
        Duration delay = seconds(header);
        if (delay == null && header != null) {
            try {
                Duration dated = Duration.between(clock.instant(), ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                if (!dated.isNegative() && !dated.isZero()) delay = dated;
            } catch (RuntimeException ignored) { }
        }
        try {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length <= MAX_RESPONSE_BYTES) {
                var json = mapper.readTree(bytes);
                var retry = json == null ? null : json.get("retry_after");
                Duration fromBody = retry != null && retry.isNumber() ? seconds(retry.asText()) : null;
                if (fromBody != null && (delay == null || fromBody.compareTo(delay) > 0)) delay = fromBody;
            }
        } catch (IOException | RuntimeException ignored) { }
        return delay == null ? Duration.ofMinutes(1) : delay;
    }

    private static Duration seconds(String value) {
        try {
            var seconds = new BigDecimal(value);
            if (seconds.signum() < 0) return null;
            return Duration.ofMillis(Math.max(1, seconds.movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact()));
        } catch (RuntimeException ex) { return null; }
    }

    private static SendResult rateLimited(Duration delay) {
        return new SendResult(NotificationStatus.FAILED, "DISCORD_RATE_LIMIT", true, null, delay);
    }
}
