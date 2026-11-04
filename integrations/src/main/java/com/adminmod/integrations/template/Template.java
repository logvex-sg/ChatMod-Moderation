package com.adminmod.integrations.template;

import java.util.Map;

/**
 * Replaces {@code %name%} placeholders in a format string.
 *
 * <p>Substituted values are escaped for the target syntax so a player cannot break out of a
 * JSON string or inject a mention by choosing a particular username.
 */
public final class Template {

    private Template() {
    }

    public static String render(String template, Map<String, String> values) {
        if (template == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(template.length() + 64);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
                continue;
            }
            int end = template.indexOf('%', i + 1);
            if (end < 0) {
                out.append(c);
                i++;
                continue;
            }
            String key = template.substring(i + 1, end);
            String value = values.get(key);
            if (value == null) {
                out.append(template, i, end + 1);
            } else {
                out.append(value);
            }
            i = end + 1;
        }
        return out.toString();
    }

    /** Escapes a value for embedding inside a JSON string literal. */
    public static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Neutralises Discord's mention syntax by inserting a zero-width space after {@code @}
     * and around {@code @everyone}/{@code @here}, so forwarded chat cannot ping a channel.
     */
    public static String escapeDiscordMentions(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("@", "@\u200b");
    }
}
