package com.adminmod.common.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Rate and burst behaviour of the keyed limiter and the packet-frequency monitor. */
class KeyedRateLimiterTest {

    @Test
    void allowsUpToTheLimitPerKeyThenRefuses() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(3, 1_000L, 64);
        assertTrue(limiter.tryAcquire("alex", 0L));
        assertTrue(limiter.tryAcquire("alex", 0L));
        assertTrue(limiter.tryAcquire("alex", 0L));
        assertFalse(limiter.tryAcquire("alex", 0L));
    }

    @Test
    void eachKeyHasItsOwnBucket() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(1, 1_000L, 64);
        assertTrue(limiter.tryAcquire("alex", 0L));
        assertFalse(limiter.tryAcquire("alex", 0L));
        assertTrue(limiter.tryAcquire("steve", 0L), "a different key is unaffected");
    }

    @Test
    void theWindowSlides() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(1, 1_000L, 64);
        assertTrue(limiter.tryAcquire("alex", 0L));
        assertFalse(limiter.tryAcquire("alex", 999L));
        assertTrue(limiter.tryAcquire("alex", 1_001L));
    }

    @Test
    void forgetReleasesABucket() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(1, 1_000L, 64);
        assertTrue(limiter.tryAcquire("alex", 0L));
        limiter.forget("alex");
        assertTrue(limiter.tryAcquire("alex", 0L));
    }

    @Test
    void theTableIsBounded() {
        KeyedRateLimiter limiter = new KeyedRateLimiter(10, 60_000L, 16);
        for (int i = 0; i < 1_000; i++) {
            limiter.tryAcquire("key-" + i, 0L);
        }
        assertTrue(limiter.trackedKeys() <= 16, "the key table must stay bounded");
    }

    @Test
    void packetMonitorTripsOnlyWhenBothWindowsAreExceeded() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor(64);
        // A short burst above the per-second rate but below the ten-second rate must not trip: that
        // is what a chunk load looks like.
        boolean tripped = false;
        for (int i = 0; i < 500; i++) {
            tripped |= monitor.observe("alex", 0L, 400, 3_000);
        }
        assertFalse(tripped, "a burst inside the long window must not be flagged");
    }

    @Test
    void packetMonitorFlagsASustainedFlood() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor(64);
        boolean tripped = false;
        // A sustained rate above both the per-second and the ten-second window.
        for (int i = 0; i < 20_000; i++) {
            tripped |= monitor.observe("alex", i, 400, 3_000);
        }
        assertTrue(tripped, "a sustained flood must be flagged");
    }

    @Test
    void packetMonitorForgetsAPlayer() {
        PacketFrequencyMonitor monitor = new PacketFrequencyMonitor(64);
        monitor.observe("alex", 0L, 400, 3_000);
        assertEquals(1, monitor.trackedPlayers());
        monitor.forget("alex");
        assertEquals(0, monitor.trackedPlayers());
    }
}
