package com.adminmod.common.config;

import java.util.List;
import java.util.Locale;

/**
 * The three optimisation profiles.
 *
 * <p>A profile is a starting point, not a lock: selecting one rewrites the protection, anticheat,
 * antidupe and performance thresholds to that profile's values, and the individual values stay
 * editable afterwards. Applying a profile never touches the master switches, so a profile cannot
 * silently turn protection off.
 *
 * <p>The three profiles differ only in how much ADMIN does, never in whether it does the checks
 * that only reject data a legitimate client cannot produce. Oversized payloads, malformed text and
 * out-of-range slot indices are rejected in every profile; what changes is how aggressively the
 * rate-based heuristics fire and how much diagnostic data is collected.
 */
public final class PerformanceProfile {

    public static final String PERFORMANCE = "performance";
    public static final String BALANCED = "balanced";
    public static final String MAXIMUM = "maximum";

    private PerformanceProfile() {
    }

    /** @return the canonical profile name, or {@code null} when it is not recognised. */
    public static String normalise(String name) {
        if (name == null) {
            return null;
        }
        String value = name.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "performance", "perf", "low" -> PERFORMANCE;
            case "balanced", "normal", "default" -> BALANCED;
            case "maximum", "max", "protection", "strict" -> MAXIMUM;
            default -> null;
        };
    }

    public static List<String> names() {
        return List.of(PERFORMANCE, BALANCED, MAXIMUM);
    }

    /** Human-readable description for the settings dashboard. */
    public static String describe(String name) {
        return switch (normalise(name) == null ? BALANCED : normalise(name)) {
            case PERFORMANCE -> "Minimal background processing, reduced diagnostics, lowest memory";
            case MAXIMUM -> "Detailed monitoring, stricter transaction validation, extra checks";
            default -> "Normal monitoring, standard protection, recommended defaults";
        };
    }

    /**
     * Rewrites the tunable thresholds on {@code config} for the named profile.
     *
     * @return {@code false} when the name was not recognised, in which case nothing changed
     */
    public static boolean apply(AdminConfig config, String name) {
        String profile = normalise(name);
        if (profile == null) {
            return false;
        }
        config.profile = profile;
        AdminConfig.Protection protection = config.protection;
        AdminConfig.Anticheat anticheat = config.anticheat;
        AdminConfig.Antidupe antidupe = config.antidupe;
        AdminConfig.Performance performance = config.performance;

        // Checks that reject impossible data stay on in every profile.
        protection.enabled = true;
        anticheat.enabled = true;
        antidupe.validateSlots = true;
        antidupe.validateModifiedStacks = true;

        switch (profile) {
            case PERFORMANCE -> {
                protection.chatPerMinute = 60;
                protection.commandsPerMinute = 90;
                protection.packetsPerSecond = 600;
                protection.packetsPerTenSeconds = 4_500;
                protection.inventoryClicksPerMinute = 480;
                protection.entityInteractionsPerMinute = 900;
                protection.violationsBeforeKick = 0;
                protection.logViolations = true;
                anticheat.connectionRatePerMinute = 45;
                anticheat.maxConnectionsPerAddress = 12;
                antidupe.monitorClickRate = false;
                antidupe.monitorRepeatClicks = false;
                performance.sampleIntervalTicks = 100;
                performance.collectCpu = false;
                performance.collectChunkActivity = false;
                performance.metricHistory = 60;
                performance.eventHistory = 128;
                performance.chatHistoryPerPlayer = 20;
                performance.chatHistoryPlayers = 64;
                performance.transactionHistory = 128;
                performance.cleanupIntervalTicks = 600;
            }
            case MAXIMUM -> {
                protection.chatPerMinute = 20;
                protection.commandsPerMinute = 30;
                protection.packetsPerSecond = 250;
                protection.packetsPerTenSeconds = 1_800;
                protection.inventoryClicksPerMinute = 180;
                protection.entityInteractionsPerMinute = 360;
                protection.violationsBeforeKick = 5;
                protection.logViolations = true;
                anticheat.connectionRatePerMinute = 20;
                anticheat.maxConnectionsPerAddress = 4;
                anticheat.restrictOnFlood = true;
                antidupe.monitorClickRate = true;
                antidupe.monitorRepeatClicks = true;
                antidupe.repeatClickThreshold = 8;
                performance.sampleIntervalTicks = 20;
                performance.collectCpu = true;
                performance.collectChunkActivity = true;
                performance.metricHistory = 600;
                performance.eventHistory = 1_024;
                performance.chatHistoryPerPlayer = 100;
                performance.chatHistoryPlayers = 512;
                performance.transactionHistory = 1_024;
                performance.cleanupIntervalTicks = 1_200;
            }
            default -> {
                protection.chatPerMinute = 40;
                protection.commandsPerMinute = 60;
                protection.packetsPerSecond = 400;
                protection.packetsPerTenSeconds = 3_000;
                protection.inventoryClicksPerMinute = 300;
                protection.entityInteractionsPerMinute = 600;
                protection.violationsBeforeKick = 0;
                protection.logViolations = true;
                anticheat.connectionRatePerMinute = 30;
                anticheat.maxConnectionsPerAddress = 8;
                anticheat.restrictOnFlood = true;
                antidupe.monitorClickRate = true;
                antidupe.monitorRepeatClicks = true;
                antidupe.repeatClickThreshold = 12;
                performance.sampleIntervalTicks = 20;
                performance.collectCpu = true;
                performance.collectChunkActivity = false;
                performance.metricHistory = 300;
                performance.eventHistory = 512;
                performance.chatHistoryPerPlayer = 50;
                performance.chatHistoryPlayers = 256;
                performance.transactionHistory = 512;
                performance.cleanupIntervalTicks = 1_200;
            }
        }
        return true;
    }
}
