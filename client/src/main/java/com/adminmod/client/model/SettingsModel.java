package com.adminmod.client.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.adminmod.common.model.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The client's mirror of the server's setting catalogue.
 *
 * <p>It is rebuilt from the catalogue payload the server sends and holds both the metadata (how a
 * setting is presented, how it is protected) and the last known value. Pending edits are kept
 * separately from the known values so the screen can show what would change and offer an apply or
 * a cancel without anything being sent until the operator commits.
 *
 * <p>The mirror is the reason the GUI never needs to know about a specific setting: it renders a
 * control from the {@link Entry}'s type and metadata, and the server validates whatever comes back.
 */
public final class SettingsModel {

    /** One setting, its metadata and its last known value. */
    public static final class Entry {
        public final String path;
        public final String label;
        public final String description;
        public final String category;
        public final String module;
        public final String type;
        public final String defaultValue;
        public final double min;
        public final double max;
        public final double step;
        public final List<String> options;
        public final String unit;
        public final boolean advanced;
        public final boolean dangerous;
        public final boolean restartRequired;
        public final boolean secret;
        public final boolean deprecated;
        public final String dependsOn;
        public final Map<String, String> recommends;
        public String value;
        public boolean configured;

        Entry(JsonObject object) {
            this.path = Json.str(object, "path", "");
            this.label = Json.str(object, "label", path);
            this.description = Json.str(object, "description", "");
            this.category = Json.str(object, "category", "GENERAL");
            this.module = Json.str(object, "module", "core");
            this.type = Json.str(object, "type", "STRING");
            this.defaultValue = Json.str(object, "default", "");
            this.min = Json.dbl(object, "min", 0);
            this.max = Json.dbl(object, "max", 0);
            this.step = Json.dbl(object, "step", 0);
            this.unit = Json.str(object, "unit", "");
            this.advanced = Json.bool(object, "advanced", false);
            this.dangerous = Json.bool(object, "dangerous", false);
            this.restartRequired = Json.bool(object, "restartRequired", false);
            this.secret = Json.bool(object, "secret", false);
            this.deprecated = Json.bool(object, "deprecated", false);
            this.dependsOn = Json.str(object, "dependsOn", "");
            this.value = Json.str(object, "value", "");
            this.configured = Json.bool(object, "configured", false);
            this.options = new ArrayList<>();
            if (object.has("options")) {
                for (JsonElement option : object.getAsJsonArray("options")) {
                    options.add(option.getAsString());
                }
            }
            this.recommends = new LinkedHashMap<>();
            if (object.has("recommends")) {
                for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("recommends").entrySet()) {
                    recommends.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }

        public boolean hasRange() {
            return max > min;
        }

        public boolean isBoolean() {
            return type.equals("BOOLEAN");
        }

        public boolean isNumber() {
            return type.equals("INTEGER") || type.equals("LONG") || type.equals("DECIMAL");
        }

        public boolean isEnum() {
            return type.equals("ENUM");
        }

        public boolean isList() {
            return type.equals("STRING_LIST");
        }
    }

    /** A category as the server describes it. */
    public record Category(String id, String label, String description) {
    }

    private final Map<String, Entry> byPath = new LinkedHashMap<>();
    private final List<Category> categories = new ArrayList<>();
    private final List<String> problems = new ArrayList<>();
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /** Rebuilds the model from a catalogue payload. */
    public void load(String json) {
        JsonObject root = Json.parseObject(json);
        byPath.clear();
        categories.clear();
        problems.clear();
        if (root.has("categories")) {
            for (JsonElement element : root.getAsJsonArray("categories")) {
                JsonObject object = element.getAsJsonObject();
                categories.add(new Category(Json.str(object, "id", ""), Json.str(object, "label", ""),
                        Json.str(object, "description", "")));
            }
        }
        if (root.has("settings")) {
            for (JsonElement element : root.getAsJsonArray("settings")) {
                Entry entry = new Entry(element.getAsJsonObject());
                byPath.put(entry.path, entry);
            }
        }
        if (root.has("problems")) {
            for (JsonElement element : root.getAsJsonArray("problems")) {
                problems.add(element.getAsString());
            }
        }
        // A pending edit for a setting that no longer exists is dropped.
        pending.keySet().removeIf(path -> !byPath.containsKey(path));
        // Any pending value now matching the server's is no longer a change.
        pending.entrySet().removeIf(entry -> {
            Entry known = byPath.get(entry.getKey());
            return known != null && known.value.equals(entry.getValue());
        });
    }

