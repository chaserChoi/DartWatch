package com.dartwatch.kakao;

import com.dartwatch.notification.NotificationSender;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

@RestController
@Hidden
@RequestMapping("/api/integrations/kakao")
@ConditionalOnProperty(name = "dartwatch.kakao.local-connect-enabled", havingValue = "true")
public class KakaoLocalController {
    private final KakaoOAuthService oauth;
    private final KakaoTokenService tokens;
    private final KakaoNotificationSender sender;
    public KakaoLocalController(KakaoOAuthService oauth, KakaoTokenService tokens, KakaoNotificationSender sender) {
        this.oauth = oauth; this.tokens = tokens; this.sender = sender;
    }
    @GetMapping(value = "/connect", produces = MediaType.TEXT_HTML_VALUE)
    public String connect() {
        return """
                <!doctype html><html lang="ko"><meta charset="utf-8"><title>DartWatch Kakao 연결</title>
                <h1>DartWatch 카카오 계정 연결</h1><p>연결만 수행합니다. 메시지는 발송하지 않습니다.</p>
                <form method="post" action="authorize"><input type="hidden" name="confirmConnect" value="true">
                <button type="submit">본인 카카오 계정 연결 시작</button></form></html>
                """;
    }
    @PostMapping("/authorize")
    public ResponseEntity<Void> authorize(@RequestParam boolean confirmConnect, HttpServletRequest request) {
        Assert.isTrue(confirmConnect, "Connection confirmation required");
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(oauth.authorize(request.getSession(true))).build();
    }
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String state, @RequestParam(required = false) String code,
                                        @RequestParam(required = false) String error, HttpServletRequest request) {
        String result = oauth.callback(request.getSession(false), state, code, error);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/api/integrations/kakao/status?result=" + result)).build();
    }
    @GetMapping("/status")
    public KakaoTokenService.Status status() { return tokens.status(); }
    @PostMapping("/test-message")
    public NotificationSender.SendResult testMessage(@RequestBody TestRequest request) {
        Assert.isTrue(Boolean.TRUE.equals(request.confirmSend()), "Explicit send confirmation required");
        return sender.send(new NotificationSender.Message(null, request.companyName(), request.reportName(), request.typeCode(), request.receiptDate(), request.rceptNo()));
    }
    @ExceptionHandler(KakaoFailure.class)
    public ResponseEntity<ProblemDetail> failure(KakaoFailure ex) {
        HttpStatus status = switch (ex.kind()) {
            case CODE_REJECTED -> HttpStatus.BAD_REQUEST; case RECONNECT_REQUIRED -> HttpStatus.CONFLICT;
            case PERMISSION -> HttpStatus.FORBIDDEN; case RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        var problem = ProblemDetail.forStatusAndDetail(status, "카카오 연결 상태와 설정을 확인하세요.");
        problem.setProperty("code", "KAKAO_" + ex.kind().name());
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (ex.retryAfter() != null) response.header("Retry-After", Long.toString(Math.max(1, ex.retryAfter().toSeconds())));
        return response.body(problem);
    }
    public record TestRequest(Boolean confirmSend, String companyName, String reportName, String typeCode,
                              java.time.LocalDate receiptDate, String rceptNo) { }
}
