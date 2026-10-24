package com.dartwatch.kakao;

import com.dartwatch.notification.NotificationSender;
import java.time.*;
import java.util.Base64;

final class KakaoFixtures {
    static final Instant NOW = Instant.parse("2026-10-24T03:00:00Z");
    // Artificial inputs for Mock HTTP / isolated DB tests; never real credentials.
    static KakaoProperties properties() {
        var p = new KakaoProperties();
        p.setEnabled(true);
        p.setRestApiKey("mock-client-id");
        p.setClientSecret("mock-client-secret");
        p.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        p.setRegisteredWebOrigin("https://service.example.invalid");
        p.setMessageWebUrl("https://service.example.invalid/disclosures");
        return p;
    }
    static NotificationSender.Message message() {
        return new NotificationSender.Message(null, "테스트 기업", "한글 보고서", "A", LocalDate.of(2026, 10, 24), "20261024000001");
    }
    static KakaoClient.Tokens initial() {
        return new KakaoClient.Tokens("mock-access", "mock-refresh", Duration.ofHours(6), Duration.ofDays(60));
    }
}
