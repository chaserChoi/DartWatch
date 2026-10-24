package com.dartwatch.collection;

import com.dartwatch.api.ApiException;
import com.dartwatch.disclosure.DisclosureClassificationService;
import com.dartwatch.notification.NotificationDispatchService;
import com.dartwatch.notification.NotificationProperties;
import com.dartwatch.opendart.OpenDartException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CollectionPipelineService {
    private static final Logger log = LoggerFactory.getLogger(CollectionPipelineService.class);
    private static final Set<String> API_BLOCKERS = Set.of("CONFIGURATION", "AUTHENTICATION", "RATE_LIMIT");
    private final AtomicBoolean running = new AtomicBoolean();
    private final DisclosureCollectionService collector;
    private final DisclosureClassificationService classifier;
    private final NotificationDispatchService notifications;
    private final NotificationProperties notificationProperties;

    public CollectionPipelineService(DisclosureCollectionService collector, DisclosureClassificationService classifier,
                                     NotificationDispatchService notifications, NotificationProperties notificationProperties) {
        this.collector = collector;
        this.classifier = classifier;
        this.notifications = notifications;
        this.notificationProperties = notificationProperties;
    }

    public Result run() {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT, "PIPELINE_BUSY", "자동 파이프라인이 진행 중입니다.");
        }
        try {
            var collection = collector.collectAll();
            List<FollowUp> followUps = new ArrayList<>();
            boolean apiBlocked = collection.companies().stream().anyMatch(outcome -> API_BLOCKERS.contains(
                    outcome.error() == null ? "" : outcome.error()));
            for (var outcome : collection.companies()) {
                if (!"SUCCESS".equals(outcome.status())) continue;
                if (apiBlocked) {
                    followUps.add(new FollowUp(outcome.watchId(), "SKIPPED", 0, 0, "API_BLOCKED"));
                    continue;
                }
                int classified;
                try {
                    classified = classifier.classify(outcome.watchId()).classified();
                } catch (RuntimeException ex) {
                    String code = safeCode(ex);
                    followUps.add(new FollowUp(outcome.watchId(), "CLASSIFICATION_FAILED", 0, 0, code));
                    log.warn("Collection pipeline classification failed: watchId={}, code={}", outcome.watchId(), code);
                    if (API_BLOCKERS.contains(code)) apiBlocked = true;
                    continue;
                }
                int enqueued = 0;
                try {
                    if (notificationProperties.isEnabled()) enqueued = notifications.enqueue(outcome.watchId());
                    followUps.add(new FollowUp(outcome.watchId(), "SUCCESS", classified, enqueued, null));
                } catch (RuntimeException ex) {
                    String code = safeCode(ex);
                    followUps.add(new FollowUp(outcome.watchId(), "ENQUEUE_FAILED", classified, 0, code));
                    log.warn("Collection pipeline enqueue failed: watchId={}, code={}", outcome.watchId(), code);
                }
            }
            boolean failed = !"COMPLETED".equals(collection.status())
                    || followUps.stream().anyMatch(result -> !"SUCCESS".equals(result.status()));
            boolean collectedAny = collection.companies().stream().anyMatch(outcome -> "SUCCESS".equals(outcome.status()));
            return new Result(!failed ? "COMPLETED" : collectedAny ? "PARTIAL_FAILED" : "FAILED",
                    collection, List.copyOf(followUps));
        } finally {
            running.set(false);
        }
    }

    private static String safeCode(RuntimeException exception) {
        if (exception instanceof OpenDartException dart) return dart.getKind().name();
        if (exception instanceof ApiException api) return api.getCode();
        return exception instanceof DataAccessException ? "PERSISTENCE" : "INTERNAL";
    }

    public record FollowUp(Long watchId, String status, int classified, int enqueued, String error) { }
    public record Result(String status, DisclosureCollectionService.CollectionResult collection, List<FollowUp> followUps) { }
}
