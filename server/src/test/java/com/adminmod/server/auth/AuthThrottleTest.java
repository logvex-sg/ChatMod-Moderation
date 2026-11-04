package com.adminmod.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The lockout is the only thing slowing a brute-force attempt against a credential, so its window
 * behaviour is pinned: failures accumulate, the limit locks the address, and the lock lapses.
 */
class AuthThrottleTest {

    @Test
    void anAddressIsNotLockedBeforeTheLimit() {
        AuthThrottle throttle = new AuthThrottle(3, 60_000L);
        long now = 1_000L;
        throttle.recordFailure("1.2.3.4", now);
        throttle.recordFailure("1.2.3.4", now);
        assertFalse(throttle.isLockedOut("1.2.3.4", now));
    }

    @Test
    void reachingTheLimitLocksTheAddress() {
        AuthThrottle throttle = new AuthThrottle(3, 60_000L);
        long now = 1_000L;
        throttle.recordFailure("1.2.3.4", now);
        throttle.recordFailure("1.2.3.4", now);
        throttle.recordFailure("1.2.3.4", now);
        assertTrue(throttle.isLockedOut("1.2.3.4", now));
        assertTrue(throttle.retryAfterMillis("1.2.3.4", now) > 0);
    }

    @Test
    void theLockLapsesAfterTheWindow() {
        AuthThrottle throttle = new AuthThrottle(2, 10_000L);
        throttle.recordFailure("1.2.3.4", 1_000L);
        throttle.recordFailure("1.2.3.4", 1_000L);
        assertTrue(throttle.isLockedOut("1.2.3.4", 1_000L));
        assertFalse(throttle.isLockedOut("1.2.3.4", 11_001L));
    }

    @Test
    void aSuccessClearsTheFailures() {
        AuthThrottle throttle = new AuthThrottle(3, 60_000L);
        throttle.recordFailure("1.2.3.4", 1_000L);
        throttle.recordFailure("1.2.3.4", 1_000L);
        throttle.recordSuccess("1.2.3.4");
        throttle.recordFailure("1.2.3.4", 1_000L);
        assertFalse(throttle.isLockedOut("1.2.3.4", 1_000L));
    }

    @Test
    void addressesAreTrackedIndependently() {
        AuthThrottle throttle = new AuthThrottle(2, 60_000L);
        throttle.recordFailure("1.1.1.1", 1_000L);
        throttle.recordFailure("1.1.1.1", 1_000L);
        assertTrue(throttle.isLockedOut("1.1.1.1", 1_000L));
        assertFalse(throttle.isLockedOut("2.2.2.2", 1_000L));
    }

    @Test
    void pruneDropsStaleEntries() {
        AuthThrottle throttle = new AuthThrottle(2, 10_000L);
        throttle.recordFailure("1.1.1.1", 1_000L);
        throttle.prune(100_000L);
        assertFalse(throttle.isLockedOut("1.1.1.1", 100_000L));
        org.junit.jupiter.api.Assertions.assertEquals(0, throttle.trackedAddresses());
    }
}
