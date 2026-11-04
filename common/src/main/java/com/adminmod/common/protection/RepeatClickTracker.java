package com.adminmod.common.protection;

import java.util.HashMap;
import java.util.Map;

/**
 * Detects a rapid burst of identical inventory clicks.
 *
 * <p>Repeating the same (window, slot, button, action) click dozens of times a second is the
 * signature of a duplication attempt and is something a human hand cannot produce. The tracker
 * keeps only the previous signature and the count of consecutive repeats inside the window, so its
 * per-player state is two fields rather than a history list.
 *
 * <p>Any different click resets the run, which keeps a fast but legitimate shift-click drag from
 * accumulating across unrelated slots.
 */
public final class RepeatClickTracker {

    private static final class Run {
        long signature;
        long startedAt;
        int count;
        long lastSeen;
    }

    private final int maxKeys;
    private final Map<String, Run> runs = new HashMap<>();

    public RepeatClickTracker(int maxKeys) {
        this.maxKeys = Math.max(16, maxKeys);
    }

    /**
     * Records one click.
     *
     * @return {@code true} when the identical click has now repeated at least {@code threshold}
     *         times inside {@code windowMillis}
     */
    public synchronized boolean observe(String playerKey, long signature, long now, int threshold,
            long windowMillis) {
        if (playerKey == null) {
            return false;
        }
        Run run = runs.get(playerKey);
        if (run == null) {
            if (runs.size() >= maxKeys) {
                evictOldest();
            }
            run = new Run();
            run.signature = signature;
            run.startedAt = now;
            run.count = 1;
            run.lastSeen = now;
            runs.put(playerKey, run);
            return false;
        }
        run.lastSeen = now;
        if (run.signature == signature && now - run.startedAt <= windowMillis) {
            run.count++;
            return run.count >= threshold;
        }
        run.signature = signature;
        run.startedAt = now;
        run.count = 1;
        return false;
    }

    public synchronized void forget(String playerKey) {
        if (playerKey != null) {
            runs.remove(playerKey);
        }
    }

    public synchronized int trackedPlayers() {
        return runs.size();
    }

    public synchronized void clear() {
        runs.clear();
    }

    private void evictOldest() {
        String oldestKey = null;
        long oldest = Long.MAX_VALUE;
        for (Map.Entry<String, Run> entry : runs.entrySet()) {
            if (entry.getValue().lastSeen < oldest) {
                oldest = entry.getValue().lastSeen;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            runs.remove(oldestKey);
        }
    }
}
