package com.dartwatch.kakao;

import com.dartwatch.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

public class KakaoLocalGuard implements HandlerInterceptor {
    private final URI redirect;
    public KakaoLocalGuard(KakaoProperties properties, String binding) {
        try {
            redirect = URI.create(properties.getRedirectUri());
            if (!Set.of("127.0.0.1", "::1").contains(binding) || !"http".equals(redirect.getScheme())
                    || !Set.of("localhost", "127.0.0.1", "[::1]").contains(redirect.getHost())
                    || !"/api/integrations/kakao/callback".equals(redirect.getRawPath()) || redirect.getRawQuery() != null
                    || redirect.getRawFragment() != null || redirect.getRawUserInfo() != null) throw new IllegalArgumentException();
        } catch (RuntimeException ex) { throw new IllegalStateException("Kakao local connection requires an explicit loopback bind and local callback"); }
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
        response.setHeader("Referrer-Policy", "no-referrer"); response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; form-action 'self' https://kauth.kakao.com; frame-ancestors 'none'; base-uri 'none'");
        String host = request.getHeader("Host");
        if (!loopback(request.getRemoteAddr()) || !loopback(request.getLocalAddr()) || host == null
                || !redirect.getRawAuthority().equalsIgnoreCase(host)) throw denied();
        for (String header : new String[]{"Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP", "X-Original-URL"})
            if (request.getHeader(header) != null) throw denied();
        if ("POST".equals(request.getMethod()) && !(redirect.getScheme() + "://" + redirect.getRawAuthority()).equals(request.getHeader("Origin")))
            throw denied();
        return true;
    }
    private static boolean loopback(String value) { return "127.0.0.1".equals(value) || "::1".equals(value) || "0:0:0:0:0:0:0:1".equals(value); }
    private static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN, "KAKAO_LOCAL_ONLY", "카카오 연결 관리는 로컬 브라우저에서만 허용됩니다."); }
}
