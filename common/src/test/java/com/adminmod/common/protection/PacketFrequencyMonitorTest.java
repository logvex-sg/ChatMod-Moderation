package com.adminmod.common.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The two-window packet monitor: a burst is forgiven, a sustained rate is not. */
class PacketFrequencyMonitorTest {

    private static final int SHORT = 50;
    private static final int LONG = 300;

    @Test
    void aShortBurstDoesNotTripTheLongWindow() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor();
        // A teleport-sized spike: 200 packets inside one second, but far under the ten-second cap.
        boolean tripped = false;
        for (int i = 0; i < 200; i++) {
            tripped |= monitor.observe("alex", i, SHORT, LONG);
        }
        assertFalse(tripped, "a one-second burst alone must not be flagged");
    }

    @Test
    void aSustainedRateTripsBothWindows() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor();
        boolean tripped = false;
        for (int i = 0; i < 500; i++) {
            // 500 packets over 500 ms: over the short cap and, once it passes 300, over the long one.
            tripped |= monitor.observe("alex", i, SHORT, LONG);
        }
        assertTrue(tripped, "a sustained flood must be flagged");
    }

    @Test
    void aBurstThatRecoversIsForgiven() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor();
        for (int i = 0; i < 200; i++) {
            monitor.observe("alex", i, SHORT, LONG);
        }
        // Eleven seconds of quiet: both windows roll over and the next packets start fresh.
        assertFalse(monitor.observe("alex", 12_000L, SHORT, LONG));
    }

    @Test
    void eachPlayerIsTrackedSeparately() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor();
        for (int i = 0; i < 500; i++) {
            monitor.observe("alex", i, SHORT, LONG);
        }
        assertFalse(monitor.observe("steve", 0L, SHORT, LONG),
                "another player starts with a clean budget");
    }

    @Test
    void theTableIsBoundedAndForgettable() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor(16);
        for (int i = 0; i < 100; i++) {
            monitor.observe("p" + i, i, SHORT, LONG);
        }
        assertTrue(monitor.trackedPlayers() <= 16, "the table must not grow without bound");
        monitor.forget("p99");
        monitor.clear();
        assertEquals(0, monitor.trackedPlayers());
    }

    @Test
    void packetsPerSecondReflectsTheCurrentWindow() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor();
        for (int i = 0; i < 20; i++) {
            monitor.observe("alex", i * 10L, SHORT, LONG);
        }
        assertTrue(monitor.packetsPerSecond("alex", 200L) > 0);
        assertEquals(0.0, monitor.packetsPerSecond("nobody", 0L));
    }
}
