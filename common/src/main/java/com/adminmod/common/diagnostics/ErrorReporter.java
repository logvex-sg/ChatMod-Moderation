package com.adminmod.common.diagnostics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.adminmod.common.model.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Central, bounded, structured error sink.
 *
 * <p>Every subsystem reports through this one place instead of writing to a logger directly, so a
 * component that starts failing is visible as a counter and a recent list rather than as a wall of
 * stack traces. It is deliberately free of any logging dependency: the server installs a
 * {@link Sink} that forwards to SLF4J, and tests run without one.
 *
 * <p>Serious errors are never silently swallowed. {@link #guard} and {@link #guardVoid} run an
 * action, catch everything it throws and record it; the caller supplies a safe fallback so the
 * surrounding work continues. The record is always kept in the counter and the "last" slot even
 * when the recent list has rolled over, so a fault that happens once an hour is still counted.
 *
 * <p>Memory is bounded: the recent list holds at most {@code capacity} entries, and the per
 * component maps hold at most {@code maxComponents} keys, evicting the oldest insertion.
 */
public final class ErrorReporter {

    /** Severity of a recorded event, ordered from least to most serious. */
    public enum Severity {
        DEBUG,
        INFO,
        WARN,
        ERROR,
        FATAL
    }

    /** One recorded event. {@code count} is how many times this (component, message) pair fired. */
    public record ErrorRecord(long at, String component, Severity severity, String message, String detail,
            long count) {
    }

    /** Destination for a recorded event, installed by the host so it can reach a logger. */
    @FunctionalInterface
    public interface Sink {
        void accept(ErrorRecord record);
    }

    private static final int DEFAULT_CAPACITY = 64;
    private static final int DEFAULT_MAX_COMPONENTS = 64;
    private static final int MAX_MESSAGE = 512;
    private static final int MAX_DETAIL = 2_048;

    private final int capacity;
    private final int maxComponents;
    private final Sink sink;

    private final Deque<ErrorRecord> recent = new ArrayDeque<>();
    private final Map<String, Long> countByComponent = new LinkedHashMap<>();
    private final Map<Severity, Long> countBySeverity = new LinkedHashMap<>();
    private final Map<String, Long> countBySignature = new LinkedHashMap<>();
    private final AtomicLong total = new AtomicLong();
    private volatile ErrorRecord last;

    public ErrorReporter() {
        this(DEFAULT_CAPACITY, DEFAULT_MAX_COMPONENTS, null);
    }

    public ErrorReporter(int capacity, int maxComponents, Sink sink) {
        this.capacity = Math.max(1, capacity);
        this.maxComponents = Math.max(1, maxComponents);
        this.sink = sink;
    }

    /** Records an event at {@code severity}. Never throws. */
    public void report(String component, Severity severity, String message, String detail) {
        Severity level = severity == null ? Severity.ERROR : severity;
        String key = component == null ? "unknown" : component;
        String text = truncate(message, MAX_MESSAGE);
        String extra = truncate(detail, MAX_DETAIL);
        String signature = key + '\u0000' + level + '\u0000' + text;

        ErrorRecord record;
        synchronized (this) {
            long count = countBySignature.merge(signature, 1L, Long::sum);
            countByComponent.merge(key, 1L, Long::sum);
            countBySeverity.merge(level, 1L, Long::sum);
            trim();
            record = new ErrorRecord(System.currentTimeMillis(), key, level, text, extra, count);
            if (recent.size() >= capacity) {
                recent.pollFirst();
            }
            recent.addLast(record);
            last = record;
        }
        total.incrementAndGet();
        if (sink != null) {
            try {
                sink.accept(record);
            } catch (RuntimeException ignored) {
                // A broken sink must not take the reporter down with it.
            }
        }
    }

    public void info(String component, String message) {
        report(component, Severity.INFO, message, null);
    }

    public void warn(String component, String message) {
        report(component, Severity.WARN, message, null);
    }

    public void error(String component, String message, Throwable cause) {
        report(component, Severity.ERROR, message, describe(cause));
    }

    public void fatal(String component, String message, Throwable cause) {
        report(component, Severity.FATAL, message, describe(cause));
    }

    /**
     * Runs {@code action}, returning {@code fallback} when it throws.
     *
     * <p>This is the isolation boundary: an exception from one subsystem must not be allowed to
     * reach the server tick and crash it. The failure is recorded, never discarded.
     */
    public <T> T guard(String component, T fallback, Supplier<T> action) {
        try {
            return action.get();
        } catch (Throwable t) {
            error(component, "unhandled failure: " + t.getClass().getSimpleName(), t);
            return fallback;
        }
    }

    /** Runs {@code action}, recording any failure and continuing. */
    public void guardVoid(String component, Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            error(component, "unhandled failure: " + t.getClass().getSimpleName(), t);
        }
    }

    /** Renders a throwable as a class + message line followed by a bounded stack trace. */
    public static String describe(Throwable t) {
        if (t == null) {
            return "";
        }
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return truncate(writer.toString(), MAX_DETAIL);
    }

    public synchronized List<ErrorRecord> recent() {
        return List.copyOf(recent);
    }

    public ErrorRecord last() {
        return last;
    }

    public long total() {
        return total.get();
    }

    public synchronized long countFor(String component) {
        return countByComponent.getOrDefault(component, 0L);
    }

    public synchronized Map<String, Long> countsByComponent() {
        return new LinkedHashMap<>(countByComponent);
    }

    /** Whether any ERROR or FATAL event has been recorded. */
    public synchronized boolean hasSeriousErrors() {
        return countBySeverity.getOrDefault(Severity.ERROR, 0L) > 0
                || countBySeverity.getOrDefault(Severity.FATAL, 0L) > 0;
    }

    /** Drops the recent list and the counters, after a report has been exported. */
    public synchronized void reset() {
        recent.clear();
        countByComponent.clear();
        countBySeverity.clear();
        countBySignature.clear();
        last = null;
        total.set(0L);
    }

    private void trim() {
        while (countByComponent.size() > maxComponents) {
            countByComponent.remove(countByComponent.keySet().iterator().next());
        }
        long signatureLimit = maxComponents * 4L;
        while (countBySignature.size() > signatureLimit) {
            countBySignature.remove(countBySignature.keySet().iterator().next());
        }
    }

    /** JSON view for the diagnostics dashboard. */
    public synchronized String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("total", total.get());
        root.addProperty("serious", hasSeriousErrors());
        JsonObject components = new JsonObject();
        for (Map.Entry<String, Long> entry : countByComponent.entrySet()) {
            components.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("components", components);
        JsonObject severities = new JsonObject();
        for (Map.Entry<Severity, Long> entry : countBySeverity.entrySet()) {
            severities.addProperty(entry.getKey().name().toLowerCase(Locale.ROOT), entry.getValue());
        }
        root.add("severities", severities);
        JsonArray records = new JsonArray();
        for (ErrorRecord record : recent) {
            JsonObject entry = new JsonObject();
            entry.addProperty("at", record.at());
            entry.addProperty("component", record.component());
            entry.addProperty("severity", record.severity().name());
            entry.addProperty("message", record.message());
            entry.addProperty("detail", record.detail());
            entry.addProperty("count", record.count());
            records.add(entry);
        }
        root.add("recent", records);
        return Json.write(root);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
