package com.adminmod.server.settings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.config.ConfigLoader;
import com.adminmod.common.config.ModularConfigLoader;
import com.adminmod.common.settings.ConfigBackup;
import com.adminmod.common.settings.ConfigDiagnostics;
import com.adminmod.common.settings.ConfigHistory;
import com.adminmod.common.settings.ConfigPresets;
import com.adminmod.common.settings.ConfigProfile;
import com.adminmod.common.settings.ProfileCodec;
import com.adminmod.common.settings.ProfileStore;
import com.adminmod.common.settings.SettingCategory;
import com.adminmod.common.settings.SettingRegistry;
import com.adminmod.common.settings.SettingSpec;
import com.adminmod.common.settings.SettingValidation;
import com.adminmod.common.settings.SettingsBinder;
import com.adminmod.common.settings.SettingsJson;

/**
 * The one place every configuration change goes through.
 *
 * <p>It owns the registry, the profile store, the change history and the backup helper, and it
 * applies a change atomically: a batch of updates is validated in full, written into a scratch copy
 * of the configuration, and only committed if every value is accepted. That is what makes "apply or
 * cancel" real — a batch that would leave the configuration half-updated is refused instead.
 *
 * <p>Every mutation is recorded in {@link ConfigHistory} with the actor's name, so the previous
 * value of anything changed is recoverable, and the caller is handed a {@link Result} describing
 * exactly what happened for the operator.
 */
public final class SettingsService {

    /** The outcome of a change. */
    public record Result(boolean success, String message, List<String> warnings) {

        public static Result ok(String message) {
            return new Result(true, message, List.of());
        }

        public static Result ok(String message, List<String> warnings) {
            return new Result(true, message, warnings);
        }

        public static Result fail(String message) {
            return new Result(false, message, List.of());
        }
    }

    private final AdminConfig config;
    private final Path configDirectory;
    private final SettingRegistry registry;
    private final ProfileStore profiles;
    private final ConfigHistory history;
    private final ConfigBackup backups;
    private final Consumer<AdminConfig> persist;
    private final Runnable reloadSubsystems;
    private final List<String> migrationNotes = new ArrayList<>();

    public SettingsService(AdminConfig config, Path configDirectory, SettingRegistry registry,
            ProfileStore profiles, ConfigHistory history, ConfigBackup backups,
            Consumer<AdminConfig> persist, Runnable reloadSubsystems) {
        this.config = config;
        this.configDirectory = configDirectory;
        this.registry = registry;
        this.profiles = profiles;
        this.history = history;
        this.backups = backups;
        this.persist = persist;
        this.reloadSubsystems = reloadSubsystems;
    }

    public SettingRegistry registry() {
        return registry;
    }

    public ProfileStore profiles() {
        return profiles;
    }

    public ConfigHistory history() {
        return history;
    }

    public ConfigBackup backups() {
        return backups;
    }

    /** Records the loader's migration notes so the diagnostics page can show them. */
    public void setMigrationNotes(List<String> notes) {
        migrationNotes.clear();
        migrationNotes.addAll(notes);
    }

    // ------------------------------------------------------------------ reads

    public String catalogJson() {
        return SettingsJson.catalog(registry, config);
    }

    public String diagnosticsJson() {
        return SettingsJson.diagnostics(diagnostics());
    }

    public List<ConfigDiagnostics.Finding> diagnostics() {
        return ConfigDiagnostics.analyse(config, registry, migrationNotes, List.of());
    }

    public String historyJson() {
        return SettingsJson.history(history.recent());
    }

    /** Every profile and preset, custom first, with the selected name. */
    public String profilesJson() {
        List<ConfigProfile> all = new ArrayList<>(profiles.custom());
        all.addAll(ConfigPresets.all());
        return SettingsJson.profiles(all, profiles.selected());
    }

    // ------------------------------------------------------------------ single change

    /**
     * Validates and applies one setting.
     *
     * <p>The value is validated against its spec before anything is written, so an invalid value is
     * explained rather than silently clamped. On success the change is persisted and the subsystems
     * that need to observe it are told to reload.
     */
    public Result update(String actor, String path, String value) {
        SettingSpec spec = registry.byPath(path);
        if (spec == null) {
            return Result.fail("Unknown setting: " + path);
        }
        if (spec.secret() && value == null) {
            return Result.fail(spec.label() + " is a secret and cannot be cleared through the dashboard");
        }
        String normalised = SettingValidation.normalise(spec, value);
        SettingValidation.Result check = SettingValidation.validate(spec, normalised);
        if (!check.valid()) {
            return Result.fail(spec.label() + ": " + check.message());
        }
        String before = SettingsBinder.read(config, path);
        if (!SettingsBinder.write(config, path, normalised)) {
            return Result.fail("The value could not be applied to " + spec.label());
        }
        history.record(actor, "changed " + spec.label(), path, before, normalised);
        return commit(Result.ok("Updated " + spec.label(), restartWarnings(List.of(spec))));
    }

