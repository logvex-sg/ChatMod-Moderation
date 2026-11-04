package com.adminmod.server.auth;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Bounded, in-memory record of authentication events for the dashboard.
 *
 * <p>Only non-secret facts are kept: who attempted, the outcome and a coarse reason. No password,
 * pairing key or client secret is ever passed in, so the history cannot become a credential leak.
 * The history is deliberately not persisted, because it describes sessions that no longer exist.
 */
public final class AuthHistory {

    /** One authentication attempt or session event. */
    public record Event(long timestamp, String actor, String action, boolean success, String detail) {
    }

    private final Deque<Event> events = new ArrayDeque<>();
    private final int limit;

    public AuthHistory(int limit) {
        this.limit = Math.max(16, limit);
    }

    public synchronized void record(String actor, String action, boolean success, String detail) {
        if (events.size() >= limit) {
            events.pollFirst();
        }
        events.addLast(new Event(System.currentTimeMillis(), safe(actor), safe(action), success, safe(detail)));
    }

    /** Newest first. */
    public synchronized List<Event> recent() {
        return List.copyOf(events).reversed();
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized void clear() {
        events.clear();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
