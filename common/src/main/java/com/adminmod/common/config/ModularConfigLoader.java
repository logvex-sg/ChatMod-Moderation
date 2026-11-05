package com.adminmod.common.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Loads and saves the split configuration under {@code config/admin/}.
 *
 * <p>The single {@code admin-mod.json} grew to the point where editing one setting meant scrolling
 * past everything else, and a single malformed edit could lose the whole file. The configuration
 * is therefore split into one file per concern:
 *
 * <pre>
 * config/admin/
 *   general.json      profile, general, announcements, maintenance, logging
 *   moderation.json   moderation, permissions
 *   anticheat.json    anticheat
 *   antidupe.json     antidupe
 *   chatbridge.json   chatBridge
 *   remoteadmin.json  remoteAdmin
 *   performance.json  performance, alerts
 *   protection.json   protection
 * </pre>
 *
 * <p>Proximity chat is deliberately absent: it already has its own authoritative
 * {@code localchat.json} written by {@code LocalChatFile}, and a second file of the same name
 * would be ambiguous. The legacy {@code admin-mod.json}'s {@code localChat} section still seeds
 * that file on the first migration.
 *
 * <p>Each file holds a JSON object whose keys are the section names it owns, so a reader sees only
 * the settings relevant to that file. A missing file is written with defaults; a malformed file is
 * moved aside with a {@code .broken} suffix and its defaults are used in memory, so one bad edit
 * cannot take the server down or destroy the other files.
 *
 * <p>An existing {@code admin-mod.json} is migrated once: it is read, split into the module files
 * and kept beside them as {@code admin-mod.json.migrated}, so nothing is lost and the operator can
 * compare if they wish.
 */
public final class ModularConfigLoader {

    /** The nine module files, in the order they are created. */
    public static final Map<String, List<String>> MODULES = modules();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ModularConfigLoader() {
    }

