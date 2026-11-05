package com.adminmod.server.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.config.ModularConfigLoader;
import com.adminmod.common.settings.ConfigBackup;
import com.adminmod.common.settings.ConfigHistory;
import com.adminmod.common.settings.ProfileStore;
import com.adminmod.common.settings.SettingRegistry;
import com.adminmod.common.settings.SettingsBinder;

/** The settings manager: atomic batches, persistence, history, rollback and profiles end to end. */
class SettingsServiceTest {

    private Path configDir;
    private AdminConfig config;
    private SettingRegistry registry;
    private ProfileStore profiles;
    private ConfigHistory history;
    private AtomicInteger reloads;
    private SettingsService service;

    @BeforeEach
    void setUp(@TempDir Path dir) throws IOException {
        configDir = dir.resolve("admin");
        config = ModularConfigLoader.load(configDir, dir.resolve("admin-mod.json"),
                new com.adminmod.common.config.ConfigLoader.ValidationReport());
        registry = SettingRegistry.createDefault();
        profiles = new ProfileStore(configDir.resolve("profiles.json"));
        history = new ConfigHistory(64);
        reloads = new AtomicInteger();
        service = new SettingsService(config, configDir, registry, profiles, history,
                new ConfigBackup(configDir.resolve("backups"), 10),
                updated -> {
                    try {
                        ModularConfigLoader.save(configDir, updated);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                },
                reloads::incrementAndGet);
    }

    @Test
    void aSingleUpdateIsPersistedAndReloaded() throws IOException {
        SettingsService.Result result = service.update("alice", "chatBridge.queueCapacity", "512");
        assertTrue(result.success(), result.message());
        assertEquals(512, config.chatBridge.queueCapacity);
        assertEquals(1, reloads.get());

        // A fresh load from disk sees the change.
        AdminConfig reloaded = ModularConfigLoader.load(configDir, configDir.resolve("nope.json"),
                new com.adminmod.common.config.ConfigLoader.ValidationReport());
        assertEquals(512, reloaded.chatBridge.queueCapacity);
    }

    @Test
    void anInvalidValueIsRefusedAndNothingChanges() {
        int before = config.chatBridge.queueCapacity;
        SettingsService.Result result = service.update("alice", "chatBridge.queueCapacity", "999999999");
        assertFalse(result.success());
        assertTrue(result.message().contains("at most"), result.message());
        assertEquals(before, config.chatBridge.queueCapacity);
        assertEquals(0, reloads.get());
    }

    @Test
    void anUnknownSettingIsRefused() {
        assertFalse(service.update("alice", "no.such.setting", "1").success());
    }

    @Test
    void aBatchAppliesAtomically() {
        SettingsService.Result result = service.updateBatch("alice", Map.of(
                "chatBridge.queueCapacity", "256",
                "chatBridge.provider", "matrix",
                "localChat.radius", "48"));
        assertTrue(result.success(), result.message());
        assertEquals(256, config.chatBridge.queueCapacity);
        assertEquals("matrix", config.chatBridge.provider);
        assertEquals(48.0, config.localChat.radius);
    }

    @Test
    void aBatchWithOneBadValueChangesNothing() {
        SettingsService.Result result = service.updateBatch("alice", Map.of(
                "chatBridge.queueCapacity", "256",
                "chatBridge.provider", "not-a-provider"));
        assertFalse(result.success());
        assertTrue(result.message().contains("rejected"), result.message());
        assertEquals(512, config.chatBridge.queueCapacity, "no part of a rejected batch may apply");
        assertEquals("discord", config.chatBridge.provider);
        assertEquals(0, reloads.get());
    }

    @Test
    void historyRecordsAndRollbackRestoresThePreviousValue() {
        int original = config.chatBridge.queueCapacity;
        service.update("alice", "chatBridge.queueCapacity", "777");
        assertEquals(1, history.size());
        assertEquals("alice", history.last().actor());

        SettingsService.Result rollback = service.rollback("bob");
        assertTrue(rollback.success(), rollback.message());
        assertEquals(original, config.chatBridge.queueCapacity);
    }

    @Test
    void resettingASettingRestoresItsDocumentedDefault() {
        service.update("alice", "chatBridge.queueCapacity", "4096");
        assertTrue(service.resetSetting("alice", "chatBridge.queueCapacity").success());
        assertEquals(Integer.parseInt(registry.byPath("chatBridge.queueCapacity").defaultValue()),
                config.chatBridge.queueCapacity);
    }

    @Test
    void creatingAProfileCapturesTheCurrentConfigurationWithoutSecrets() {
        config.chatBridge.discord.webhookUrl = "https://discord.com/api/webhooks/1/token";
        config.chatBridge.queueCapacity = 2048;
        SettingsService.Result result = service.createProfile("mine", "test profile");
        assertTrue(result.success(), result.message());
        var profile = profiles.find("mine");
        assertEquals("2048", profile.value("chatBridge.queueCapacity"));
        assertFalse(profile.values().containsKey("chatBridge.discord.webhookUrl"));
    }

    @Test
    void applyingAProfileChangesTheNamedSettingsOnly() {
        service.createProfile("mine", "");
        // Change the live value after capturing, then apply the profile to restore it.
        service.update("alice", "chatBridge.queueCapacity", "4096");
        service.applyProfile("alice", "mine");
        assertEquals(512, config.chatBridge.queueCapacity);
    }

    @Test
    void aPresetIsAppliedThroughTheService() {
        SettingsService.Result result = service.applyProfile("alice", "anarchy");
        assertTrue(result.success(), result.message());
        assertEquals(32.0, config.localChat.radius);
    }

    @Test
    void exportThenImportReproducesTheProfile() {
        config.chatBridge.queueCapacity = 333;
        service.createProfile("mine", "desc");
        String exported = service.exportProfile("mine");
        assertFalse(exported.isBlank());
        assertFalse(exported.contains("webhookUrl"), "an export must not contain secret keys");

        SettingsService.Result imported = service.importProfile("alice", exported, false);
        assertTrue(imported.success(), imported.message());
        assertEquals("333", profiles.find("mine").value("chatBridge.queueCapacity"));
    }

    @Test
    void importingWithApplyChangesTheLiveConfigurationAndBacksUpFirst() {
        config.chatBridge.queueCapacity = 999;
        service.createProfile("mine", "");
        String exported = service.exportProfile("mine");
        service.update("alice", "chatBridge.queueCapacity", "1");

        SettingsService.Result imported = service.importProfile("alice", exported, true);
        assertTrue(imported.success(), imported.message());
        assertEquals(999, config.chatBridge.queueCapacity);
        assertFalse(service.backups().list().isEmpty(), "an import must leave a backup");
    }

    @Test
    void aBrokenImportIsRefusedBeforeAnythingIsWritten() {
        SettingsService.Result result = service.importProfile("alice", "{not json", true);
        assertFalse(result.success());
    }

    @Test
    void diagnosticsJsonIsProducedWithoutError() {
        String json = service.diagnosticsJson();
        assertTrue(json.contains("findings"));
    }

    @Test
    void catalogJsonHidesSecretValues() {
        config.chatBridge.discord.webhookUrl = "https://discord.com/api/webhooks/000/topsecret";
        String json = service.catalogJson();
        assertFalse(json.contains("topsecret"), "a secret value must never appear in the catalogue");
        assertTrue(json.contains("\"configured\":true") || json.contains("\"configured\": true"));
    }

    @Test
    void aBackupCapturesTheConfigurationFiles() throws IOException {
        Path folder = service.backups().snapshot(configDir, "test");
        assertTrue(folder != null && java.nio.file.Files.isDirectory(folder));
        try (var stream = java.nio.file.Files.list(folder)) {
            assertTrue(stream.anyMatch(p -> p.getFileName().toString().equals("general.json")));
        }
    }

    @Test
    void theHistoryJsonListsTheChange() {
        service.update("alice", "chatBridge.provider", "matrix");
        String json = service.historyJson();
        assertTrue(json.contains("alice"));
        assertTrue(json.contains("chatBridge.provider"));
    }
}
