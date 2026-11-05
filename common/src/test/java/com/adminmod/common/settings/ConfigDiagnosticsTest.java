package com.adminmod.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.adminmod.common.config.AdminConfig;

/** Diagnostics: the findings that explain a misconfiguration. */
class ConfigDiagnosticsTest {

    private final SettingRegistry registry = SettingRegistry.createDefault();

    @Test
    void aHealthyDefaultConfigurationHasNoErrors() {
        List<ConfigDiagnostics.Finding> findings =
                ConfigDiagnostics.analyse(new AdminConfig(), registry, List.of(), List.of());
        assertTrue(findings.stream().noneMatch(f -> f.severity() == ConfigDiagnostics.Severity.ERROR),
                "the default config must not produce errors: " + findings);
    }

    @Test
    void anInvalidValueIsReportedAsAnError() {
        AdminConfig config = new AdminConfig();
        // Write a value the field accepts but the spec rejects: queueCapacity is an int, so put an
        // out-of-range value directly on the model.
        config.chatBridge.queueCapacity = -5;
        List<ConfigDiagnostics.Finding> findings =
                ConfigDiagnostics.analyse(config, registry, List.of(), List.of());
        assertTrue(findings.stream().anyMatch(f -> f.severity() == ConfigDiagnostics.Severity.ERROR
                && f.path().equals("chatBridge.queueCapacity")), findings.toString());
    }

    @Test
    void aDisabledDependencyIsReportedAsAWarning() {
        AdminConfig config = new AdminConfig();
        config.chatBridge.enabled = false;
        config.notifications.enabled = true;
        config.notifications.toBridge = true;
        List<ConfigDiagnostics.Finding> findings =
                ConfigDiagnostics.analyse(config, registry, List.of(), List.of());
        assertTrue(findings.stream().anyMatch(f -> f.path().equals("notifications.toBridge")
                && f.severity() == ConfigDiagnostics.Severity.WARNING), findings.toString());
    }

    @Test
    void remoteAdminWithoutAuthenticationIsAnError() {
        AdminConfig config = new AdminConfig();
        config.remoteAdmin.enabled = true;
        config.remoteAdmin.requireAuthentication = false;
        config.remoteAdmin.allowPasswordAuth = true;
        List<ConfigDiagnostics.Finding> findings =
                ConfigDiagnostics.analyse(config, registry, List.of(), List.of());
        assertTrue(findings.stream().anyMatch(f -> f.path().equals("remoteAdmin.requireAuthentication")
                && f.severity() == ConfigDiagnostics.Severity.ERROR), findings.toString());
    }

    @Test
    void migrationNotesAndModuleErrorsAreCarriedThrough() {
        List<ConfigDiagnostics.Finding> findings = ConfigDiagnostics.analyse(new AdminConfig(), registry,
                List.of("created general.json with defaults"), List.of("chat bridge failed to start"));
        assertTrue(findings.stream().anyMatch(f -> f.category().equals("migration")));
        assertTrue(findings.stream().anyMatch(f -> f.category().equals("module")
                && f.severity() == ConfigDiagnostics.Severity.ERROR));
    }

    @Test
    void shortSecurityRetentionIsFlagged() {
        AdminConfig config = new AdminConfig();
        config.audit.enabled = true;
        config.audit.retentionDays = 90;
        config.audit.securityRetentionDays = 30;
        List<ConfigDiagnostics.Finding> findings =
                ConfigDiagnostics.analyse(config, registry, List.of(), List.of());
        assertTrue(findings.stream().anyMatch(f -> f.path().equals("audit.securityRetentionDays")),
                findings.toString());
    }
}