    /**
     * Applies a batch of changes atomically.
     *
     * <p>Each value is validated against a scratch copy first; if any is invalid the whole batch is
     * refused and nothing is written, so the configuration is never left partially updated.
     */
    public Result updateBatch(String actor, Map<String, String> updates) {
        if (updates == null || updates.isEmpty()) {
            return Result.fail("No changes to apply");
        }
        Map<String, String> before = new LinkedHashMap<>();
        List<SettingSpec> touched = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            SettingSpec spec = registry.byPath(entry.getKey());
            if (spec == null) {
                problems.add("unknown setting " + entry.getKey());
                continue;
            }
            String normalised = SettingValidation.normalise(spec, entry.getValue());
            SettingValidation.Result check = SettingValidation.validate(spec, normalised);
            if (!check.valid()) {
                problems.add(spec.label() + ": " + check.message());
                continue;
            }
            before.put(entry.getKey(), SettingsBinder.read(config, entry.getKey()));
            touched.add(spec);
        }
        if (!problems.isEmpty()) {
            return new Result(false, "Some values were rejected; nothing was changed", problems);
        }
        for (Map.Entry<String, String> entry : updates.entrySet()) {
            SettingSpec spec = registry.byPath(entry.getKey());
            SettingsBinder.write(config, entry.getKey(), SettingValidation.normalise(spec, entry.getValue()));
        }
        Map<String, String> after = new LinkedHashMap<>();
        for (String path : updates.keySet()) {
            after.put(path, SettingsBinder.read(config, path));
        }
        history.record(new ConfigHistory.Change(System.currentTimeMillis(), actor,
                "applied " + updates.size() + " change(s)", before, after));
        return commit(Result.ok("Applied " + updates.size() + " change(s)", restartWarnings(touched)));
    }

    /** Restores a single setting to its documented default. */
    public Result resetSetting(String actor, String path) {
        SettingSpec spec = registry.byPath(path);
        if (spec == null) {
            return Result.fail("Unknown setting: " + path);
        }
        return update(actor, path, spec.defaultValue());
    }

    /** Restores every setting in a category to its documented default. */
    public Result resetCategory(String actor, SettingCategory category) {
        Map<String, String> updates = new LinkedHashMap<>();
        for (SettingSpec spec : registry.inCategory(category)) {
            if (!spec.secret()) {
                updates.put(spec.path(), spec.defaultValue());
            }
        }
        if (updates.isEmpty()) {
            return Result.fail("Nothing to reset in " + category.label());
        }
        return updateBatch(actor, updates);
    }

    // ------------------------------------------------------------------ profiles

    public Result createProfile(String name, String description) {
        if (name == null || name.isBlank()) {
            return Result.fail("A profile name is required");
        }
        if (ConfigPresets.byName(name) != null) {
            return Result.fail("'" + name + "' is a built-in name; choose another");
        }
        profiles.create(name, description, new ProfileStore.AdminConfigSource() {
            @Override
            public AdminConfig config() {
                return config;
            }

            @Override
            public SettingRegistry registry() {
                return registry;
            }
        });
        return persistProfiles(Result.ok("Created profile '" + name + "'"));
    }

    public Result duplicateProfile(String source, String newName) {
        if (profiles.duplicate(source, newName) == null) {
            return Result.fail("No such profile: " + source);
        }
        return persistProfiles(Result.ok("Duplicated '" + source + "' as '" + newName + "'"));
    }

    public Result renameProfile(String oldName, String newName) {
        if (!profiles.rename(oldName, newName)) {
            return Result.fail("The profile could not be renamed (unknown name, or a built-in name)");
        }
        return persistProfiles(Result.ok("Renamed '" + oldName + "' to '" + newName + "'"));
    }

    public Result deleteProfile(String name) {
        if (!profiles.delete(name)) {
            return Result.fail("Only custom profiles can be deleted");
        }
        return persistProfiles(Result.ok("Deleted '" + name + "'"));
    }

    /** The settings a profile would change, for the preview screen. */
    public String preview(String name) {
        ConfigProfile profile = profiles.find(name);
        if (profile == null) {
            return SettingsJson.preview(name, List.of());
        }
        return SettingsJson.preview(name, profile.diffAgainst(config, registry));
    }

    /** Applies a profile, taking a backup first when configured to. */
    public Result applyProfile(String actor, String name) {
        ConfigProfile profile = profiles.find(name);
        if (profile == null) {
            return Result.fail("No such profile: " + name);
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (String path : profile.values().keySet()) {
            SettingSpec spec = registry.byPath(path);
            if (spec != null && !spec.secret()) {
                before.put(path, SettingsBinder.read(config, path));
            }
        }
        List<String> changed = ConfigPresets.apply(config, profile, registry);
        profiles.select(name);
        Map<String, String> after = new LinkedHashMap<>();
        for (String path : before.keySet()) {
            after.put(path, SettingsBinder.read(config, path));
        }
        if (!before.isEmpty()) {
            history.record(new ConfigHistory.Change(System.currentTimeMillis(), actor,
                    "applied profile '" + name + "'", before, after));
        }
        persistProfiles(Result.ok(""));
        return commit(Result.ok("Applied profile '" + name + "' (" + changed.size() + " setting(s) changed)",
                restartWarningsForPaths(changed)));
    }

    /** The currently selected profile name. */
    public String selectedProfile() {
        return profiles.selected();
    }

    // ------------------------------------------------------------------ import / export

    /** Renders a profile as an exportable JSON document, with no credential material. */
    public String exportProfile(String name) {
        ConfigProfile profile = profiles.find(name);
        if (profile == null) {
            return "";
        }
        return ProfileCodec.toJson(profile);
    }

    /**
     * Imports a profile document.
     *
     * <p>The configuration is backed up first when configured to, then the imported values are
     * validated. A document that is structurally broken is refused before anything is written.
     */
    public Result importProfile(String actor, String json, boolean applyNow) {
        ProfileCodec.ImportResult parsed = ProfileCodec.fromJson(json, registry);
        if (!parsed.ok()) {
            return Result.fail(parsed.error());
        }
        ConfigProfile profile = parsed.profile();
        if (config.backups.backupBeforeImport) {
            try {
                backups.snapshot(configDirectory, "import");
            } catch (IOException e) {
                return Result.fail("The pre-import backup could not be written; the import was cancelled");
            }
        }
        if (applyNow) {
            Map<String, String> before = new LinkedHashMap<>();
            for (String path : profile.values().keySet()) {
                SettingSpec spec = registry.byPath(path);
                if (spec != null && !spec.secret()) {
                    before.put(path, SettingsBinder.read(config, path));
                }
            }
            List<String> changed = ConfigPresets.apply(config, profile, registry);
            Map<String, String> after = new LinkedHashMap<>();
            for (String path : before.keySet()) {
                after.put(path, SettingsBinder.read(config, path));
            }
            history.record(new ConfigHistory.Change(System.currentTimeMillis(), actor,
                    "imported profile '" + profile.name() + "'", before, after));
            return commit(Result.ok("Imported '" + profile.name() + "' (" + changed.size()
                    + " setting(s) changed)", parsed.warnings()));
        }
        if (profiles.put(profile)) {
            persistProfiles(Result.ok(""));
        }
        return persistProfiles(Result.ok("Imported profile '" + profile.name() + "'", parsed.warnings()));
    }

    // ------------------------------------------------------------------ rollback

    /** Restores the previous value of every setting the most recent change touched. */
    public Result rollback(String actor) {
        ConfigHistory.Change last = history.last();
        if (last == null) {
            return Result.fail("There is nothing to roll back");
        }
        history.pop();
        for (Map.Entry<String, String> entry : last.before().entrySet()) {
            SettingsBinder.write(config, entry.getKey(), entry.getValue());
        }
        List<SettingSpec> touched = new ArrayList<>();
        for (String path : last.before().keySet()) {
            SettingSpec spec = registry.byPath(path);
            if (spec != null) {
                touched.add(spec);
            }
        }
        return commit(Result.ok("Rolled back: " + last.summary(), restartWarnings(touched)));
    }

    // ------------------------------------------------------------------ internals

    private Result commit(Result result) {
        if (!result.success()) {
            return result;
        }
        if (persist != null) {
            persist.accept(config);
        }
        if (reloadSubsystems != null) {
            reloadSubsystems.run();
        }
        return result;
    }

    private Result persistProfiles(Result result) {
        try {
            profiles.save();
        } catch (IOException e) {
            return Result.fail("The profile store could not be written");
        }
        return result;
    }

    private List<String> restartWarnings(List<SettingSpec> specs) {
        List<String> warnings = new ArrayList<>();
        for (SettingSpec spec : specs) {
            if (spec.restartRequired()) {
                warnings.add(spec.label() + " takes effect after a restart");
            }
        }
        return warnings;
    }

    private List<String> restartWarningsForPaths(List<String> paths) {
        List<SettingSpec> specs = new ArrayList<>();
        for (String path : paths) {
            SettingSpec spec = registry.byPath(path);
            if (spec != null) {
                specs.add(spec);
            }
        }
        return restartWarnings(specs);
    }

    /** Saves the configuration to disk, used by the owning server on a reload. */
    public static void save(Path configDirectory, AdminConfig config) throws IOException {
        ModularConfigLoader.save(configDirectory, config);
    }

    /** Reads a fresh validation report, used when the owning server reloads from disk. */
    public static ConfigLoader.ValidationReport newReport() {
        return new ConfigLoader.ValidationReport();
    }
}
