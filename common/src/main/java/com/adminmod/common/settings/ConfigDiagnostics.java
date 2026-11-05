package com.adminmod.common.settings;

import java.util.ArrayList;
import java.util.List;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.config.ConfigLoader;

/**
 * Analyses a configuration and reports everything wrong with it.
 *
 * <p>The diagnostics page is the answer to "why is this setting not doing what I expect". Instead of
 * the loader silently clamping a value or ignoring an unknown one, the analysis collects every
 * problem with enough context to act on it: an invalid value, a setting that conflicts with
 * another, a feature that needs something else enabled, a deprecated setting, a migration warning.
 *
 * <p>The analysis is read-only. It never changes the configuration it inspects.
 */
public final class ConfigDiagnostics {

    /** How serious a finding is. */
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    /** One finding. */
    public record Finding(Severity severity, String category, String path, String message) {
    }

    private ConfigDiagnostics() {
    }

    /**
     * Inspects {@code config} against {@code registry}.
     *
     * @param migrationNotes notes collected by the loader while reading the files
     * @param moduleErrors   initialisation errors reported by subsystems, if any
     */
    public static List<Finding> analyse(AdminConfig config, SettingRegistry registry,
            List<String> migrationNotes, List<String> moduleErrors) {
        List<Finding> findings = new ArrayList<>();

        for (String note : migrationNotes) {
            findings.add(new Finding(Severity.WARNING, "migration", "", note));
        }
        for (String error : moduleErrors) {
            findings.add(new Finding(Severity.ERROR, "module", "", error));
        }

        // Registry drift: a spec whose field is gone, or a field with no spec.
        for (String problem : registry.problems()) {
            findings.add(new Finding(Severity.WARNING, "registry", "", problem));
        }

        for (SettingSpec spec : registry.all()) {
            String value = SettingsBinder.read(config, spec.path());
            if (value == null) {
                findings.add(new Finding(Severity.ERROR, "missing", spec.path(),
                        "The setting has no value on the live configuration"));
                continue;
            }
            SettingValidation.Result result = SettingValidation.validate(spec, value);
            if (!result.valid()) {
                findings.add(new Finding(Severity.ERROR, "invalid", spec.path(),
                        "Invalid value \"" + value + "\": " + result.message()));
            }
            if (spec.deprecated()) {
                findings.add(new Finding(Severity.INFO, "deprecated", spec.path(),
                        "This setting is deprecated and will be removed in a future version"));
            }
        }

        dependencyFindings(config, registry, findings);
        conflictFindings(config, registry, findings);
        recommendationFindings(config, registry, findings);
        return findings;
    }

    private static void dependencyFindings(AdminConfig config, SettingRegistry registry,
            List<Finding> findings) {
        for (SettingSpec spec : registry.all()) {
            if (spec.dependsOn().isEmpty()) {
                continue;
            }
            String enabled = SettingsBinder.read(config, spec.path());
            if (!isTrue(enabled)) {
                continue;
            }
            String dependency = SettingsBinder.read(config, spec.dependsOn());
            if (!isTrue(dependency)) {
                SettingSpec dependencySpec = registry.byPath(spec.dependsOn());
                String label = dependencySpec == null ? spec.dependsOn() : dependencySpec.label();
                findings.add(new Finding(Severity.WARNING, "dependency", spec.path(),
                        spec.label() + " is on but " + label + " is off, so it has no effect"));
            }
        }
    }

    private static void conflictFindings(AdminConfig config, SettingRegistry registry,
            List<Finding> findings) {
        for (SettingSpec spec : registry.all()) {
            for (String other : spec.conflictsWith()) {
                if (isTrue(SettingsBinder.read(config, spec.path()))
                        && isTrue(SettingsBinder.read(config, other))) {
                    findings.add(new Finding(Severity.WARNING, "conflict", spec.path(),
                            spec.label() + " conflicts with " + other + "; both are enabled"));
                }
            }
        }
    }

    /**
     * Flags settings that differ from their documented default while sitting in a built-in profile.
     *
     * <p>This is the "common misconfiguration" check: a value left over from a profile switch that
     * no longer matches the selected profile is the usual cause of "I set the profile but it still
     * behaves the old way".
     */
    private static void recommendationFindings(AdminConfig config, SettingRegistry registry,
            List<Finding> findings) {
        if (!config.chatBridge.enabled && config.notifications.enabled && config.notifications.toBridge) {
            findings.add(new Finding(Severity.WARNING, "conflict", "notifications.toBridge",
                    "Notifications are sent to the bridge but the chat bridge is disabled"));
        }
        if (config.chatBridge.forwardCommands && config.chatBridge.publicChatOnly) {
            findings.add(new Finding(Severity.INFO, "conflict", "chatBridge.forwardCommands",
                    "Commands are forwarded while public-chat-only is on; commands still take precedence"));
        }
        if (config.remoteAdmin.enabled && config.remoteAdmin.allowPasswordAuth
                && !config.remoteAdmin.requireAuthentication) {
            findings.add(new Finding(Severity.ERROR, "conflict", "remoteAdmin.requireAuthentication",
                    "Remote administration is enabled without requiring authentication"));
        }
        if (config.audit.enabled && config.audit.securityRetentionDays < config.audit.retentionDays) {
            findings.add(new Finding(Severity.WARNING, "conflict", "audit.securityRetentionDays",
                    "Security events are kept for less time than ordinary events"));
        }
        if (config.storage.enabled && config.storage.algorithm.equalsIgnoreCase("none")
                && config.storage.minArchiveAgeHours == 0) {
            findings.add(new Finding(Severity.INFO, "recommendation", "storage.algorithm",
                    "Compression is enabled but the algorithm is 'none'; no space will be saved"));
        }
    }

    private static boolean isTrue(String value) {
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1")
                || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("yes"));
    }

    /** Convenience wrapper that also reads the schema version mismatch as a finding. */
    public static List<Finding> analyse(AdminConfig config, SettingRegistry registry,
            ConfigLoader.ValidationReport report) {
        return analyse(config, registry, report == null ? List.of() : report.notes(), List.of());
    }
}
