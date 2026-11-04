package com.adminmod.common.security;

import java.util.HashMap;
import java.util.Map;

import com.adminmod.common.net.AdminProtocol;

/**
 * Rejects frames whose nonce has already been seen, and frames whose timestamp falls outside
 * the accepted clock skew.
 *
 * <p>Nonces are only remembered for a bounded window so the table cannot grow without limit
 * on a long-running server. Because every nonce is a 64-bit random value, a replay can only
 * succeed inside that window, which the clock-skew check already keeps small.
 */
public final class ReplayGuard {

    private final Map<Long, Long> seen = new HashMap<>();
    private final long windowMillis;

    public ReplayGuard() {
        this(AdminProtocol.REPLAY_WINDOW_MILLIS);
    }

    public ReplayGuard(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    /**
     * @return {@code true} when the frame is fresh and should be processed; {@code false} when
     *         it is stale or a duplicate.
     */
    public synchronized boolean accept(long nonce, long timestamp, long now) {
        long drift = Math.abs(now - timestamp);
        if (drift > AdminProtocol.MAX_CLOCK_SKEW_MILLIS) {
            return false;
        }
        purge(now);
        if (seen.containsKey(nonce)) {
            return false;
        }
        seen.put(nonce, now);
        return true;
    }

    private void purge(long now) {
        seen.entrySet().removeIf(entry -> now - entry.getValue() > windowMillis);
    }

    synchronized int size() {
        return seen.size();
    }
}
