package com.adminmod.common.localchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Distance and boundary handling for the proximity rule. */
class PositionTest {

    @Test
    void squaredDistanceIsZeroForTheSamePoint() {
        Position point = new Position(1, 2, 3);
        assertEquals(0.0, point.squaredDistanceTo(new Position(1, 2, 3)));
    }

    @Test
    void squaredDistanceIgnoresTheSquareRoot() {
        Position origin = new Position(0, 0, 0);
        assertEquals(25.0, origin.squaredDistanceTo(new Position(3, 4, 0)), 1e-9);
    }

    @Test
    void withinIsInclusiveOnTheBoundary() {
        Position origin = new Position(0, 0, 0);
        assertTrue(origin.isWithin(new Position(10, 0, 0), 10.0));
        assertFalse(origin.isWithin(new Position(10.5, 0, 0), 10.0));
    }

    @Test
    void aNonPositiveRadiusMatchesOnlyTheSamePoint() {
        Position origin = new Position(5, 5, 5);
        assertTrue(origin.isWithin(new Position(5, 5, 5), 0));
        assertTrue(origin.isWithin(new Position(5, 5, 5), -1));
        assertFalse(origin.isWithin(new Position(5, 5, 5.1), 0));
    }

    @Test
    void withinANullPositionIsFalse() {
        assertFalse(new Position(0, 0, 0).isWithin(null, 10.0));
    }
}
