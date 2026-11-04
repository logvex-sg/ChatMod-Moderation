package com.adminmod.server.auth;

import java.util.HashMap;
import java.util.Map;

/**
 * Tracks failed authentication attempts per address and briefly locks out an address that keeps
 * failing.
 *
 * <p>The lockout is keyed by the peer address rather than by player, because a failed handshake is
 * cheap for the attacker to repeat with a fresh connection. State is bounded: an entry is dropped
 * once its window has passed, so the table is proportional to recently-active addresses only.
 *
 * <p>Time is passed in rather than read from the clock so the rule can be tested without sleeping.
 */
public final class AuthThrottle {

    private static final class Failures {
        int count;
        long lockedUntil;
        long lastFailure;
    }

    private final int limit;
    private final long lockoutMillis;
    private final Map<String, Failures> failures = new HashMap<>();

    public AuthThrottle(int limit, long lockoutMillis) {
        this.limit = Math.max(1, limit);
        this.lockoutMillis = Math.max(1_000L, lockoutMillis);
    }

    /** @return {@code true} when the address is currently locked out. */
    public synchronized boolean isLockedOut(String address, long now) {
        Failures state = failures.get(address);
        if (state == null) {
            return false;
        }
        if (state.lockedUntil > now) {
            return true;
        }
        // The lockout has lapsed; start the next window clean so an occasional typo never
        // accumulates into a permanent lock.
        if (now - state.lastFailure > lockoutMillis) {
            failures.remove(address);
        }
        return false;
    }

    /** Records a failed attempt and locks the address out once the limit is reached. */
    public synchronized void recordFailure(String address, long now) {
        Failures state = failures.computeIfAbsent(address, key -> new Failures());
        state.count++;
        state.lastFailure = now;
        if (state.count >= limit) {
            state.lockedUntil = now + lockoutMillis;
        }
    }

    /** Clears the failure count for an address after a successful authentication. */
    public synchronized void recordSuccess(String address) {
        failures.remove(address);
    }

    /** Milliseconds until the address may try again, or {@code 0} when it is not locked out. */
    public synchronized long retryAfterMillis(String address, long now) {
        Failures state = failures.get(address);
        if (state == null || state.lockedUntil <= now) {
            return 0L;
        }
        return state.lockedUntil - now;
    }

    /** Drops entries whose lockout and window have both lapsed. */
    public synchronized void prune(long now) {
        failures.entrySet().removeIf(entry ->
                entry.getValue().lockedUntil <= now && now - entry.getValue().lastFailure > lockoutMillis);
    }

    synchronized int trackedAddresses() {
        return failures.size();
    }
}
