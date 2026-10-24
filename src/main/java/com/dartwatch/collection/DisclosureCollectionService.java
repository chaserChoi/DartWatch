package com.dartwatch.collection;

import com.dartwatch.api.ApiException;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.OpenDartException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class DisclosureCollectionService {
    private static final Logger log = LoggerFactory.getLogger(DisclosureCollectionService.class);
    private final AtomicBoolean running = new AtomicBoolean();
    private final OpenDartClient client;
    private final CollectionStore store;

    public DisclosureCollectionService(OpenDartClient client, CollectionStore store) {
        this.client = client;
        this.store = store;
    }

    public CollectionResult collectAll() {
        if (!running.compareAndSet(false, true)) {
            throw new ApiException(HttpStatus.CONFLICT, "COLLECTION_BUSY", "공시 수집이 진행 중입니다.");
        }
        try {
            List<CollectionStore.Outcome> results = new ArrayList<>();
            for (Long watchId : store.activeWatchIds()) {
                try {
                    var plan = store.prepare(watchId);
                    if (plan == null) {
                        results.add(CollectionStore.Outcome.skipped(watchId));
                        continue;
                    }
                    var records = client.findDisclosures(plan.corpCode(), plan.from(), plan.through());
                    var result = store.complete(plan, records);
                    results.add(result);
                    log.info("Disclosure collection: watchId={}, status={}, fetched={}, inserted={}",
                            watchId, result.status(), result.fetched(), result.inserted());
                } catch (RuntimeException ex) {
                    String code = ex instanceof OpenDartException dart ? dart.getKind().name()
                            : ex instanceof DataAccessException ? "PERSISTENCE" : "INTERNAL";
                    try {
                        store.fail(watchId, code);
                    } catch (RuntimeException stateFailure) {
                        log.warn("Unable to record collection failure: watchId={}", watchId);
                    }
                    log.warn("Disclosure collection failed: watchId={}, kind={}", watchId, code);
                    results.add(new CollectionStore.Outcome(watchId, "FAILED", null, null, 0, 0, code));
                    if (ex instanceof OpenDartException dart && switch (dart.getKind()) {
                        case CONFIGURATION, AUTHENTICATION, RATE_LIMIT -> true;
                        default -> false;
                    }) break;
                }
            }
            long failures = results.stream().filter(result -> "FAILED".equals(result.status())).count();
            String status = failures == 0 ? "COMPLETED" : failures == results.size() ? "FAILED" : "PARTIAL_FAILED";
            return new CollectionResult(status, List.copyOf(results));
        } finally {
            running.set(false);
        }
    }

    public record CollectionResult(String status, List<CollectionStore.Outcome> companies) { }
}
