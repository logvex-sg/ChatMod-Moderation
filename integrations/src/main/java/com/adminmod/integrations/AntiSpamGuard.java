package com.adminmod.integrations;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

import com.adminmod.common.config.AdminConfig;

/**
 * Rate guard for the outbound bridge.
 *
 * <p>It exists so one player cannot flood the external channel or burn the webhook rate limit
 * for everybody else. Two rules are applied: a minimum gap between messages from the same
 * player, and a cap on how often the same body may be repeated inside a window. Both are keyed
 * per player so a busy server does not let one spammer silence everyone.
 *
 * <p>State is bounded: the per-player table is pruned to the window on every call, so memory is
 * proportional to the number of players who spoke recently, not to the server's lifetime.
 */
public final class AntiSpamGuard {

    private final AdminConfig.AntiSpam config;

    private final Map<String, Long> lastSeen = new HashMap<>();
    private final Map<String, Deque<Repeat>> repeats = new HashMap<>();

    private record Repeat(String body, long at) {
    }

    public AntiSpamGuard(AdminConfig.AntiSpam config) {
        this.config = config;
    }

    /**
     * @return {@code true} when the message may be forwarded, {@code false} when it should be
     *         dropped as spam
     */
    public synchronized boolean accept(String playerKey, String message, long now) {
        if (!config.enabled) {
            return true;
        }
        prune(now);
        String key = playerKey == null ? "" : playerKey.toLowerCase(Locale.ROOT);

        Long previous = lastSeen.get(key);
        if (previous != null && now - previous < config.minIntervalMillis) {
            return false;
        }

        if (config.duplicateWindowSeconds > 0L && config.maxDuplicates >= 0) {
            long windowMillis = config.duplicateWindowSeconds * 1000L;
            Deque<Repeat> history = repeats.computeIfAbsent(key, k -> new ArrayDeque<>());
            int identical = 0;
            for (Repeat repeat : history) {
                if (repeat.body().equals(message) && now - repeat.at() <= windowMillis) {
                    identical++;
                }
            }
            if (identical >= config.maxDuplicates) {
                // Still record the attempt so the gap keeps growing while the player repeats.
                lastSeen.put(key, now);
                return false;
            }
            history.addLast(new Repeat(message, now));
        }

        lastSeen.put(key, now);
        return true;
    }

    /** Drops state for players who have been quiet for longer than the widest window. */
    private void prune(long now) {
        long widest = Math.max(config.duplicateWindowSeconds * 1000L, 60_000L);
        Iterator<Map.Entry<String, Long>> seenIterator = lastSeen.entrySet().iterator();
        while (seenIterator.hasNext()) {
            if (now - seenIterator.next().getValue() > widest) {
                seenIterator.remove();
            }
        }
        Iterator<Map.Entry<String, Deque<Repeat>>> repeatIterator = repeats.entrySet().iterator();
        while (repeatIterator.hasNext()) {
            Deque<Repeat> history = repeatIterator.next().getValue();
            while (!history.isEmpty() && now - history.peekFirst().at() > widest) {
                history.removeFirst();
            }
            if (history.isEmpty()) {
                repeatIterator.remove();
            }
        }
    }

    synchronized int trackedPlayers() {
        return lastSeen.size();
    }
}
