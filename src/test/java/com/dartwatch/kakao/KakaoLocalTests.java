package com.dartwatch.kakao;

import com.dartwatch.api.ApiExceptionHandler;
import com.dartwatch.notification.NotificationSender;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.dartwatch.kakao.KakaoFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class KakaoLocalTests {
    private final KakaoProperties p = properties();
    private final KakaoOAuthService oauth = mock(KakaoOAuthService.class);
    private final KakaoTokenService tokens = mock(KakaoTokenService.class);
    private final KakaoNotificationSender sender = mock(KakaoNotificationSender.class);
    private final com.dartwatch.notification.NotificationProperties notifications = new com.dartwatch.notification.NotificationProperties();
    private MockMvc mvc;
    @BeforeEach void setup() {
        notifications.setEnabled(true);
        mvc = MockMvcBuilders.standaloneSetup(new KakaoLocalController(oauth, tokens, sender, notifications))
                .addInterceptors(new KakaoLocalGuard(p, "127.0.0.1")).setControllerAdvice(new ApiExceptionHandler()).build();
    }
    @Test void localConnectionPageHasNoSecretAndDisablesCachingAndReferrers() throws Exception {
        mvc.perform(local(get("/api/integrations/kakao/connect"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("mock-client-secret"))));
        verifyNoInteractions(oauth, tokens, sender);
    }
    @Test void authorizeRequiresExplicitSameOriginPostAndCallbackRedirectOmitsCode() throws Exception {
        when(oauth.authorize(any())).thenReturn(URI.create("https://kauth.kakao.com/oauth/authorize?state=mock-state"));
        mvc.perform(local(post("/api/integrations/kakao/authorize").param("confirmConnect", "true")))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("https://kauth.kakao.com/oauth/authorize?state=mock-state"));
        when(oauth.callback(any(), eq("mock-state"), eq("mock-code"), isNull())).thenReturn("CONNECTED");
        mvc.perform(local(get("/api/integrations/kakao/callback").param("state", "mock-state").param("code", "mock-code")))
                .andExpect(status().isSeeOther()).andExpect(redirectedUrl("/api/integrations/kakao/status?result=CONNECTED"))
                .andExpect(content().string(""));
    }
    @Test void statusExposesOnlyConnectionAndExpiryMetadata() throws Exception {
        when(tokens.status()).thenReturn(new KakaoTokenService.Status("CONNECTED", NOW.plusSeconds(10), NOW.plusSeconds(100), null));
        mvc.perform(local(get("/api/integrations/kakao/status"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONNECTED")).andExpect(jsonPath("$.access").doesNotExist())
                .andExpect(jsonPath("$.refresh").doesNotExist()).andExpect(jsonPath("$.clientSecret").doesNotExist());
    }
    @Test void externalAddressWrongHostAndCrossOriginAreRejected() throws Exception {
        mvc.perform(local(get("/api/integrations/kakao/status")).with(r -> { r.setRemoteAddr("192.0.2.1"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(local(get("/api/integrations/kakao/status")).with(r -> { r.setLocalAddr("192.0.2.2"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(local(get("/api/integrations/kakao/status")).with(r -> { r.removeHeader("Host"); r.addHeader("Host", "evil.example:8080"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(local(post("/api/integrations/kakao/authorize").param("confirmConnect", "true"))
                .with(r -> { r.removeHeader("Origin"); return r; })).andExpect(status().isForbidden());
        verifyNoInteractions(oauth, tokens, sender);
    }
    @ParameterizedTest @ValueSource(strings = {"Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP", "X-Original-URL"})
    void forwardingHeadersAreNeverTrusted(String header) throws Exception {
        mvc.perform(local(get("/api/integrations/kakao/status")).header(header, "untrusted")).andExpect(status().isForbidden());
        verifyNoInteractions(tokens);
    }
    @Test void manualMessageRequiresExplicitConfirmationAndDoesNotCreateHistory() throws Exception {
        String body = """
                {"confirmSend":%s,"companyName":"테스트 기업","reportName":"보고서","typeCode":"A",
                 "receiptDate":"2026-10-24","rceptNo":"20261024000001"}
                """;
        mvc.perform(local(post("/api/integrations/kakao/test-message").contentType(MediaType.APPLICATION_JSON).content(body.formatted("false"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sender);
        when(sender.send(any())).thenReturn(NotificationSender.SendResult.sent());
        mvc.perform(local(post("/api/integrations/kakao/test-message").contentType(MediaType.APPLICATION_JSON).content(body.formatted("true"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        verify(sender, times(1)).send(any());
    }
    @Test void unsafeBindOrCallbackAndProxyModeFailAtStartup() {
        assertThatThrownBy(() -> new KakaoLocalGuard(p, "0.0.0.0")).isInstanceOf(IllegalStateException.class);
        p.setRedirectUri("https://public.example/api/integrations/kakao/callback");
        assertThatThrownBy(() -> new KakaoLocalGuard(p, "127.0.0.1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new KakaoLocalConfiguration(properties(), new MockEnvironment()
                .withProperty("server.address", "127.0.0.1").withProperty("server.forward-headers-strategy", "framework")))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void globalSwitchAlsoBlocksExplicitLocalTestMessage() throws Exception {
        notifications.setEnabled(false);
        mvc.perform(local(post("/api/integrations/kakao/test-message").contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmSend\":true}"))).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(sender);
    }
    private static MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.header("Host", "localhost:8080").header("Origin", "http://localhost:8080")
                .with(r -> { r.setRemoteAddr("127.0.0.1"); r.setLocalAddr("127.0.0.1"); return r; });
    }
}
