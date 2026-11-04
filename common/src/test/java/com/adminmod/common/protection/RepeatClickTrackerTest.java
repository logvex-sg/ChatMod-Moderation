package com.adminmod.common.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Repeat-click detection: a burst of the identical click is a dupe signature. */
class RepeatClickTrackerTest {

    @Test
    void anIdenticalBurstTripsAtTheThreshold() {
        RepeatClickTracker tracker = new RepeatClickTracker(64);
        long signature = 0x1234L;
        boolean tripped = false;
        for (int i = 0; i < 12; i++) {
            tripped |= tracker.observe("alex", signature, i, 12, 1_000L);
        }
        assertTrue(tripped, "the twelfth identical click inside the window trips the threshold");
    }

    @Test
    void aDifferentClickResetsTheRun() {
        RepeatClickTracker tracker = new RepeatClickTracker(64);
        for (int i = 0; i < 11; i++) {
            tracker.observe("alex", 1L, i, 12, 1_000L);
        }
        // A different signature restarts the run, so the next identical click cannot complete it.
        assertFalse(tracker.observe("alex", 2L, 11L, 12, 1_000L));
        assertFalse(tracker.observe("alex", 1L, 12L, 12, 1_000L));
    }

    @Test
    void theWindowExpires() {
        RepeatClickTracker tracker = new RepeatClickTracker(64);
        for (int i = 0; i < 11; i++) {
            tracker.observe("alex", 7L, i, 12, 1_000L);
        }
        // The next click is more than the window after the run started, so the run restarts.
        assertFalse(tracker.observe("alex", 7L, 5_000L, 12, 1_000L));
    }

    @Test
    void playersAreIndependent() {
        RepeatClickTracker tracker = new RepeatClickTracker(64);
        for (int i = 0; i < 11; i++) {
            tracker.observe("alex", 9L, i, 12, 1_000L);
        }
        assertFalse(tracker.observe("steve", 9L, 11L, 12, 1_000L),
                "another player's run is separate");
    }

    @Test
    void forgettingAPlayerClearsTheRun() {
        RepeatClickTracker tracker = new RepeatClickTracker(64);
        for (int i = 0; i < 11; i++) {
            tracker.observe("alex", 5L, i, 12, 1_000L);
        }
        tracker.forget("alex");
        assertEquals(0, tracker.trackedPlayers());
        assertFalse(tracker.observe("alex", 5L, 12L, 12, 1_000L));
    }
}
