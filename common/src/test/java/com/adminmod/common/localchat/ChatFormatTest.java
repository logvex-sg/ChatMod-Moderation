package com.adminmod.common.localchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Format rendering, colour translation and placeholder parsing. */
class ChatFormatTest {

    @Test
    void renderSubstitutesKnownPlaceholders() {
        String rendered = ChatFormat.render("[Local] %player%: %message%",
                Map.of("player", "Alex", "message", "hi"));
        assertEquals("[Local] Alex: hi", rendered);
    }

    @Test
    void renderLeavesUnknownPlaceholdersAlone() {
        assertEquals("[%nope%] Alex", ChatFormat.render("[%nope%] %player%", Map.of("player", "Alex")));
    }

    @Test
    void toLegacyConvertsOnlyValidCodes() {
        assertEquals("\u00a7aGreen", ChatFormat.toLegacy("&aGreen"));
        // A literal ampersand followed by a non-code character is left as-is.
        assertEquals("Tom & Jerry", ChatFormat.toLegacy("Tom & Jerry"));
    }

    @Test
    void stripColorsRemovesBothCodeStyles() {
        assertEquals("Green", ChatFormat.stripColors("&aGreen"));
        assertEquals("Green", ChatFormat.stripColors("\u00a7aGreen"));
    }

    @Test
    void parseSeparatesLiteralsFromPlaceholders() {
        var parts = ChatFormat.parse("[Local] &7%player%&f: %message%");
        assertEquals("[Local] &7", parts.get(0).text());
        assertTrue(!parts.get(0).placeholder());
        assertEquals("player", parts.get(1).text());
        assertTrue(parts.get(1).placeholder());
        assertEquals("&f: ", parts.get(2).text());
        assertTrue(!parts.get(2).placeholder());
        assertEquals("message", parts.get(3).text());
        assertTrue(parts.get(3).placeholder());
    }

    @Test
    void parseTreatsAnUnterminatedPercentAsLiteral() {
        var parts = ChatFormat.parse("100% sure");
        assertEquals(1, parts.size());
        assertEquals("100% sure", parts.get(0).text());
        assertTrue(!parts.get(0).placeholder());
    }

    @Test
    void parseRejectsAPlaceholderNameWithSpaces() {
        // A space inside the percent signs is not a placeholder name, so it stays literal.
        var parts = ChatFormat.parse("a %not a name% b");
        assertEquals(1, parts.size());
        assertTrue(!parts.get(0).placeholder());
    }
}
