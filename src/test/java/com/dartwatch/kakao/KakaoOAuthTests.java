package com.dartwatch.kakao;

import com.dartwatch.api.ApiException;
import java.time.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.util.UriComponentsBuilder;

import static com.dartwatch.kakao.KakaoFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KakaoOAuthTests {
    private final KakaoProperties p = properties();
    private final KakaoTokenService tokens = mock(KakaoTokenService.class);
    private final Clock clock = mock(Clock.class);
    private final KakaoCrypto crypto = new KakaoCrypto(p);
    private KakaoOAuthService oauth;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        oauth = new KakaoOAuthService(p, tokens, crypto, clock);
    }
    @Test void authorizationRequestsTalkScopeAndRandomSessionBoundState() {
        var session = new MockHttpSession();
        var first = oauth.authorize(session);
        var params = UriComponentsBuilder.fromUri(first).build().getQueryParams();
        assertThat(first.getHost()).isEqualTo("kauth.kakao.com");
        assertThat(params.getFirst("response_type")).isEqualTo("code");
        assertThat(params.getFirst("scope")).isEqualTo("talk_message");
        String state = params.getFirst("state");
        assertThat(state).matches("[A-Za-z0-9_-]{43}");
        assertThat(session.getMaxInactiveInterval()).isEqualTo(300);
        assertThat(state(oauth, session)).isNotEqualTo(state);
        invalid(() -> oauth.callback(session, state, "code", null));
        verifyNoInteractions(tokens);
    }
    @Test void validStateIsConsumedBeforeExchangeAndCannotBeReplayed() {
        var session = new MockHttpSession(); String state = state(oauth, session);
        assertThat(oauth.callback(session, state, "mock-code", null)).isEqualTo("CONNECTED");
        verify(tokens).connect("mock-code");
        invalid(() -> oauth.callback(session, state, "mock-code", null));
        verifyNoMoreInteractions(tokens);
    }
    @Test void wrongBrowserOrWrongStateCannotConsumeOriginalRequest() {
        var session = new MockHttpSession(); String state = state(oauth, session);
        invalid(() -> oauth.callback(null, state, "code", null));
        invalid(() -> oauth.callback(new MockHttpSession(), state, "code", null));
        invalid(() -> oauth.callback(session, "x".repeat(43), "code", null));
        assertThat(oauth.callback(session, state, "mock-code", null)).isEqualTo("CONNECTED");
    }
    @Test void expiredStateIsRejectedAtBoundary() {
        var session = new MockHttpSession(); String state = state(oauth, session);
        when(clock.instant()).thenReturn(NOW.plusSeconds(300));
        invalid(() -> oauth.callback(session, state, "code", null));
        verifyNoInteractions(tokens);
    }
    @Test void cancelledOrFailedLoginConsumesStateWithoutCodeExchange() {
        var session = new MockHttpSession(); String state = state(oauth, session);
        assertThat(oauth.callback(session, state, null, "access_denied")).isEqualTo("CANCELLED");
        invalid(() -> oauth.callback(session, state, "code", null));
        assertThat(oauth.callback(session, state(oauth, session), null, "private-provider-error")).isEqualTo("AUTHORIZATION_FAILED");
        verifyNoInteractions(tokens);
    }
    @Test void failedCodeExchangeStillConsumesState() {
        var session = new MockHttpSession(); String state = state(oauth, session);
        doThrow(new KakaoFailure(KakaoFailure.Kind.CODE_REJECTED)).when(tokens).connect(any());
        assertThatThrownBy(() -> oauth.callback(session, state, "code", null)).isInstanceOf(KakaoFailure.class);
        invalid(() -> oauth.callback(session, state, "code", null));
        verify(tokens, times(1)).connect(any());
    }
    @Test void disabledOrMissingEncryptionKeyCannotStartOAuth() {
        p.setEnabled(false);
        assertThatThrownBy(() -> oauth.authorize(new MockHttpSession())).isInstanceOf(KakaoFailure.class);
        p.setEnabled(true); p.setEncryptionKey("");
        assertThatThrownBy(() -> oauth.authorize(new MockHttpSession())).isInstanceOf(KakaoFailure.class);
        verifyNoInteractions(tokens);
    }
    @Test void encryptionIsRandomizedAuthenticatedAndBoundToTokenPurpose() {
        String one = crypto.encrypt("mock-token", "access"), two = crypto.encrypt("mock-token", "access");
        assertThat(one).startsWith("v1:").isNotEqualTo(two).doesNotContain("mock-token");
        assertThat(crypto.decrypt(one, "access")).isEqualTo("mock-token");
        assertThatThrownBy(() -> crypto.decrypt(one, "refresh")).isInstanceOf(KakaoFailure.class);
        assertThatThrownBy(() -> crypto.decrypt(one.substring(0, one.length() - 4) + "AAAA", "access")).isInstanceOf(KakaoFailure.class);
        p.setEncryptionKey(java.util.Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> crypto.decrypt(one, "access")).isInstanceOf(KakaoFailure.class);
    }
    private static String state(KakaoOAuthService oauth, MockHttpSession session) {
        return UriComponentsBuilder.fromUri(oauth.authorize(session)).build().getQueryParams().getFirst("state");
    }
    private static void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOf(ApiException.class); }
}
