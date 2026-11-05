package com.adminmod.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.adminmod.common.config.AdminConfig;

/** The registry: it must cover the whole model, capture defaults and never drift from the fields. */
class SettingRegistryTest {

    @Test
    void everyCategoryInTheCatalogueHasAtLeastOneSetting() {
        SettingRegistry registry = SettingRegistry.createDefault();
        for (SettingCategory category : SettingCategory.ordered()) {
            assertFalse(registry.inCategory(category).isEmpty(),
                    category + " must have at least one setting");
        }
    }

    @Test
    void theRegistryDescribesEveryEditableFieldOnTheModel() {
        AdminConfig defaults = new AdminConfig();
        SettingRegistry registry = SettingRegistry.createDefault();
        for (String path : SettingsBinder.leafPaths(defaults)) {
            if (path.equals("configVersion")) {
                continue; // internal bookkeeping, not an operator setting
            }
            assertNotNull(registry.byPath(path), "no spec for model field " + path);
        }
    }

    @Test
    void noSpecPointsAtAFieldThatDoesNotExist() {
        SettingRegistry registry = SettingRegistry.createDefault();
        assertTrue(registry.problems().isEmpty(),
                "the registry must not report drift: " + registry.problems());
    }

    @Test
    void everyModelFieldHasAnExplicitCatalogEntry() {
        // The catalog is meant to describe every setting by hand; the registry only falls back to a
        // placeholder. An empty notes list proves the catalog is complete.
        SettingRegistry registry = SettingRegistry.createDefault();
        assertTrue(registry.notes().isEmpty(),
                "the catalog is missing entries for: " + registry.notes());
    }

    @Test
    void eachSpecCapturesTheDocumentedDefault() {
        SettingRegistry registry = SettingRegistry.createDefault();
        AdminConfig defaults = new AdminConfig();
        // A representative sample, including a nested object and a list.
        assertEquals("discord", registry.byPath("chatBridge.provider").defaultValue());
        assertEquals("true", registry.byPath("chatBridge.enabled").defaultValue());
        assertEquals("512", registry.byPath("chatBridge.queueCapacity").defaultValue());
        assertEquals(SettingsBinder.read(defaults, "localChat.radius"),
                registry.byPath("localChat.radius").defaultValue());
        assertEquals(SettingsBinder.read(defaults, "chatBridge.blockedKeywords"),
                registry.byPath("chatBridge.blockedKeywords").defaultValue());
    }

    @Test
    void searchMatchesAcrossLabelDescriptionAndPath() {
        SettingRegistry registry = SettingRegistry.createDefault();
        List<SettingSpec> byPath = registry.search("chatBridge.queueCapacity");
        assertEquals(1, byPath.size());
        assertEquals("chatBridge.queueCapacity", byPath.get(0).path());

        // Multiple terms are ANDed.
        List<SettingSpec> byTerms = registry.search("chat queue");
        assertTrue(byTerms.stream().anyMatch(spec -> spec.path().equals("chatBridge.queueCapacity")));
        assertTrue(byTerms.stream().allMatch(spec -> spec.path().toLowerCase().contains("chat")
                || spec.description().toLowerCase().contains("chat")));
    }

    @Test
    void secretSettingsAreMarkedAndKnowTheirCategory() {
        SettingRegistry registry = SettingRegistry.createDefault();
        SettingSpec webhook = registry.byPath("chatBridge.discord.webhookUrl");
        assertTrue(webhook.secret());
        assertEquals(SettingCategory.INTEGRATIONS, webhook.category());
        assertFalse(registry.byPath("chatBridge.discord.username").secret());
    }

    @Test
    void typesAreDerivedFromTheField() {
        SettingRegistry registry = SettingRegistry.createDefault();
        assertEquals(SettingType.BOOLEAN, registry.byPath("chatBridge.enabled").type());
        assertEquals(SettingType.INTEGER, registry.byPath("chatBridge.queueCapacity").type());
        assertEquals(SettingType.LONG, registry.byPath("moderation.defaultTempBanSeconds").type());
        assertEquals(SettingType.DECIMAL, registry.byPath("localChat.radius").type());
        assertEquals(SettingType.ENUM, registry.byPath("chatBridge.provider").type());
        assertEquals(SettingType.COLOR, registry.byPath("chatBridge.discord.embedColor").type());
        assertEquals(SettingType.STRING_LIST, registry.byPath("chatBridge.blockedKeywords").type());
    }

    @Test
    void anEmptyRegistryIsUsableInTests() {
        SettingRegistry registry = SettingRegistry.empty();
        assertTrue(registry.isEmpty());
        assertNull(registry.byPath("anything"));
        assertTrue(registry.search("anything").isEmpty());
    }
}
