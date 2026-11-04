package com.adminmod.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.adminmod.common.config.AdminConfig;

import org.junit.jupiter.api.Test;

class AntiSpamGuardTest {

    private static AdminConfig.AntiSpam config() {
        AdminConfig.AntiSpam config = new AdminConfig.AntiSpam();
        config.enabled = true;
        config.duplicateWindowSeconds = 8L;
        config.maxDuplicates = 2;
        config.minIntervalMillis = 250L;
        return config;
    }

    @Test
    void repeatedIdenticalMessagesAreEventuallyDropped() {
        AntiSpamGuard guard = new AntiSpamGuard(config());
        long now = 1_000_000L;
        assertTrue(guard.accept("alex", "buy my stuff", now));
        assertTrue(guard.accept("alex", "buy my stuff", now + 1_000L));
        // maxDuplicates = 2, so the third identical copy inside the window is dropped.
        assertFalse(guard.accept("alex", "buy my stuff", now + 2_000L));
    }

    @Test
    void messagesFromDifferentPlayersAreIndependent() {
        AntiSpamGuard guard = new AntiSpamGuard(config());
        long now = 1_000_000L;
        assertTrue(guard.accept("alex", "same text", now));
        assertTrue(guard.accept("bob", "same text", now));
    }

    @Test
    void messagesFasterThanTheMinimumIntervalAreDropped() {
        AntiSpamGuard guard = new AntiSpamGuard(config());
        long now = 1_000_000L;
        assertTrue(guard.accept("alex", "first", now));
        assertFalse(guard.accept("alex", "second", now + 100L), "below the minimum gap");
        assertTrue(guard.accept("alex", "third", now + 300L));
    }

    @Test
    void distinctMessagesOutsideTheWindowAreAccepted() {
        AntiSpamGuard guard = new AntiSpamGuard(config());
        long now = 1_000_000L;
        assertTrue(guard.accept("alex", "hello", now));
        assertTrue(guard.accept("alex", "world", now + 300L));
        assertTrue(guard.accept("alex", "hello", now + 10_000L), "the duplicate window has passed");
    }

    @Test
    void aDisabledGuardAcceptsEverything() {
        AdminConfig.AntiSpam config = config();
        config.enabled = false;
        AntiSpamGuard guard = new AntiSpamGuard(config);
        long now = 1_000_000L;
        for (int i = 0; i < 100; i++) {
            assertTrue(guard.accept("alex", "spam", now));
        }
    }

    @Test
    void stateIsPrunedSoMemoryDoesNotGrowWithoutBound() {
        AntiSpamGuard guard = new AntiSpamGuard(config());
        long now = 1_000_000L;
        for (int i = 0; i < 1_000; i++) {
            guard.accept("player" + i, "message", now);
        }
        // Advance well past every window and touch the guard once more to trigger a prune.
        guard.accept("fresh", "message", now + 120_000L);
        assertTrue(guard.trackedPlayers() < 1_000, "old players should have been pruned");
    }
}
