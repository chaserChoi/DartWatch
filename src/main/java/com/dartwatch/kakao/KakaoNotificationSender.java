package com.dartwatch.kakao;

import com.dartwatch.notification.NotificationSender;
import com.dartwatch.notification.NotificationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class KakaoNotificationSender implements NotificationSender {
    private final KakaoProperties properties;
    private final KakaoTokenService tokens;
    private final KakaoClient client;
    private final ObjectMapper mapper;
    public KakaoNotificationSender(KakaoProperties properties, KakaoTokenService tokens, KakaoClient client, ObjectMapper mapper) {
        this.properties = properties; this.tokens = tokens; this.client = client; this.mapper = mapper;
    }
    @Override public String channel() { return "KAKAO"; }
    @Override
    public SendResult send(Message message) {
        if (!properties.isEnabled()) return SendResult.failed("KAKAO_DISABLED", false);
        String template;
        try { template = mapper.writeValueAsString(KakaoMessage.create(message, properties)); }
        catch (KakaoFailure ex) { return SendResult.failed("KAKAO_LINK_CONFIGURATION", false); }
        catch (Exception ex) { return SendResult.failed("COMPOSITION_FAILED", false); }
        boolean attempted = false;
        try {
            var token = tokens.access();
            attempted = true;
            try { client.send(token.value(), template); }
            catch (KakaoFailure ex) {
                if (ex.kind() != KakaoFailure.Kind.INVALID_TOKEN) throw ex;
                attempted = false;
                var renewed = tokens.refreshRejected(token);
                attempted = true;
                client.send(renewed.value(), template); // Only after explicit 401/-401 proves the first request was rejected.
            }
            return SendResult.sent();
        } catch (KakaoFailure ex) {
            if (ex.kind() == KakaoFailure.Kind.INVALID_TOKEN || ex.kind() == KakaoFailure.Kind.RECONNECT_REQUIRED || ex.kind() == KakaoFailure.Kind.PERMISSION) {
                try { tokens.reconnect(); } catch (RuntimeException ignored) { }
            }
            return switch (ex.kind()) {
                case RATE_LIMIT -> new SendResult(NotificationStatus.FAILED, "KAKAO_RATE_LIMIT", true, null, ex.retryAfter());
                case INVALID_TOKEN, RECONNECT_REQUIRED -> SendResult.failed("KAKAO_RECONNECT_REQUIRED", false);
                case PERMISSION -> SendResult.failed("KAKAO_PERMISSION", false);
                case CONFIGURATION -> SendResult.failed("CONFIGURATION", false);
                case CODE_REJECTED -> SendResult.failed("KAKAO_TEMPLATE_REJECTED", false);
                default -> attempted ? SendResult.unknown() : SendResult.failed("KAKAO_TOKEN_UNAVAILABLE", false);
            };
        } catch (RuntimeException ex) { return attempted ? SendResult.unknown() : SendResult.failed("KAKAO_TOKEN_UNAVAILABLE", false); }
    }
}