    private static Map<String, List<String>> modules() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("general.json", List.of("configVersion", "profile", "general", "announcements", "maintenance", "logging"));
        map.put("moderation.json", List.of("moderation", "permissions"));
        map.put("anticheat.json", List.of("anticheat"));
        map.put("antidupe.json", List.of("antidupe"));
        map.put("chatbridge.json", List.of("chatBridge"));
        map.put("remoteadmin.json", List.of("remoteAdmin"));
        map.put("performance.json", List.of("performance", "alerts"));
        map.put("protection.json", List.of("protection"));
        map.put("audit.json", List.of("audit"));
        map.put("storage.json", List.of("storage"));
        map.put("backups.json", List.of("backups"));
        map.put("automation.json", List.of("automation"));
        map.put("notifications.json", List.of("notifications"));
        return java.util.Collections.unmodifiableMap(map);
    }

    /**
     * Loads the whole configuration from {@code adminDir}, creating it (and migrating a legacy
     * {@code admin-mod.json} when present) as needed.
     *
     * @param adminDir   the {@code config/admin} directory
     * @param legacyFile the old single file, used only for a one-time migration
     * @param report     collects every correction and migration note
     */
    public static AdminConfig load(Path adminDir, Path legacyFile, ConfigLoader.ValidationReport report)
            throws IOException {
        return load(adminDir, legacyFile, report, null);
    }

    /**
     * Loads the whole configuration, optionally taking a backup first when a migration is needed.
     *
     * @param backupBeforeMigration invoked with the directory when the files are older than the
     *                              current schema and are about to be rewritten; may be {@code null}
     */
    public static AdminConfig load(Path adminDir, Path legacyFile, ConfigLoader.ValidationReport report,
            java.util.function.Consumer<Path> backupBeforeMigration) throws IOException {
        Files.createDirectories(adminDir);
        restrictDirectory(adminDir);

        boolean fresh = !Files.exists(adminDir.resolve("general.json"));
        AdminConfig config;
        if (fresh && legacyFile != null && Files.exists(legacyFile)) {
            if (backupBeforeMigration != null) {
                backupBeforeMigration.accept(adminDir);
            }
            config = migrateLegacy(adminDir, legacyFile, report);
        } else {
            config = new AdminConfig();
            // A module file written by an older build declares its version; if any is behind, the
            // files are backed up before the modules are rewritten below.
            int declared = readDeclaredVersion(adminDir);
            if (declared > 0 && declared < ConfigLoader.CURRENT_VERSION && backupBeforeMigration != null) {
                backupBeforeMigration.accept(adminDir);
                report.add("backed up the configuration before migrating from schema " + declared
                        + " to " + ConfigLoader.CURRENT_VERSION);
            }
        }

        // Each module is read over the accumulating config, so a file that is absent keeps the
        // defaults and a file that is present overrides only its own sections.
        for (Map.Entry<String, List<String>> module : MODULES.entrySet()) {
            Path file = adminDir.resolve(module.getKey());
            if (Files.notExists(file)) {
                saveModule(file, config, module.getValue());
                report.add("created " + module.getKey() + " with defaults");
                continue;
            }
            loadModule(file, config, module.getValue(), report);
        }

        ConfigLoader.validateForReport(config, report);
        // A profile named in the file is re-applied so a profile switch that was only half edited
        // (profile renamed but thresholds left behind) converges on the profile's values.
        if (PerformanceProfile.normalise(config.profile) != null) {
            PerformanceProfile.apply(config, config.profile);
        } else {
            report.add("profile '" + config.profile + "' is not recognised; keeping the current thresholds");
            config.profile = "balanced";
        }
        return config;
    }

    /** Reads the schema version declared in {@code general.json}, or 0 when it is absent. */
    private static int readDeclaredVersion(Path adminDir) {
        Path general = adminDir.resolve("general.json");
        if (Files.notExists(general)) {
            return 0;
        }
        try (Reader reader = Files.newBufferedReader(general, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.has("configVersion")) {
                return root.get("configVersion").getAsInt();
            }
        } catch (IOException | RuntimeException ignored) {
            // An unreadable file is handled by loadModule; treat it as unversioned here.
        }
        return 0;
    }

    /** Writes every module file from {@code config}. */
    public static void save(Path adminDir, AdminConfig config) throws IOException {
        Files.createDirectories(adminDir);
        restrictDirectory(adminDir);
        for (Map.Entry<String, List<String>> module : MODULES.entrySet()) {
            saveModule(adminDir.resolve(module.getKey()), config, module.getValue());
        }
    }

    private static void saveModule(Path file, AdminConfig config, List<String> sections) throws IOException {
        JsonObject root = new JsonObject();
        for (String section : sections) {
            root.add(section, GSON.toJsonTree(sectionValue(config, section)));
        }
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(root, writer);
        }
        restrict(file);
    }

    private static void loadModule(Path file, AdminConfig target, List<String> sections,
            ConfigLoader.ValidationReport report) {
        JsonObject root;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            backup(file);
            report.add(file.getFileName() + " could not be read (" + e.getClass().getSimpleName()
                    + "); defaults were used and the file was kept as " + file.getFileName() + ".broken");
            return;
        }
        // Deserialising the whole object with Gson leaves any section the file omits at its class
        // default, so a partially written file cannot null a section out.
        AdminConfig partial = GSON.fromJson(root, AdminConfig.class);
        if (partial == null) {
            report.add(file.getFileName() + " was empty; defaults were used");
            return;
        }
        for (String section : sections) {
            applySection(target, partial, section);
        }
    }

    /** Copies one named section from {@code partial} onto {@code target}, guarding against nulls. */
    private static void applySection(AdminConfig target, AdminConfig partial, String section) {
        switch (section) {
            case "configVersion" -> {
                if (partial.configVersion > 0) {
                    target.configVersion = partial.configVersion;
                }
            }
            case "profile" -> {
                if (partial.profile != null) {
                    target.profile = partial.profile;
                }
            }
            case "general" -> {
                if (partial.general != null) {
                    target.general = partial.general;
                }
            }
            case "announcements" -> {
                if (partial.announcements != null) {
                    target.announcements = partial.announcements;
                }
            }
            case "maintenance" -> {
                if (partial.maintenance != null) {
                    target.maintenance = partial.maintenance;
                }
            }
            case "logging" -> {
                if (partial.logging != null) {
                    target.logging = partial.logging;
                }
            }
            case "moderation" -> {
                if (partial.moderation != null) {
                    target.moderation = partial.moderation;
                }
            }
            case "permissions" -> {
                if (partial.permissions != null) {
                    target.permissions = partial.permissions;
                }
            }
            case "anticheat" -> {
                if (partial.anticheat != null) {
                    target.anticheat = partial.anticheat;
                }
            }
            case "antidupe" -> {
                if (partial.antidupe != null) {
                    target.antidupe = partial.antidupe;
                }
            }
            case "localChat" -> {
                if (partial.localChat != null) {
                    target.localChat = partial.localChat;
                }
            }
            case "chatBridge" -> {
                if (partial.chatBridge != null) {
                    target.chatBridge = partial.chatBridge;
                }
            }
            case "remoteAdmin" -> {
                if (partial.remoteAdmin != null) {
                    target.remoteAdmin = partial.remoteAdmin;
                }
            }
            case "performance" -> {
                if (partial.performance != null) {
                    target.performance = partial.performance;
                }
            }
            case "alerts" -> {
                if (partial.alerts != null) {
                    target.alerts = partial.alerts;
                }
            }
            case "protection" -> {
                if (partial.protection != null) {
                    target.protection = partial.protection;
                }
            }
            case "audit" -> {
                if (partial.audit != null) {
                    target.audit = partial.audit;
                }
            }
            case "storage" -> {
                if (partial.storage != null) {
                    target.storage = partial.storage;
                }
            }
            case "backups" -> {
                if (partial.backups != null) {
                    target.backups = partial.backups;
                }
            }
            case "automation" -> {
                if (partial.automation != null) {
                    target.automation = partial.automation;
                }
            }
            case "notifications" -> {
                if (partial.notifications != null) {
                    target.notifications = partial.notifications;
                }
            }
            default -> {
                // Unknown section names are ignored; the file may be from a newer build.
            }
        }
    }

    /** Reads one named section off a config, used when writing a module file. */
    private static Object sectionValue(AdminConfig config, String section) {
        return switch (section) {
            case "configVersion" -> config.configVersion;
            case "profile" -> config.profile;
            case "general" -> config.general;
            case "announcements" -> config.announcements;
            case "maintenance" -> config.maintenance;
            case "logging" -> config.logging;
            case "moderation" -> config.moderation;
            case "permissions" -> config.permissions;
            case "anticheat" -> config.anticheat;
            case "antidupe" -> config.antidupe;
            case "localChat" -> config.localChat;
            case "chatBridge" -> config.chatBridge;
            case "remoteAdmin" -> config.remoteAdmin;
            case "performance" -> config.performance;
            case "alerts" -> config.alerts;
            case "protection" -> config.protection;
            case "audit" -> config.audit;
            case "storage" -> config.storage;
            case "backups" -> config.backups;
            case "automation" -> config.automation;
            case "notifications" -> config.notifications;
            default -> null;
        };
    }

    /** Reads a legacy {@code admin-mod.json}, writes the modules and keeps the old file aside. */
    private static AdminConfig migrateLegacy(Path adminDir, Path legacyFile, ConfigLoader.ValidationReport report)
            throws IOException {
        AdminConfig config;
        try {
            config = ConfigLoader.load(legacyFile, report);
        } catch (IOException e) {
            report.add("the legacy admin-mod.json could not be read; defaults were used");
            config = new AdminConfig();
        }
        report.add("migrated the configuration from admin-mod.json into " + adminDir.getFileName() + "/");
        save(adminDir, config);
        try {
            Files.move(legacyFile, legacyFile.resolveSibling("admin-mod.json.migrated"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            report.add("the legacy admin-mod.json could not be renamed; it is left in place");
        }
        return config;
    }

    /** Reads a single module file into a fresh object, used by tests and the migration tool. */
    public static AdminConfig readModule(Path file, List<String> sections) throws IOException {
        AdminConfig config = new AdminConfig();
        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        loadModule(file, config, new ArrayList<>(sections), report);
        return config;
    }

    private static void backup(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".broken"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best effort; the defaults are already in memory.
        }
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

    private static void restrictDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system.
        }
    }
}
