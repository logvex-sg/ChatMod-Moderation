package com.adminmod.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReplayGuardTest {

    @Test
    void freshFrameIsAccepted() {
        ReplayGuard guard = new ReplayGuard();
        assertTrue(guard.accept(1L, 1_000_000L, 1_000_000L));
    }

    @Test
    void duplicateNonceIsRejected() {
        ReplayGuard guard = new ReplayGuard();
        assertTrue(guard.accept(7L, 1_000_000L, 1_000_000L));
        assertFalse(guard.accept(7L, 1_000_000L, 1_000_001L));
    }

    @Test
    void staleTimestampIsRejected() {
        ReplayGuard guard = new ReplayGuard();
        assertFalse(guard.accept(9L, 1_000_000L, 1_000_000L + 61_000L));
    }

    @Test
    void futureTimestampIsRejected() {
        ReplayGuard guard = new ReplayGuard();
        assertFalse(guard.accept(9L, 1_000_000L + 61_000L, 1_000_000L));
    }

    @Test
    void entriesArePurgedAfterTheWindow() {
        ReplayGuard guard = new ReplayGuard(1_000L);
        assertTrue(guard.accept(1L, 1_000_000L, 1_000_000L));
        assertTrue(guard.accept(2L, 1_000_000L, 1_000_000L + 50_000L));
        assertTrue(guard.size() <= 1);
    }
}
