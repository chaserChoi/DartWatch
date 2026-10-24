package com.dartwatch.scheduling;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

public class SchedulerReadiness {
    private volatile boolean ready;

    // Notification activation must be persisted before either scheduled job can start.
    @Order(1)
    @EventListener(ApplicationReadyEvent.class)
    public void markReady() { ready = true; }

    public boolean isReady() { return ready; }
}
