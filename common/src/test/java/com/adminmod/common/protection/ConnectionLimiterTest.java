package com.adminmod.common.protection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Connection rate, concurrent cap and temporary restriction behaviour. */
class ConnectionLimiterTest {

    @Test
    void refusesOnceTheAcceptRateIsExceededAndRestrictsTheAddress() {
        ConnectionLimiter limiter = new ConnectionLimiter(3, 8, 64);
        assertTrue(limiter.accept("1.2.3.4:5000", 0L));
        limiter.release("1.2.3.4:5000", 0L);
        assertTrue(limiter.accept("1.2.3.4:5001", 1L));
        limiter.release("1.2.3.4:5001", 1L);
        assertTrue(limiter.accept("1.2.3.4:5002", 2L));
        limiter.release("1.2.3.4:5002", 2L);
        assertFalse(limiter.accept("1.2.3.4:5003", 3L), "the fourth accept in the window is refused");
        assertTrue(limiter.isRestricted("1.2.3.4", 3L), "an over-rate address is temporarily restricted");
        assertFalse(limiter.accept("1.2.3.4", 4L), "a restricted address stays refused");
        assertTrue(limiter.accept("1.2.3.4", 61_000L), "the restriction expires on its own");
    }

    @Test
    void enforcesTheConcurrentConnectionCap() {
        ConnectionLimiter limiter = new ConnectionLimiter(100, 2, 64);
        assertTrue(limiter.accept("10.0.0.1", 0L));
        assertTrue(limiter.accept("10.0.0.1", 0L));
        assertFalse(limiter.accept("10.0.0.1", 0L), "a third concurrent connection is refused");
        limiter.release("10.0.0.1", 0L);
        assertTrue(limiter.accept("10.0.0.1", 0L), "a freed slot is reusable");
    }

    @Test
    void theEphemeralPortIsIgnoredSoOneHostSharesABucket() {
        ConnectionLimiter limiter = new ConnectionLimiter(1, 8, 64);
        assertTrue(limiter.accept("/192.168.1.5:41000", 0L));
        assertFalse(limiter.accept("/192.168.1.5:42000", 0L),
                "a second connection from the same host shares the rate bucket");
    }

    @Test
    void differentAddressesAreIndependent() {
        ConnectionLimiter limiter = new ConnectionLimiter(1, 8, 64);
        assertTrue(limiter.accept("1.1.1.1", 0L));
        assertFalse(limiter.accept("1.1.1.1", 0L));
        assertTrue(limiter.accept("2.2.2.2", 0L));
    }
}
