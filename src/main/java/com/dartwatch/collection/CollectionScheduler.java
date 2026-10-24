package com.dartwatch.collection;

import com.dartwatch.api.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public class CollectionScheduler {
    private static final Logger log = LoggerFactory.getLogger(CollectionScheduler.class);
    private final DisclosureCollectionService service;

    public CollectionScheduler(DisclosureCollectionService service) { this.service = service; }

    @Scheduled(fixedDelayString = "${dartwatch.collection.interval:PT10M}",
            initialDelayString = "${dartwatch.collection.initial-delay:PT30S}")
    public void collect() {
        try {
            var result = service.collectAll();
            log.info("Scheduled collection finished: status={}, attempted={}", result.status(), result.companies().size());
        } catch (ApiException ex) {
            log.info("Scheduled collection skipped: code={}", ex.getCode());
        } catch (RuntimeException ex) {
            log.warn("Scheduled collection failed before completion");
        }
    }
}
