package com.adminmod.integrations.http;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with full jitter, capped at a maximum delay. When the server advertises
 * a {@code Retry-After} value it takes precedence, because ignoring it is what turns a rate
 * limit into a ban.
 */
public final class RetryPolicy {

    private static final long MAX_RETRY_AFTER_MILLIS = 60_000L;

    private final long baseMillis;
    private final long maxMillis;

    public RetryPolicy(long baseMillis, long maxMillis) {
        this.baseMillis = Math.max(1L, baseMillis);
        this.maxMillis = Math.max(baseMillis, maxMillis);
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(500L, 30_000L);
    }

    /** @param attempt 1 for the first retry after the initial failure. */
    public long delayMillis(int attempt) {
        return delayMillis(attempt, 0L);
    }

    public long delayMillis(int attempt, long retryAfterMillis) {
        if (retryAfterMillis > 0L) {
            return Math.min(retryAfterMillis, MAX_RETRY_AFTER_MILLIS);
        }
        int exponent = Math.max(0, Math.min(attempt - 1, 20));
        long exponential = baseMillis << exponent;
        if (exponential <= 0L || exponential > maxMillis) {
            exponential = maxMillis;
        }
        long jitter = ThreadLocalRandom.current().nextLong(exponential + 1);
        return Math.max(1L, jitter);
    }
}
