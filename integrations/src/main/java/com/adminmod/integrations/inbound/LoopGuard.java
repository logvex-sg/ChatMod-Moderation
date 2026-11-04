package com.adminmod.integrations.inbound;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Suppresses duplicate inbound messages.
 *
 * <p>Two situations produce duplicates. A platform may redeliver an event after a reconnect, and
 * a relay bot may echo a message back. Both are handled by remembering the identity of recent
 * messages for a short window: an identical {@code (platform, user, body)} seen again inside the
 * window is dropped.
 *
 * <p>The window is bounded by both a time and a count, so a busy channel cannot grow the table
 * without limit.
 */
public final class LoopGuard {

    private static final int MAX_ENTRIES = 512;

    private record Entry(String key, long at) {
    }

    private final Deque<Entry> recent = new ArrayDeque<>();
    private final long windowMillis;

    public LoopGuard(long windowMillis) {
        this.windowMillis = Math.max(0L, windowMillis);
    }

    /**
     * @return {@code true} when the message is new and should be shown, {@code false} when it is
     *         a duplicate of one already seen inside the window
     */
    public synchronized boolean accept(String platform, String user, String message, long now) {
        if (windowMillis == 0L) {
            return true;
        }
        purge(now);
        String key = key(platform, user, message);
        for (Entry entry : recent) {
            if (entry.key().equals(key)) {
                return false;
            }
        }
        recent.addLast(new Entry(key, now));
        while (recent.size() > MAX_ENTRIES) {
            recent.removeFirst();
        }
        return true;
    }

    private void purge(long now) {
        while (!recent.isEmpty() && now - recent.peekFirst().at() > windowMillis) {
            recent.removeFirst();
        }
    }

    private static String key(String platform, String user, String message) {
        return Objects.toString(platform, "") + '\u0000' + Objects.toString(user, "") + '\u0000'
                + Objects.toString(message, "");
    }

    synchronized int size() {
        return recent.size();
    }
}
