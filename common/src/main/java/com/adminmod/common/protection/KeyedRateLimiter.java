package com.adminmod.common.protection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-key token-bucket rate limiter with a bounded key table.
 *
 * <p>A token bucket is used rather than a fixed window because it tolerates a short burst (a
 * player opening a chest and moving a few stacks, say) while still enforcing a long-run average,
 * which is what a spam or flood check actually wants. A fixed window would either punish the
 * burst or let a client sit exactly on the boundary and double its effective rate.
 *
 * <p>State is bounded two ways. Idle keys are swept once the bucket would have refilled, and the
 * table is hard-capped at {@code maxKeys}, evicting the least recently touched key when full, so
 * a hostile client cannot grow the map by cycling identifiers.
 *
 * <p>It is thread-safe, though in practice the network checks run on the server thread.
 */
public final class KeyedRateLimiter {

    private static final class Bucket {
        double tokens;
        long updatedAt;
    }

    private final double capacity;
    private final double refillPerMilli;
    private final int maxKeys;

    private final Map<String, Bucket> buckets = new HashMap<>();
    private long lastSweep;

    /**
     * @param limit  tokens replenished per {@code windowMillis}; also the maximum burst
     * @param windowMillis the window over which {@code limit} tokens are replenished
     * @param maxKeys hard cap on tracked keys
     */
    public KeyedRateLimiter(int limit, long windowMillis, int maxKeys) {
        this.capacity = Math.max(1.0, limit);
        long window = Math.max(1L, windowMillis);
        this.refillPerMilli = this.capacity / window;
        this.maxKeys = Math.max(1, maxKeys);
    }

    /** @return {@code true} when the caller may proceed, {@code false} when it is over budget. */
    public synchronized boolean tryAcquire(String key, long now) {
        return tryAcquire(key, now, 1.0);
    }

    /** @return {@code true} when {@code cost} tokens are available and consumed. */
    public synchronized boolean tryAcquire(String key, long now, double cost) {
        if (key == null) {
            return true;
        }
        sweep(now);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            if (buckets.size() >= maxKeys) {
                evictOldest();
            }
            bucket = new Bucket();
            bucket.tokens = capacity;
            bucket.updatedAt = now;
            buckets.put(key, bucket);
        }
        refill(bucket, now);
        if (bucket.tokens + 1e-9 >= cost) {
            bucket.tokens -= cost;
            return true;
        }
        return false;
    }

    /** Tokens currently available to {@code key}, for diagnostics. */
    public synchronized double available(String key, long now) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return capacity;
        }
        refill(bucket, now);
        return bucket.tokens;
    }

    private void refill(Bucket bucket, long now) {
        if (now > bucket.updatedAt) {
            bucket.tokens = Math.min(capacity, bucket.tokens + (now - bucket.updatedAt) * refillPerMilli);
            bucket.updatedAt = now;
        }
    }

    /** Drops keys whose bucket is already full and that have been idle for a full window. */
    private void sweep(long now) {
        long refillMillis = (long) Math.ceil(1.0 / refillPerMilli);
        if (now - lastSweep < refillMillis) {
            return;
        }
        lastSweep = now;
        Iterator<Map.Entry<String, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Bucket> entry = iterator.next();
            Bucket bucket = entry.getValue();
            if (now - bucket.updatedAt >= refillMillis && bucket.tokens >= capacity - 1e-9) {
                iterator.remove();
            }
        }
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            if (entry.getValue().updatedAt < oldest) {
                oldest = entry.getValue().updatedAt;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            buckets.remove(oldestKey);
        }
    }

    /** Forgets one key, used when a player disconnects. */
    public synchronized void forget(String key) {
        if (key != null) {
            buckets.remove(key);
        }
    }

    public synchronized int trackedKeys() {
        return buckets.size();
    }

    public synchronized void clear() {
        buckets.clear();
    }
}
