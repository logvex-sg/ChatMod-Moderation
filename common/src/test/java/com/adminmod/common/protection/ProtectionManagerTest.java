package com.adminmod.common.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.diagnostics.ErrorReporter;

/** The decision layer: grace period, per-check routing and the violation log. */
class ProtectionManagerTest {

    private static ProtectionManager manager(AdminConfig config) {
        return new ProtectionManager(config, new ErrorReporter(16, 8, null), new ViolationLog(64));
    }

    @Test
    void oversizedChatIsDroppedAndLogged() {
        AdminConfig config = new AdminConfig();
        ProtectionManager manager = manager(config);
        assertFalse(manager.allowChat("alex", "x".repeat(config.protection.maxChatLength + 1), 0L));
        assertEquals(1, manager.violations().count(ViolationLog.Kind.OVERSIZED_CHAT));
    }

    @Test
    void controlCharactersInChatAreDropped() {
        ProtectionManager manager = manager(new AdminConfig());
        assertFalse(manager.allowChat("alex", "bad\u0001", 0L));
        assertEquals(1, manager.violations().count(ViolationLog.Kind.SUSPICIOUS_NBT));
    }

    @Test
    void theGracePeriodSuppressesRateChecks() {
        AdminConfig config = new AdminConfig();
        config.protection.chatPerMinute = 1;
        config.protection.joinGraceTicks = 100; // five seconds
        ProtectionManager manager = manager(config);
        manager.onJoin("alex", 0L);
        // During the grace window even a flood of messages is allowed.
        for (int i = 0; i < 50; i++) {
            assertTrue(manager.allowChat("alex", "hi", 10L), "grace period must not count messages");
        }
        // After the grace window the limiter applies again.
        assertTrue(manager.allowChat("alex", "hi", 10_000L));
        assertFalse(manager.allowChat("alex", "hi", 10_000L));
    }

    @Test
    void chatRateIsPerPlayer() {
        AdminConfig config = new AdminConfig();
        config.protection.chatPerMinute = 1;
        config.protection.joinGraceTicks = 0;
        ProtectionManager manager = manager(config);
        assertTrue(manager.allowChat("alex", "hi", 0L));
        assertFalse(manager.allowChat("alex", "hi", 0L));
        assertTrue(manager.allowChat("steve", "hi", 0L), "another player has their own budget");
    }

    @Test
    void clickShapeIsValidatedAgainstTheContainer() {
        ProtectionManager manager = manager(new AdminConfig());
        assertTrue(manager.validateClickShape("alex", 5, 46, 3));
        assertFalse(manager.validateClickShape("alex", 99, 46, 3), "a slot outside the container is refused");
        assertFalse(manager.validateClickShape("alex", 5, 46, 10_000),
                "an implausible modified-stack count is refused");
    }

    @Test
    void connectionChecksAreIndependentOfPlayerChecks() {
        AdminConfig config = new AdminConfig();
        config.anticheat.connectionRatePerMinute = 2;
        ProtectionManager manager = manager(config);
        assertTrue(manager.allowConnection("1.2.3.4", 0L));
        manager.onConnectionClosed("1.2.3.4", 0L);
        assertTrue(manager.allowConnection("1.2.3.4", 0L));
        manager.onConnectionClosed("1.2.3.4", 0L);
        assertFalse(manager.allowConnection("1.2.3.4", 0L));
    }

    @Test
    void statusJsonCarriesTheThresholdsAndRecentViolations() {
        AdminConfig config = new AdminConfig();
        ProtectionManager manager = manager(config);
        manager.allowChat("alex", "x".repeat(config.protection.maxChatLength + 1), 0L);
        String json = manager.statusJson();
        assertNotNull(json);
        assertTrue(json.contains("maxChatLength"));
        assertTrue(json.contains("oversized_chat"));
    }

    @Test
    void clearingTheViolationsResetsTheLog() {
        ProtectionManager manager = manager(new AdminConfig());
        manager.allowChat("alex", "x".repeat(100_000), 0L);
        assertTrue(manager.violations().total() > 0);
        manager.violations().reset();
        assertEquals(0, manager.violations().total());
    }

    @Test
    void aProfileChangeTakesEffectThroughReload() {
        AdminConfig config = new AdminConfig();
        config.protection.chatPerMinute = 1;
        config.protection.joinGraceTicks = 0;
        ProtectionManager manager = manager(config);
        assertTrue(manager.allowChat("alex", "hi", 0L));
        assertFalse(manager.allowChat("alex", "hi", 0L));

        com.adminmod.common.config.PerformanceProfile.apply(config, "performance");
        manager.reload();
        // The new profile raised the limit and cleared the buckets, so the same player is allowed.
        assertTrue(manager.allowChat("alex", "hi", 1L));
    }
}
