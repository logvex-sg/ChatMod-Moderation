package com.adminmod.common.security;

/**
 * Per-key sliding window limiter used to cap how many remote requests a single session may
 * issue. The window is a plain timestamp ring, so memory stays proportional to the limit and
 * no background thread is required.
 */
public final class SlidingWindowRateLimiter {

    private final int limit;
    private final long windowMillis;
    private final long[] timestamps;

    private int cursor;
    private int count;

    public SlidingWindowRateLimiter(int limit, long windowMillis) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.timestamps = new long[limit];
    }

    /** @return {@code true} when the request is within budget. */
    public synchronized boolean tryAcquire(long now) {
        if (count == limit) {
            long oldest = timestamps[cursor];
            if (now - oldest < windowMillis) {
                return false;
            }
            timestamps[cursor] = now;
            cursor = (cursor + 1) % limit;
            return true;
        }
        timestamps[(cursor + count) % limit] = now;
        count++;
        return true;
    }

    public synchronized long millisUntilNextSlot(long now) {
        if (count < limit) {
            return 0L;
        }
        long oldest = timestamps[cursor];
        return Math.max(0L, windowMillis - (now - oldest));
    }
}
