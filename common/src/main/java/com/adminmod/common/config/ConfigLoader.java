package com.adminmod.common.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads and writes {@code config/admin-mod.json}. A missing file is materialised with the
 * documented defaults, and the file is written owner-only so webhook URLs and access tokens
 * are not world readable on a shared host.
 *
 * <p>The loader is the single place that decides what a valid configuration is. It fills in
 * structures that an older file did not have, migrates the schema forward, clamps values into
 * sane ranges and reports anything it had to change, so an operator can see exactly which
 * settings were rejected and why rather than discovering it from a crash later.
 */
public final class ConfigLoader {

    /** Schema version this build writes. Older files are upgraded to it on load. */
    public static final int CURRENT_VERSION = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigLoader() {
    }

    /**
     * Loads the configuration, creating it with defaults when absent.
     *
     * <p>Whatever the state of the file, the returned object is always usable: a value that is
     * out of range is replaced by a safe default and recorded in the returned report.
     */
    public static AdminConfig load(Path file) throws IOException {
        return load(file, new ValidationReport());
    }

    /** Loads the configuration and collects every correction in {@code report}. */
    public static AdminConfig load(Path file, ValidationReport report) throws IOException {
        if (Files.notExists(file)) {
            AdminConfig defaults = new AdminConfig();
            validate(defaults, report);
            save(file, defaults);
            report.add("created a new configuration with defaults at " + file);
            return defaults;
        }

        AdminConfig config;
        // The declared version is read separately: Gson leaves an absent field at the class default,
        // which would make an unversioned file look current and skip its migration.
        int declaredVersion = peekVersion(file);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config = GSON.fromJson(reader, AdminConfig.class);
        } catch (RuntimeException e) {
            // Malformed JSON must not stop the server from starting; fall back to defaults and
            // keep the broken file aside so it can be inspected.
            report.add("the configuration could not be parsed (" + e.getClass().getSimpleName()
                    + "); defaults were used and the old file was kept as " + file.getFileName() + ".broken");
            backup(file);
            config = new AdminConfig();
            declaredVersion = CURRENT_VERSION;
        }
        if (config == null) {
            config = new AdminConfig();
        }
        config.configVersion = declaredVersion;
        migrate(config, report);
        validate(config, report);
        // A file written by an older build is upgraded on disk as well, so the operator sees the
        // current schema and the next boot does not repeat the migration. A malformed file is left
        // alone here: it was already set aside and its defaults are only in memory.
        if (declaredVersion < CURRENT_VERSION) {
            try {
                save(file, config);
                report.add("wrote the migrated configuration back to " + file.getFileName());
            } catch (IOException e) {
                report.add("the migrated configuration could not be written back (" + e.getMessage() + ")");
            }
        }
        return config;
    }

    public static void save(Path file, AdminConfig config) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        restrict(file);
    }

    private static void backup(Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".broken"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // Best effort only; the defaults are already in use.
        }
    }

    /**
     * Fills in structures that were absent from an older configuration file.
     *
     * <p>Gson leaves a missing object field at its declared default only when the whole object
     * is absent; a partially written object can still arrive with null members, so every nested
     * reference is checked here rather than trusted.
     */
    private static void migrate(AdminConfig config, ValidationReport report) {
        int from = config.configVersion;
        if (from < 1) {
            report.add("upgraded the configuration from an unversioned file");
        }

        if (config.chatBridge == null) {
            config.chatBridge = new AdminConfig.ChatBridge();
        }
        if (config.chatBridge.discord == null) {
            config.chatBridge.discord = new AdminConfig.Discord();
        }
        if (config.chatBridge.matrix == null) {
            config.chatBridge.matrix = new AdminConfig.Matrix();
        }
        if (config.chatBridge.generic == null) {
            config.chatBridge.generic = new AdminConfig.GenericWebhook();
        }
        if (config.chatBridge.generic.headers == null) {
            config.chatBridge.generic.headers = java.util.Map.of();
        }
        if (config.chatBridge.antiSpam == null) {
            config.chatBridge.antiSpam = new AdminConfig.AntiSpam();
        }
        if (config.chatBridge.blockedKeywords == null) {
            config.chatBridge.blockedKeywords = List.of();
        }
        if (config.chatBridge.extraDiscordChannels == null) {
            config.chatBridge.extraDiscordChannels = List.of();
        }
        if (config.chatBridge.extraMatrixRooms == null) {
            config.chatBridge.extraMatrixRooms = List.of();
        }
        if (config.chatBridge.inbound == null) {
            config.chatBridge.inbound = new AdminConfig.Inbound();
        }
        if (config.chatBridge.inbound.discord == null) {
            config.chatBridge.inbound.discord = new AdminConfig.DiscordInbound();
        }
        if (config.chatBridge.inbound.discord.extraChannelIds == null) {
            config.chatBridge.inbound.discord.extraChannelIds = List.of();
        }
        if (config.chatBridge.inbound.matrix == null) {
            config.chatBridge.inbound.matrix = new AdminConfig.MatrixInbound();
        }
        if (config.chatBridge.inbound.matrix.extraRoomIds == null) {
            config.chatBridge.inbound.matrix.extraRoomIds = List.of();
        }
        if (config.moderation == null) {
            config.moderation = new AdminConfig.Moderation();
        }
        if (config.moderation.escalation == null) {
            config.moderation.escalation = new AdminConfig.Escalation();
        }
        if (config.moderation.templates == null) {
            config.moderation.templates = List.of();
        }
        if (config.remoteAdmin == null) {
            config.remoteAdmin = new AdminConfig.RemoteAdmin();
        }
        if (config.remoteAdmin.blockedCommands == null) {
            config.remoteAdmin.blockedCommands = List.of("stop", "restart");
        }
        if (config.permissions == null) {
            config.permissions = new AdminConfig.Permissions();
        }
        if (config.permissions.moderators == null) {
            config.permissions.moderators = List.of();
        }
        if (config.announcements == null) {
            config.announcements = new AdminConfig.Announcements();
        }
        if (config.announcements.messages == null) {
            config.announcements.messages = List.of();
        }
        if (config.maintenance == null) {
            config.maintenance = new AdminConfig.Maintenance();
        }
        if (config.maintenance.allowList == null) {
            config.maintenance.allowList = List.of();
        }
        if (config.logging == null) {
            config.logging = new AdminConfig.Logging();
        }
        config.configVersion = CURRENT_VERSION;
    }

    /** Clamps every value into a range the rest of the code can rely on. */
    private static void validate(AdminConfig config, ValidationReport report) {
        clampInt(report, "chatBridge.queueCapacity", config.chatBridge.queueCapacity, 16, 65_536, v -> config.chatBridge.queueCapacity = v);
        clampInt(report, "chatBridge.maxAttempts", config.chatBridge.maxAttempts, 1, 20, v -> config.chatBridge.maxAttempts = v);
        clampInt(report, "chatBridge.requestTimeoutMillis", config.chatBridge.requestTimeoutMillis, 1_000, 120_000, v -> config.chatBridge.requestTimeoutMillis = v);
        clampInt(report, "chatBridge.maxMessageLength", config.chatBridge.maxMessageLength, 64, 8_000, v -> config.chatBridge.maxMessageLength = v);
        clampLong(report, "chatBridge.antiSpam.duplicateWindowSeconds", config.chatBridge.antiSpam.duplicateWindowSeconds, 0L, 3_600L, v -> config.chatBridge.antiSpam.duplicateWindowSeconds = v);
        clampInt(report, "chatBridge.antiSpam.maxDuplicates", config.chatBridge.antiSpam.maxDuplicates, 0, 100, v -> config.chatBridge.antiSpam.maxDuplicates = v);
        clampLong(report, "chatBridge.antiSpam.minIntervalMillis", config.chatBridge.antiSpam.minIntervalMillis, 0L, 60_000L, v -> config.chatBridge.antiSpam.minIntervalMillis = v);

        if (config.chatBridge.inbound.format == null || config.chatBridge.inbound.format.isBlank()) {
            config.chatBridge.inbound.format = "[%platform%] %user%: %message%";
        }
        clampLong(report, "chatBridge.inbound.dedupeWindowMillis", config.chatBridge.inbound.dedupeWindowMillis, 0L, 600_000L, v -> config.chatBridge.inbound.dedupeWindowMillis = v);
        clampInt(report, "chatBridge.inbound.maxMessageLength", config.chatBridge.inbound.maxMessageLength, 16, 4_096, v -> config.chatBridge.inbound.maxMessageLength = v);
        clampInt(report, "chatBridge.inbound.matrix.syncTimeoutMillis", config.chatBridge.inbound.matrix.syncTimeoutMillis, 1_000, 300_000, v -> config.chatBridge.inbound.matrix.syncTimeoutMillis = v);
        clampInt(report, "chatBridge.inbound.matrix.syncLimit", config.chatBridge.inbound.matrix.syncLimit, 1, 1_000, v -> config.chatBridge.inbound.matrix.syncLimit = v);

        clampLong(report, "moderation.maxPunishmentSeconds", config.moderation.maxPunishmentSeconds, 60L, 315_360_000L, v -> config.moderation.maxPunishmentSeconds = v);
        clampLong(report, "moderation.defaultTempBanSeconds", config.moderation.defaultTempBanSeconds, 0L, config.moderation.maxPunishmentSeconds, v -> config.moderation.defaultTempBanSeconds = v);
        clampLong(report, "moderation.defaultTempMuteSeconds", config.moderation.defaultTempMuteSeconds, 0L, config.moderation.maxPunishmentSeconds, v -> config.moderation.defaultTempMuteSeconds = v);
        clampInt(report, "moderation.escalation.warnThreshold", config.moderation.escalation.warnThreshold, 1, 1_000, v -> config.moderation.escalation.warnThreshold = v);
        clampLong(report, "moderation.escalation.windowSeconds", config.moderation.escalation.windowSeconds, 0L, 31_536_000L, v -> config.moderation.escalation.windowSeconds = v);

        clampInt(report, "remoteAdmin.requestsPerMinute", config.remoteAdmin.requestsPerMinute, 1, 100_000, v -> config.remoteAdmin.requestsPerMinute = v);
        clampInt(report, "remoteAdmin.consoleRequestsPerMinute", config.remoteAdmin.consoleRequestsPerMinute, 1, 100_000, v -> config.remoteAdmin.consoleRequestsPerMinute = v);
        clampLong(report, "remoteAdmin.sessionTimeoutSeconds", config.remoteAdmin.sessionTimeoutSeconds, 30L, 86_400L, v -> config.remoteAdmin.sessionTimeoutSeconds = v);
        if (config.remoteAdmin.vaultDirectory == null || config.remoteAdmin.vaultDirectory.isBlank()) {
            config.remoteAdmin.vaultDirectory = new AdminConfig.RemoteAdmin().vaultDirectory;
        }

        clampInt(report, "permissions.viewLevel", config.permissions.viewLevel, 0, 4, v -> config.permissions.viewLevel = v);
        clampInt(report, "permissions.moderateLevel", config.permissions.moderateLevel, 0, 4, v -> config.permissions.moderateLevel = v);
        clampInt(report, "permissions.adminLevel", config.permissions.adminLevel, 0, 4, v -> config.permissions.adminLevel = v);

        clampLong(report, "announcements.intervalSeconds", config.announcements.intervalSeconds, 10L, 86_400L, v -> config.announcements.intervalSeconds = v);

        if (config.logging.level == null || normaliseLevel(config.logging.level) == null) {
            report.add("logging.level was not one of off/error/warn/info/debug; using info");
            config.logging.level = "info";
        } else {
            config.logging.level = normaliseLevel(config.logging.level);
        }

        // Drop blank keyword and template entries so a stray empty line cannot block everything.
        config.chatBridge.blockedKeywords = withoutBlanks(config.chatBridge.blockedKeywords);
        List<AdminConfig.PunishmentTemplate> templates = new ArrayList<>();
        for (AdminConfig.PunishmentTemplate template : config.moderation.templates) {
            if (template != null && template.name != null && !template.name.isBlank()) {
                if (template.type == null || template.type.isBlank()) {
                    template.type = "MUTE";
                }
                templates.add(template);
            }
        }
        config.moderation.templates = templates;
    }

    /** @return the canonical lower-case level name, or {@code null} when it is not recognised. */
    public static String normaliseLevel(String level) {
        if (level == null) {
            return null;
        }
        String value = level.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "off", "error", "warn", "info", "debug" -> value;
            default -> null;
        };
    }

    private static List<String> withoutBlanks(List<String> values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value);
            }
        }
        return result;
    }

    private static void clampInt(ValidationReport report, String key, int value, int min, int max,
            java.util.function.IntConsumer setter) {
        if (value < min || value > max) {
            int replacement = Math.max(min, Math.min(max, value));
            setter.accept(replacement);
            report.add(key + " = " + value + " is outside " + min + ".." + max + "; using " + replacement);
        }
    }

    private static void clampLong(ValidationReport report, String key, long value, long min, long max,
            java.util.function.LongConsumer setter) {
        if (value < min || value > max) {
            long replacement = Math.max(min, Math.min(max, value));
            setter.accept(replacement);
            report.add(key + " = " + value + " is outside " + min + ".." + max + "; using " + replacement);
        }
    }

    /** Parses just the version field, used to decide whether a migration is needed. */
    public static int peekVersion(Path file) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("configVersion")) {
                return root.get("configVersion").getAsInt();
            }
        } catch (IOException | RuntimeException ignored) {
            // A missing or unreadable file is treated as version 0.
        }
        return 0;
    }

    private static void restrict(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system; the parent directory permission is the remaining guard.
        }
    }

    /** Collects the corrections and migrations applied while loading, for the server log. */
    public static final class ValidationReport {
        private final List<String> notes = new ArrayList<>();

        public void add(String note) {
            notes.add(note);
        }

        public boolean isEmpty() {
            return notes.isEmpty();
        }

        public List<String> notes() {
            return List.copyOf(notes);
        }
    }
}
