package com.dartwatch.notification;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Service
public class NotificationHistoryService {
    private static final Set<String> SAFE_ERRORS = Set.of("NOTIFICATIONS_DISABLED", "POLICY_CHANGED", "CONFIGURATION",
            "COMPOSITION_FAILED", "AUTHENTICATION", "SMTP_TEMPORARY_REJECTION", "SMTP_PERMANENT_REJECTION",
            "CONNECTION_FAILED", "TRANSPORT_UNCERTAIN", "RECOVERED_IN_FLIGHT", "LEGACY_IN_FLIGHT",
            "DISCORD_AUTHENTICATION", "DISCORD_BAD_REQUEST", "DISCORD_DISABLED", "DISCORD_RATE_LIMIT", "DISCORD_REJECTED",
            "DISCORD_SERVER_UNCERTAIN", "DISCORD_TIMEOUT_UNCERTAIN", "DISCORD_WEBHOOK_NOT_FOUND", "KAKAO_DISABLED",
            "KAKAO_LINK_CONFIGURATION", "KAKAO_PERMISSION", "KAKAO_RATE_LIMIT", "KAKAO_RECONNECT_REQUIRED", "KAKAO_TEMPLATE_REJECTED", "KAKAO_TOKEN_UNAVAILABLE");
    private final NotificationDeliveryRepository deliveries;

    public NotificationHistoryService(NotificationDeliveryRepository deliveries) { this.deliveries = deliveries; }

    @Transactional(readOnly = true)
    public History list(NotificationStatus status, int page, int size) {
        return list(status,null,page,size);
    }

    @Transactional(readOnly = true)
    public History list(NotificationStatus status, NotificationChannel channel, int page, int size) {
        Assert.isTrue(page >= 0 && size >= 1 && size <= 100, "Invalid history page");
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        var result = deliveries.search(status,channel,pageable);
        return new History(result.map(Entry::from).getContent(), page, size, result.getTotalElements());
    }

    public record History(List<Entry> items, int page, int size, long totalElements) { }
    public record Entry(Long id, String rceptNo, NotificationChannel channel, String recipient, NotificationStatus status,
                        int attemptCount, Instant createdAt, Instant lastAttemptAt, Instant sentAt,
                        Instant nextAttemptAt, boolean retryable, String error, String externalMessageId) {
        static Entry from(NotificationDelivery delivery) {
            String error = delivery.getLastError();
            if (error != null && !SAFE_ERRORS.contains(error)) error = "LEGACY_FAILURE";
            return new Entry(delivery.getId(), delivery.getDisclosure().getRceptNo(), delivery.getChannel(), delivery.getRecipient(),
                    delivery.getStatus(), delivery.getAttemptCount(), delivery.getCreatedAt(), delivery.getLastAttemptAt(),
                    delivery.getSentAt(), delivery.getNextAttemptAt(), delivery.isRetryable(), error, delivery.getExternalMessageId());
        }
    }
}
