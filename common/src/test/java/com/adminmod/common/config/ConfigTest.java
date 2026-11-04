package com.adminmod.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {

    @Test
    void missingFileIsMaterialisedWithDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config").resolve("admin-mod.json");
        AdminConfig config = ConfigLoader.load(file);
        assertTrue(Files.exists(file));
        assertTrue(config.chatBridge.enabled);
        assertEquals("discord", config.chatBridge.provider);
        assertTrue(config.moderation.requireReasons);
        assertTrue(config.remoteAdmin.enabled);
        assertTrue(config.remoteAdmin.vaultDirectory.contains("H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme"));
    }

    @Test
    void valuesSurviveARoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        AdminConfig config = new AdminConfig();
        config.chatBridge.provider = "matrix";
        config.chatBridge.matrix.homeserver = "https://matrix.example.org";
        config.chatBridge.matrix.roomId = "!room:example.org";
        config.chatBridge.matrix.accessToken = "token-value";
        config.remoteAdmin.ownerUuid = "11111111-2222-3333-4444-555555555555";
        ConfigLoader.save(file, config);

        AdminConfig reloaded = ConfigLoader.load(file);
        assertEquals("matrix", reloaded.chatBridge.provider);
        assertEquals("https://matrix.example.org", reloaded.chatBridge.matrix.homeserver);
        assertEquals("token-value", reloaded.chatBridge.matrix.accessToken);
        assertEquals("11111111-2222-3333-4444-555555555555", reloaded.remoteAdmin.ownerUuid);
    }

    @Test
    void outOfRangeValuesAreClamped(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        AdminConfig config = new AdminConfig();
        config.chatBridge.queueCapacity = -1;
        config.chatBridge.maxAttempts = 0;
        config.chatBridge.requestTimeoutMillis = 5;
        ConfigLoader.save(file, config);

        AdminConfig reloaded = ConfigLoader.load(file);
        assertEquals(16, reloaded.chatBridge.queueCapacity);
        assertEquals(1, reloaded.chatBridge.maxAttempts);
        assertEquals(1000, reloaded.chatBridge.requestTimeoutMillis);
    }

    @Test
    void redactionHidesCredentialsButReportsPresence() {
        AdminConfig config = new AdminConfig();
        config.chatBridge.discord.webhookUrl = "https://discord.com/api/webhooks/123/secret-token";
        config.chatBridge.matrix.accessToken = "matrix-secret";
        config.chatBridge.generic.authToken = "generic-secret";

        ConfigRedactor.ServerSettingsView view = ConfigRedactor.view(config);
        String json = com.adminmod.common.model.Json.write(view);

        assertTrue(view.discordWebhookConfigured);
        assertTrue(view.matrixTokenConfigured);
        assertTrue(view.genericTokenConfigured);
        assertFalse(json.contains("secret-token"), "webhook token must not be serialised");
        assertFalse(json.contains("matrix-secret"), "matrix token must not be serialised");
        assertFalse(json.contains("generic-secret"), "generic token must not be serialised");
    }

    @Test
    void redactionReportsUnconfiguredProviders() {
        AdminConfig config = new AdminConfig();
        ConfigRedactor.ServerSettingsView view = ConfigRedactor.view(config);
        assertFalse(view.discordWebhookConfigured);
        assertFalse(view.matrixTokenConfigured);
        assertFalse(view.genericTokenConfigured);
        assertNotNull(view.moderators);
    }
}