    public void setWarnings(List<String> newWarnings) {
        warnings.clear();
        if (newWarnings != null) {
            warnings.addAll(newWarnings);
        }
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public List<Category> categories() {
        return List.copyOf(categories);
    }

    public List<String> problems() {
        return List.copyOf(problems);
    }

    public Entry get(String path) {
        return byPath.get(path);
    }

    public List<Entry> all() {
        return new ArrayList<>(byPath.values());
    }

    public List<Entry> inCategory(String categoryId) {
        List<Entry> result = new ArrayList<>();
        for (Entry entry : byPath.values()) {
            if (entry.category.equals(categoryId)) {
                result.add(entry);
            }
        }
        result.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return result;
    }

    public boolean isEmpty() {
        return byPath.isEmpty();
    }

    /** Applies a value the server reported as stored, clearing any pending edit for it. */
    public void applyServerValue(String path, String value) {
        Entry entry = byPath.get(path);
        if (entry != null) {
            entry.value = value;
        }
        pending.remove(path);
    }

    /** Records a pending edit; a value equal to the known one clears the edit instead. */
    public void setPending(String path, String value) {
        Entry entry = byPath.get(path);
        if (entry == null) {
            return;
        }
        if (entry.value.equals(value)) {
            pending.remove(path);
        } else {
            pending.put(path, value);
        }
    }

    public String pendingValue(String path) {
        Entry entry = byPath.get(path);
        return pending.getOrDefault(path, entry == null ? "" : entry.value);
    }

    public boolean isPending(String path) {
        return pending.containsKey(path);
    }

    public Map<String, String> pending() {
        return new LinkedHashMap<>(pending);
    }

    public int pendingCount() {
        return pending.size();
    }

    public void discardPending() {
        pending.clear();
    }

    /** Reverts one setting's pending edit. */
    public void discardPending(String path) {
        pending.remove(path);
    }

    /** Free-text search over label, path, description and category. */
    public List<Entry> search(String query) {
        if (query == null || query.isBlank()) {
            return all();
        }
        String[] terms = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : byPath.values()) {
            String haystack = (entry.path + " " + entry.label + " " + entry.description + " "
                    + entry.category + " " + entry.module).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String term : terms) {
                if (!haystack.contains(term)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matches.add(entry);
            }
        }
        matches.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return matches;
    }

    /** The count of settings per category, for the sidebar badge. */
    public int countIn(String categoryId) {
        int count = 0;
        for (Entry entry : byPath.values()) {
            if (entry.category.equals(categoryId)) {
                count++;
            }
        }
        return count;
    }

    /** The number of settings in a category that differ from their default. */
    public int changedIn(String categoryId) {
        int count = 0;
        for (Entry entry : byPath.values()) {
            if (entry.category.equals(categoryId) && differsFromDefault(entry)) {
                count++;
            }
        }
        return count;
    }

    /** Whether a setting's current value differs from its documented default. */
    public boolean differsFromDefault(Entry entry) {
        if (entry.secret) {
            return entry.configured;
        }
        if (entry.isBoolean()) {
            return !normaliseBoolean(entry.value).equals(normaliseBoolean(entry.defaultValue));
        }
        if (entry.isList()) {
            return !entry.value.replace("\r", "").trim().equals(entry.defaultValue.replace("\r", "").trim());
        }
        return !entry.value.trim().equalsIgnoreCase(entry.defaultValue.trim());
    }

    private static String normaliseBoolean(String value) {
        String lowered = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return (lowered.equals("true") || lowered.equals("on") || lowered.equals("1") || lowered.equals("yes"))
                ? "true" : "false";
    }

    /** Builds the JSON object the batch-update order expects. */
    public String pendingAsJson() {
        JsonObject root = new JsonObject();
        pending.forEach(root::addProperty);
        return root.toString();
    }
}
