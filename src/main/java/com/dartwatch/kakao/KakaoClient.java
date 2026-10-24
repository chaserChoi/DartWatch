package com.dartwatch.kakao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import static com.dartwatch.kakao.KakaoFailure.Kind.*;

public class KakaoClient {
    private static final int MAX_BYTES = 64 * 1024;
    private final RestClient http;
    private final ObjectMapper mapper;
    private final KakaoProperties properties;
    private final Clock clock;
    private Instant tokenBlocked = Instant.MIN, messageBlocked = Instant.MIN;
    public KakaoClient(RestClient http, ObjectMapper mapper, KakaoProperties properties, Clock clock) {
        this.http = http; this.mapper = mapper; this.properties = properties; this.clock = clock;
    }
    public synchronized Tokens exchange(String code) {
        var form = baseForm("authorization_code");
        if (code == null || code.isBlank() || code.length() > 4096) throw new KakaoFailure(CODE_REJECTED);
        form.add("code", code);
        form.add("redirect_uri", properties.getRedirectUri());
        return tokens(form, false);
    }
    public synchronized Tokens refresh(String refresh) {
        var form = baseForm("refresh_token"); form.add("refresh_token", refresh);
        return tokens(form, true);
    }
    private Tokens tokens(MultiValueMap<String, String> form, boolean refresh) {
        check(tokenBlocked);
        try {
            return http.post().uri("https://kauth.kakao.com/oauth/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            Duration delay = delay(response.getHeaders().getFirst("Retry-After"));
                            tokenBlocked = clock.instant().plus(delay); throw new KakaoFailure(RATE_LIMIT, delay);
                        }
                        if (status >= 500) throw new KakaoFailure(SERVER);
                        JsonNode json = read(response.getBody());
                        if ("KOE237".equals(json.path("error_code").asText())) {
                            Duration delay = delay(response.getHeaders().getFirst("Retry-After"));
                            tokenBlocked = clock.instant().plus(delay); throw new KakaoFailure(RATE_LIMIT, delay);
                        }
                        if (status != 200) {
                            if ("invalid_grant".equals(json.path("error").asText()))
                                throw new KakaoFailure(refresh ? RECONNECT_REQUIRED : CODE_REJECTED);
                            throw new KakaoFailure(CONFIGURATION);
                        }
                        String access = text(json, "access_token");
                        if (!"bearer".equalsIgnoreCase(json.path("token_type").asText())) throw new KakaoFailure(INVALID_RESPONSE);
                        Duration accessTtl = ttl(json, "expires_in");
                        String renewed = json.hasNonNull("refresh_token") ? text(json, "refresh_token") : null;
                        Duration refreshTtl = renewed != null ? ttl(json, "refresh_token_expires_in") : null;
                        if (!refresh && renewed == null) throw new KakaoFailure(INVALID_RESPONSE);
                        String scope = json.hasNonNull("scope") ? json.path("scope").asText() : null;
                        if (scope != null && !java.util.Arrays.asList(scope.split("\\s+")).contains("talk_message")) throw new KakaoFailure(PERMISSION);
                        return new Tokens(access, renewed, accessTtl, refreshTtl);
                    });
        } catch (KakaoFailure ex) { throw ex; }
        catch (RuntimeException ex) { throw new KakaoFailure(TRANSPORT); }
    }
    public synchronized void send(String access, String template) {
        if (!properties.isEnabled()) throw new KakaoFailure(CONFIGURATION);
        check(messageBlocked);
        var form = new LinkedMultiValueMap<String, String>(); form.add("template_object", template);
        try {
            http.post().uri("https://kapi.kakao.com/v2/api/talk/memo/default/send").headers(h -> h.setBearerAuth(access))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 429) {
                            Duration wait = delay(response.getHeaders().getFirst("Retry-After"));
                            messageBlocked = clock.instant().plus(wait); throw new KakaoFailure(RATE_LIMIT, wait);
                        }
                        if (status >= 500) throw new KakaoFailure(SERVER);
                        JsonNode json = read(response.getBody());
                        int code = json.path("code").isIntegralNumber() ? json.path("code").asInt() : 0;
                        if (code == -10) {
                            Duration wait = delay(response.getHeaders().getFirst("Retry-After"));
                            messageBlocked = clock.instant().plus(wait); throw new KakaoFailure(RATE_LIMIT, wait);
                        }
                        if (code == -401 && status == 401) throw new KakaoFailure(INVALID_TOKEN);
                        if (status == 403 || code == -402 || code == -3 || code == -5) throw new KakaoFailure(PERMISSION);
                        if (status == 401) throw new KakaoFailure(RECONNECT_REQUIRED);
                        if (status >= 400 && status < 500 && code != -603 && code != -7) throw new KakaoFailure(CODE_REJECTED);
                        if (status != 200 || !json.path("result_code").isIntegralNumber() || json.path("result_code").asInt() != 0)
                            throw new KakaoFailure(INVALID_RESPONSE);
                        return null;
                    });
        } catch (KakaoFailure ex) { throw ex; }
        catch (RuntimeException ex) { throw new KakaoFailure(TRANSPORT); }
    }
    private MultiValueMap<String, String> baseForm(String grant) {
        if (!properties.isEnabled() || properties.getRestApiKey() == null || properties.getRestApiKey().isBlank()) throw new KakaoFailure(CONFIGURATION);
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", grant); form.add("client_id", properties.getRestApiKey());
        if (properties.getClientSecret() != null && !properties.getClientSecret().isBlank()) form.add("client_secret", properties.getClientSecret());
        return form;
    }
    private JsonNode read(java.io.InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new KakaoFailure(INVALID_RESPONSE);
        try {
            var json = mapper.readTree(bytes);
            if (json == null || !json.isObject()) throw new KakaoFailure(INVALID_RESPONSE);
            return json;
        } catch (IOException ex) { throw new KakaoFailure(INVALID_RESPONSE); }
    }
    private static String text(JsonNode json, String name) {
        var value = json.path(name);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 8192
                || value.asText().chars().anyMatch(Character::isISOControl)) throw new KakaoFailure(INVALID_RESPONSE);
        return value.asText();
    }
    private static Duration ttl(JsonNode json, String name) {
        if (!json.path(name).isIntegralNumber()) throw new KakaoFailure(INVALID_RESPONSE);
        long value = json.path(name).asLong();
        if (value <= 0 || value > 315360000) throw new KakaoFailure(INVALID_RESPONSE);
        return Duration.ofSeconds(value);
    }
    private void check(Instant blocked) {
        Instant now = clock.instant();
        if (now.isBefore(blocked)) throw new KakaoFailure(RATE_LIMIT, Duration.between(now, blocked));
    }
    private static Duration delay(String value) {
        try {
            long millis = new java.math.BigDecimal(value).movePointRight(3)
                    .setScale(0, java.math.RoundingMode.CEILING).longValueExact();
            return millis > 0 ? Duration.ofMillis(millis) : Duration.ofDays(1);
        }
        catch (RuntimeException ex) { return Duration.ofDays(1); }
    }
    public record Tokens(String access, String refresh, Duration accessTtl, Duration refreshTtl) {
        @Override public String toString() { return "KakaoTokens[redacted]"; }
    }
}
