package com.adminmod.common.protection;

import java.util.List;

/**
 * Validates the shape and size of data that arrives from a client, before anything acts on it.
 *
 * <p>Minecraft already caps most of these at the codec, but not all, and not with a limit an
 * operator can tune: book pages, sign lines and custom payloads are the classic vehicles for a
 * crash or a memory blow-up, and a malicious client is free to send the maximum. Each method here
 * returns a plain verdict and records nothing; the caller decides whether to log, kick or ignore.
 *
 * <p>Every check is a pure function of its arguments, which is what makes it testable without a
 * running server.
 */
public final class PayloadGuard {

    /** Outcome of a validation. */
    public enum Verdict {
        /** Within every limit. */
        OK,
        /** Over a size or count limit. */
        TOO_LARGE,
        /** Structurally invalid regardless of size. */
        MALFORMED
    }

    private PayloadGuard() {
    }

    /**
     * Validates a chat message.
     *
     * @param length   number of characters
     * @param maxLength configured cap
     */
    public static Verdict checkChat(int length, int maxLength) {
        if (length < 0) {
            return Verdict.MALFORMED;
        }
        return length > maxLength ? Verdict.TOO_LARGE : Verdict.OK;
    }

    /**
     * Validates book-and-quill content.
     *
     * @param pages     page bodies
     * @param title     optional title
     * @param maxPages  configured page cap
     * @param maxPageLength configured characters per page
     * @param maxTitleLength configured characters in the title
     */
    public static Verdict checkBook(List<String> pages, String title, int maxPages, int maxPageLength,
            int maxTitleLength) {
        if (pages == null) {
            return Verdict.MALFORMED;
        }
        if (pages.size() > maxPages) {
            return Verdict.TOO_LARGE;
        }
        long total = 0L;
        for (String page : pages) {
            if (page == null) {
                return Verdict.MALFORMED;
            }
            if (page.length() > maxPageLength) {
                return Verdict.TOO_LARGE;
            }
            total += page.length();
        }
        if (title != null && title.length() > maxTitleLength) {
            return Verdict.TOO_LARGE;
        }
        // A combined cap catches many small pages that individually pass but together exhaust memory.
        long combined = (long) maxPages * maxPageLength;
        return total > combined ? Verdict.TOO_LARGE : Verdict.OK;
    }

    /**
     * Validates a sign update.
     *
     * @param lines         the sign lines
     * @param maxLineLength configured characters per line
     */
    public static Verdict checkSign(String[] lines, int maxLineLength) {
        if (lines == null) {
            return Verdict.MALFORMED;
        }
        if (lines.length > 8) {
            return Verdict.TOO_LARGE;
        }
        for (String line : lines) {
            if (line != null && line.length() > maxLineLength) {
                return Verdict.TOO_LARGE;
            }
        }
        return Verdict.OK;
    }

    /**
     * Validates an inbound custom payload.
     *
     * @param declaredLength the length the packet claims
     * @param maxBytes       configured cap
     */
    public static Verdict checkPayload(int declaredLength, int maxBytes) {
        if (declaredLength < 0) {
            return Verdict.MALFORMED;
        }
        return declaredLength > maxBytes ? Verdict.TOO_LARGE : Verdict.OK;
    }

    /**
     * Rejects text that carries characters no legitimate client sends: NUL and the other C0
     * control characters other than tab, newline and carriage return. These are a common vehicle
     * for breaking a downstream parser (a webhook JSON body, a log line, a command).
     */
    public static Verdict checkTextContent(String text) {
        if (text == null) {
            return Verdict.MALFORMED;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\u0000') {
                return Verdict.MALFORMED;
            }
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                return Verdict.MALFORMED;
            }
        }
        return Verdict.OK;
    }

    /**
     * Validates a count against a configured cap, e.g. entities in one interaction burst.
     *
     * @return {@code true} when {@code count} is within {@code max}
     */
    public static boolean withinCount(int count, int max) {
        return count >= 0 && count <= max;
    }

    /**
     * Validates the shape of a slot index against the size of the container it claims to address.
     *
     * @return {@code true} when the slot is addressable, i.e. a normal slot or the outside
     *         interaction slot (-1) that {@code ClickSlot} uses
     */
    public static boolean validSlot(int slot, int containerSize) {
        return slot == -1 || (slot >= 0 && slot < containerSize);
    }

    /**
     * Validates the number of stacks a single click may claim to modify. A vanilla client sends at
     * most a handful; a large map is a memory-amplification attempt.
     */
    public static boolean validModifiedStacks(int modifiedCount, int max) {
        return modifiedCount >= 0 && modifiedCount <= max;
    }
}
