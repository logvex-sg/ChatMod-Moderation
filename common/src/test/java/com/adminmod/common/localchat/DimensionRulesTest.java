package com.adminmod.common.localchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.adminmod.common.config.AdminConfig;

import org.junit.jupiter.api.Test;

/** Resolution of the effective settings for a dimension. */
class DimensionRulesTest {

    private static AdminConfig.LocalChat base() {
        AdminConfig.LocalChat config = new AdminConfig.LocalChat();
        config.enabled = true;
        config.radius = 50.0;
        config.globalPrefix = "!";
        return config;
    }

    @Test
    void withoutAMatchingRuleTheGlobalDefaultsApply() {
        DimensionRules.Effective effective = DimensionRules.resolve(base(), "minecraft:overworld");
        assertTrue(effective.enabled());
        assertEquals(50.0, effective.radius());
        assertEquals("!", effective.globalPrefix());
    }

    @Test
    void aMatchingRuleOverridesTheRadius() {
        AdminConfig.LocalChat config = base();
        AdminConfig.DimensionRule rule = new AdminConfig.DimensionRule();
        rule.dimension = "minecraft:the_nether";
        rule.radius = 16.0;
        config.dimensions = List.of(rule);

        assertEquals(16.0, DimensionRules.resolve(config, "minecraft:the_nether").radius());
        assertEquals(50.0, DimensionRules.resolve(config, "minecraft:overworld").radius(),
                "another dimension keeps the global radius");
    }

    @Test
    void aRuleCanDisableProximityChatForADimension() {
        AdminConfig.LocalChat config = base();
        AdminConfig.DimensionRule rule = new AdminConfig.DimensionRule();
        rule.dimension = "minecraft:the_end";
        rule.enabled = false;
        config.dimensions = List.of(rule);

        assertFalse(DimensionRules.resolve(config, "minecraft:the_end").enabled());
        assertTrue(DimensionRules.resolve(config, "minecraft:overworld").enabled());
    }

    @Test
    void aBlankRuleFieldInheritsTheGlobalValue() {
        AdminConfig.LocalChat config = base();
        config.localFormat = "[L] %player%";
        AdminConfig.DimensionRule rule = new AdminConfig.DimensionRule();
        rule.dimension = "minecraft:overworld";
        rule.localFormat = "";
        config.dimensions = List.of(rule);

        assertEquals("[L] %player%", DimensionRules.resolve(config, "minecraft:overworld").localFormat());
    }

    @Test
    void aNonPositiveRuleRadiusFallsBackToTheGlobalRadius() {
        AdminConfig.LocalChat config = base();
        AdminConfig.DimensionRule rule = new AdminConfig.DimensionRule();
        rule.dimension = "minecraft:overworld";
        rule.radius = 0;
        config.dimensions = List.of(rule);

        assertEquals(50.0, DimensionRules.resolve(config, "minecraft:overworld").radius());
    }

    @Test
    void aRuleCanTurnOffGlobalReachForOneDimension() {
        AdminConfig.LocalChat config = base();
        AdminConfig.DimensionRule rule = new AdminConfig.DimensionRule();
        rule.dimension = "minecraft:overworld";
        rule.globalReachesAllDimensions = Boolean.FALSE;
        config.dimensions = List.of(rule);

        assertFalse(DimensionRules.resolve(config, "minecraft:overworld").globalReachesAll());
        assertTrue(DimensionRules.resolve(config, "minecraft:the_nether").globalReachesAll());
    }

    @Test
    void isGlobalMatchesOnlyTheConfiguredPrefix() {
        DimensionRules.Effective effective = DimensionRules.resolve(base(), "minecraft:overworld");
        assertTrue(DimensionRules.isGlobal(effective, "!hello"));
        assertFalse(DimensionRules.isGlobal(effective, "hello"));
    }

    @Test
    void stripPrefixRemovesThePrefixAndLeadingSpace() {
        DimensionRules.Effective effective = DimensionRules.resolve(base(), "minecraft:overworld");
        assertEquals("hello", DimensionRules.stripPrefix(effective, "!hello"));
        assertEquals("hello", DimensionRules.stripPrefix(effective, "!   hello"));
        assertEquals("hello", DimensionRules.stripPrefix(effective, "hello"));
    }
}
