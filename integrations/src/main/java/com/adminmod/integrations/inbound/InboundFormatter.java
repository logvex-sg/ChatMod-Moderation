package com.adminmod.integrations.inbound;

/**
 * Renders an inbound platform message into the single line shown in Minecraft chat.
 *
 * <p>The configured template uses three placeholders: {@code %platform%}, {@code %user%} and
 * {@code %message%}. The default produces {@code [Discord] Username: Message}. Values are
 * sanitised first, because a platform display name or body is untrusted input that ends up in
 * a chat line: control characters are stripped, newlines are flattened and the whole line is
 * truncated to a configured ceiling.
 */
public final class InboundFormatter {

    private InboundFormatter() {
    }

    public static String format(String template, String platformLabel, String user, String message, int maxLength) {
        String safeUser = sanitise(user, 64);
        String safeBody = sanitise(message, Math.max(16, maxLength));
        String safePlatform = sanitise(platformLabel, 32);
        String pattern = template == null || template.isBlank() ? "[%platform%] %user%: %message%" : template;

        String rendered = pattern
                .replace("%platform%", safePlatform)
                .replace("%user%", safeUser)
                .replace("%message%", safeBody);
        rendered = flatten(rendered);
        return rendered.length() <= maxLength ? rendered : rendered.substring(0, maxLength);
    }

    /**
     * Removes characters that would corrupt a chat line and collapses runs of whitespace.
     *
     * <p>Section signs are dropped so a platform user cannot inject a Minecraft colour code and
     * impersonate a server prefix.
     */
    public static String sanitise(String value, int maxLength) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), maxLength));
        boolean lastWasSpace = false;
        for (int i = 0; i < value.length() && out.length() < maxLength; i++) {
            char c = value.charAt(i);
            if (c == '\u00a7') {
                // Skip the section sign and the formatting code that follows it.
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
            if (c < 0x20 || c == 0x7f) {
                continue;
            }
            out.append(c);
            lastWasSpace = false;
        }
        return out.toString().strip();
    }

    private static String flatten(String value) {
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
