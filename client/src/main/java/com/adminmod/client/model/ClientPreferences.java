package com.adminmod.client.model;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Small, client-local preferences for the settings screen.
 *
 * <p>Favourites and recently viewed settings, plus the two view toggles, are the operator's own
 * convenience and have nothing to do with the server, so they live on the client. Keeping them out
 * of the server configuration means a shared server's config never grows a per-operator tail.
 *
 * <p>The file is best-effort: if it cannot be read or written, the screen simply starts with no
 * favourites and keeps working.
 */
public final class ClientPreferences {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path file;
    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<String> recents = new ArrayList<>();
    private boolean showAdvanced;
    private boolean changedOnly;

    public ClientPreferences(Path file) {
        this.file = file;
    }

    public void load() {
        if (Files.notExists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            showAdvanced = root.has("showAdvanced") && root.get("showAdvanced").getAsBoolean();
            changedOnly = root.has("changedOnly") && root.get("changedOnly").getAsBoolean();
            if (root.has("favorites")) {
                for (JsonElement element : root.getAsJsonArray("favorites")) {
                    favorites.add(element.getAsString());
                }
            }
            if (root.has("recents")) {
                for (JsonElement element : root.getAsJsonArray("recents")) {
                    recents.add(element.getAsString());
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // A malformed preferences file is not worth failing the screen over.
        }
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("showAdvanced", showAdvanced);
            root.addProperty("changedOnly", changedOnly);
            JsonArray favoriteArray = new JsonArray();
            favorites.forEach(favoriteArray::add);
            root.add("favorites", favoriteArray);
            JsonArray recentArray = new JsonArray();
            recents.forEach(recentArray::add);
            root.add("recents", recentArray);
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
        } catch (IOException ignored) {
            // As above: a preference that cannot be saved is not an error worth surfacing.
        }
    }

    public boolean showAdvanced() {
        return showAdvanced;
    }

    public void setShowAdvanced(boolean value) {
        this.showAdvanced = value;
        save();
    }

    public boolean changedOnly() {
        return changedOnly;
    }

    public void setChangedOnly(boolean value) {
        this.changedOnly = value;
        save();
    }

    public boolean isFavorite(String path) {
        return favorites.contains(path);
    }

    public void toggleFavorite(String path) {
        if (!favorites.remove(path)) {
            favorites.add(path);
        }
        save();
    }

    public List<String> favorites() {
        return new ArrayList<>(favorites);
    }

    /** Records that a setting was edited, most recent first, capped at a short list. */
    public void noteRecent(String path) {
        recents.remove(path);
        recents.add(0, path);
        while (recents.size() > 12) {
            recents.remove(recents.size() - 1);
        }
        save();
    }

    public List<String> recents() {
        return new ArrayList<>(recents);
    }
}
