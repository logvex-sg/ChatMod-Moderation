package com.adminmod.common.localchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The per-player chat cooldown. */
class CooldownTrackerTest {

    @Test
    void theFirstMessageIsAlwaysAllowed() {
        CooldownTracker tracker = new CooldownTracker();
        assertTrue(tracker.tryAcquire("alex", 1_000L, 500L));
    }

    @Test
    void aSecondMessageInsideTheWindowIsRefused() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryAcquire("alex", 1_000L, 500L);
        assertFalse(tracker.tryAcquire("alex", 1_200L, 500L), "200ms is inside a 500ms cooldown");
        assertTrue(tracker.tryAcquire("alex", 1_500L, 500L), "exactly 500ms is allowed");
    }

    @Test
    void aZeroCooldownDisablesTheCheck() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryAcquire("alex", 1_000L, 0L);
        assertTrue(tracker.tryAcquire("alex", 1_000L, 0L));
    }

    @Test
    void retryAfterReportsTheRemainingWait() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryAcquire("alex", 1_000L, 500L);
        assertEquals(300L, tracker.retryAfterMillis("alex", 1_200L, 500L));
        assertEquals(0L, tracker.retryAfterMillis("alex", 1_600L, 500L));
    }

    @Test
    void cooldownsArePerPlayer() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryAcquire("alex", 1_000L, 500L);
        assertTrue(tracker.tryAcquire("steve", 1_100L, 500L), "another player is not throttled by Alex");
    }

    @Test
    void pruneDropsStaleEntries() {
        CooldownTracker tracker = new CooldownTracker();
        tracker.tryAcquire("alex", 1_000L, 500L);
        tracker.prune(10_000L, 500L);
        assertEquals(0, tracker.trackedPlayers(), "an entry older than the window is dropped");
    }
}
