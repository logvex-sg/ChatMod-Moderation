package com.adminmod.common.settings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.config.PerformanceProfile;

/**
 * The built-in profiles and ready-made presets.
 *
 * <p>Two kinds of starting point are offered. The five <em>profiles</em> are the original
 * optimisation profiles plus a custom one, and they only rewrite protection and performance
 * thresholds. The eight <em>presets</em> describe a whole kind of server (a small private server, an
 * anarchy server, a large public server) and set several modules at once, but they never touch
 * Minecraft or server settings outside ADMIN's own configuration.
 *
 * <p>Every profile and preset is expressed as a sparse map of setting path to value, so it stays a
 * starting point: a value it does not name is left exactly as the operator had it.
 */
public final class ConfigPresets {

    /** The name of the always-present profile that follows whatever the operator last set. */
    public static final String CUSTOM = "custom";

    private ConfigPresets() {
    }

    /** The built-in optimisation profiles, in display order. */
    public static List<ConfigProfile> builtInProfiles() {
        List<ConfigProfile> profiles = new ArrayList<>();
        profiles.add(new ConfigProfile("minimal", "Fewest checks and lowest overhead", true,
                Map.of("profile", PerformanceProfile.PERFORMANCE)));
        profiles.add(new ConfigProfile("balanced", "Recommended defaults for most servers", true,
                Map.of("profile", PerformanceProfile.BALANCED)));
        profiles.add(new ConfigProfile("performance", "Detailed diagnostics, more background work", true,
                Map.of("profile", PerformanceProfile.PERFORMANCE)));
        profiles.add(new ConfigProfile("maximum-protection", "Strictest checks; expect more false positives", true,
                Map.of("profile", PerformanceProfile.MAXIMUM)));
        profiles.add(new ConfigProfile(CUSTOM, "Your own values; nothing is rewritten automatically", true,
                Map.of()));
        return profiles;
    }

    /**
     * The ready-made server presets.
     *
     * <p>Each is a sparse map; applying one changes only the named settings. None of them touches a
     * credential, and none of them enables the remote console or disables authentication.
     */
    public static List<ConfigProfile> presets() {
        List<ConfigProfile> presets = new ArrayList<>();

        presets.add(preset("small-private",
                "A handful of trusted players on a small server",
                map -> {
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("localChat.enabled", "true");
                    map.put("localChat.radius", "100");
                    map.put("protection.chatPerMinute", "60");
                    map.put("protection.violationsBeforeKick", "0");
                    map.put("anticheat.connectionRatePerMinute", "20");
                    map.put("audit.retentionDays", "14");
                    map.put("performance.sampleIntervalTicks", "100");
                }));

        presets.add(preset("survival",
                "A normal survival world with moderate protection",
                map -> {
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("localChat.enabled", "false");
                    map.put("protection.chatPerMinute", "40");
                    map.put("antidupe.enabled", "true");
                    map.put("antidupe.validateSlots", "true");
                    map.put("antidupe.validateModifiedStacks", "true");
                    map.put("audit.retentionDays", "30");
                }));

        presets.add(preset("smp",
                "A shared multiplayer world, balanced defaults",
                map -> {
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("localChat.enabled", "false");
                    map.put("moderation.requireReasons", "true");
                    map.put("moderation.notifyStaff", "true");
                    map.put("antidupe.enabled", "true");
                    map.put("audit.retentionDays", "30");
                }));

        presets.add(preset("anarchy",
                "No rules: minimal interference, keep only crash protection",
                map -> {
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("localChat.enabled", "true");
                    map.put("localChat.radius", "32");
                    map.put("protection.enabled", "true");
                    map.put("protection.chatPerMinute", "120");
                    map.put("protection.commandsPerMinute", "180");
                    map.put("protection.violationsBeforeKick", "0");
                    map.put("antidupe.enabled", "true");
                    map.put("antidupe.monitorClickRate", "false");
                    map.put("antidupe.monitorRepeatClicks", "false");
                    map.put("moderation.broadcastPunishments", "false");
                    map.put("audit.retentionDays", "14");
                }));

        presets.add(preset("minigame",
                "Lobbies and matches: strict transaction validation, local chat off",
                map -> {
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("localChat.enabled", "false");
                    map.put("antidupe.enabled", "true");
                    map.put("antidupe.monitorClickRate", "true");
                    map.put("antidupe.monitorRepeatClicks", "true");
                    map.put("protection.inventoryClicksPerMinute", "480");
                    map.put("protection.violationsBeforeKick", "0");
                    map.put("audit.retentionDays", "14");
                }));

        presets.add(preset("creative",
                "Creative building: lenient rate limits, local chat on",
                map -> {
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("localChat.enabled", "true");
                    map.put("localChat.radius", "128");
                    map.put("protection.chatPerMinute", "60");
                    map.put("antidupe.validateSlots", "true");
                    map.put("antidupe.monitorRepeatClicks", "false");
                    map.put("audit.retentionDays", "30");
                }));

        presets.add(preset("large-public",
                "A large public server: bigger queues, longer history, stricter checks",
                map -> {
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("chatBridge.queueCapacity", "2048");
                    map.put("chatBridge.batching.enabled", "true");
                    map.put("remoteAdmin.requestsPerMinute", "240");
                    map.put("remoteAdmin.requirePairedClient", "true");
                    map.put("protection.chatPerMinute", "30");
                    map.put("protection.violationsBeforeKick", "3");
                    map.put("antidupe.enabled", "true");
                    map.put("antidupe.monitorClickRate", "true");
                    map.put("performance.chatHistoryPlayers", "512");
                    map.put("audit.retentionDays", "90");
                    map.put("audit.securityRetentionDays", "730");
                }));

        presets.add(preset("development",
                "Testing and development: verbose diagnostics, everything logged",
                map -> {
                    map.put("logging.level", "debug");
                    map.put("chatBridge.publicChatOnly", "true");
                    map.put("performance.sampleIntervalTicks", "20");
                    map.put("performance.collectCpu", "true");
                    map.put("performance.collectChunkActivity", "true");
                    map.put("audit.enabled", "true");
                    map.put("audit.retentionDays", "7");
                    map.put("protection.violationsBeforeKick", "0");
                }));

        return presets;
    }

