package com.adminmod.common.localchat;

import java.util.Map;

/**
 * Renders a chat format template and translates colour codes.
 *
 * <p>Formats are written with {@code &} colour codes, which is what an operator expects from a
 * Minecraft config. In game the codes must become {@code §} for the client to colour them; on the
 * bridge they must be removed entirely, because a webhook has no idea what {@code §a} means.
 * Keeping both conversions here means a format is written once and rendered correctly in both
 * directions.
 *
 * <p>This class is deliberately free of any Minecraft type so it can be unit-tested directly.
 */
public final class ChatFormat {

    private ChatFormat() {
    }

    /**
     * Substitutes {@code %name%} placeholders in {@code template}.
     *
     * <p>Substitution is single-pass: a value that itself contains a placeholder is not re-expanded,
     * so a player cannot smuggle a placeholder into a rendered line through their name.
     */
    public static String render(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '%') {
                int end = template.indexOf('%', i + 1);
                if (end > i) {
                    String key = template.substring(i + 1, end);
                    String value = values.get(key);
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Converts {@code &}-style colour codes to the {@code §} form Minecraft renders.
     *
     * <p>Only a valid code character after {@code &} is converted, so a literal ampersand in a
     * player's name is left alone.
     */
    public static String toLegacy(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '&' && i + 1 < value.length() && isCode(value.charAt(i + 1))) {
                out.append('\u00a7').append(value.charAt(i + 1));
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Removes both {@code &}- and {@code §}-style colour codes, for a plain-text destination. */
    public static String stripColors(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < value.length() && isCode(value.charAt(i + 1))) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Whether a character is a Minecraft formatting code ({@code 0-9a-fk-or}). */
    public static boolean isCode(char c) {
        char lower = Character.toLowerCase(c);
        return (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')
                || lower == 'k' || lower == 'l' || lower == 'm' || lower == 'n' || lower == 'o'
                || lower == 'r';
    }

    /**
     * A piece of a format string: either literal text or a {@code %name%} placeholder.
     *
     * <p>Splitting a format this way is what lets the renderer treat the two halves differently.
     * The literal text has its {@code &} colour codes applied, while a substituted value is added
     * as plain text, so a player cannot colour their own name or inject a formatting code through
     * their message.
     */
    public record Part(String text, boolean placeholder) {
    }

    /** Splits a format into literal and placeholder parts, in order. */
    public static java.util.List<Part> parse(String template) {
        java.util.List<Part> parts = new java.util.ArrayList<>();
        if (template == null || template.isEmpty()) {
            return parts;
        }
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '%') {
                int end = template.indexOf('%', i + 1);
                if (end > i && isPlaceholderName(template, i + 1, end)) {
                    if (literal.length() > 0) {
                        parts.add(new Part(literal.toString(), false));
                        literal.setLength(0);
                    }
                    parts.add(new Part(template.substring(i + 1, end), true));
                    i = end + 1;
                    continue;
                }
            }
            literal.append(c);
            i++;
        }
        if (literal.length() > 0) {
            parts.add(new Part(literal.toString(), false));
        }
        return parts;
    }

    private static boolean isPlaceholderName(String template, int from, int to) {
        if (to - from <= 0 || to - from > 24) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char c = template.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }
}
