package com.dartwatch.collection;

import com.dartwatch.api.ApiException;
import com.dartwatch.scheduling.SchedulerReadiness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class CollectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(CollectionScheduler.class);
    private final CollectionPipelineService service;
    private final SchedulerReadiness readiness;

    public CollectionScheduler(CollectionPipelineService service, SchedulerReadiness readiness) {
        this.service = service;
        this.readiness = readiness;
    }

    @Scheduled(fixedDelayString = "${dartwatch.collection.interval:PT10M}",
            initialDelayString = "${dartwatch.collection.initial-delay:PT30S}")
    public void collect() {
        if (!readiness.isReady()) return;
        try {
            var result = service.run();
            log.info("Scheduled pipeline finished: status={}, attempted={}, followUps={}", result.status(),
                    result.collection().companies().size(), result.followUps().size());
        } catch (ApiException ex) {
            log.info("Scheduled collection skipped: code={}", ex.getCode());
        } catch (RuntimeException ex) {
            log.warn("Scheduled collection failed before completion");
        }
    }
}
