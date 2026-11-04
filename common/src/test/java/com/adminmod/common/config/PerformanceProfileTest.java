package com.adminmod.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The optimisation profiles and the invariant that they never disable the hard checks. */
class PerformanceProfileTest {

    @Test
    void normalisesKnownNames() {
        assertEquals(PerformanceProfile.PERFORMANCE, PerformanceProfile.normalise("perf"));
        assertEquals(PerformanceProfile.PERFORMANCE, PerformanceProfile.normalise("LOW"));
        assertEquals(PerformanceProfile.BALANCED, PerformanceProfile.normalise("default"));
        assertEquals(PerformanceProfile.MAXIMUM, PerformanceProfile.normalise("strict"));
        assertNull(PerformanceProfile.normalise("nonsense"));
    }

    @Test
    void applyingAProfileRewritesTheThresholds() {
        AdminConfig config = new AdminConfig();
        assertTrue(PerformanceProfile.apply(config, "maximum"));
        assertEquals(PerformanceProfile.MAXIMUM, config.profile);
        assertEquals(20, config.protection.chatPerMinute);
        assertTrue(config.antidupe.monitorRepeatClicks);
        assertTrue(config.performance.collectChunkActivity);

        assertTrue(PerformanceProfile.apply(config, "performance"));
        assertEquals(60, config.protection.chatPerMinute);
        assertFalse(config.antidupe.monitorRepeatClicks);
        assertFalse(config.performance.collectChunkActivity);
    }

    @Test
    void anUnknownProfileChangesNothing() {
        AdminConfig config = new AdminConfig();
        int before = config.protection.chatPerMinute;
        assertFalse(PerformanceProfile.apply(config, "nope"));
        assertEquals(before, config.protection.chatPerMinute);
    }

    @Test
    void everyProfileKeepsTheChecksThatOnlyRejectImpossibleData() {
        for (String name : PerformanceProfile.names()) {
            AdminConfig config = new AdminConfig();
            PerformanceProfile.apply(config, name);
            assertTrue(config.protection.enabled, name + " must keep protection enabled");
            assertTrue(config.anticheat.enabled, name + " must keep anticheat enabled");
            assertTrue(config.antidupe.validateSlots, name + " must keep slot validation on");
            assertTrue(config.antidupe.validateModifiedStacks, name + " must keep stack validation on");
        }
    }

    @Test
    void everyProfileHasADescription() {
        for (String name : PerformanceProfile.names()) {
            assertFalse(PerformanceProfile.describe(name).isBlank());
        }
    }
}
