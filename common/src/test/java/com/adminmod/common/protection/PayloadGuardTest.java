package com.adminmod.common.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Size and shape validation for the payloads a malicious client can inflate. */
class PayloadGuardTest {

    @Test
    void chatWithinAndOverTheLimit() {
        assertEquals(PayloadGuard.Verdict.OK, PayloadGuard.checkChat(10, 256));
        assertEquals(PayloadGuard.Verdict.OK, PayloadGuard.checkChat(256, 256));
        assertEquals(PayloadGuard.Verdict.TOO_LARGE, PayloadGuard.checkChat(257, 256));
        assertEquals(PayloadGuard.Verdict.MALFORMED, PayloadGuard.checkChat(-1, 256));
    }

    @Test
    void bookPageAndTitleLimits() {
        assertEquals(PayloadGuard.Verdict.OK,
                PayloadGuard.checkBook(List.of("a", "b"), "title", 100, 1024, 64));
        assertEquals(PayloadGuard.Verdict.TOO_LARGE,
                PayloadGuard.checkBook(List.of("a".repeat(2000)), null, 100, 1024, 64));
        assertEquals(PayloadGuard.Verdict.TOO_LARGE,
                PayloadGuard.checkBook(List.of("a"), "t".repeat(65), 100, 1024, 64));
        assertEquals(PayloadGuard.Verdict.MALFORMED,
                PayloadGuard.checkBook(null, "t", 100, 1024, 64));
    }

    @Test
    void manySmallPagesAreAcceptedWhenWithinEveryLimit() {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pages.add("x".repeat(1024));
        }
        assertEquals(PayloadGuard.Verdict.OK, PayloadGuard.checkBook(pages, null, 100, 1024, 64));
        // One page over the count cap trips before any size arithmetic matters.
        pages.add("x");
        assertEquals(PayloadGuard.Verdict.TOO_LARGE, PayloadGuard.checkBook(pages, null, 100, 1024, 64));
    }

    @Test
    void signLines() {
        assertEquals(PayloadGuard.Verdict.OK, PayloadGuard.checkSign(new String[] { "a", "b" }, 384));
        assertEquals(PayloadGuard.Verdict.TOO_LARGE,
                PayloadGuard.checkSign(new String[] { "a".repeat(400) }, 384));
        assertEquals(PayloadGuard.Verdict.MALFORMED, PayloadGuard.checkSign(null, 384));
    }

    @Test
    void customPayloadLength() {
        assertEquals(PayloadGuard.Verdict.OK, PayloadGuard.checkPayload(1024, 32768));
        assertEquals(PayloadGuard.Verdict.TOO_LARGE, PayloadGuard.checkPayload(40000, 32768));
        assertEquals(PayloadGuard.Verdict.MALFORMED, PayloadGuard.checkPayload(-5, 32768));
    }

    @Test
    void controlCharactersAreRejectedButWhitespaceIsAllowed() {
        assertEquals(PayloadGuard.Verdict.OK, PayloadGuard.checkTextContent("hello\tworld\nnext\r"));
        assertEquals(PayloadGuard.Verdict.MALFORMED, PayloadGuard.checkTextContent("bad\u0000nul"));
        assertEquals(PayloadGuard.Verdict.MALFORMED, PayloadGuard.checkTextContent("bell\u0007"));
        assertEquals(PayloadGuard.Verdict.MALFORMED, PayloadGuard.checkTextContent(null));
    }

    @Test
    void slotAndModifiedStackShapes() {
        assertTrue(PayloadGuard.validSlot(0, 46));
        assertTrue(PayloadGuard.validSlot(45, 46));
        assertTrue(PayloadGuard.validSlot(-1, 46), "the outside slot is valid");
        assertFalse(PayloadGuard.validSlot(46, 46));
        assertFalse(PayloadGuard.validSlot(-2, 46));

        assertTrue(PayloadGuard.validModifiedStacks(0, 128));
        assertTrue(PayloadGuard.validModifiedStacks(128, 128));
        assertFalse(PayloadGuard.validModifiedStacks(129, 128));
        assertFalse(PayloadGuard.validModifiedStacks(-1, 128));
    }
}
