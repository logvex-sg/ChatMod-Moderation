package com.adminmod.common.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.adminmod.common.config.AdminConfig;

/**
 * The single registry every ADMIN subsystem contributes its settings to.
 *
 * <p>This is what replaces the scattered, hand-written setting switches that used to live in the
 * remote request handler: instead of a {@code switch} over string keys, each subsystem registers a
 * {@link SettingSpec}, and the registry can then list, search, read, write, validate and reset any
 * setting without knowing which module owns it.
 *
 * <p>The registry is built once per server from a freshly constructed {@code AdminConfig}, so each
 * spec captures the documented default for its field. It also cross-checks its specs against the
 * model's real fields and reports any drift through {@link #problems()}, so a setting that was
 * renamed or removed cannot silently linger in the metadata.
 */
public final class SettingRegistry {

    private final Map<String, SettingSpec> byPath = new LinkedHashMap<>();
    private final Map<SettingCategory, List<SettingSpec>> byCategory = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    private SettingRegistry() {
    }

    /** A registry with no settings, used in tests. */
    public static SettingRegistry empty() {
        return new SettingRegistry();
    }

    /**
     * Builds the default registry covering every ADMIN module.
     *
     * <p>The metadata is derived from a default config so a field that is added later still gets a
     * spec (a conservative, advanced, restart-required one) rather than disappearing from the GUI.
     */
    public static SettingRegistry createDefault() {
        SettingRegistry registry = new SettingRegistry();
        AdminConfig defaults = new AdminConfig();
        registry.registerAll(SettingsCatalog.specs(), defaults);
        registry.autoComplete(defaults);
        registry.verify(defaults);
        registry.index();
        return registry;
    }

    private void registerAll(List<SettingSpec> specs, AdminConfig defaults) {
        for (SettingSpec spec : specs) {
            // Capture the documented default from the model so a spec cannot drift from the field.
            String modelDefault = SettingsBinder.read(defaults, spec.path());
            if (modelDefault != null && !spec.secret()) {
                spec = spec.withDefault(modelDefault);
            }
            if (byPath.putIfAbsent(spec.path(), spec) != null) {
                problems.add("duplicate setting spec for " + spec.path());
            }
        }
    }

    /** Paths that are internal bookkeeping rather than operator settings. */
    private static final java.util.Set<String> INTERNAL_PATHS = java.util.Set.of("configVersion");

    /**
     * Adds a placeholder spec for any model field the catalog did not describe.
     *
     * <p>A missing spec would otherwise make a real setting invisible and unconfigurable. The
     * placeholder is marked advanced and restart-required so an undocumented setting is not offered
     * casually, and its type and default are read from the model so it still works.
     */
    private void autoComplete(AdminConfig defaults) {
        for (String path : SettingsBinder.leafPaths(defaults)) {
            if (byPath.containsKey(path) || INTERNAL_PATHS.contains(path)) {
                continue;
            }
            SettingsBinder.Accessor accessor = SettingsBinder.resolve(defaults, path);
            if (accessor == null) {
                continue;
            }
            SettingType type = typeOf(accessor.type());
            SettingSpec spec = SettingSpec.builder(path, categoryFor(path), type)
                    .label(path)
                    .description("Automatically registered setting; no description was supplied.")
                    .advanced()
                    .restartRequired()
                    .build();
            byPath.put(path, spec);
            notes.add("no catalog entry for " + path + "; a generic entry was registered");
        }
    }

    /** Checks every spec against the model and records any spec whose field no longer exists. */
    private void verify(AdminConfig defaults) {
        for (String path : new ArrayList<>(byPath.keySet())) {
            if (SettingsBinder.resolve(defaults, path) == null) {
                problems.add("spec for " + path + " does not match a field on AdminConfig");
            }
        }
    }

    private void index() {
        for (SettingSpec spec : byPath.values()) {
            byCategory.computeIfAbsent(spec.category(), key -> new ArrayList<>()).add(spec);
        }
        for (List<SettingSpec> specs : byCategory.values()) {
            specs.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        }
    }

    public SettingSpec byPath(String path) {
        return path == null ? null : byPath.get(path);
    }

    public List<SettingSpec> all() {
        return List.copyOf(byPath.values());
    }

    public List<SettingSpec> inCategory(SettingCategory category) {
        return byCategory.getOrDefault(category, List.of());
    }

    public Map<SettingCategory, List<SettingSpec>> grouped() {
        return Collections.unmodifiableMap(byCategory);
    }

    public boolean isEmpty() {
        return byPath.isEmpty();
    }

    /** Notes raised while building the registry; shown on the diagnostics page. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /** Informational notes raised while building the registry; not errors. */
    public List<String> notes() {
        return List.copyOf(notes);
    }

    /**
     * Free-text search over label, description, path, module and category.
     *
     * <p>Terms are ANDed and matched case-insensitively, so "chat queue" finds the queue capacity
     * and not every chat setting.
     */
    public List<SettingSpec> search(String query) {
        if (query == null || query.isBlank()) {
            return all();
        }
        String[] terms = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<SettingSpec> matches = new ArrayList<>();
        for (SettingSpec spec : byPath.values()) {
            String haystack = (spec.path() + " " + spec.label() + " " + spec.description() + " "
                    + spec.module() + " " + spec.category().label()).toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String term : terms) {
                if (!haystack.contains(term)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matches.add(spec);
            }
        }
        return matches;
    }

    /** Every category that has at least one setting. */
    public List<SettingCategory> categories() {
        List<SettingCategory> result = new ArrayList<>();
        for (SettingCategory category : SettingCategory.ordered()) {
            if (byCategory.containsKey(category) && !byCategory.get(category).isEmpty()) {
                result.add(category);
            }
        }
        return result;
    }

    private static SettingType typeOf(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return SettingType.BOOLEAN;
        }
        if (type == int.class || type == Integer.class) {
            return SettingType.INTEGER;
        }
        if (type == long.class || type == Long.class) {
            return SettingType.LONG;
        }
        if (type == double.class || type == Double.class || type == float.class || type == Float.class) {
            return SettingType.DECIMAL;
        }
        if (java.util.Collection.class.isAssignableFrom(type)) {
            return SettingType.STRING_LIST;
        }
        return SettingType.STRING;
    }

    /** A best-effort category for an auto-registered setting, based on its top-level section. */
    private static SettingCategory categoryFor(String path) {
        String head = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
        return switch (head) {
            case "moderation", "permissions" -> SettingCategory.MODERATION;
            case "anticheat", "protection" -> SettingCategory.ANTI_CHEAT;
            case "antidupe" -> SettingCategory.ANTI_DUPE;
            case "localChat" -> SettingCategory.LOCAL_CHAT;
            case "chatBridge" -> SettingCategory.CHAT_BRIDGE;
            case "remoteAdmin" -> SettingCategory.REMOTE_ADMIN;
            case "performance", "alerts" -> SettingCategory.PERFORMANCE;
            case "storage", "audit" -> SettingCategory.STORAGE;
            default -> SettingCategory.GENERAL;
        };
    }

    /** A stable, sorted map of path to spec, for the diagnostics page. */
    public Map<String, SettingSpec> sorted() {
        return Collections.unmodifiableMap(new TreeMap<>(byPath));
    }
}
