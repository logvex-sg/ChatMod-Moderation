package com.adminmod.common.model;

import java.util.Locale;

/**
 * Parses the compact duration syntax used by moderation commands: a number followed by an
 * optional unit ({@code s}, {@code m}, {@code h}, {@code d}, {@code w}). A bare number is read
 * as seconds, and {@code perm}/{@code permanent}/{@code 0} mean "no expiry".
 */
public final class DurationParser {

    private static final long MAX_SECONDS = 10L * 365 * 24 * 3600;

    private DurationParser() {
    }

    /** @return the duration in seconds, or 0 for a permanent punishment. */
    public static long parseSeconds(String input) {
        if (input == null) {
            throw new IllegalArgumentException("duration is required");
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new IllegalArgumentException("duration is required");
        }
        if (value.equals("perm") || value.equals("permanent") || value.equals("forever")) {
            return 0L;
        }
        char last = value.charAt(value.length() - 1);
        long multiplier = 1L;
        String digits = value;
        if (!Character.isDigit(last)) {
            multiplier = switch (last) {
                case 's' -> 1L;
                case 'm' -> 60L;
                case 'h' -> 3600L;
                case 'd' -> 86_400L;
                case 'w' -> 604_800L;
                default -> throw new IllegalArgumentException("unknown duration unit '" + last + "'");
            };
            digits = value.substring(0, value.length() - 1).trim();
        }
        long amount;
        try {
            amount = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + input + "' is not a valid duration");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        if (amount == 0L) {
            return 0L;
        }
        long seconds = Math.multiplyExact(amount, multiplier);
        if (seconds > MAX_SECONDS) {
            throw new IllegalArgumentException("duration is unreasonably long");
        }
        return seconds;
    }

    /** Human readable rendering used in logs and dashboard rows. */
    public static String describe(long seconds) {
        if (seconds <= 0L) {
            return "permanent";
        }
        if (seconds % 604_800L == 0L) {
            return (seconds / 604_800L) + "w";
        }
        if (seconds % 86_400L == 0L) {
            return (seconds / 86_400L) + "d";
        }
        if (seconds % 3600L == 0L) {
            return (seconds / 3600L) + "h";
        }
        if (seconds % 60L == 0L) {
            return (seconds / 60L) + "m";
        }
        return seconds + "s";
    }
}
