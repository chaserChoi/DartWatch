package com.dartwatch.kakao;

import com.dartwatch.api.ApiException;
import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class KakaoOAuthService {
    private static final String ATTRIBUTE = KakaoOAuthService.class.getName();
    private final SecureRandom random = new SecureRandom();
    private final KakaoProperties properties;
    private final KakaoTokenService tokens;
    private final KakaoCrypto crypto;
    private final Clock clock;
    public KakaoOAuthService(KakaoProperties properties, KakaoTokenService tokens, KakaoCrypto crypto, Clock clock) {
        this.properties = properties; this.tokens = tokens; this.crypto = crypto; this.clock = clock;
    }
    public URI authorize(HttpSession session) {
        if (!properties.isEnabled()) throw new KakaoFailure(KakaoFailure.Kind.CONFIGURATION);
        crypto.validateKey(); crypto.clientFingerprint();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        synchronized (session) {
            session.setMaxInactiveInterval(300);
            session.setAttribute(ATTRIBUTE, new Pending(digest(state), clock.instant().plusSeconds(300)));
        }
        return UriComponentsBuilder.fromUriString("https://kauth.kakao.com/oauth/authorize")
                .queryParam("response_type", "code").queryParam("client_id", properties.getRestApiKey())
                .queryParam("redirect_uri", properties.getRedirectUri()).queryParam("scope", "talk_message")
                .queryParam("state", state).build().encode().toUri();
    }
    public String callback(HttpSession session, String state, String code, String error) {
        if (session == null || state == null || !state.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        synchronized (session) {
            Object value = session.getAttribute(ATTRIBUTE);
            if (!(value instanceof Pending pending)) throw invalid();
            if (!clock.instant().isBefore(pending.expires())) { session.removeAttribute(ATTRIBUTE); throw invalid(); }
            if (!MessageDigest.isEqual(pending.hash(), digest(state))) throw invalid();
            session.removeAttribute(ATTRIBUTE);
        }
        if (error != null) return "access_denied".equals(error) ? "CANCELLED" : "AUTHORIZATION_FAILED";
        tokens.connect(code);
        return "CONNECTED";
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception ex) { throw new IllegalStateException("Digest unavailable"); }
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "KAKAO_STATE_INVALID", "연결 요청이 없거나 만료되었습니다. 연결을 다시 시작하세요."); }
    private record Pending(byte[] hash, Instant expires) { @Override public String toString() { return "Pending[redacted]"; } }
}
