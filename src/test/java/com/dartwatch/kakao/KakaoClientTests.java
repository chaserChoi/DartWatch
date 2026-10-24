package com.dartwatch.kakao;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import static com.dartwatch.kakao.KakaoFixtures.*;
import static com.dartwatch.kakao.KakaoFailure.Kind.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class KakaoClientTests {
    private final KakaoProperties p = properties();
    private final Clock clock = mock(Clock.class);
    private MockRestServiceServer server;
    private KakaoClient client;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new KakaoClient(builder.build(), new ObjectMapper(), p, clock);
    }
    @AfterEach void verifyRequests() { server.verify(); }
    @Test void codeExchangeUsesOfficialFormAndMapsExpiries() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "authorization_code"); form.add("client_id", "mock-client-id");
        form.add("client_secret", "mock-client-secret"); form.add("code", "mock-code"); form.add("redirect_uri", p.getRedirectUri());
        server.expect(requestTo("https://kauth.kakao.com/oauth/token")).andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist("Authorization")).andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().formData(form)).andRespond(withSuccess("""
                        {"token_type":"bearer","access_token":"mock-access","expires_in":21600,
                        "refresh_token":"mock-refresh","refresh_token_expires_in":5184000,"scope":"talk_message"}
                        """, MediaType.APPLICATION_JSON));
        assertThat(client.exchange("mock-code")).isEqualTo(initial());
    }
    @Test void refreshCanOmitNewRefreshTokenAndClientSecret() {
        p.setClientSecret("");
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token"); form.add("client_id", "mock-client-id"); form.add("refresh_token", "mock-refresh");
        server.expect(requestTo("https://kauth.kakao.com/oauth/token")).andExpect(content().formData(form))
                .andRespond(withSuccess("{\"access_token\":\"mock-new\",\"token_type\":\"bearer\",\"expires_in\":100}", MediaType.APPLICATION_JSON));
        var tokens = client.refresh("mock-refresh");
        assertThat(tokens.accessTtl()).isEqualTo(Duration.ofSeconds(100));
        assertThat(tokens.refresh()).isNull();
        assertThat(tokens.toString()).doesNotContain("mock-new");
    }
    @Test void invalidGrantDistinguishesCodeAndRevokedRefresh() {
        server.expect(anything()).andRespond(withBadRequest().body("{\"error\":\"invalid_grant\",\"error_description\":\"private\"}"));
        server.expect(anything()).andRespond(withBadRequest().body("{\"error\":\"invalid_grant\"}"));
        failure(() -> client.exchange("mock-code"), CODE_REJECTED);
        failure(() -> client.refresh("mock-refresh"), RECONNECT_REQUIRED);
    }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "{broken", "{\"access_token\":\"a\",\"token_type\":\"bearer\",\"expires_in\":0}",
            "{\"access_token\":\"a\",\"token_type\":\"bearer\",\"expires_in\":100}",
            "{\"access_token\":\"a\",\"token_type\":\"bearer\",\"expires_in\":100,\"refresh_token\":\"r\"}"})
    void incompleteTokenResponseIsNeverAccepted(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        failure(() -> client.exchange("mock-code"), INVALID_RESPONSE);
    }
    @Test void missingTalkPermissionIsRejected() {
        server.expect(anything()).andRespond(withSuccess("""
                {"token_type":"bearer","access_token":"a","expires_in":100,"refresh_token":"r",
                "refresh_token_expires_in":1000,"scope":"profile_nickname"}
                """, MediaType.APPLICATION_JSON));
        failure(() -> client.exchange("mock-code"), PERMISSION);
    }
    @Test void messageUsesBearerEncodedTemplateAndResultCode() {
        String template = "{\"object_type\":\"text\",\"text\":\"한글 & 공시\"}";
        var form = new LinkedMultiValueMap<String, String>(); form.add("template_object", template);
        server.expect(requestTo("https://kapi.kakao.com/v2/api/talk/memo/default/send")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer mock-access")).andExpect(content().formData(form))
                .andExpect(content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andRespond(withSuccess("{\"result_code\":0}", MediaType.APPLICATION_JSON));
        client.send("mock-access", template);
    }
    @ParameterizedTest @CsvSource({"401,-401,INVALID_TOKEN", "403,-402,PERMISSION", "400,-3,PERMISSION",
            "403,-5,PERMISSION", "400,-2,CODE_REJECTED", "400,-603,INVALID_RESPONSE", "400,-7,INVALID_RESPONSE"})
    void providerFailuresAreSanitized(int status, int code, KakaoFailure.Kind kind) {
        server.expect(anything()).andRespond(withStatus(HttpStatus.valueOf(status)).body("{\"code\":" + code + ",\"msg\":\"private\"}"));
        failure(() -> client.send("mock-access", "{}"), kind);
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "{\"result_code\":1}", "{\"result_code\":\"0\"}", "{broken"})
    void http200WithoutIntegerZeroIsUncertain(String body) {
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        failure(() -> client.send("mock-access", "{}"), INVALID_RESPONSE);
    }
    @Test void rateLimitHonorsDelayAndSuppressesFurtherHttpUntilDue() {
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "2.5").body("not json"));
        server.expect(anything()).andRespond(withSuccess("{\"result_code\":0}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.send("a", "{}")).isInstanceOfSatisfying(KakaoFailure.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofMillis(2500)));
        failure(() -> client.send("a", "{}"), RATE_LIMIT);
        when(clock.instant()).thenReturn(NOW.plusSeconds(3));
        client.send("a", "{}");
    }
    @Test void quotaBodyWithoutPositiveHeaderDefersOneDayRatherThanSpinning() {
        server.expect(anything()).andRespond(withBadRequest().header("Retry-After", "-1").body("{\"code\":-10}"));
        assertThatThrownBy(() -> client.send("a", "{}")).isInstanceOfSatisfying(KakaoFailure.class,
                e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofDays(1)));
        failure(() -> client.send("a", "{}"), RATE_LIMIT);
    }
    @Test void tokenIssuanceLimitHasIndependentCooldown() {
        server.expect(anything()).andRespond(withBadRequest().body("{\"error_code\":\"KOE237\"}"));
        failure(() -> client.exchange("code"), RATE_LIMIT);
        failure(() -> client.refresh("r"), RATE_LIMIT);
    }
    @Test void serverAndTimeoutFailuresDoNotRetryOrLeakOriginalException() {
        server.expect(anything()).andRespond(withServerError().body("private"));
        server.expect(anything()).andRespond(withException(new SocketTimeoutException("private mock-access")));
        failure(() -> client.exchange("code"), SERVER);
        failure(() -> client.send("a", "{}"), TRANSPORT);
    }
    @Test void disabledClientMakesNoHttpRequest() {
        p.setEnabled(false);
        failure(() -> client.exchange("code"), CONFIGURATION);
        failure(() -> client.send("a", "{}"), CONFIGURATION);
    }
    private static void failure(Runnable call, KakaoFailure.Kind kind) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(KakaoFailure.class, e -> {
            assertThat(e.kind()).isEqualTo(kind);
            assertThat(e.getMessage()).doesNotContain("private", "mock-access", "mock-refresh");
            assertThat(e.getCause()).isNull();
        });
    }
}
