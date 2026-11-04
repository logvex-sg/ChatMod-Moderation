package com.adminmod.common.protection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-player packet-frequency monitor.
 *
 * <p>It watches a sustained rate rather than any single packet, because a legitimate client is
 * bursty: teleporting, opening a large chest or eating produces short spikes that must not be
 * flagged. The monitor therefore compares a one-second window and a longer ten-second window, and
 * only reports when both are over their thresholds. A transient lag spike that inflates one
 * second is forgiven as soon as the ten-second average recovers.
 *
 * <p>The caller supplies the thresholds so they can be reconfigured live. The state is bounded:
 * only the most recent {@code windowMillis} of timestamps per player are kept, and the table is
 * capped.
 */
public final class PacketFrequencyMonitor {

    private static final long SHORT_WINDOW_MILLIS = 1_000L;
    private static final long LONG_WINDOW_MILLIS = 10_000L;

    private static final class Counters {
        long shortStart;
        int shortCount;
        long longStart;
        int longCount;
        long lastSeen;
    }

    private final int maxKeys;
    private final Map<String, Counters> players = new HashMap<>();
    private long lastSweep;

    public PacketFrequencyMonitor() {
        this(4_096);
    }

    public PacketFrequencyMonitor(int maxKeys) {
        this.maxKeys = Math.max(16, maxKeys);
    }

    /**
     * Counts one packet from {@code key}.
     *
     * @param shortLimit packets allowed inside one second before the short window is over budget
     * @param longLimit  packets allowed inside ten seconds before the long window is over budget
     * @return {@code true} when both windows are over budget, i.e. a sustained flood
     */
    public synchronized boolean observe(String key, long now, int shortLimit, int longLimit) {
        if (key == null) {
            return false;
        }
        sweep(now);
        Counters counters = players.get(key);
        if (counters == null) {
            if (players.size() >= maxKeys) {
                evictOldest();
            }
            counters = new Counters();
            counters.shortStart = now;
            counters.longStart = now;
            players.put(key, counters);
        }
        if (now - counters.shortStart >= SHORT_WINDOW_MILLIS) {
            counters.shortStart = now;
            counters.shortCount = 0;
        }
        if (now - counters.longStart >= LONG_WINDOW_MILLIS) {
            counters.longStart = now;
            counters.longCount = 0;
        }
        counters.shortCount++;
        counters.longCount++;
        counters.lastSeen = now;
        return counters.shortCount > shortLimit && counters.longCount > longLimit;
    }

    /** The current packets-per-second estimate for a player, for diagnostics. */
    public synchronized double packetsPerSecond(String key, long now) {
        Counters counters = players.get(key);
        if (counters == null) {
            return 0.0;
        }
        long elapsed = Math.max(1L, now - counters.shortStart);
        return counters.shortCount * 1000.0 / elapsed;
    }

    public synchronized void forget(String key) {
        if (key != null) {
            players.remove(key);
        }
    }

    public synchronized int trackedPlayers() {
        return players.size();
    }

    public synchronized void clear() {
        players.clear();
    }

    private void sweep(long now) {
        if (now - lastSweep < LONG_WINDOW_MILLIS) {
            return;
        }
        lastSweep = now;
        Iterator<Map.Entry<String, Counters>> iterator = players.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Counters> entry = iterator.next();
            if (now - entry.getValue().lastSeen > LONG_WINDOW_MILLIS * 3L) {
                iterator.remove();
            }
        }
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Counters> entry : players.entrySet()) {
            if (entry.getValue().lastSeen < oldest) {
                oldest = entry.getValue().lastSeen;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            players.remove(oldestKey);
        }
    }
}
