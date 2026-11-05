package com.adminmod.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.adminmod.common.config.AdminConfig;

/** The binder: dotted paths must read and write the nested model without surprise. */
class SettingsBinderTest {

    @Test
    void readsAndWritesScalarPaths() {
        AdminConfig config = new AdminConfig();
        assertEquals("discord", SettingsBinder.read(config, "chatBridge.provider"));
        assertTrue(SettingsBinder.write(config, "chatBridge.provider", "matrix"));
        assertEquals("matrix", config.chatBridge.provider);

        assertTrue(SettingsBinder.write(config, "chatBridge.queueCapacity", "512"));
        assertEquals(512, config.chatBridge.queueCapacity);

        assertTrue(SettingsBinder.write(config, "moderation.defaultTempBanSeconds", "7200"));
        assertEquals(7_200L, config.moderation.defaultTempBanSeconds);

        assertTrue(SettingsBinder.write(config, "localChat.radius", "64.5"));
        assertEquals(64.5, config.localChat.radius);
    }

    @Test
    void writesBooleansFromEveryAcceptedSpelling() {
        AdminConfig config = new AdminConfig();
        for (String on : new String[] {"true", "on", "1", "yes"}) {
            assertTrue(SettingsBinder.write(config, "chatBridge.enabled", on), on);
            assertTrue(config.chatBridge.enabled);
        }
        for (String off : new String[] {"false", "off", "0", "no"}) {
            assertTrue(SettingsBinder.write(config, "chatBridge.enabled", off), off);
            assertFalse(config.chatBridge.enabled);
        }
    }

    @Test
    void writesStringListsFromNewlineSeparatedText() {
        AdminConfig config = new AdminConfig();
        assertTrue(SettingsBinder.write(config, "chatBridge.blockedKeywords", "slur1\nslur2\n\nslur3\n"));
        assertEquals(java.util.List.of("slur1", "slur2", "slur3"), config.chatBridge.blockedKeywords);
    }

    @Test
    void anUnknownPathIsRejectedRatherThanCreatingOne() {
        AdminConfig config = new AdminConfig();
        assertFalse(SettingsBinder.write(config, "chatBridge.notAField", "x"));
        assertFalse(SettingsBinder.write(config, "noSuchSection.field", "x"));
        assertEquals(null, SettingsBinder.read(config, "chatBridge.notAField"));
    }

    @Test
    void anUnparseableValueLeavesTheFieldAlone() {
        AdminConfig config = new AdminConfig();
        int before = config.chatBridge.queueCapacity;
        assertFalse(SettingsBinder.write(config, "chatBridge.queueCapacity", "not a number"));
        assertEquals(before, config.chatBridge.queueCapacity);
    }

    @Test
    void leafPathsIncludeNestedScalarsButNotStructuredLists() {
        AdminConfig config = new AdminConfig();
        java.util.List<String> paths = SettingsBinder.leafPaths(config);
        assertTrue(paths.contains("chatBridge.discord.webhookUrl"));
        assertTrue(paths.contains("chatBridge.queueCapacity"));
        assertTrue(paths.contains("chatBridge.blockedKeywords"));
        // A list of records is edited in its file, not as a string, so it is not a leaf.
        assertFalse(paths.contains("moderation.templates"));
        assertFalse(paths.contains("chatBridge.extraDiscordChannels"));
        assertFalse(paths.contains("protection.dimensions"));
    }
}
