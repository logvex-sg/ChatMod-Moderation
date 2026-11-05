package com.adminmod.common.audit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.model.Json;
import com.adminmod.common.storage.CompressedLogStore;
import com.adminmod.common.storage.StorageSettings;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The audit log: a bounded, asynchronous front end over {@link CompressedLogStore}.
 *
 * <p>Server code calls {@link #record(AuditEvent)} from the tick thread. That method filters the
 * event against the enabled categories, computes its retention expiry and offers it to a bounded
 * queue; it does no encoding, no locking and no I/O. A single dispatcher thread drains the queue,
 * encodes each event and hands it to the store, which does the actual disk and compression work on
 * its own threads. An event offered to a full queue is dropped and counted rather than blocking the
 * server.
 *
 * <p>Retention is expressed per record: a record carries the moment it may be deleted, computed
 * from the ordinary or the security window. Security-relevant events (moderation, permissions,
 * authentication, protection triggers) use the longer window and are never removed by the ordinary
 * sweep, which is what the specification means by "preserve important security events".
 */
public final class AuditLog implements AutoCloseable {

    private final AdminConfig config;
    private final CompressedLogStore store;
    private final BlockingQueue<AuditEvent> queue;
    private volatile Set<AuditCategory> enabledCategories;

    private Thread dispatcher;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong filtered = new AtomicLong();

    /**
     * @param directory    the directory the audit segments and archives live in
     * @param config       the live configuration; the audit and storage sections are read from it
     * @param msptSupplier a live MSPT reading so compression can pause during lag, or {@code null}
     */
    public AuditLog(Path directory, AdminConfig config, DoubleSupplier msptSupplier) {
        this.config = config;
        this.store = new CompressedLogStore(directory, StorageSettings.from(config.storage),
                config.audit.memoryBuffer, msptSupplier);
        this.queue = new ArrayBlockingQueue<>(Math.max(64, config.audit.queueCapacity));
        this.enabledCategories = resolveCategories(config);
    }

    private static Set<AuditCategory> resolveCategories(AdminConfig config) {
        Set<AuditCategory> categories = EnumSet.noneOf(AuditCategory.class);
        if (config.audit.categories != null) {
            for (String name : config.audit.categories) {
                AuditCategory category = AuditCategory.parse(name);
                if (category != null) {
                    categories.add(category);
                }
            }
        }
        return categories;
    }

    /** Opens the store and starts the dispatcher. */
    public void start() throws IOException {
        store.open();
        store.start();
        if (!running.compareAndSet(false, true)) {
            return;
        }
        dispatcher = new Thread(this::dispatchLoop, "adminmod-audit-dispatch");
        dispatcher.setDaemon(true);
        dispatcher.start();
    }

    /** Re-reads the retention windows and enabled categories after a hot reload. */
    public void reload() {
        this.enabledCategories = resolveCategories(config);
        this.store.updateSettings(StorageSettings.from(config.storage));
    }

    /**
     * Records an event.
     *
     * <p>Called from the server tick thread. Returns immediately; the only work done here is a set
     * membership test, a retention computation and a queue offer.
     *
     * @return {@code true} when the event was accepted for writing
     */
    public boolean record(AuditEvent event) {
        if (event == null || !config.audit.enabled) {
            filtered.incrementAndGet();
            return false;
        }
        if (!enabledCategories.contains(event.category())) {
            filtered.incrementAndGet();
            return false;
        }
        if (config.audit.writeThrough) {
            // Opt-in synchronous path: encode and hand straight to the store's writer queue.
            return store.append(event.encode(), event.timestamp(), expiryFor(event));
        }
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
            return false;
        }
        return true;
    }

    private long expiryFor(AuditEvent event) {
        int days = event.isSecurityRelevant() ? config.audit.securityRetentionDays : config.audit.retentionDays;
        return event.timestamp() + Math.max(1, days) * 86_400_000L;
    }

    private void dispatchLoop() {
        List<AuditEvent> batch = new ArrayList<>(64);
        while (running.get() || !queue.isEmpty()) {
            try {
                AuditEvent first = queue.poll(250, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, 63);
                    for (AuditEvent event : batch) {
                        if (store.append(event.encode(), event.timestamp(), expiryFor(event))) {
                            recorded.incrementAndGet();
                        } else {
                            dropped.incrementAndGet();
                        }
                    }
                    batch.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException e) {
                // A serialisation failure must not kill the dispatcher; the batch is counted dropped.
                dropped.addAndGet(batch.size());
                batch.clear();
            }
        }
    }

    /** Runs a search, clamping the limit to the configured maximum. */
    public List<AuditEvent> search(AuditQuery query) {
        AuditQuery effective = query == null ? AuditQuery.all(config.audit.maxQueryResults) : query;
        int limit = Math.max(1, Math.min(effective.limit(), config.audit.maxQueryResults));
        long from = effective.fromMillis() == null ? 0L : effective.fromMillis();
        long to = effective.toMillis() == null ? Long.MAX_VALUE : effective.toMillis();
        List<CompressedLogStore.Record> raw = store.query(from, to, Math.max(limit * 4, 256));
        List<AuditEvent> results = new ArrayList<>();
        for (CompressedLogStore.Record record : raw) {
            AuditEvent event = AuditEvent.decode(record.payload());
            if (event != null && effective.matches(event)) {
                results.add(event);
                if (results.size() >= limit) {
                    break;
                }
            }
        }
        return results;
    }

    /** The most recent events for one player, matched on name or UUID. */
    public List<AuditEvent> timeline(String player, int limit) {
        return search(new AuditQuery(player, null, null, null, null, null, null, null, null, null, limit));
    }

    /** A JSON view of a search, ready for the dashboard. */
    public String searchJson(AuditQuery query) {
        List<AuditEvent> events = search(query);
        JsonObject root = new JsonObject();
        root.addProperty("count", events.size());
        JsonArray array = new JsonArray();
        for (AuditEvent event : events) {
            array.add(event.toJson());
        }
        root.add("events", array);
        return Json.write(root);
    }

    /** A CSV export of a search. */
    public String exportCsv(AuditQuery query) {
        StringBuilder builder = new StringBuilder();
        builder.append("timestamp,category,severity,actor,actorUuid,subject,detail\n");
        for (AuditEvent event : search(query)) {
            builder.append(event.timestamp()).append(',')
                    .append(event.category().name().toLowerCase(java.util.Locale.ROOT)).append(',')
                    .append(event.severity().name().toLowerCase(java.util.Locale.ROOT)).append(',')
                    .append(csv(event.actor())).append(',')
                    .append(csv(event.actorUuid())).append(',')
                    .append(csv(event.subject())).append(',')
                    .append(csv(event.detail())).append('\n');
        }
        return builder.toString();
    }

    /** Writes a search to a file, choosing JSON or CSV from the extension, and returns the path. */
    public Path export(AuditQuery query, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String content = name.endsWith(".csv") ? exportCsv(query) : searchJson(query);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.indexOf(',') >= 0 || escaped.indexOf('"') >= 0 || escaped.indexOf('\n') >= 0) {
            return '"' + escaped + '"';
        }
        return escaped;
    }

    /** The storage view for one subsystem; the audit log owns exactly one store. */
    public String statusJson() {
        return store.statusJson("audit");
    }

    /** The archive index, for the archive inspection view. */
    public String archivesJson() {
        return store.archivesJson();
    }

    /** Forces a compression pass, used by the manual compress button. */
    public void requestCompression() {
        store.requestCompression();
    }

    public long recordedCount() {
        return recorded.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long filteredCount() {
        return filtered.get();
    }

    public int queuedCount() {
        return queue.size();
    }

    public CompressedLogStore store() {
        return store;
    }

    public Set<AuditCategory> enabledCategories() {
        return Set.copyOf(enabledCategories);
    }

    @Override
    public void close() {
        if (running.compareAndSet(true, false)) {
            Thread thread = dispatcher;
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(3_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        // Drain anything still queued so a clean shutdown loses no accepted event.
        AuditEvent event;
        while ((event = queue.poll()) != null) {
            if (store.append(event.encode(), event.timestamp(), expiryFor(event))) {
                recorded.incrementAndGet();
            }
        }
        store.close();
    }
}
