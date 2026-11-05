package com.adminmod.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.adminmod.common.config.AdminConfig;

/** Profiles and presets: sparse application, secret exclusion, and the profile store round-trip. */
class ConfigProfileTest {

    private final SettingRegistry registry = SettingRegistry.createDefault();

    @Test
    void aPresetChangesOnlyTheSettingsItNames() {
        AdminConfig config = new AdminConfig();
        config.chatBridge.queueCapacity = 700;
        ConfigProfile anarchy = ConfigPresets.byName("anarchy");
        assertNotNull(anarchy);
        List<String> changed = ConfigPresets.apply(config, anarchy, registry);

        assertTrue(changed.contains("localChat.radius"));
        assertEquals("32.0", SettingsBinder.read(config, "localChat.radius"));
        // A setting the preset does not mention is untouched.
        assertEquals(700, config.chatBridge.queueCapacity);
    }

    @Test
    void aProfileNeverCarriesASecret() {
        AdminConfig config = new AdminConfig();
        config.chatBridge.discord.webhookUrl = "https://discord.com/api/webhooks/000/secret-token";
        config.chatBridge.matrix.accessToken = "syt_secret";
        ConfigProfile profile = ConfigProfile.fromConfig("test", "", config, registry);
        assertFalse(profile.values().containsKey("chatBridge.discord.webhookUrl"));
        assertFalse(profile.values().containsKey("chatBridge.matrix.accessToken"));
        for (String value : profile.values().values()) {
            assertFalse(value.contains("secret-token"), "a secret leaked into the profile");
        }
    }

    @Test
    void applyingAProfileThatNamesASecretIsIgnored() {
        AdminConfig config = new AdminConfig();
        ConfigProfile malicious = new ConfigProfile("evil", "", false,
                java.util.Map.of("chatBridge.discord.webhookUrl", "https://attacker.example/x"));
        ConfigPresets.apply(config, malicious, registry);
        assertEquals("", config.chatBridge.discord.webhookUrl);
    }

    @Test
    void theDiffReportsOnlyRealChanges() {
        AdminConfig config = new AdminConfig();
        ConfigProfile profile = new ConfigProfile("p", "", false,
                java.util.Map.of("chatBridge.provider", "matrix", "chatBridge.enabled", "true"));
        List<ConfigProfile.Difference> differences = profile.diffAgainst(config, registry);
        assertEquals(1, differences.size());
        assertEquals("chatBridge.provider", differences.get(0).path());
        assertEquals("discord", differences.get(0).current());
        assertEquals("matrix", differences.get(0).proposed());
    }

    @Test
    void theBuiltInProfileListIncludesTheFiveOptimisationProfiles() {
        List<ConfigProfile> profiles = ConfigPresets.builtInProfiles();
        assertEquals(5, profiles.size());
        assertNotNull(ConfigPresets.byName("custom"));
        assertNotNull(ConfigPresets.byName("maximum-protection"));
    }

    @Test
    void eightServerPresetsAreOffered() {
        assertEquals(8, ConfigPresets.presets().size());
        for (ConfigProfile preset : ConfigPresets.presets()) {
            assertFalse(preset.values().isEmpty(), preset.name() + " must set at least one setting");
            assertTrue(preset.builtIn());
        }
    }

    @Test
    void aProfileStoreRoundTripsCustomProfilesAndNeverStoresBuiltIns(@TempDir Path dir) throws IOException {
        ProfileStore store = new ProfileStore(dir.resolve("profiles.json"));
        AdminConfig config = new AdminConfig();
        config.chatBridge.queueCapacity = 4096;
        store.create("mine", "my profile", new ProfileStore.AdminConfigSource() {
            @Override
            public AdminConfig config() {
                return config;
            }

            @Override
            public SettingRegistry registry() {
                return registry;
            }
        });
        store.select("mine");
        store.save();

        ProfileStore reloaded = new ProfileStore(dir.resolve("profiles.json"));
        reloaded.load();
        assertEquals("mine", reloaded.selected());
        ConfigProfile loaded = reloaded.find("mine");
        assertNotNull(loaded);
        assertEquals("4096", loaded.value("chatBridge.queueCapacity"));
        // Built-ins are not written to the store; they come from the build.
        assertTrue(reloaded.custom().size() == 1);
    }

    @Test
    void aCustomProfileCannotShadowABuiltInName(@TempDir Path dir) {
        ProfileStore store = new ProfileStore(dir.resolve("profiles.json"));
        assertFalse(store.put(new ConfigProfile("anarchy", "", false, java.util.Map.of())));
    }

    @Test
    void renamingAndDeletingBehave() {
        ProfileStore store = new ProfileStore(Path.of("/tmp/never-written.json"));
        store.put(new ConfigProfile("a", "", false, java.util.Map.of("chatBridge.enabled", "false")));
        assertTrue(store.rename("a", "b"));
        assertNotNull(store.find("b"));
        assertNull(store.find("a"));
        assertFalse(store.rename("b", "anarchy"), "cannot rename onto a built-in");
        assertTrue(store.delete("b"));
        assertNull(store.find("b"));
    }
}
