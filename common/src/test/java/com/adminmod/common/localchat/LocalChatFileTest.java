package com.adminmod.common.localchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.adminmod.common.config.AdminConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Reading and writing the standalone proximity settings file. */
class LocalChatFileTest {

    @Test
    void aMissingFileIsCreatedFromTheDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config/admin/localchat.json");
        AdminConfig.LocalChat defaults = new AdminConfig.LocalChat();
        defaults.enabled = true;

        AdminConfig.LocalChat loaded = LocalChatFile.load(file, defaults);

        assertTrue(Files.exists(file), "the file is written so the operator has something to edit");
        assertTrue(loaded.enabled);
    }

    @Test
    void aWrittenFileRoundTrips(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("localchat.json");
        AdminConfig.LocalChat config = new AdminConfig.LocalChat();
        config.enabled = true;
        config.radius = 32.5;
        config.globalPrefix = "#";
        config.cooldownMillis = 2_000L;
        AdminConfig.DimensionRule rule = new AdminConfig.DimensionRule();
        rule.dimension = "minecraft:the_nether";
        rule.radius = 8.0;
        config.dimensions = java.util.List.of(rule);

        LocalChatFile.save(file, config);
        AdminConfig.LocalChat loaded = LocalChatFile.load(file, new AdminConfig.LocalChat());

        assertTrue(loaded.enabled);
        assertEquals(32.5, loaded.radius);
        assertEquals("#", loaded.globalPrefix);
        assertEquals(2_000L, loaded.cooldownMillis);
        assertEquals(1, loaded.dimensions.size());
        assertEquals("minecraft:the_nether", loaded.dimensions.get(0).dimension);
        assertEquals(8.0, loaded.dimensions.get(0).radius);
    }

    @Test
    void aMalformedFileFallsBackToTheDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("localchat.json");
        Files.writeString(file, "{ not valid json");

        AdminConfig.LocalChat defaults = new AdminConfig.LocalChat();
        defaults.enabled = true;
        AdminConfig.LocalChat loaded = LocalChatFile.load(file, defaults);

        assertNotNull(loaded);
        assertTrue(loaded.enabled, "a broken file must not stop the server");
    }

    @Test
    void aPartiallyWrittenFileIsNormalised(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("localchat.json");
        // A hand-edited file with the format and sound fields missing.
        Files.writeString(file, "{\"enabled\":true,\"radius\":40.0}");

        AdminConfig.LocalChat defaults = new AdminConfig.LocalChat();
        AdminConfig.LocalChat loaded = LocalChatFile.load(file, defaults);

        assertTrue(loaded.enabled);
        assertEquals(40.0, loaded.radius);
        assertNotNull(loaded.localFormat);
        assertNotNull(loaded.globalFormat);
        assertNotNull(loaded.soundId);
    }
}
