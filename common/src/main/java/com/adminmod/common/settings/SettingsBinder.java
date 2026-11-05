package com.adminmod.common.settings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.adminmod.common.config.AdminConfig;

/**
 * Reads and writes {@code AdminConfig} values by their dotted path.
 *
 * <p>This is the bridge between the flat, string-based settings protocol and the nested object
 * graph the server actually uses. It resolves a path once into a {@link Accessor} (a field plus the
 * object that owns it) so a read or write is a plain field access rather than a repeated search.
 *
 * <p>The binder also enumerates every leaf path on a config, which is what lets the registry verify
 * that its metadata has not drifted from the model: a field added to {@code AdminConfig} without a
 * spec shows up as an unregistered setting, and a spec whose field no longer exists is rejected.
 */
public final class SettingsBinder {

    private SettingsBinder() {
    }

    /** A resolved field and the object instance that holds it. */
    public record Accessor(Object owner, Field field) {

        /** Reads the field, converting lists and colours into their string form. */
        public String read() {
            try {
                return render(field, field.get(owner));
            } catch (IllegalAccessException e) {
                return "";
            }
        }

        /** Writes the field from its string form; returns false when the value cannot be parsed. */
        public boolean write(String value) {
            try {
                field.set(owner, parse(field, value));
                return true;
            } catch (IllegalAccessException | IllegalArgumentException e) {
                return false;
            }
        }

        public Class<?> type() {
            return field.getType();
        }
    }

