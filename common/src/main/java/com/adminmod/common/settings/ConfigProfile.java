package com.adminmod.common.settings;

import java.util.LinkedHashMap;
import java.util.Map;

import com.adminmod.common.config.AdminConfig;

/**
 * A named set of setting values that can be applied, exported and compared.
 *
 * <p>A profile is stored as a flat map of setting path to string value, exactly the form the
 * settings protocol uses. Only the values it names are changed when it is applied, so a profile
 * that predates a new setting leaves that setting alone rather than resetting it.
 *
 * <p>Credential values are never stored in a profile. {@link #fromConfig} skips every spec marked
 * secret, so an exported profile is safe to share and an imported one cannot carry a webhook token
 * or an access token into the server.
 */
public final class ConfigProfile {

    /** Schema version written into an exported profile, so an older file can be migrated. */
    public static final int FORMAT_VERSION = 1;

    private final String name;
    private final String description;
    private final boolean builtIn;
    private final Map<String, String> values;

    public ConfigProfile(String name, String description, boolean builtIn, Map<String, String> values) {
        this.name = name;
        this.description = description == null ? "" : description;
        this.builtIn = builtIn;
        this.values = new LinkedHashMap<>(values);
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public boolean builtIn() {
        return builtIn;
    }

    public Map<String, String> values() {
        return java.util.Collections.unmodifiableMap(values);
    }

    public String value(String path) {
        return values.get(path);
    }

    public int size() {
        return values.size();
    }

    public ConfigProfile withName(String newName) {
        return new ConfigProfile(newName, description, false, values);
    }

    /**
     * Captures the current values of {@code config} for the paths named by {@code registry}.
     *
     * <p>Secret settings are skipped, so the result never contains a credential.
     */
    public static ConfigProfile fromConfig(String name, String description, AdminConfig config,
            SettingRegistry registry) {
        Map<String, String> values = new LinkedHashMap<>();
        for (SettingSpec spec : registry.all()) {
            if (spec.secret()) {
                continue;
            }
            String value = SettingsBinder.read(config, spec.path());
            if (value != null) {
                values.put(spec.path(), value);
            }
        }
        return new ConfigProfile(name, description, false, values);
    }

    /**
     * Computes the difference between this profile and the live configuration.
     *
     * <p>Only non-secret settings are compared. Each entry records the current value and the value
     * the profile would set, which is what the preview screen shows before anything is applied.
     */
    public java.util.List<Difference> diffAgainst(AdminConfig config, SettingRegistry registry) {
        java.util.List<Difference> differences = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            SettingSpec spec = registry.byPath(entry.getKey());
            if (spec == null || spec.secret()) {
                continue;
            }
            String current = SettingsBinder.read(config, entry.getKey());
            if (current == null) {
                continue;
            }
            if (!normalisedEquals(spec, current, entry.getValue())) {
                differences.add(new Difference(entry.getKey(), spec.label(), current, entry.getValue()));
            }
        }
        return differences;
    }

    private static boolean normalisedEquals(SettingSpec spec, String a, String b) {
        String left = SettingValidation.normalise(spec, a);
        String right = SettingValidation.normalise(spec, b);
        return left.equals(right);
    }

    /** One setting a profile would change. */
    public record Difference(String path, String label, String current, String proposed) {
    }
}
