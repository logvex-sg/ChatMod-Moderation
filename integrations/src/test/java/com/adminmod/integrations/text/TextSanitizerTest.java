package com.adminmod.integrations.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextSanitizerTest {

    @Test
    void colourCodesAreRemoved() {
        assertEquals("free stuff", TextSanitizer.clean("\u00a7lfree stuff", 100));
        assertEquals("red", TextSanitizer.clean("\u00a7c\u00a7lred", 100));
    }

    @Test
    void controlCharactersAreRemoved() {
        assertEquals("ab", TextSanitizer.clean("a\u0000b", 100));
        assertEquals("ab", TextSanitizer.clean("a\u0007b", 100));
    }

    @Test
    void newlinesBecomeSpacesSoALineCannotBeForged() {
        assertEquals("line1 line2", TextSanitizer.clean("line1\nline2", 100));
        assertEquals("line1 line2", TextSanitizer.clean("line1\r\nline2", 100));
    }

    @Test
    void theResultIsLengthBounded() {
        assertEquals(10, TextSanitizer.clean("x".repeat(100), 10).length());
    }

    @Test
    void nullBecomesAnEmptyString() {
        assertEquals("", TextSanitizer.clean(null, 100));
    }

    @Test
    void collapsedVariantReducesRepeatedWhitespace() {
        assertEquals("a b", TextSanitizer.cleanCollapsed("a    b", 100));
        assertEquals("a b", TextSanitizer.cleanCollapsed("a \t\n b", 100));
    }

    @Test
    void truncationDoesNotSplitASurrogatePair() {
        String emoji = "\ud83d\ude00"; // U+1F600
        String value = "ab" + emoji;
        // The limit falls in the middle of the surrogate pair.
        String truncated = TextSanitizer.truncate(value, 3);
        assertEquals("ab", truncated);
        assertTrue(truncated.length() <= 3);
    }
}
