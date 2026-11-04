package com.adminmod.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Versioning, migration and validation behaviour of {@link ConfigLoader}. */
class ConfigLoaderTest {

    @Test
    void anUnversionedFileIsMigratedAndGainsTheMissingSections(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        // A file written before the schema was versioned: no version, no inbound, no antiSpam.
        Files.writeString(file, """
                {
                  "chatBridge": {
                    "enabled": true,
                    "provider": "discord",
                    "discord": { "webhookUrl": "https://discord.com/api/webhooks/1/token" }
                  }
                }
                """);

        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        AdminConfig config = ConfigLoader.load(file, report);

        assertEquals(ConfigLoader.CURRENT_VERSION, config.configVersion);
        assertNotNull(config.chatBridge.antiSpam);
        assertNotNull(config.chatBridge.inbound);
        assertNotNull(config.chatBridge.generic.headers);
        assertNotNull(config.moderation.escalation);
        assertNotNull(config.maintenance.allowList);
        assertFalse(report.notes().isEmpty(), "the migration should be reported");
    }

    @Test
    void aMigrationIsWrittenBackSoTheNextLoadIsCurrent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        Files.writeString(file, """
                {
                  "chatBridge": { "enabled": true }
                }
                """);

        ConfigLoader.load(file);

        // The upgraded file now declares the current version and carries the sections the old file
        // was missing, so a second load has nothing left to migrate.
        ConfigLoader.ValidationReport second = new ConfigLoader.ValidationReport();
        AdminConfig reloaded = ConfigLoader.load(file, second);
        assertEquals(ConfigLoader.CURRENT_VERSION, reloaded.configVersion);
        assertTrue(second.notes().isEmpty(), "nothing should need migrating a second time: " + second.notes());
    }

    @Test
    void pairingSettingsAreClampedToSafeBounds(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        Files.writeString(file, """
                {
                  "remoteAdmin": {
                    "pairingKeyTtlSeconds": 0,
                    "pairingKeyLength": 1,
                    "clientSecretLength": 1,
                    "authFailureLimit": 0,
                    "authLockoutSeconds": 0,
                    "authHistoryLimit": 1
                  }
                }
                """);

        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        AdminConfig config = ConfigLoader.load(file, report);

        // A zero TTL or a one-character secret would be a security hole, so the loader raises them
        // to their floors rather than honouring the file.
        assertTrue(config.remoteAdmin.pairingKeyTtlSeconds >= 30L);
        assertTrue(config.remoteAdmin.pairingKeyLength >= 12);
        assertTrue(config.remoteAdmin.clientSecretLength >= 24);
        assertTrue(config.remoteAdmin.authFailureLimit >= 1);
        assertTrue(config.remoteAdmin.authLockoutSeconds >= 10L);
        assertTrue(config.remoteAdmin.authHistoryLimit >= 10);
        assertFalse(report.notes().isEmpty(), "each correction should be reported");
    }

    @Test
    void aMalformedFileIsNotRewrittenAsAFreshDefault(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        Files.writeString(file, "{ this is not json ");

        ConfigLoader.load(file);

        // The defaults are only in memory; the broken file was set aside and no replacement written.
        assertFalse(Files.exists(file), "a broken file should not be replaced by defaults on disk");
    }

    @Test
    void aMalformedFileFallsBackToDefaultsAndKeepsTheBrokenCopy(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        Files.writeString(file, "{ this is not json ");

        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        AdminConfig config = ConfigLoader.load(file, report);

        assertTrue(config.chatBridge.enabled, "defaults should be in use");
        assertTrue(Files.exists(dir.resolve("admin-mod.json.broken")), "the broken file should be kept");
        assertTrue(report.notes().stream().anyMatch(m -> m.contains("could not be parsed")));
    }

    @Test
    void clampsCarryTheBoundaryValueNotAFixedFallback(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        AdminConfig config = new AdminConfig();
        config.chatBridge.queueCapacity = 5;           // below the floor of 16
        config.remoteAdmin.requestsPerMinute = 999_999; // above the ceiling
        ConfigLoader.save(file, config);

        AdminConfig reloaded = ConfigLoader.load(file);
        assertEquals(16, reloaded.chatBridge.queueCapacity);
        assertEquals(100_000, reloaded.remoteAdmin.requestsPerMinute);
    }

    @Test
    void aNullListInTheFileIsReplacedNotTrusted(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        Files.writeString(file, """
                { "configVersion": 2, "chatBridge": { "blockedKeywords": null } }
                """);

        AdminConfig config = ConfigLoader.load(file);
        assertNotNull(config.chatBridge.blockedKeywords);
        assertTrue(config.chatBridge.blockedKeywords.isEmpty());
    }

    @Test
    void blankSecretsDoNotBlockLoading(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        AdminConfig config = new AdminConfig();
        config.chatBridge.discord.webhookUrl = "";
        config.chatBridge.matrix.accessToken = "";
        ConfigLoader.save(file, config);

        AdminConfig reloaded = ConfigLoader.load(file);
        assertEquals("", reloaded.chatBridge.discord.webhookUrl);
        assertTrue(reloaded.chatBridge.enabled);
    }

    @Test
    void defaultsIncludeTheDocumentedEscalationAndAnnouncementSections() {
        AdminConfig config = new AdminConfig();
        assertEquals(3, config.moderation.escalation.warnThreshold);
        assertEquals(900L, config.remoteAdmin.sessionTimeoutSeconds);
        assertEquals(1_500, config.chatBridge.maxMessageLength);
        assertTrue(config.chatBridge.stripFormatting);
        assertFalse(config.announcements.enabled);
        assertFalse(config.maintenance.enabled);
        assertTrue(config.moderation.templates.size() >= 4, "the shipped templates should be present");
    }

    @Test
    void templatesSurviveARoundTrip(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("admin-mod.json");
        AdminConfig config = new AdminConfig();
        ConfigLoader.save(file, config);
        AdminConfig reloaded = ConfigLoader.load(file);

        List<AdminConfig.PunishmentTemplate> templates = reloaded.moderation.templates;
        assertEquals(config.moderation.templates.size(), templates.size());
        assertTrue(templates.stream().anyMatch(t -> t.name.equals("ban-perm") && t.seconds == 0L));
    }
}
