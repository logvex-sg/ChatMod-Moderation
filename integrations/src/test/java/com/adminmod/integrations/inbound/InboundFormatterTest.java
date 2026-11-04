package com.adminmod.integrations.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InboundFormatterTest {

    @Test
    void defaultTemplateProducesTheDocumentedShape() {
        assertEquals("[Discord] Alex: hello",
                InboundFormatter.format("[%platform%] %user%: %message%", "Discord", "Alex", "hello", 512));
    }

    @Test
    void aBlankTemplateFallsBackToTheDefault() {
        assertEquals("[Matrix] alice: hi",
                InboundFormatter.format("  ", "Matrix", "alice", "hi", 512));
    }

    @Test
    void newlinesInTheBodyAreFlattened() {
        String rendered = InboundFormatter.format("%message%", "Discord", "Alex", "line1\nline2", 512);
        assertEquals("line1 line2", rendered);
    }

    @Test
    void colourCodesInTheNameAreStripped() {
        String rendered = InboundFormatter.format("%user%", "Discord", "\u00a7cAdmin", "", 512);
        assertEquals("Admin", rendered);
    }

    @Test
    void colourCodesInTheBodyAreStripped() {
        String rendered = InboundFormatter.format("%message%", "Discord", "Alex", "\u00a7lfree stuff", 512);
        assertEquals("free stuff", rendered);
    }

    @Test
    void anOverlongLineIsTruncated() {
        String rendered = InboundFormatter.format("%message%", "Discord", "Alex", "x".repeat(5000), 100);
        assertEquals(100, rendered.length());
    }

    @Test
    void unicodeAndEmojiSurvive() {
        String rendered = InboundFormatter.format("%user%: %message%", "Discord", "Ava \u2764", "caf\u00e9 \ud83d\ude00", 512);
        assertEquals("Ava \u2764: caf\u00e9 \ud83d\ude00", rendered);
    }

    @Test
    void controlCharactersAreRemoved() {
        assertEquals("ab", InboundFormatter.sanitise("a\u0000b", 512));
        assertEquals("ab", InboundFormatter.sanitise("a\u0007b", 512));
    }

    @Test
    void runsOfWhitespaceCollapse() {
        assertEquals("a b", InboundFormatter.sanitise("a    b", 512));
    }
}
