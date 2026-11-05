package com.adminmod.common.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Stores the operator's own profiles in {@code config/admin/profiles.json}.
 *
 * <p>The built-in profiles and presets are compiled in and are never written to disk, so a new
 * build can improve them without a stale copy overriding the improvement. This store only holds the
 * profiles an operator created, renamed or duplicated.
 *
 * <p>The file is written owner-only, matching the other ADMIN configuration, because a custom
 * profile can name a sensitive (though never a secret) setting.
 */
public final class ProfileStore {

    /** Schema version of the store file. */
    public static final int STORE_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Map<String, ConfigProfile> custom = new LinkedHashMap<>();
    private String selected = ConfigPresets.CUSTOM;

    public ProfileStore(Path file) {
        this.file = file;
    }

    /** The profile the operator last selected, used to preselect the GUI. */
    public String selected() {
        return selected;
    }

    public void select(String name) {
        this.selected = name == null ? ConfigPresets.CUSTOM : name;
    }

    public List<ConfigProfile> custom() {
        return List.copyOf(custom.values());
    }

    public ConfigProfile find(String name) {
        if (name == null) {
            return null;
        }
        ConfigProfile local = custom.get(name.toLowerCase(Locale.ROOT));
        return local != null ? local : ConfigPresets.byName(name);
    }

    /** Loads the store, tolerating a missing or malformed file by starting empty. */
    public void load() throws IOException {
        if (Files.notExists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("selected")) {
                selected = root.get("selected").getAsString();
            }
            JsonArray profiles = root.has("profiles") ? root.getAsJsonArray("profiles") : new JsonArray();
            for (JsonElement element : profiles) {
                JsonObject object = element.getAsJsonObject();
                String name = object.has("name") ? object.get("name").getAsString() : "";
                if (name.isBlank()) {
                    continue;
                }
                String description = object.has("description") ? object.get("description").getAsString() : "";
                Map<String, String> values = new LinkedHashMap<>();
                if (object.has("values")) {
                    for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("values").entrySet()) {
                        values.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                custom.put(name.toLowerCase(Locale.ROOT), new ConfigProfile(name, description, false, values));
            }
        } catch (IOException | RuntimeException e) {
            throw new IOException("could not read " + file.getFileName(), e);
        }
    }

    /** Writes the store; the parent directory is created if needed. */
    public void save() throws IOException {
        Files.createDirectories(file.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("version", STORE_VERSION);
        root.addProperty("selected", selected);
        JsonArray profiles = new JsonArray();
        for (ConfigProfile profile : custom.values()) {
            JsonObject object = new JsonObject();
            object.addProperty("name", profile.name());
            object.addProperty("description", profile.description());
            JsonObject values = new JsonObject();
            for (Map.Entry<String, String> entry : profile.values().entrySet()) {
                values.addProperty(entry.getKey(), entry.getValue());
            }
            object.add("values", values);
            profiles.add(object);
        }
        root.add("profiles", profiles);
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        restrict(file);
    }

    /**
     * Adds or replaces a custom profile.
     *
     * @return {@code false} when the name collides with a built-in profile
     */
    public boolean put(ConfigProfile profile) {
        if (profile == null || profile.name().isBlank()) {
            return false;
        }
        if (ConfigPresets.byName(profile.name()) != null) {
            return false;
        }
        custom.put(profile.name().toLowerCase(Locale.ROOT), profile);
        return true;
    }

    /** Creates a custom profile from the current configuration. */
    public ConfigProfile create(String name, String description, AdminConfigSource source) {
        ConfigProfile profile = ConfigProfile.fromConfig(name, description, source.config(), source.registry());
        custom.put(name.toLowerCase(Locale.ROOT), profile);
        return profile;
    }

    public boolean delete(String name) {
        return custom.remove(name.toLowerCase(Locale.ROOT)) != null;
    }

    /** Copies a profile under a new name; the copy is always custom. */
    public ConfigProfile duplicate(String source, String newName) {
        ConfigProfile existing = find(source);
        if (existing == null) {
            return null;
        }
        ConfigProfile copy = new ConfigProfile(newName, existing.description() + " (copy)", false,
                existing.values());
        custom.put(newName.toLowerCase(Locale.ROOT), copy);
        return copy;
    }

    public boolean rename(String oldName, String newName) {
        ConfigProfile existing = custom.remove(oldName.toLowerCase(Locale.ROOT));
        if (existing == null || newName.isBlank() || ConfigPresets.byName(newName) != null) {
            if (existing != null) {
                custom.put(oldName.toLowerCase(Locale.ROOT), existing);
            }
            return false;
        }
        custom.put(newName.toLowerCase(Locale.ROOT), existing.withName(newName));
        return true;
    }

    /** The directory and file this store reads and writes, for the diagnostics page. */
    public Path file() {
        return file;
    }

    private static void restrict(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system; the directory permission is the remaining guard.
        }
    }

    /** Supplies the live configuration and registry a profile is captured from. */
    public interface AdminConfigSource {
        com.adminmod.common.config.AdminConfig config();

        SettingRegistry registry();
    }

    /** Names that cannot be used for a custom profile because a built-in already owns them. */
    public static List<String> reservedNames() {
        List<String> names = new ArrayList<>();
        for (ConfigProfile profile : ConfigPresets.all()) {
            names.add(profile.name());
        }
        return names;
    }
}
