package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.watchlist.WatchedCompanyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Service
@EnableConfigurationProperties({NotificationProperties.class, MailProperties.class})
public class NotificationDispatchService {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);
    private final NotificationProperties properties;
    private final NotificationWorkStore store;
    private final NotificationSender sender;
    private final WatchedCompanyRepository watched;

    public NotificationDispatchService(NotificationProperties properties, NotificationWorkStore store,
                                       NotificationSender sender, WatchedCompanyRepository watched) {
        this.properties = properties;
        this.store = store;
        this.sender = sender;
        this.watched = watched;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        store.synchronizeConfiguration();
    }

    public int enqueue(Long watchId) {
        requireEnabled();
        Assert.isTrue(watchId != null && watchId > 0, "Invalid watch id");
        if (!watched.existsById(watchId)) throw new ApiException(HttpStatus.NOT_FOUND, "WATCH_NOT_FOUND", "관심 기업을 찾을 수 없습니다.");
        return store.enqueue(watchId);
    }

    public DispatchResult dispatch() {
        requireEnabled();
        int recovered = store.recoverStale();
        int sent = 0, failed = 0, unknown = 0, cancelled = store.cancelIneligible();
        for (int index = 0; index < properties.getBatchSize(); index++) {
            if (!properties.isEnabled()) break;
            var claim = store.claimOne(); // This transaction commits before SMTP is invoked.
            if (claim == null) break;
            if (!store.isStillEligible(claim) || !properties.isEnabled()) {
                store.cancelClaim(claim);
                cancelled++;
                continue;
            }
            NotificationSender.SendResult result;
            try {
                result = sender.send(claim.message());
                if (result == null) result = NotificationSender.SendResult.unknown();
            } catch (RuntimeException ex) {
                result = NotificationSender.SendResult.unknown();
            }
            boolean recorded;
            try {
                recorded = store.finish(claim, result);
            } catch (RuntimeException ex) {
                // SMTP may have accepted the email. Preserve uncertainty, never put it back in PENDING.
                try { store.finish(claim, NotificationSender.SendResult.unknown()); }
                catch (RuntimeException unavailable) { /* Stale PROCESSING will become UNKNOWN on recovery. */ }
                log.warn("Notification result could not be persisted: deliveryId={}", claim.id());
                unknown++;
                break;
            }
            if (!recorded) unknown++;
            else switch (result.status()) {
                case SENT -> sent++;
                case FAILED -> failed++;
                default -> unknown++;
            }
            log.info("Notification processed: deliveryId={}, status={}, recorded={}", claim.id(), result.status(), recorded);
            if ("AUTHENTICATION".equals(result.error()) || "CONFIGURATION".equals(result.error())) break;
        }
        return new DispatchResult(sent, failed, unknown, cancelled, recovered);
    }

    private void requireEnabled() {
        if (properties.isEnabled()) {
            try {
                EmailAddress.normalize(properties.getEmailTo());
                EmailAddress.normalize(properties.getMailFrom());
            } catch (IllegalArgumentException ex) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOTIFICATION_CONFIGURATION", "이메일 주소 설정을 확인하세요.");
            }
        }
        store.synchronizeConfiguration();
        if (!properties.isEnabled()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "NOTIFICATIONS_DISABLED", "이메일 전송이 비활성화되어 있습니다.");
    }

    public record DispatchResult(int sent, int failed, int unknown, int cancelled, int recoveredUnknown) { }
}
