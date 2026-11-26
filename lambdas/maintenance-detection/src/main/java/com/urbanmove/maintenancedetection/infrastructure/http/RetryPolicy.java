package com.urbanmove.maintenancedetection.infrastructure.http;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongUnaryOperator;

/** Reintentos con espera exponencial y jitter completo (AGENTS.md 6.4). */
public final class RetryPolicy {
    private final int maxAttempts;
    private final Duration baseDelay;
    private final Duration maxDelay;
    private final LongUnaryOperator jitter;

    public RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay, LongUnaryOperator jitter) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelay = Objects.requireNonNull(baseDelay);
        this.maxDelay = Objects.requireNonNull(maxDelay);
        this.jitter = Objects.requireNonNull(jitter);
    }

    public static RetryPolicy withRandomJitter(int maxAttempts) {
        return new RetryPolicy(maxAttempts, Duration.ofMillis(200), Duration.ofSeconds(2),
                bound -> java.util.concurrent.ThreadLocalRandom.current().nextLong(bound + 1));
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration delayAfter(int failedAttempt) {
        long exponential = baseDelay.toMillis() << Math.min(failedAttempt - 1, 20);
        long capped = Math.min(maxDelay.toMillis(), exponential);
        return Duration.ofMillis(jitter.applyAsLong(capped));
    }
}
