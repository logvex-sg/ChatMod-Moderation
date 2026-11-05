package com.adminmod.common.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Validation and normalisation: a value is either accepted exactly or explained. */
class SettingValidationTest {

    private static SettingSpec booleanSpec() {
        return SettingSpec.builder("x.flag", SettingCategory.GENERAL, SettingType.BOOLEAN).build();
    }

    @Test
    void booleansAcceptEverySpellingAndRejectAnythingElse() {
        SettingSpec spec = booleanSpec();
        for (String value : new String[] {"true", "on", "1", "yes", "FALSE", "off"}) {
            assertTrue(SettingValidation.validate(spec, value).valid(), value);
        }
        assertFalse(SettingValidation.validate(spec, "maybe").valid());
        assertEquals("false", SettingValidation.normalise(spec, "no"));
        assertEquals("true", SettingValidation.normalise(spec, "ON"));
    }

    @Test
    void integersAreRangeCheckedWithAReadableMessage() {
        SettingSpec spec = SettingSpec.builder("x.count", SettingCategory.GENERAL, SettingType.INTEGER)
                .range(1, 10).unit("items").build();
        assertTrue(SettingValidation.validate(spec, "5").valid());
        assertTrue(SettingValidation.validate(spec, "1").valid());
        assertTrue(SettingValidation.validate(spec, "10").valid());
        SettingValidation.Result low = SettingValidation.validate(spec, "0");
        assertFalse(low.valid());
        assertTrue(low.message().contains("at least 1 items"), low.message());
        SettingValidation.Result high = SettingValidation.validate(spec, "11");
        assertFalse(high.valid());
        assertTrue(high.message().contains("at most 10"), high.message());
        assertFalse(SettingValidation.validate(spec, "abc").valid());
    }

    @Test
    void decimalsRejectNaN() {
        SettingSpec spec = SettingSpec.builder("x.ratio", SettingCategory.GENERAL, SettingType.DECIMAL)
                .range(0, 1).build();
        assertTrue(SettingValidation.validate(spec, "0.5").valid());
        assertFalse(SettingValidation.validate(spec, "NaN").valid());
        assertFalse(SettingValidation.validate(spec, "Infinity").valid());
    }

    @Test
    void enumsOnlyAcceptOneOfTheListedOptions() {
        SettingSpec spec = SettingSpec.builder("x.mode", SettingCategory.GENERAL, SettingType.ENUM)
                .options("discord", "matrix", "webhook").build();
        assertTrue(SettingValidation.validate(spec, "MATRIX").valid());
        assertFalse(SettingValidation.validate(spec, "irc").valid());
        assertTrue(SettingValidation.validate(spec, "irc").message().contains("discord, matrix, webhook"));
        assertEquals("matrix", SettingValidation.normalise(spec, "MATRIX"));
    }

    @Test
    void coloursAcceptSixHexDigitsWithOrWithoutHash() {
        SettingSpec spec = SettingSpec.builder("x.colour", SettingCategory.GENERAL, SettingType.COLOR).build();
        assertTrue(SettingValidation.validate(spec, "#5865F2").valid());
        assertTrue(SettingValidation.validate(spec, "5865f2").valid());
        assertFalse(SettingValidation.validate(spec, "#FFF").valid());
        assertFalse(SettingValidation.validate(spec, "blue").valid());
        assertEquals("#5865F2", SettingValidation.normalise(spec, "5865f2"));
    }

    @Test
    void stringsAreLengthChecked() {
        SettingSpec spec = SettingSpec.builder("x.name", SettingCategory.GENERAL, SettingType.STRING)
                .maxLength(4).build();
        assertTrue(SettingValidation.validate(spec, "abcd").valid());
        assertFalse(SettingValidation.validate(spec, "abcde").valid());
    }

    @Test
    void stringListsAreBoundedInSize() {
        SettingSpec spec = SettingSpec.builder("x.list", SettingCategory.GENERAL, SettingType.STRING_LIST).build();
        assertTrue(SettingValidation.validate(spec, "a\nb\nc").valid());
        StringBuilder huge = new StringBuilder();
        huge.append("x".repeat(70_000));
        assertFalse(SettingValidation.validate(spec, huge.toString()).valid());
    }
}
