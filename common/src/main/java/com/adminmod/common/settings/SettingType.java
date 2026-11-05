package com.adminmod.common.settings;

/**
 * The kind of value a setting holds, and therefore the control the dashboard renders for it.
 *
 * <p>The type is derived from the Java field's declared type when a spec is built, so a new setting
 * on {@code AdminConfig} gets a working control without any extra registration code. Only the
 * presentation differs between types; every value travels as a string on the wire and is parsed and
 * range-checked server-side.
 */
public enum SettingType {
    /** On/off switch. */
    BOOLEAN,
    /** Whole number, rendered as a slider when a range is known, otherwise a text field. */
    INTEGER,
    /** Decimal number, rendered as a slider when a range is known, otherwise a text field. */
    DECIMAL,
    /** Long integer (durations in milliseconds, sizes in bytes). */
    LONG,
    /** Free text. */
    STRING,
    /** A colour, edited with a picker and stored as {@code #RRGGBB}. */
    COLOR,
    /** One value from a fixed list. */
    ENUM,
    /** An ordered list of strings, edited as a multi-line text area. */
    STRING_LIST
}
