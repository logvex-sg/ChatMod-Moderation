package com.adminmod.common.settings;

import java.util.Locale;

/**
 * Validates a single string value against a {@link SettingSpec}.
 *
 * <p>Validation is deliberately separate from writing: the dashboard can validate a pending change
 * and show the reason it is rejected before anything is sent, and the server runs the same check
 * again before it writes, so a hand-crafted request cannot bypass a rule the GUI enforces.
 *
 * <p>An invalid value is always explained. The old behaviour of silently substituting a default is
 * gone for the interactive path; the loader still clamps a hand-edited file, but a change made
 * through the dashboard is either accepted exactly as typed or refused with a reason.
 */
public final class SettingValidation {

    /** The outcome of validating one value. */
    public record Result(boolean valid, String message) {

        public static Result ok() {
            return new Result(true, "");
        }

        public static Result fail(String message) {
            return new Result(false, message);
        }
    }

    private SettingValidation() {
    }

    public static Result validate(SettingSpec spec, String value) {
        if (value == null) {
            value = "";
        }
        return switch (spec.type()) {
            case BOOLEAN -> validateBoolean(value);
            case INTEGER -> validateInteger(spec, value);
            case LONG -> validateLong(spec, value);
            case DECIMAL -> validateDecimal(spec, value);
            case COLOR -> validateColor(value);
            case ENUM -> validateEnum(spec, value);
            case STRING_LIST -> validateStringList(value);
            case STRING -> validateString(spec, value);
        };
    }

    private static Result validateBoolean(String value) {
        String lowered = value.trim().toLowerCase(Locale.ROOT);
        if (lowered.equals("true") || lowered.equals("false")
                || lowered.equals("on") || lowered.equals("off")
                || lowered.equals("1") || lowered.equals("0")
                || lowered.equals("yes") || lowered.equals("no")) {
            return Result.ok();
        }
        return Result.fail("Expected true or false, got \"" + value + "\"");
    }

    private static Result validateInteger(SettingSpec spec, String value) {
        int parsed;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return Result.fail("Expected a whole number, got \"" + value + "\"");
        }
        return range(spec, parsed);
    }

    private static Result validateLong(SettingSpec spec, String value) {
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return Result.fail("Expected a whole number, got \"" + value + "\"");
        }
        return range(spec, parsed);
    }

    private static Result validateDecimal(SettingSpec spec, String value) {
        double parsed;
        try {
            parsed = Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return Result.fail("Expected a number, got \"" + value + "\"");
        }
        if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
            return Result.fail("The number must be finite");
        }
        return range(spec, parsed);
    }

    private static Result range(SettingSpec spec, double value) {
        if (spec.min() != null && value < spec.min()) {
            return Result.fail("Must be at least " + trim(spec.min()) + unitSuffix(spec));
        }
        if (spec.max() != null && value > spec.max()) {
            return Result.fail("Must be at most " + trim(spec.max()) + unitSuffix(spec));
        }
        return Result.ok();
    }

    private static String unitSuffix(SettingSpec spec) {
        return spec.unit().isEmpty() ? "" : " " + spec.unit();
    }

    private static String trim(Double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf(value.longValue());
        }
        return String.valueOf(value);
    }

    private static Result validateColor(String value) {
        String trimmed = value.trim();
        if (trimmed.matches("#?[0-9a-fA-F]{6}")) {
            return Result.ok();
        }
        return Result.fail("Expected a colour like #5865F2, got \"" + value + "\"");
    }

    private static Result validateEnum(SettingSpec spec, String value) {
        String lowered = value.trim().toLowerCase(Locale.ROOT);
        for (String option : spec.options()) {
            if (option.toLowerCase(Locale.ROOT).equals(lowered)) {
                return Result.ok();
            }
        }
        return Result.fail("Must be one of " + String.join(", ", spec.options()) + ", got \"" + value + "\"");
    }

    private static Result validateStringList(String value) {
        // Every line is accepted; the only rule is that the total stays a sane size so a paste
        // cannot grow the config file without bound.
        if (value.length() > 64_000) {
            return Result.fail("The list is too long (" + value.length() + " characters)");
        }
        return Result.ok();
    }

    private static Result validateString(SettingSpec spec, String value) {
        if (value.length() > spec.maxLength()) {
            return Result.fail("Must be at most " + spec.maxLength() + " characters");
        }
        return Result.ok();
    }

    /**
     * Normalises a value into the canonical form stored on disk.
     *
     * <p>Booleans become {@code true}/{@code false}, enums are lower-cased, colours gain a leading
     * {@code #}. This keeps a value the GUI round-trips byte-identical to what the server stored.
     */
    public static String normalise(SettingSpec spec, String value) {
        if (value == null) {
            return "";
        }
        return switch (spec.type()) {
            case BOOLEAN -> {
                String lowered = value.trim().toLowerCase(Locale.ROOT);
                yield (lowered.equals("true") || lowered.equals("on") || lowered.equals("1") || lowered.equals("yes"))
                        ? "true" : "false";
            }
            case ENUM -> {
                String lowered = value.trim().toLowerCase(Locale.ROOT);
                for (String option : spec.options()) {
                    if (option.toLowerCase(Locale.ROOT).equals(lowered)) {
                        yield option;
                    }
                }
                yield value.trim();
            }
            case COLOR -> {
                String trimmed = value.trim();
                if (trimmed.matches("[0-9a-fA-F]{6}")) {
                    yield "#" + trimmed.toUpperCase(Locale.ROOT);
                }
                yield trimmed.toUpperCase(Locale.ROOT);
            }
            default -> value.trim();
        };
    }
}
