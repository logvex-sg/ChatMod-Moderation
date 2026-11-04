package com.adminmod.common.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A small, bounded, time-limited cache of per-player records.
 *
 * <p>Building a player summary touches the world, the inventory and the moderation store, which is
 * far more work than the dashboard needs for a row of text. The cache means a burst of dashboard
 * refreshes reads the same record instead of rebuilding it, and a record is rebuilt only after its
 * TTL has passed or the player's state is known to have changed.
 *
 * <p>Eviction is least-recently-used once the limit is reached, and a record older than the TTL is
 * treated as absent. Both bounds come from configuration, so a large server can raise them and a
 * small one keeps the footprint tiny. The class is thread-safe.
 */
public final class PlayerCache<V> {

    private static final class Entry<V> {
        V value;
        long builtAt;
        long lastAccess;

        Entry(V value, long builtAt) {
            this.value = value;
            this.builtAt = builtAt;
            this.lastAccess = builtAt;
        }
    }

    private final int limit;
    private final long ttlMillis;
    private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);

    private long hits;
    private long misses;
    private long evictions;

    public PlayerCache(int limit, long ttlMillis) {
        this.limit = Math.max(1, limit);
        this.ttlMillis = Math.max(1L, ttlMillis);
    }

    /**
     * Returns the cached value for {@code key}, building it with {@code builder} when absent or
     * stale.
     */
    public synchronized V get(String key, long now, Supplier<V> builder) {
        if (key == null) {
            return builder.get();
        }
        Entry<V> entry = entries.get(key);
        if (entry != null && now - entry.builtAt <= ttlMillis) {
            entry.lastAccess = now;
            hits++;
            return entry.value;
        }
        V value = builder.get();
        entries.remove(key);
        put(key, value, now);
        misses++;
        return value;
    }

    /** Inserts or replaces a value, refreshing its timestamp. */
    public synchronized void put(String key, V value, long now) {
        if (key == null) {
            return;
        }
        entries.put(key, new Entry<>(value, now));
        evict();
    }

    /** Marks a record stale so the next read rebuilds it. */
    public synchronized void invalidate(String key) {
        if (key != null) {
            entries.remove(key);
        }
    }

    public synchronized void clear() {
        entries.clear();
    }

    /** Drops records older than the TTL; called periodically by the server. */
    public synchronized int sweep(long now) {
        int before = entries.size();
        entries.entrySet().removeIf(entry -> now - entry.getValue().builtAt > ttlMillis);
        return before - entries.size();
    }

    private void evict() {
        while (entries.size() > limit) {
            String oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
            evictions++;
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized long hits() {
        return hits;
    }

    public synchronized long misses() {
        return misses;
    }

    public synchronized long evictions() {
        return evictions;
    }

    /** Hit ratio in the range 0..1, or 0 when nothing has been requested yet. */
    public synchronized double hitRatio() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    public synchronized List<String> keys() {
        return new ArrayList<>(entries.keySet());
    }

    /** Snapshot for the diagnostics dashboard. */
    public synchronized Map<String, Object> stats() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("size", entries.size());
        map.put("limit", limit);
        map.put("hits", hits);
        map.put("misses", misses);
        map.put("evictions", evictions);
        map.put("hitRatio", Math.round(hitRatio() * 1000.0) / 1000.0);
        return map;
    }
}
