package com.dartwatch.discord;

import com.dartwatch.notification.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DiscordWebhookTests {
    // Deliberately fake, intercepted by MockRestServiceServer; no real webhook is loaded.
    private static final String URL = "https://discord.com/api/webhooks/123456789012345678/mock_only_not_a_real_token";
    private static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    private final DiscordProperties properties = new DiscordProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = mock(Clock.class);
    private MockRestServiceServer server;
    private DiscordNotificationSender sender;

    @BeforeEach
    void setup() {
        properties.setEnabled(true);
        properties.setWebhookUrl(URL);
        when(clock.instant()).thenReturn(NOW);
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new DiscordNotificationSender(properties, new DiscordWebhookClient(builder.build(), properties, mapper, clock));
    }

    @Test
    void postsOneEmbedWithWaitTrueAndReturnsConfirmedMessageId() {
        server.expect(requestTo(URL + "?wait=true")).andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(request -> {
                    var body = mapper.readTree(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString());
                    assertThat(body.path("allowed_mentions").path("parse").size()).isZero();
                    assertThat(body.path("embeds").size()).isEqualTo(1);
                    assertThat(body.at("/embeds/0/fields/0/value").asText()).isEqualTo("테스트 기업");
                    assertThat(body.at("/embeds/0/fields/1/value").asText()).isEqualTo("한글 보고서");
                    assertThat(body.at("/embeds/0/fields/2/value").asText()).contains("A", "정기공시");
                    assertThat(body.at("/embeds/0/fields/3/value").asText()).isEqualTo("2026-10-24");
                    assertThat(body.at("/embeds/0/url").asText()).endsWith("rcpNo=20261024000001");
                }).andRespond(withSuccess("{\"id\":\"987654321012345678\"}", MediaType.APPLICATION_JSON));
        var result = sender.send(message());
        assertThat(sender.channel()).isEqualTo("DISCORD");
        assertThat(result.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(result.externalMessageId()).isEqualTo("987654321012345678");
        assertThat(result.retryAfter()).isNull();
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"400,DISCORD_BAD_REQUEST", "401,DISCORD_AUTHENTICATION", "403,DISCORD_AUTHENTICATION",
            "404,DISCORD_WEBHOOK_NOT_FOUND", "422,DISCORD_REJECTED"})
    void permanentRejectionDoesNotRetryOrExposeBody(int status, String error) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)).body("private webhook token"));
        var result = sender.send(message());
        assertThat(result).isEqualTo(NotificationSender.SendResult.failed(error, false));
        assertThat(result.toString()).doesNotContain("private", "mock_only", "https://");
        server.verify();
    }

    @Test
    void rateLimitUsesLongestHeaderOrBodyDelayAndSuppressesCallsUntilDue() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1.25")
                .body("{\"retry_after\":2.501,\"message\":\"private\"}").contentType(MediaType.APPLICATION_JSON));
        var first = sender.send(message());
        assertThat(first.retryable()).isTrue();
        assertThat(first.retryAfter()).isEqualTo(Duration.ofMillis(2501));
        assertThat(first.error()).isEqualTo("DISCORD_RATE_LIMIT");
        when(clock.instant()).thenReturn(NOW.plusMillis(2500));
        assertThat(sender.send(message()).retryAfter()).isEqualTo(Duration.ofMillis(1));
        server.verify();
        server.reset();
        when(clock.instant()).thenReturn(NOW.plusMillis(2501));
        server.expect(anything()).andRespond(withSuccess("{\"id\":\"123\"}", MediaType.APPLICATION_JSON));
        assertThat(sender.send(message()).status()).isEqualTo(NotificationStatus.SENT);
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"3.75,{broken,3750", "invalid,{\"retry_after\":4.125},4125", "-1,{\"retry_after\":-2},60000"})
    void retryAfterHeaderAndBodyHaveSafeFallbacks(String header, String body, long millis) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", header).body(body));
        assertThat(sender.send(message()).retryAfter()).isEqualTo(Duration.ofMillis(millis));
        server.verify();
    }

    @Test
    void supportsRetryAfterHttpDateWithoutBlockingOrSleeping() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "Sat, 24 Oct 2026 03:00:10 GMT"));
        assertThat(sender.send(message()).retryAfter()).isEqualTo(Duration.ofSeconds(10));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {204, 302, 500, 502, 503})
    void unconfirmedPostResultsAreUnknownAndNeverRetried(int status) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)));
        var result = sender.send(message());
        assertThat(result.status()).isEqualTo(NotificationStatus.UNKNOWN);
        assertThat(result.retryable()).isFalse();
        if (status >= 500) assertThat(result.error()).isEqualTo("DISCORD_SERVER_UNCERTAIN");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{broken", "{}", "null", "{\"id\":0}", "{\"id\":\"0\"}", "{\"id\":\"private-token\"}"})
    void invalidSuccessResponseCannotProveMessageCreation(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.unknown());
        server.verify();
    }

    @Test
    void responseSizeIsBoundedAndOversizedSuccessRemainsUnknown() {
        server.expect(anything()).andRespond(withSuccess("{\"id\":\"123\",\"body\":\"" + "x".repeat(66000) + "\"}", MediaType.APPLICATION_JSON));
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.unknown());
        server.verify();
    }

    @Test
    void readTimeoutDoesNotAuthorizeAnAutomaticResendOrExposeTheRequest() {
        server.expect(anything()).andRespond(request -> { throw new SocketTimeoutException(URL); });
        var result = sender.send(message());
        assertThat(result.status()).isEqualTo(NotificationStatus.UNKNOWN);
        assertThat(result.error()).isEqualTo("DISCORD_TIMEOUT_UNCERTAIN");
        assertThat(result.toString()).doesNotContain("mock_only", "discord.com");
        server.verify();
    }

    @Test
    void provenConnectionFailuresCanBeRetriedByTheCaller() {
        server.expect(anything()).andRespond(request -> { throw new ConnectException(URL); });
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.failed("CONNECTION_FAILED", true));
        server.verify();
        server.reset();
        server.expect(anything()).andRespond(request -> { throw new HttpConnectTimeoutException(URL); });
        assertThat(sender.send(message()).retryable()).isTrue();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not a uri", "http://discord.com/api/webhooks/1/fake", "https://example.invalid/api/webhooks/1/fake",
            "https://discord.com.evil.invalid/api/webhooks/1/fake", "https://discord.com@localhost/api/webhooks/1/fake",
            "https://discord.com/api/webhooks/1/fake?wait=false", "https://discord.com:8443/api/webhooks/1/fake",
            "https://discord.com/api/webhooks/1/fake#fragment", "https://discord.com/api/webhooks/1/%2Ffake"})
    void badConfigurationFailsWithoutAnyRequest(String url) {
        properties.setWebhookUrl(url);
        assertThat(sender.send(message())).isEqualTo(NotificationSender.SendResult.failed("CONFIGURATION", false));
        server.verify();
    }

    @Test
    void disabledAndInvalidMessageNeverInvokeHttp() {
        properties.setEnabled(false);
        assertThat(sender.send(message()).error()).isEqualTo("DISCORD_DISABLED");
        properties.setEnabled(true);
        assertThat(sender.send(new NotificationSender.Message(null, "Test", "Report", "A", LocalDate.now(), "invalid")).error())
                .isEqualTo("COMPOSITION_FAILED");
        server.verify();
    }

    @Test
    void truncatesExternalTextWithoutSplittingUnicodeAndDisablesAllMentions() throws Exception {
        var input = new NotificationSender.Message(null, "@everyone" + "😀".repeat(500), "@here <@&123> " + "보고서".repeat(2000),
                "B", LocalDate.of(2026, 10, 24), "20261024000001");
        var payload = mapper.valueToTree(DiscordMessage.create(input));
        var embed = payload.at("/embeds/0");
        assertThat(embed.path("title").asText().length()).isLessThanOrEqualTo(256);
        int length = embed.path("title").asText().length();
        for (var field : embed.path("fields")) {
            assertThat(field.path("value").asText().length()).isLessThanOrEqualTo(1024);
            length += field.path("name").asText().length() + field.path("value").asText().length();
        }
        assertThat(length).isLessThan(6000);
        assertThat(payload.at("/allowed_mentions/parse").size()).isZero();
        assertThat(payload.toString()).doesNotContain("@everyone", "@here", "<@&123>");
        String company = embed.at("/fields/0/value").asText();
        assertThat(company.codePoints().anyMatch(cp -> cp >= 0xD800 && cp <= 0xDFFF)).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"A,정기공시", "B,주요사항보고", "C,발행공시", "D,지분공시", "E,기타공시"})
    void mapsAllSupportedOfficialTypeLabels(String code, String label) {
        var input = new NotificationSender.Message(null, "기업", "공시", code, LocalDate.now(), "20261024000001");
        assertThat(mapper.valueToTree(DiscordMessage.create(input)).at("/embeds/0/fields/2/value").asText()).contains(code, label);
    }

    @Test
    void defaultsAndLegacyEmailResultFactoriesRemainCompatible() {
        assertThat(new DiscordProperties().isEnabled()).isFalse();
        assertThat(NotificationSender.SendResult.sent()).isEqualTo(new NotificationSender.SendResult(NotificationStatus.SENT, null, false));
        assertThatThrownBy(() -> properties.setConnectTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties.setReadTimeout(Duration.ofMinutes(2))).isInstanceOf(IllegalArgumentException.class);
    }

    private NotificationSender.Message message() {
        return new NotificationSender.Message(null, "테스트 기업", "한글 보고서", "A", LocalDate.of(2026, 10, 24), "20261024000001");
    }
}
