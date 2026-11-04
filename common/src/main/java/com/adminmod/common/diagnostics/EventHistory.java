package com.adminmod.common.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.adminmod.common.model.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Bounded, timestamped record of notable server and staff events.
 *
 * <p>Three views are served from one ring: the recent server events (start, stop, reload, alerts),
 * the staff activity feed (who ran which moderation or console action) and the administrative
 * action history. They share a shape — time, category, actor, subject, detail — so the dashboard
 * can render any of them with one component.
 *
 * <p>The ring is bounded by {@code capacity}; older entries are dropped. Entries older than the
 * configured retention are dropped when {@link #sweep} runs, which the server calls periodically.
 */
public final class EventHistory {

    /** Broad category, used to filter the feed on the dashboard. */
    public enum Category {
        SERVER,
        STAFF,
        MODERATION,
        CONSOLE,
        ALERT,
        PROTECTION,
        INTEGRATION
    }

    /** One recorded event. */
    public record Event(long at, Category category, String actor, String subject, String detail) {
    }

    private final int capacity;
    private final Deque<Event> events = new ArrayDeque<>();

    public EventHistory(int capacity) {
        this.capacity = Math.max(16, capacity);
    }

    public synchronized void record(Category category, String actor, String subject, String detail) {
        Event event = new Event(System.currentTimeMillis(), category, safe(actor), safe(subject), safe(detail));
        if (events.size() >= capacity) {
            events.pollFirst();
        }
        events.addLast(event);
    }

    public synchronized void server(String detail) {
        record(Category.SERVER, "system", "", detail);
    }

    public synchronized void staff(String actor, String subject, String detail) {
        record(Category.STAFF, actor, subject, detail);
    }

    public synchronized void moderation(String actor, String subject, String detail) {
        record(Category.MODERATION, actor, subject, detail);
    }

    public synchronized void alert(String detail) {
        record(Category.ALERT, "system", "", detail);
    }

    public synchronized List<Event> recent() {
        return List.copyOf(events);
    }

    /** Most recent events of one category, newest first. */
    public synchronized List<Event> recent(Category category, int limit) {
        List<Event> out = new ArrayList<>();
        java.util.Iterator<Event> iterator = events.descendingIterator();
        while (iterator.hasNext() && out.size() < limit) {
            Event event = iterator.next();
            if (event.category() == category) {
                out.add(event);
            }
        }
        return out;
    }

    /** Drops events older than {@code retentionMillis}. */
    public synchronized void sweep(long retentionMillis) {
        long cutoff = System.currentTimeMillis() - Math.max(0L, retentionMillis);
        while (!events.isEmpty() && events.peekFirst().at() < cutoff) {
            events.pollFirst();
        }
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized void clear() {
        events.clear();
    }

    /** JSON view for the diagnostics and history dashboards. */
    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("size", events.size());
        JsonArray array = new JsonArray();
        java.util.Iterator<Event> iterator = events.descendingIterator();
        while (iterator.hasNext()) {
            Event event = iterator.next();
            JsonObject object = new JsonObject();
            object.addProperty("at", event.at());
            object.addProperty("category", event.category().name().toLowerCase(java.util.Locale.ROOT));
            object.addProperty("actor", event.actor());
            object.addProperty("subject", event.subject());
            object.addProperty("detail", event.detail());
            array.add(object);
        }
        root.add("events", array);
        return Json.write(root);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
