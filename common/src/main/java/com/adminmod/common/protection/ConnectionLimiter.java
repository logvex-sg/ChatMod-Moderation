package com.adminmod.common.protection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Connection-level protection: a per-address accept rate, a concurrent-connection cap and a set
 * of temporary restrictions.
 *
 * <p>All three are deliberately forgiving. A rate that only trips on a genuine flood, a cap well
 * above a normal household's shared address, and restrictions that expire on their own mean a
 * player on a laggy or double-NATed connection is not locked out. The point is to blunt a scripted
 * connection flood, not to police honest reconnects.
 *
 * <p>The address table is bounded and idle entries are swept, so a flood that cycles spoofed
 * addresses cannot exhaust memory.
 */
public final class ConnectionLimiter {

    /** How long an automatic restriction lasts, in milliseconds. */
    public static final long RESTRICTION_MILLIS = 60_000L;

    private static final long IDLE_SWEEP_MILLIS = 120_000L;

    private static final class AddressState {
        int active;
        long lastSeen;
    }

    private final KeyedRateLimiter acceptRate;
    private final int maxPerAddress;
    private final int maxKeys;
    private final Map<String, AddressState> addresses = new HashMap<>();
    private final Map<String, Long> restrictedUntil = new HashMap<>();
    private long lastSweep;

    /**
     * @param acceptsPerMinute accept rate allowed from one address
     * @param maxPerAddress    concurrent connections allowed from one address
     * @param maxKeys          hard cap on tracked addresses
     */
    public ConnectionLimiter(int acceptsPerMinute, int maxPerAddress, int maxKeys) {
        this.acceptRate = new KeyedRateLimiter(acceptsPerMinute, 60_000L, maxKeys);
        this.maxPerAddress = Math.max(1, maxPerAddress);
        this.maxKeys = Math.max(16, maxKeys);
    }

    /**
     * Decides whether a new connection from {@code address} may proceed.
     *
     * @return {@code true} when accepted; {@code false} when it is over the rate, over the
     *         concurrent cap, or temporarily restricted
     */
    public synchronized boolean accept(String address, long now) {
        String key = normalise(address);
        sweep(now);
        Long until = restrictedUntil.get(key);
        if (until != null) {
            if (now < until) {
                return false;
            }
            restrictedUntil.remove(key);
        }
        if (!acceptRate.tryAcquire(key, now)) {
            restrict(key, now);
            return false;
        }
        AddressState state = addresses.computeIfAbsent(key, k -> new AddressState());
        if (state.active >= maxPerAddress) {
            return false;
        }
        state.active++;
        state.lastSeen = now;
        return true;
    }

    /** Records that a connection from {@code address} closed. */
    public synchronized void release(String address, long now) {
        String key = normalise(address);
        AddressState state = addresses.get(key);
        if (state != null) {
            state.active = Math.max(0, state.active - 1);
            state.lastSeen = now;
        }
    }

    /** Temporarily restricts an address, e.g. after a detected flood. */
    public synchronized void restrict(String address, long now) {
        restrictedUntil.put(normalise(address), now + RESTRICTION_MILLIS);
    }

    public synchronized boolean isRestricted(String address, long now) {
        Long until = restrictedUntil.get(normalise(address));
        return until != null && now < until;
    }

    public synchronized int activeFor(String address) {
        AddressState state = addresses.get(normalise(address));
        return state == null ? 0 : state.active;
    }

    public synchronized int trackedAddresses() {
        return addresses.size();
    }

    public synchronized int restrictedCount() {
        return restrictedUntil.size();
    }

    public synchronized void clear() {
        addresses.clear();
        restrictedUntil.clear();
        acceptRate.clear();
    }

    private void sweep(long now) {
        if (now - lastSweep < IDLE_SWEEP_MILLIS) {
            return;
        }
        lastSweep = now;
        Iterator<Map.Entry<String, AddressState>> iterator = addresses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AddressState> entry = iterator.next();
            if (entry.getValue().active <= 0 && now - entry.getValue().lastSeen > IDLE_SWEEP_MILLIS) {
                iterator.remove();
            }
        }
        restrictedUntil.entrySet().removeIf(entry -> now >= entry.getValue());
        while (addresses.size() > maxKeys) {
            addresses.remove(addresses.keySet().iterator().next());
        }
    }

    private static String normalise(String address) {
        if (address == null || address.isBlank()) {
            return "unknown";
        }
        // Strip the ephemeral port so two connections from one host share a bucket.
        int slash = address.indexOf('/');
        String host = slash >= 0 ? address.substring(slash + 1) : address;
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            host = host.substring(0, colon);
        }
        return host;
    }
}
