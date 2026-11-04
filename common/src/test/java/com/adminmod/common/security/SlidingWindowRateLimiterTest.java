package com.adminmod.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

    @Test
    void allowsUpToTheLimitThenRefuses() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3, 1_000L);
        assertTrue(limiter.tryAcquire(0L));
        assertTrue(limiter.tryAcquire(1L));
        assertTrue(limiter.tryAcquire(2L));
        assertFalse(limiter.tryAcquire(3L));
    }

    @Test
    void permitsAgainOnceTheWindowSlides() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(2, 1_000L);
        assertTrue(limiter.tryAcquire(0L));
        assertTrue(limiter.tryAcquire(0L));
        assertFalse(limiter.tryAcquire(500L));
        assertTrue(limiter.tryAcquire(1_001L));
    }

    @Test
    void reportsTimeUntilNextSlot() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1, 1_000L);
        assertTrue(limiter.tryAcquire(0L));
        assertEquals(0L, limiter.millisUntilNextSlot(1_500L));
        assertEquals(500L, limiter.millisUntilNextSlot(500L));
    }
}
