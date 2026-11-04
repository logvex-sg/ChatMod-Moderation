package com.adminmod.common.protection;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.adminmod.common.model.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Bounded, per-category record of protection triggers.
 *
 * <p>Every anti-crash check that fires reports here, so an operator can see what the server is
 * actually defending against instead of trusting that the checks exist. The log is deliberately
 * small and lossy: it keeps a rolling window of recent events plus a lifetime counter per
 * category, and never grows without bound.
 *
 * <p>It is thread-safe because triggers fire from the server thread and, for a few checks, from
 * a network thread.
 */
public final class ViolationLog {

    /** What a check detected. New kinds are appended; the name is part of the diagnostics JSON. */
    public enum Kind {
        OVERSIZED_CHAT,
        CHAT_SPAM,
        COMMAND_SPAM,
        PACKET_FLOOD,
        JOIN_SPAM,
        CONNECTION_RATE,
        OVERSIZED_BOOK,
        OVERSIZED_SIGN,
        SUSPICIOUS_NBT,
        INVALID_INVENTORY,
        INVENTORY_ABUSE,
        ENTITY_INTERACTION,
        MALFORMED_PACKET,
        INTEGRATION_FAILURE,
        RESOURCE_EXHAUSTION
    }

    /** One recorded trigger. */
    public record Violation(long at, Kind kind, String subject, String detail) {
    }

    private final int capacity;
    private final Deque<Violation> recent = new ArrayDeque<>();
    private final Map<Kind, Long> counts = new LinkedHashMap<>();
    private volatile Violation last;

    public ViolationLog() {
        this(128);
    }

    public ViolationLog(int capacity) {
        this.capacity = Math.max(8, capacity);
    }

    public synchronized void record(Kind kind, String subject, String detail) {
        Kind key = kind == null ? Kind.RESOURCE_EXHAUSTION : kind;
        counts.merge(key, 1L, Long::sum);
        Violation violation = new Violation(System.currentTimeMillis(), key, safe(subject), safe(detail));
        if (recent.size() >= capacity) {
            recent.pollFirst();
        }
        recent.addLast(violation);
        last = violation;
    }

    public synchronized long count(Kind kind) {
        return counts.getOrDefault(kind, 0L);
    }

    public synchronized long total() {
        long sum = 0L;
        for (long value : counts.values()) {
            sum += value;
        }
        return sum;
    }

    public synchronized List<Violation> recent() {
        return List.copyOf(recent);
    }

    public Violation last() {
        return last;
    }

    public synchronized void reset() {
        recent.clear();
        counts.clear();
        last = null;
    }

    /** JSON view for the protection dashboard. */
    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("total", total());
        JsonObject byKind = new JsonObject();
        for (Map.Entry<Kind, Long> entry : counts.entrySet()) {
            byKind.addProperty(entry.getKey().name().toLowerCase(java.util.Locale.ROOT), entry.getValue());
        }
        root.add("counts", byKind);
        JsonArray records = new JsonArray();
        for (Violation violation : recent) {
            JsonObject entry = new JsonObject();
            entry.addProperty("at", violation.at());
            entry.addProperty("kind", violation.kind().name().toLowerCase(java.util.Locale.ROOT));
            entry.addProperty("subject", violation.subject());
            entry.addProperty("detail", violation.detail());
            records.add(entry);
        }
        root.add("recent", records);
        return Json.write(root);
    }

    private static String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 256 ? value : value.substring(0, 256);
    }
}
