package com.adminmod.common.localchat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Enforces a per-player chat cooldown.
 *
 * <p>Time is passed in rather than read from the clock, so the rule can be tested without sleeping.
 * The table is bounded: entries older than the cooldown are dropped as they are encountered, so it
 * stays proportional to the players who have spoken recently rather than to everyone who ever has.
 */
public final class CooldownTracker {

    private final Map<String, Long> lastMessage = new HashMap<>();

    /**
     * Whether a player may speak now, recording the attempt when it is allowed.
     *
     * @param key      the player's stable key (UUID string)
     * @param now      the current time in epoch milliseconds
     * @param cooldown the minimum gap between messages, in milliseconds; zero disables the check
     * @return {@code true} when the message is allowed
     */
    public synchronized boolean tryAcquire(String key, long now, long cooldown) {
        if (cooldown <= 0 || key == null || key.isBlank()) {
            return true;
        }
        Long last = lastMessage.get(key);
        if (last != null && now - last < cooldown) {
            return false;
        }
        lastMessage.put(key, now);
        prune(now, cooldown);
        return true;
    }

    /** Milliseconds until a player may speak again, or {@code 0} when they may speak now. */
    public synchronized long retryAfterMillis(String key, long now, long cooldown) {
        if (cooldown <= 0 || key == null) {
            return 0L;
        }
        Long last = lastMessage.get(key);
        if (last == null) {
            return 0L;
        }
        long remaining = cooldown - (now - last);
        return Math.max(0L, remaining);
    }

    /** Drops entries whose cooldown has long expired. */
    public synchronized void prune(long now, long cooldown) {
        long window = Math.max(cooldown, 1_000L);
        Iterator<Map.Entry<String, Long>> iterator = lastMessage.entrySet().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().getValue() > window) {
                iterator.remove();
            }
        }
    }

    /** Clears all state, for a configuration reload. */
    public synchronized void clear() {
        lastMessage.clear();
    }

    synchronized int trackedPlayers() {
        return lastMessage.size();
    }
}
