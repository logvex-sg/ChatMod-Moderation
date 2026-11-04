package com.adminmod.integrations.inbound;

import com.adminmod.integrations.text.TextSanitizer;

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
        String safeUser = TextSanitizer.cleanCollapsed(user, 64);
        String safeBody = TextSanitizer.cleanCollapsed(message, Math.max(16, maxLength));
        String safePlatform = TextSanitizer.cleanCollapsed(platformLabel, 32);
        String pattern = template == null || template.isBlank() ? "[%platform%] %user%: %message%" : template;

        String rendered = pattern
                .replace("%platform%", safePlatform)
                .replace("%user%", safeUser)
                .replace("%message%", safeBody);
        return TextSanitizer.truncate(rendered, maxLength);
    }

    /** Removes characters that would corrupt a chat line. */
    public static String sanitise(String value, int maxLength) {
        return TextSanitizer.cleanCollapsed(value, maxLength);
    }
}
