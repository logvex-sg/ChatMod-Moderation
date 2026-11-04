package com.adminmod.integrations.text;

/**
 * Cleans untrusted text before it is rendered, forwarded or logged.
 *
 * <p>Two different problems are handled here. Minecraft colour codes ({@code §x}) are control
 * characters that either break a webhook payload or leak formatting into a platform that does
 * not understand them. Control characters in general (newlines, tabs, NUL) let a message
 * fabricate extra lines in a terminal or a Discord message, so they are flattened.
 *
 * <p>Everything is length-bounded as well: a message body is attacker-controlled up to the
 * server's own chat limit, and truncating here is cheaper and safer than trusting every caller.
 */
public final class TextSanitizer {

    private TextSanitizer() {
    }

    /**
     * Removes Minecraft formatting codes and flattens control characters.
     *
     * @param value  the text to clean; {@code null} becomes an empty string
     * @param maxLen maximum length of the result
     */
    public static String clean(String value, int maxLen) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), Math.max(0, maxLen)));
        int limit = maxLen <= 0 ? value.length() : maxLen;
        for (int i = 0; i < value.length() && out.length() < limit; i++) {
            char c = value.charAt(i);
            if (c == '\u00a7') {
                // Skip the section sign and the formatting code that follows it.
                i++;
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t') {
                // A CRLF pair is one line break, not two, so it becomes a single space.
                if (c == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                    i++;
                }
                out.append(' ');
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            out.append(c);
        }
        return out.toString().strip();
    }

    /** Convenience overload using the inbound default limit. */
    public static String clean(String value) {
        return clean(value, 512);
    }

    /**
     * Like {@link #clean(String, int)} but also collapses runs of spaces.
     *
     * <p>Used for inbound platform text, where a display name or body may contain repeated
     * whitespace that would look broken inside a single chat line.
     */
    public static String cleanCollapsed(String value, int maxLen) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), Math.max(0, maxLen)));
        int limit = maxLen <= 0 ? value.length() : maxLen;
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length() && out.length() < limit; i++) {
            char c = value.charAt(i);
            if (c == '\u00a7') {
                i++;
                continue;
            }
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                if (!lastWasSpace && out.length() > 0) {
                    out.append(' ');
                    lastWasSpace = true;
                }
                continue;
            }
            if (c < 0x20 || c == 0x7F) {
                continue;
            }
            out.append(c);
            lastWasSpace = false;
        }
        return out.toString().strip();
    }

    /** Truncates a value without splitting a surrogate pair. */
    public static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }
}
