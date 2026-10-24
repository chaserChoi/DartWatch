package com.dartwatch.collection;

import com.dartwatch.api.ApiException;
import com.dartwatch.opendart.OpenDartClient;
import com.dartwatch.opendart.OpenDartException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
        String runId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        String batchStatus = "FAILED";
        List<CollectionStore.Outcome> results = new ArrayList<>();
        log.info("Collection started: runId={}", runId);
        try {
            for (Long watchId : store.activeWatchIds()) {
                long companyStarted = System.nanoTime();
                String corpCode = null;
                try {
                    var plan = store.prepare(watchId);
                    if (plan == null) {
                        results.add(CollectionStore.Outcome.skipped(watchId));
                        log.info("Collection company finished: runId={}, watchId={}, status=SKIPPED, durationMs={}",
                                runId, watchId, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - companyStarted));
                        continue;
                    }
                    corpCode = plan.corpCode();
                    log.info("Collection company started: runId={}, watchId={}, corpCode={}, from={}, through={}",
                            runId, watchId, corpCode, plan.from(), plan.through());
                    var records = client.findDisclosures(plan.corpCode(), plan.from(), plan.through());
                    var result = store.complete(plan, records);
                    results.add(result);
                    log.info("Collection company finished: runId={}, watchId={}, corpCode={}, status={}, fetched={}, inserted={}, duplicates={}, durationMs={}",
                            runId, watchId, corpCode, result.status(), result.fetched(), result.inserted(),
                            result.fetched() - result.inserted(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - companyStarted));
                } catch (RuntimeException ex) {
                    String code = ex instanceof OpenDartException dart ? dart.getKind().name()
                            : ex instanceof DataAccessException ? "PERSISTENCE" : "INTERNAL";
                    try {
                        store.fail(watchId, code);
                    } catch (RuntimeException stateFailure) {
                        log.warn("Collection failure recording unavailable: runId={}, watchId={}", runId, watchId);
                    }
                    log.warn("Collection company finished: runId={}, watchId={}, corpCode={}, status=FAILED, error={}, durationMs={}",
                            runId, watchId, corpCode, code, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - companyStarted));
                    results.add(new CollectionStore.Outcome(watchId, "FAILED", null, null, 0, 0, code));
                    if (ex instanceof OpenDartException dart && switch (dart.getKind()) {
                        case CONFIGURATION, AUTHENTICATION, RATE_LIMIT, INTERRUPTED -> true;
                        default -> false;
                    }) break;
                }
            }
            long failures = results.stream().filter(result -> "FAILED".equals(result.status())).count();
            batchStatus = failures == 0 ? "COMPLETED" : failures == results.size() ? "FAILED" : "PARTIAL_FAILED";
            return new CollectionResult(batchStatus, List.copyOf(results));
        } catch (RuntimeException ex) {
            log.warn("Collection interrupted: runId={}, error={}", runId,
                    ex instanceof DataAccessException ? "PERSISTENCE" : "INTERNAL");
            throw ex;
        } finally {
            running.set(false);
            log.info("Collection finished: runId={}, status={}, attempted={}, durationMs={}",
                    runId, batchStatus, results.size(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    public record CollectionResult(String status, List<CollectionStore.Outcome> companies) { }
}
