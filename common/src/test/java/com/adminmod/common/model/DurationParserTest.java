package com.adminmod.common.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DurationParserTest {

    @Test
    void parsesBareNumbersAsSeconds() {
        assertEquals(45L, DurationParser.parseSeconds("45"));
    }

    @Test
    void parsesUnits() {
        assertEquals(30L, DurationParser.parseSeconds("30s"));
        assertEquals(600L, DurationParser.parseSeconds("10m"));
        assertEquals(7_200L, DurationParser.parseSeconds("2h"));
        assertEquals(172_800L, DurationParser.parseSeconds("2d"));
        assertEquals(604_800L, DurationParser.parseSeconds("1w"));
    }

    @Test
    void parsesPermanentForms() {
        assertEquals(0L, DurationParser.parseSeconds("perm"));
        assertEquals(0L, DurationParser.parseSeconds("PERMANENT"));
        assertEquals(0L, DurationParser.parseSeconds("0"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("soon"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("10y"));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds(""));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds(null));
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("-5m"));
    }

    @Test
    void rejectsUnreasonablyLongDurations() {
        assertThrows(IllegalArgumentException.class, () -> DurationParser.parseSeconds("9999w"));
    }

    @Test
    void describesDurations() {
        assertEquals("permanent", DurationParser.describe(0L));
        assertEquals("1w", DurationParser.describe(604_800L));
        assertEquals("1d", DurationParser.describe(86_400L));
        assertEquals("1h", DurationParser.describe(3_600L));
        assertEquals("5m", DurationParser.describe(300L));
        assertEquals("90s", DurationParser.describe(90L));
    }

    @Test
    void punishmentExpiryLogic() {
        Punishment permanent = new Punishment(1, PunishmentType.BAN, "u", "n", "s", "s", "r", 0L, 0L, true, "perm");
        assertTrue(permanent.isPermanent());
        assertFalse(permanent.isExpired(Long.MAX_VALUE));

        Punishment temporary = new Punishment(2, PunishmentType.MUTE, "u", "n", "s", "s", "r", 0L, 1_000L, true, "1s");
        assertFalse(temporary.isPermanent());
        assertFalse(temporary.isExpired(999L));
        assertTrue(temporary.isExpired(1_000L));
    }
}
