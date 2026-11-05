package com.adminmod.common.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads and writes a {@link ConfigProfile} as a versioned JSON document.
 *
 * <p>The same codec serves export to a file, import from a file and the clipboard, so a profile
 * always round-trips through one format. On import every value is validated against the registry
 * and every secret setting is rejected, so an imported file cannot smuggle a credential into the
 * server or introduce a value the server would not have accepted from the GUI.
 *
 * <p>A file whose format version is newer than this build understands is refused rather than
 * partially read, and a file from an older version is migrated where possible and otherwise
 * reported with the settings that could not be carried over.
 */
public final class ProfileCodec {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ProfileCodec() {
    }

    /** The outcome of parsing a profile document. */
    public record ImportResult(ConfigProfile profile, List<String> warnings, String error) {

        public boolean ok() {
            return error == null;
        }

        static ImportResult failed(String message) {
            return new ImportResult(null, List.of(), message);
        }
    }

    /** Serialises a profile to its JSON form. */
    public static String toJson(ConfigProfile profile) {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", ConfigProfile.FORMAT_VERSION);
        root.addProperty("kind", "adminmod-profile");
        root.addProperty("name", profile.name());
        root.addProperty("description", profile.description());
        JsonObject values = new JsonObject();
        for (Map.Entry<String, String> entry : profile.values().entrySet()) {
            values.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("values", values);
        return GSON.toJson(root);
    }

    public static void write(Path file, ConfigProfile profile) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(toJson(profile));
        }
    }

    /**
     * Parses a profile document, validating it against {@code registry}.
     *
     * <p>Unknown settings, secret settings and out-of-range values are dropped with a warning rather
     * than failing the whole import, because a profile exported from a different build may
     * legitimately mention a setting this one no longer has. Only a structurally broken document —
     * wrong kind, unreadable JSON, a format version from the future — is refused outright.
     */
    public static ImportResult fromJson(String json, SettingRegistry registry) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return ImportResult.failed("The file is not valid JSON");
        }
        if (root.has("kind") && !root.get("kind").getAsString().equals("adminmod-profile")) {
            return ImportResult.failed("This is not an ADMIN profile file");
        }
        int format = root.has("formatVersion") ? root.get("formatVersion").getAsInt() : 0;
        if (format > ConfigProfile.FORMAT_VERSION) {
            return ImportResult.failed("This profile was written by a newer ADMIN (format " + format
                    + "); update the server first");
        }
        String name = root.has("name") ? root.get("name").getAsString() : "imported";
        if (name.isBlank()) {
            name = "imported";
        }
        String description = root.has("description") ? root.get("description").getAsString() : "";

        List<String> warnings = new java.util.ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        JsonObject raw = root.has("values") ? root.getAsJsonObject("values") : new JsonObject();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            String path = entry.getKey();
            SettingSpec spec = registry.byPath(path);
            if (spec == null) {
                warnings.add("ignored unknown setting: " + path);
                continue;
            }
            if (spec.secret()) {
                warnings.add("refused to import secret setting: " + path);
                continue;
            }
            String value = entry.getValue().isJsonArray()
                    ? String.join("\n", toStrings(entry.getValue().getAsJsonArray()))
                    : entry.getValue().getAsString();
            String normalised = SettingValidation.normalise(spec, value);
            SettingValidation.Result result = SettingValidation.validate(spec, normalised);
            if (!result.valid()) {
                warnings.add("ignored " + path + ": " + result.message());
                continue;
            }
            values.put(path, normalised);
        }
        if (format < ConfigProfile.FORMAT_VERSION) {
            warnings.add("migrated a profile from format " + format + " to " + ConfigProfile.FORMAT_VERSION);
        }
        return new ImportResult(new ConfigProfile(name, description, false, values), warnings, null);
    }

    private static List<String> toStrings(JsonArray array) {
        List<String> result = new java.util.ArrayList<>();
        for (JsonElement element : array) {
            result.add(element.getAsString());
        }
        return result;
    }

    /** Reads and parses a profile file. */
    public static ImportResult read(Path file, SettingRegistry registry) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            StringBuilder builder = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                builder.append(buffer, 0, read);
            }
            return fromJson(builder.toString(), registry);
        } catch (IOException e) {
            return ImportResult.failed("The file could not be read");
        }
    }
}
