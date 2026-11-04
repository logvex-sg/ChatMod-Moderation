package com.adminmod.integrations.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoopGuardTest {

    @Test
    void aFreshMessageIsAccepted() {
        LoopGuard guard = new LoopGuard(5_000L);
        assertTrue(guard.accept("Discord", "Alex", "hello", 1_000L));
    }

    @Test
    void anIdenticalMessageInsideTheWindowIsRejected() {
        LoopGuard guard = new LoopGuard(5_000L);
        assertTrue(guard.accept("Discord", "Alex", "hello", 1_000L));
        assertFalse(guard.accept("Discord", "Alex", "hello", 1_001L));
    }

    @Test
    void theSameMessageAfterTheWindowIsAcceptedAgain() {
        LoopGuard guard = new LoopGuard(5_000L);
        assertTrue(guard.accept("Discord", "Alex", "hello", 1_000L));
        assertTrue(guard.accept("Discord", "Alex", "hello", 1_000L + 6_000L));
    }

    @Test
    void aDifferentBodyIsAlwaysAccepted() {
        LoopGuard guard = new LoopGuard(5_000L);
        assertTrue(guard.accept("Discord", "Alex", "one", 1_000L));
        assertTrue(guard.accept("Discord", "Alex", "two", 1_000L));
    }

    @Test
    void aZeroWindowDisablesSuppression() {
        LoopGuard guard = new LoopGuard(0L);
        assertTrue(guard.accept("Discord", "Alex", "hello", 1_000L));
        assertTrue(guard.accept("Discord", "Alex", "hello", 1_000L));
    }

    @Test
    void theTableStaysBounded() {
        LoopGuard guard = new LoopGuard(60_000L);
        for (int i = 0; i < 5_000; i++) {
            guard.accept("Discord", "Alex", "message " + i, 1_000L);
        }
        assertTrue(guard.size() <= 512, "size " + guard.size());
    }
}
