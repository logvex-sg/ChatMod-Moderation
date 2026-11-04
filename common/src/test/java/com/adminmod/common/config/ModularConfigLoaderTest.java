package com.adminmod.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Splitting, migrating and round-tripping the modular configuration. */
class ModularConfigLoaderTest {

    @Test
    void writesEveryModuleFileOnFirstLoad(@TempDir Path dir) throws IOException {
        Path adminDir = dir.resolve("admin");
        Path legacy = dir.resolve("admin-mod.json");
        AdminConfig config = ModularConfigLoader.load(adminDir, legacy, new ConfigLoader.ValidationReport());
        assertTrue(Files.isDirectory(adminDir));
        for (String module : ModularConfigLoader.MODULES.keySet()) {
            assertTrue(Files.exists(adminDir.resolve(module)), module + " must be created");
        }
        assertEquals(ConfigLoader.CURRENT_VERSION, config.configVersion);
    }

    @Test
    void aChangeInOneModuleSurvivesAReload(@TempDir Path dir) throws IOException {
        Path adminDir = dir.resolve("admin");
        Path legacy = dir.resolve("admin-mod.json");
        AdminConfig config = ModularConfigLoader.load(adminDir, legacy, new ConfigLoader.ValidationReport());
        config.chatBridge.provider = "matrix";
        config.remoteAdmin.ownerUuid = "00000000-0000-0000-0000-0000000000bb";
        ModularConfigLoader.save(adminDir, config);

        AdminConfig reloaded = ModularConfigLoader.load(adminDir, legacy, new ConfigLoader.ValidationReport());
        assertEquals("matrix", reloaded.chatBridge.provider);
        assertEquals("00000000-0000-0000-0000-0000000000bb", reloaded.remoteAdmin.ownerUuid);
    }

    @Test
    void theSelectedProfileIsReappliedOnLoad(@TempDir Path dir) throws IOException {
        Path adminDir = dir.resolve("admin");
        Path legacy = dir.resolve("admin-mod.json");
        AdminConfig config = ModularConfigLoader.load(adminDir, legacy, new ConfigLoader.ValidationReport());
        PerformanceProfile.apply(config, "maximum");
        ModularConfigLoader.save(adminDir, config);

        AdminConfig reloaded = ModularConfigLoader.load(adminDir, legacy, new ConfigLoader.ValidationReport());
        assertEquals(PerformanceProfile.MAXIMUM, reloaded.profile);
        assertEquals(20, reloaded.protection.chatPerMinute, "the profile's thresholds are restored");
    }

    @Test
    void aMalformedModuleIsMovedAsideAndDefaultsAreUsed(@TempDir Path dir) throws IOException {
        Path adminDir = dir.resolve("admin");
        Path legacy = dir.resolve("admin-mod.json");
        ModularConfigLoader.load(adminDir, legacy, new ConfigLoader.ValidationReport());
        Files.writeString(adminDir.resolve("protection.json"), "{ this is not json");

        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        AdminConfig config = ModularConfigLoader.load(adminDir, legacy, report);
        assertTrue(Files.exists(adminDir.resolve("protection.json.broken")),
                "the malformed file is kept aside rather than deleted");
        assertEquals(new AdminConfig().protection.chatPerMinute, config.protection.chatPerMinute);
        assertFalse(report.isEmpty(), "the report must mention the bad file");
    }

    @Test
    void aLegacyFileIsMigratedAndKeptAside(@TempDir Path dir) throws IOException {
        Path adminDir = dir.resolve("admin");
        Path legacy = dir.resolve("admin-mod.json");
        AdminConfig legacyConfig = new AdminConfig();
        legacyConfig.chatBridge.provider = "generic";
        legacyConfig.remoteAdmin.ownerUuid = "00000000-0000-0000-0000-0000000000aa";
        ConfigLoader.save(legacy, legacyConfig);

        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        AdminConfig config = ModularConfigLoader.load(adminDir, legacy, report);
        assertEquals("generic", config.chatBridge.provider, "the legacy values are carried over");
        assertEquals("00000000-0000-0000-0000-0000000000aa", config.remoteAdmin.ownerUuid);
        assertTrue(Files.exists(dir.resolve("admin-mod.json.migrated")), "the legacy file is kept aside");
        assertTrue(report.notes().stream().anyMatch(note -> note.contains("migrated")),
                "the report mentions the migration");
    }

    @Test
    void everySectionIsOwnedByExactlyOneModule() {
        List<String> sections = List.of("general", "announcements", "maintenance", "logging", "moderation",
                "permissions", "anticheat", "antidupe", "chatBridge", "remoteAdmin", "performance", "alerts",
                "protection");
        for (String section : sections) {
            long owners = ModularConfigLoader.MODULES.values().stream()
                    .filter(module -> module.contains(section)).count();
            assertEquals(1, owners, section + " must be owned by exactly one module file");
        }
    }
}