    /** Resolves {@code path} against {@code config}, or returns {@code null} when it does not exist. */
    public static Accessor resolve(AdminConfig config, String path) {
        String[] parts = path.split("\\.");
        Object current = config;
        for (int i = 0; i < parts.length; i++) {
            Field field = findField(current.getClass(), parts[i]);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            if (i == parts.length - 1) {
                return new Accessor(current, field);
            }
            try {
                Object next = field.get(current);
                if (next == null) {
                    return null;
                }
                current = next;
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    /** Reads a path as a string, or {@code null} when the path does not exist. */
    public static String read(AdminConfig config, String path) {
        Accessor accessor = resolve(config, path);
        return accessor == null ? null : accessor.read();
    }

    /** Writes a path from a string; returns false when the path is unknown or the value is invalid. */
    public static boolean write(AdminConfig config, String path, String value) {
        Accessor accessor = resolve(config, path);
        return accessor != null && accessor.write(value);
    }

    /**
     * Enumerates every leaf path under {@code config}, for example {@code chatBridge.discord.username}.
     *
     * <p>A leaf is a field whose type is primitive, a wrapper, {@code String} or a collection. Nested
     * objects are walked; a {@code List} is a leaf even though it holds objects, because the settings
     * protocol edits it as a whole.
     */
    public static List<String> leafPaths(AdminConfig config) {
        List<String> paths = new ArrayList<>();
        collect(config, "", paths);
        return paths;
    }

    private static void collect(Object node, String prefix, List<String> out) {
        if (node == null) {
            return;
        }
        for (Field field : node.getClass().getFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
            if (isEditableLeaf(field)) {
                out.add(path);
                continue;
            }
            if (isOpaque(field.getType())) {
                continue;
            }
            try {
                Object child = field.get(node);
                if (child != null) {
                    collect(child, path, out);
                }
            } catch (IllegalAccessException ignored) {
                // A field we cannot read is not enumerable; the registry tolerates its absence.
            }
        }
    }

    /**
     * Whether a field is safe to edit as a single string value.
     *
     * <p>Scalars qualify. A list qualifies only when it holds strings: a list of structured records
     * (dimension overrides, punishment templates, extra channels) is edited in its file, because
     * writing a list of strings into a list-typed field would only fail later with a class cast.
     */
    private static boolean isEditableLeaf(Field field) {
        Class<?> type = field.getType();
        if (type.isPrimitive() || type == String.class || type == Boolean.class || type == Character.class
                || Number.class.isAssignableFrom(type)) {
            return true;
        }
        if (java.util.Collection.class.isAssignableFrom(type)) {
            return elementTypeIsString(field);
        }
        return false;
    }

    private static boolean elementTypeIsString(Field field) {
        java.lang.reflect.Type generic = field.getGenericType();
        if (generic instanceof java.lang.reflect.ParameterizedType parameterized) {
            java.lang.reflect.Type[] arguments = parameterized.getActualTypeArguments();
            return arguments.length == 1 && arguments[0] == String.class;
        }
        return false;
    }

    /** Collections and maps are never descended into; they are edited as a whole or in the file. */
    private static boolean isOpaque(Class<?> type) {
        return java.util.Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /** Renders a field value in the string form the protocol uses. */
    public static String render(Field field, Object value) {
        if (value == null) {
            return "";
        }
        if (isColour(field) && value instanceof Number number) {
            return String.format("#%06X", number.intValue() & 0xFFFFFF);
        }
        if (value instanceof java.util.Collection<?> collection) {
            return String.join("\n", collection.stream().map(String::valueOf).toList());
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder builder = new StringBuilder();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(entry.getKey()).append('=').append(entry.getValue());
            }
            return builder.toString();
        }
        return String.valueOf(value);
    }

    /** Renders a value with no field context; used by tests and by the diagnostics page. */
    public static String render(Object value) {
        return render(null, value);
    }

    /** A colour is an int or long field whose name ends in {@code Color} or {@code Colour}. */
    private static boolean isColour(Field field) {
        if (field == null) {
            return false;
        }
        Class<?> type = field.getType();
        if (type != int.class && type != Integer.class && type != long.class && type != Long.class) {
            return false;
        }
        String name = field.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith("color") || name.endsWith("colour");
    }

    /** Parses a string into the target field type; throws when it cannot. */
    @SuppressWarnings("unchecked")
    public static Object parse(Field field, String value) {
        return parse(field.getType(), isColour(field), value);
    }

    /** Parses a string with no field context; used by tests. */
    public static Object parse(Class<?> type, String value) {
        return parse(type, false, value);
    }

    private static Object parse(Class<?> type, boolean colour, String value) {
        String trimmed = value == null ? "" : value;
        if (colour && (type == int.class || type == Integer.class || type == long.class || type == Long.class)) {
            long rgb = Long.parseLong(normaliseColour(trimmed), 16);
            return type == long.class || type == Long.class ? rgb : (int) rgb;
        }
        if (type == String.class) {
            return trimmed;
        }
        if (type == boolean.class || type == Boolean.class) {
            String lowered = trimmed.trim().toLowerCase(java.util.Locale.ROOT);
            if (lowered.equals("true") || lowered.equals("on") || lowered.equals("1") || lowered.equals("yes")) {
                return true;
            }
            if (lowered.equals("false") || lowered.equals("off") || lowered.equals("0") || lowered.equals("no")) {
                return false;
            }
            throw new IllegalArgumentException("not a boolean: " + value);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(trimmed.trim());
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(trimmed.trim());
        }
        if (type == double.class || type == Double.class) {
            return Double.parseDouble(trimmed.trim());
        }
        if (type == float.class || type == Float.class) {
            return Float.parseFloat(trimmed.trim());
        }
        if (type == short.class || type == Short.class) {
            return Short.parseShort(trimmed.trim());
        }
        if (type == byte.class || type == Byte.class) {
            return Byte.parseByte(trimmed.trim());
        }
        if (type == char.class || type == Character.class) {
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("empty character");
            }
            return trimmed.charAt(0);
        }
        if (java.util.List.class.isAssignableFrom(type)) {
            List<String> lines = new ArrayList<>();
            for (String line : trimmed.split("\n", -1)) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
            return lines;
        }
        throw new IllegalArgumentException("unsupported field type " + type.getName());
    }

    /**
     * Strips a leading {@code #} and validates a six-digit hex colour.
     *
     * @throws IllegalArgumentException when the value is not a colour
     */
    private static String normaliseColour(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (!trimmed.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("not a colour: " + value);
        }
        return trimmed;
    }
}
