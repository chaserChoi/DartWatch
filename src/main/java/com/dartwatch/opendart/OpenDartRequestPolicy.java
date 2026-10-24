package com.dartwatch.opendart;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.dartwatch.opendart.OpenDartException.Kind.*;

/** Shared by directory, collection and classification requests in one application. */
final class OpenDartRequestPolicy {
    private static final Logger log = LoggerFactory.getLogger(OpenDartRequestPolicy.class);
    private final OpenDartProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;
    private final DoubleSupplier random;
    private Instant blockedUntil = Instant.MIN;

    OpenDartRequestPolicy(OpenDartProperties properties) {
        this(properties, Clock.systemUTC(), Thread::sleep, () -> ThreadLocalRandom.current().nextDouble());
    }

    OpenDartRequestPolicy(OpenDartProperties properties, Clock clock, Sleeper sleeper, DoubleSupplier random) {
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
        this.random = random;
    }

    <T> T execute(Supplier<T> request) {
        for (int retries = 0; ; retries++) {
            if (Thread.currentThread().isInterrupted()) throw new OpenDartException(INTERRUPTED);
            checkCooldown();
            try {
                return request.get();
            } catch (OpenDartException ex) {
                if (ex.getKind() == RATE_LIMIT) blockRequests();
                boolean transientFailure = switch (ex.getKind()) {
                    case UPSTREAM_SYSTEM, HTTP_SERVER, NETWORK, TIMEOUT -> true;
                    default -> false;
                };
                if (!transientFailure || retries >= properties.getMaxRetries()) throw ex;
                long cap = Math.min(properties.getRetryMaxDelay().toMillis(),
                        properties.getRetryInitialDelay().toMillis() * (1L << retries));
                // Equal jitter keeps a nonzero pause while spreading retry requests.
                long delay = Math.max(1, (long) (cap * (0.5 + random.getAsDouble() * 0.5)));
                log.warn("OpenDART retry: kind={}, retry={}, delayMs={}", ex.getKind(), retries + 1, delay);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new OpenDartException(INTERRUPTED);
                }
            }
        }
    }

    private synchronized void checkCooldown() {
        if (clock.instant().isBefore(blockedUntil)) throw new OpenDartException(RATE_LIMIT);
    }

    private synchronized void blockRequests() {
        Instant until = clock.instant().plus(properties.getRateLimitCooldown());
        if (until.isAfter(blockedUntil)) blockedUntil = until;
    }

    @FunctionalInterface
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
}