    /** Every built-in profile and preset by name, for lookup and for the GUI list. */
    public static List<ConfigProfile> all() {
        List<ConfigProfile> all = new ArrayList<>();
        all.addAll(builtInProfiles());
        all.addAll(presets());
        return all;
    }

    /** @return the built-in profile or preset with this name, or {@code null}. */
    public static ConfigProfile byName(String name) {
        if (name == null) {
            return null;
        }
        for (ConfigProfile profile : all()) {
            if (profile.name().equalsIgnoreCase(name)) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Applies a profile's values onto {@code config}.
     *
     * <p>A profile that selects one of the optimisation profiles also runs
     * {@link PerformanceProfile#apply}, because those thresholds are computed rather than listed.
     *
     * @return the paths that were changed
     */
    public static List<String> apply(AdminConfig config, ConfigProfile profile, SettingRegistry registry) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : profile.values().entrySet()) {
            SettingSpec spec = registry.byPath(entry.getKey());
            if (spec == null || spec.secret()) {
                continue;
            }
            String before = SettingsBinder.read(config, entry.getKey());
            String value = SettingValidation.normalise(spec, entry.getValue());
            if (!SettingValidation.validate(spec, value).valid()) {
                continue;
            }
            if (SettingsBinder.write(config, entry.getKey(), value) && !value.equals(before)) {
                changed.add(entry.getKey());
            }
        }
        // The optimisation profile is computed rather than listed, so run it after the literal
        // values so an explicit threshold in the profile still wins.
        String profileName = profile.value("profile");
        if (profileName != null) {
            PerformanceProfile.apply(config, profileName);
        }
        return changed;
    }

    private interface Builder {
        void build(Map<String, String> map);
    }

    private static ConfigProfile preset(String name, String description, Builder builder) {
        Map<String, String> map = new LinkedHashMap<>();
        builder.build(map);
        return new ConfigProfile(name, description, true, map);
    }
}
