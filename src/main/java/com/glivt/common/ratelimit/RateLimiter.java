package com.glivt.common.ratelimit;

import com.glivt.common.exception.TooManyRequestsException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Lightweight in-memory fixed-window rate limiter for sensitive endpoints
 * (login, OTP, commands, report exports). Per-instance only; for a multi-node
 * deployment back this with Redis. Buckets are swept periodically.
 */
@Component
public class RateLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** Records an attempt for {@code key}; throws once {@code maxAttempts} in {@code window} is exceeded. */
    public void check(String key, int maxAttempts, Duration window) {
        Instant now = Instant.now();
        Window w = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.expiresAt.isBefore(now)) {
                return new Window(now.plus(window), 1);
            }
            existing.count++;
            return existing;
        });
        if (w.count > maxAttempts) {
            throw new TooManyRequestsException("Too many attempts. Try again later.");
        }
    }

    /** Clears a bucket after a successful operation (e.g. successful login). */
    public void reset(String key) {
        windows.remove(key);
    }

    @Scheduled(fixedDelay = 300_000L)
    void sweep() {
        Instant now = Instant.now();
        windows.entrySet().removeIf(e -> e.getValue().expiresAt.isBefore(now));
    }

    private static final class Window {
        private final Instant expiresAt;
        private int count;

        private Window(Instant expiresAt, int count) {
            this.expiresAt = expiresAt;
            this.count = count;
        }
    }
}
