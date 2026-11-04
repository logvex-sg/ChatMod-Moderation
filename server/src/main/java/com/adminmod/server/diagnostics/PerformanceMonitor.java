package com.adminmod.server.diagnostics;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.Map;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.diagnostics.EventHistory;
import com.adminmod.common.diagnostics.MetricsRecorder;
import com.adminmod.server.AdminServerMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.Entity;

/**
 * Collects server and ADMIN performance samples on the tick thread.
 *
 * <p>Sampling is cheap by design: the TPS, MSPT and player counts are fields on the server, the
 * memory figures come from the JVM's own bean, and the ADMIN figures are counters other components
 * already keep. The two expensive metrics — counting entities and walking chunks — are off by
 * default and, when enabled, are sampled far less often than everything else, so the measurement
 * never becomes the load.
 *
 * <p>Every series is stored in a bounded {@link MetricsRecorder}, so memory is constant regardless
 * of uptime. Crossing an alert threshold records an event in the {@link EventHistory} at most once
 * per cooldown, which is what stops a sustained problem from flooding the feed.
 *
 * <p>It runs on the server thread only, from the end-of-tick event.
 */
public final class PerformanceMonitor {

    private final AdminConfig config;
    private final MetricsRecorder metrics;
    private final EventHistory events;

    private int tickCounter;
    private int entityTickCounter;
    private long lastAlertAt;
    private String lastAlertKey = "";
    private volatile long lastSampleAt;
    private volatile double lastMspt;
    private volatile double lastTps;
    private volatile int lastEntities;
    private volatile int lastChunks;
    private volatile double lastAdminMspt;

    /** Nanoseconds spent inside ADMIN's own per-tick work during the last window. */
    private long adminNanosWindow;

    public PerformanceMonitor(AdminConfig config, MetricsRecorder metrics, EventHistory events) {
        this.config = config;
        this.metrics = metrics;
        this.events = events;
    }

    /** Called at the end of every tick. */
    public void tick(MinecraftServer server) {
        tickCounter++;
        int interval = Math.max(1, config.performance.sampleIntervalTicks);
        if (tickCounter % interval != 0) {
            return;
        }
        sample(server);
    }

    /** Records the nanoseconds ADMIN spent inside its own tick handlers for the current window. */
    public void recordAdminTickNanos(long nanos) {
        adminNanosWindow += nanos;
    }

    private void sample(MinecraftServer server) {
        lastSampleAt = System.currentTimeMillis();

        double mspt = server.getAverageTickTime();
        double tps = mspt <= 0 ? 20.0 : Math.min(20.0, 1000.0 / mspt);
        lastMspt = mspt;
        lastTps = tps;
        metrics.record("mspt", "ms", mspt);
        metrics.record("tps", "tps", tps);
        metrics.record("players", "players", server.getCurrentPlayerCount());

        int interval = Math.max(1, config.performance.sampleIntervalTicks);
        double adminMspt = adminNanosWindow / 1_000_000.0 / interval;
        adminNanosWindow = 0L;
        lastAdminMspt = adminMspt;
        metrics.record("admin_mspt", "ms", adminMspt);

        if (config.performance.collectCpu) {
            metrics.record("cpu_load", "fraction", processCpuLoad());
        }

        MemoryUsage heap = heapUsage();
        metrics.record("heap_used_mb", "MB", heap.getUsed() / 1_048_576.0);
        metrics.record("heap_max_mb", "MB", heap.getMax() / 1_048_576.0);
        double heapFraction = heap.getMax() <= 0 ? 0.0 : (double) heap.getUsed() / heap.getMax();

        if (config.performance.collectChunkActivity) {
            lastChunks = countChunks(server);
            metrics.record("chunks", "chunks", lastChunks);
        }
        if (config.performance.collectEntityCounts) {
            int everyTicks = Math.max(1, config.performance.entityCountIntervalTicks);
            if (++entityTickCounter * interval >= everyTicks) {
                entityTickCounter = 0;
                lastEntities = countEntities(server);
                metrics.record("entities", "entities", lastEntities);
            }
        }

        var bridge = AdminServerMod.chatBridge();
        if (bridge != null && bridge.dispatcher() != null) {
            metrics.record("bridge_queue", "messages", bridge.dispatcher().queuedCount());
        }

        checkAlerts(mspt, tps, heapFraction);
    }

    private void checkAlerts(double mspt, double tps, double heapFraction) {
        if (!config.alerts.enabled) {
            return;
        }
        String key = "";
        String detail = "";
        if (mspt >= config.alerts.msptWarn) {
            key = "mspt";
            detail = "MSPT " + round(mspt) + " is at or above the alert threshold " + config.alerts.msptWarn;
        } else if (tps <= config.alerts.tpsWarn) {
            key = "tps";
            detail = "TPS " + round(tps) + " is at or below the alert threshold " + config.alerts.tpsWarn;
        } else if (heapFraction >= config.alerts.memoryWarnFraction) {
            key = "memory";
            detail = "heap " + round(heapFraction * 100.0) + "% used, at or above the alert threshold "
                    + round(config.alerts.memoryWarnFraction * 100.0) + "%";
        }
        if (key.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = config.alerts.alertCooldownSeconds * 1000L;
        if (key.equals(lastAlertKey) && now - lastAlertAt < cooldown) {
            return;
        }
        lastAlertKey = key;
        lastAlertAt = now;
        events.alert(detail);
    }

    private static MemoryUsage heapUsage() {
        try {
            MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
            return bean.getHeapMemoryUsage();
        } catch (RuntimeException e) {
            return new MemoryUsage(0L, 0L, 0L, 0L);
        }
    }

    /** Process CPU load in the range 0..1, or -1 when the platform cannot report it. */
    private static double processCpuLoad() {
        try {
            com.sun.management.OperatingSystemMXBean bean =
                    (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double load = bean.getProcessCpuLoad();
            return load < 0 ? 0.0 : load;
        } catch (RuntimeException | LinkageError e) {
            // Not every JVM exposes the com.sun bean; the metric is simply omitted.
            return -1.0;
        }
    }

    private static int countChunks(MinecraftServer server) {
        int total = 0;
        for (ServerWorld world : server.getWorlds()) {
            total += world.getChunkManager().getLoadedChunkCount();
        }
        return total;
    }

    private static int countEntities(MinecraftServer server) {
        int total = 0;
        for (ServerWorld world : server.getWorlds()) {
            for (Entity ignored : world.iterateEntities()) {
                total++;
            }
        }
        return total;
    }

    /** Latest values, for the dashboard and the report export. */
    public Map<String, Object> snapshot() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mspt", round(lastMspt));
        map.put("tps", round(lastTps));
        map.put("adminMspt", round(lastAdminMspt));
        map.put("entities", lastEntities);
        map.put("chunks", lastChunks);
        map.put("sampledAt", lastSampleAt);
        return map;
    }

    public double lastMspt() {
        return lastMspt;
    }

    public double lastTps() {
        return lastTps;
    }

    public double lastAdminMspt() {
        return lastAdminMspt;
    }

    public int lastEntities() {
        return lastEntities;
    }

    public int lastChunks() {
        return lastChunks;
    }

    public MetricsRecorder metrics() {
        return metrics;
    }

    private static double round(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }
}
