package com.glivt.ai.client;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal circuit breaker for the AI service.
 *
 * <p>When the AI service is down, GPS ingestion still fires AI evaluations for
 * every packet. Without a breaker each one would wait out the full connect
 * timeout and pin the AI thread pool. After {@code failureThreshold} consecutive
 * transport failures the breaker opens and calls fail instantly with
 * {@link AiErrorCode#CIRCUIT_OPEN} until {@code openMillis} has elapsed, at which
 * point a single trial call is allowed through.
 *
 * <p>Only transport-level failures trip it. A 422 validation error means our
 * request was wrong, not that the service is unhealthy, so it does not count.
 */
public class AiCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(AiCircuitBreaker.class);

    private final String name;
    private final boolean enabled;
    private final int failureThreshold;
    private final long openMillis;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong(0);

    public AiCircuitBreaker(String name, boolean enabled, int failureThreshold, long openMillis) {
        this.name = name;
        this.enabled = enabled;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openMillis = Math.max(1000L, openMillis);
    }

    /** @return true when the call may proceed. */
    public boolean allowRequest() {
        if (!enabled) {
            return true;
        }
        long opened = openedAt.get();
        if (opened == 0L) {
            return true;
        }
        if (System.currentTimeMillis() - opened >= openMillis) {
            // Half-open: let one trial request through.
            if (openedAt.compareAndSet(opened, 0L)) {
                log.info("AI circuit breaker '{}' entering half-open; allowing a trial request", name);
            }
            return true;
        }
        return false;
    }

    public void recordSuccess() {
        if (consecutiveFailures.getAndSet(0) >= failureThreshold) {
            log.info("AI circuit breaker '{}' closed after a successful call", name);
        }
        openedAt.set(0L);
    }

    /** Records a transport-level failure. Non-transient failures are ignored. */
    public void recordFailure(AiErrorCode errorCode) {
        if (!enabled || errorCode == AiErrorCode.CIRCUIT_OPEN) {
            return;
        }
        if (!errorCode.isTransient()) {
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && openedAt.get() == 0L) {
            openedAt.set(System.currentTimeMillis());
            log.warn("AI circuit breaker '{}' OPEN after {} consecutive failures (last: {}); "
                    + "AI calls will fail fast for {} ms", name, failures, errorCode, openMillis);
        }
    }

    public boolean isOpen() {
        return openedAt.get() != 0L;
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    public String name() {
        return name;
    }
}
