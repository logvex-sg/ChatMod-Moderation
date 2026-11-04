package com.adminmod.server.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.diagnostics.ErrorReporter;
import com.adminmod.common.diagnostics.EventHistory;
import com.adminmod.common.diagnostics.MetricsRecorder;
import com.adminmod.common.model.Json;
import com.adminmod.server.AdminServerMod;
import com.adminmod.server.chat.ChatBridgeService;
import com.adminmod.server.moderation.ModerationService;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;

/**
 * Assembles the diagnostics payloads and writes the exportable report.
 *
 * <p>Everything the dashboard shows is produced here from components that already exist: the
 * {@link PerformanceMonitor}'s series, the {@link ErrorReporter}'s counters, the
 * {@link EventHistory} ring and the live queue and cache sizes. Nothing is recomputed, so opening
 * the dashboard does not add load.
 *
 * <p>The export is a single JSON file written under {@code config/admin/reports/}. It is the
 * artefact an operator attaches to a bug report, and it is deliberately free of credentials: the
 * bridge status it includes is the same redacted view the dashboard gets.
 */
public final class DiagnosticsService {

    private final AdminConfig config;
    private final MinecraftServer server;
    private final PerformanceMonitor performance;
    private final ErrorReporter errors;
    private final EventHistory events;
    private final MetricsRecorder metrics;
    private final Path reportsDirectory;

    public DiagnosticsService(AdminConfig config, MinecraftServer server, PerformanceMonitor performance,
            ErrorReporter errors, EventHistory events, MetricsRecorder metrics, Path reportsDirectory) {
        this.config = config;
        this.server = server;
        this.performance = performance;
        this.errors = errors;
        this.events = events;
        this.metrics = metrics;
        this.reportsDirectory = reportsDirectory;
    }

    /** The live health view for the diagnostics tab. */
    public String statusJson() {
        JsonObject root = new JsonObject();
        root.addProperty("profile", config.profile);
        root.addProperty("uptimeMillis", uptimeMillis());
        root.addProperty("mspt", performance.lastMspt());
        root.addProperty("tps", performance.lastTps());
        root.addProperty("adminMspt", performance.lastAdminMspt());
        root.addProperty("players", server.getCurrentPlayerCount());
        root.addProperty("maxPlayers", server.getMaxPlayerCount());
        root.addProperty("entities", performance.lastEntities());
        root.addProperty("chunks", performance.lastChunks());
        root.addProperty("heapUsedMb", heapUsedMb());
        root.addProperty("heapMaxMb", heapMaxMb());
        root.addProperty("errors", errors.total());
        root.addProperty("seriousErrors", errors.hasSeriousErrors());

        ChatBridgeService bridge = AdminServerMod.chatBridge();
        root.addProperty("bridgeQueue", bridge == null || bridge.dispatcher() == null
                ? 0 : bridge.dispatcher().queuedCount());
        root.addProperty("bridgeDelivered", bridge == null || bridge.dispatcher() == null
                ? 0 : bridge.dispatcher().deliveredCount());
        root.addProperty("bridgeFailed", bridge == null || bridge.dispatcher() == null
                ? 0 : bridge.dispatcher().failedCount());

        ModerationService moderation = AdminServerMod.moderation();
        root.addProperty("punishments", moderation == null ? 0 : moderation.store().allPunishments().size());

        JsonArray components = new JsonArray();
        errors.countsByComponent().forEach((name, count) -> {
            JsonObject entry = new JsonObject();
            entry.addProperty("component", name);
            entry.addProperty("count", count);
            components.add(entry);
        });
        root.add("errorComponents", components);

        root.add("metrics", com.google.gson.JsonParser.parseString(metrics.toJson()));
        return Json.write(root);
    }

    /** Recent server and staff events, newest first. */
    public String historyJson() {
        return events.toJson();
    }

    /**
     * Writes the exportable report and returns its path.
     *
     * @throws IOException when the report could not be written
     */
    public Path exportReport() throws IOException {
        Files.createDirectories(reportsDirectory);
        String name = "admin-report-" + Instant.now().toString().replace(':', '-') + ".json";
        Path file = reportsDirectory.resolve(name);
        Files.writeString(file, reportJson(), StandardCharsets.UTF_8);
        events.server("diagnostic report exported to " + name);
        return file;
    }

    /** The full report as JSON text: status, metrics, errors and recent events. */
    public String reportJson() {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("modVersion", AdminServerMod.VERSION);
        root.addProperty("protocol", AdminServerMod.protocolVersion());
        root.add("status", com.google.gson.JsonParser.parseString(statusJson()));
        root.add("metrics", com.google.gson.JsonParser.parseString(metrics.toJson()));
        root.add("errors", com.google.gson.JsonParser.parseString(errors.toJson()));
        root.add("events", com.google.gson.JsonParser.parseString(events.toJson()));
        return Json.write(root);
    }

    /** Path of the directory the reports are written to, for the dashboard to display. */
    public String reportsPath() {
        return reportsDirectory.toAbsolutePath().toString();
    }

    private long uptimeMillis() {
        return System.currentTimeMillis() - AdminServerMod.startedAt();
    }

    private static double heapUsedMb() {
        Runtime runtime = Runtime.getRuntime();
        return round((runtime.totalMemory() - runtime.freeMemory()) / 1_048_576.0);
    }

    private static double heapMaxMb() {
        return round(Runtime.getRuntime().maxMemory() / 1_048_576.0);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** Snapshot map for the benchmark harness. */
    public Map<String, Object> snapshot() {
        return performance.snapshot();
    }
}
