package com.dartwatch.notification;

import com.dartwatch.api.ApiException;
import com.dartwatch.scheduling.SchedulerReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class NotificationScheduler {
    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);
    private final NotificationDispatchService dispatcher;
    private final SchedulerReadiness readiness;

    public NotificationScheduler(NotificationDispatchService dispatcher, SchedulerReadiness readiness) {
        this.dispatcher = dispatcher;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${dartwatch.notification.scheduler.interval:PT1M}",
            initialDelayString = "${dartwatch.notification.scheduler.initial-delay:PT30S}")
    public void dispatch() {
        if (!readiness.isReady()) return;
        try {
            var result = dispatcher.dispatch();
            log.info("Scheduled notifications finished: sent={}, failed={}, unknown={}, cancelled={}, recovered={}",
                    result.sent(), result.failed(), result.unknown(), result.cancelled(), result.recoveredUnknown());
        } catch (ApiException ex) {
            log.info("Scheduled notifications skipped: code={}", ex.getCode());
        } catch (RuntimeException ex) {
            log.warn("Scheduled notification processing failed");
        }
    }
}
