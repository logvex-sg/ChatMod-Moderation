package com.adminmod.common.settings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A bounded, in-memory record of configuration changes.
 *
 * <p>Every accepted change — a single setting, a category reset, a profile apply, an import — is
 * appended with who made it, when, the old value and the new one. The most recent entry can be
 * rolled back, which restores the previous value of every setting the change touched.
 *
 * <p>The history is intentionally small and lives only for the life of the server process: it is a
 * safety net for "undo that", not an audit trail. The durable record is the audit log.
 */
public final class ConfigHistory {

    /** One recorded change. */
    public record Change(long at, String actor, String summary, Map<String, String> before,
            Map<String, String> after) {

        /** The single-setting convenience constructor. */
        public static Change of(String actor, String summary, String path, String before, String after) {
            return new Change(System.currentTimeMillis(), actor, summary,
                    Map.of(path, before), Map.of(path, after));
        }

        public boolean single() {
            return before.size() == 1;
        }
    }

    private final int capacity;
    private final Deque<Change> changes = new ArrayDeque<>();

    public ConfigHistory(int capacity) {
        this.capacity = Math.max(8, capacity);
    }

    /** Appends a change, dropping the oldest when the capacity is reached. */
    public void record(Change change) {
        if (change == null || change.before().isEmpty()) {
            return;
        }
        synchronized (changes) {
            changes.addLast(change);
            while (changes.size() > capacity) {
                changes.removeFirst();
            }
        }
    }

    /** Records a single-setting change. */
    public void record(String actor, String summary, String path, String before, String after) {
        if (before != null && before.equals(after)) {
            return;
        }
        record(Change.of(actor, summary, path, before == null ? "" : before, after == null ? "" : after));
    }

    /** The most recent change, or {@code null} when nothing has changed. */
    public Change last() {
        synchronized (changes) {
            return changes.peekLast();
        }
    }

    /** Removes and returns the most recent change, for a rollback. */
    public Change pop() {
        synchronized (changes) {
            return changes.pollLast();
        }
    }

    /** The recorded changes, newest first. */
    public List<Change> recent() {
        synchronized (changes) {
            List<Change> list = new ArrayList<>(changes);
            java.util.Collections.reverse(list);
            return list;
        }
    }

    public int size() {
        synchronized (changes) {
            return changes.size();
        }
    }

    public void clear() {
        synchronized (changes) {
            changes.clear();
        }
    }
}
