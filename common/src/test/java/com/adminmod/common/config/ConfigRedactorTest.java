package com.adminmod.common.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.adminmod.common.model.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dashboard consumes a redacted view of the server configuration. These tests pin down the
 * one property that matters: no credential may survive serialisation.
 */
class ConfigRedactorTest {

    private static AdminConfig configWithSecrets() {
        AdminConfig config = new AdminConfig();
        config.chatBridge.discord.webhookUrl = "https://discord.com/api/webhooks/123456/SUPER-SECRET-TOKEN";
        config.chatBridge.matrix.accessToken = "syt_MATRIX-SECRET";
        config.chatBridge.generic.authToken = "GENERIC-SECRET";
        config.remoteAdmin.ownerUuid = "11111111-2222-3333-4444-555555555555";
        return config;
    }

    @Test
    void redactedViewNeverContainsCredentialMaterial() {
        String rendered = Json.write(ConfigRedactor.view(configWithSecrets()));
        assertFalse(rendered.contains("SUPER-SECRET-TOKEN"), rendered);
        assertFalse(rendered.contains("syt_MATRIX-SECRET"), rendered);
        assertFalse(rendered.contains("GENERIC-SECRET"), rendered);
    }

    @Test
    void redactedViewReportsWhetherCredentialsAreSet() {
        String rendered = Json.write(ConfigRedactor.view(configWithSecrets()));
        assertTrue(rendered.contains("\"discordWebhookConfigured\":true"), rendered);
        assertTrue(rendered.contains("\"matrixTokenConfigured\":true"), rendered);
        assertTrue(rendered.contains("\"genericTokenConfigured\":true"), rendered);
    }

    @Test
    void emptyCredentialsAreReportedAsAbsent() {
        String rendered = Json.write(ConfigRedactor.view(new AdminConfig()));
        assertTrue(rendered.contains("\"discordWebhookConfigured\":false"), rendered);
        assertTrue(rendered.contains("\"matrixTokenConfigured\":false"), rendered);
    }

    @Test
    void writingTheConfigurationLeavesNoSecretInTheRedactedPath(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        ConfigLoader.save(file, configWithSecrets());
        AdminConfig reloaded = ConfigLoader.load(file);
        String rendered = Json.write(ConfigRedactor.view(reloaded));
        assertFalse(rendered.contains("SUPER-SECRET-TOKEN"), rendered);
    }
}
