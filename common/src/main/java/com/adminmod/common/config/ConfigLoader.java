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
    public static final int CURRENT_VERSION = 4;

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
        if (config.chatBridge.batching == null) {
            config.chatBridge.batching = new AdminConfig.Batching();
        }
        if (config.localChat == null) {
            config.localChat = new AdminConfig.LocalChat();
        }
        if (config.localChat.dimensions == null) {
            config.localChat.dimensions = List.of();
        }
        if (config.localChat.globalPrefix == null) {
            config.localChat.globalPrefix = "!";
        }
        if (config.localChat.soundId == null) {
            config.localChat.soundId = "minecraft:entity.experience_orb.pickup";
        }
        if (config.localChat.noRecipientsMessage == null) {
            config.localChat.noRecipientsMessage = "";
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
        if (config.general == null) {
            config.general = new AdminConfig.General();
        }
        if (config.anticheat == null) {
            config.anticheat = new AdminConfig.Anticheat();
        }
        if (config.antidupe == null) {
            config.antidupe = new AdminConfig.Antidupe();
        }
        if (config.protection == null) {
            config.protection = new AdminConfig.Protection();
        }
        if (config.performance == null) {
            config.performance = new AdminConfig.Performance();
        }
        if (config.alerts == null) {
            config.alerts = new AdminConfig.Alerts();
        }
        if (config.audit == null) {
            config.audit = new AdminConfig.Audit();
        }
        if (config.audit.categories == null) {
            config.audit.categories = new AdminConfig.Audit().categories;
        }
        if (config.storage == null) {
            config.storage = new AdminConfig.Storage();
        }
        if (config.storage.algorithm == null || config.storage.algorithm.isBlank()) {
            config.storage.algorithm = "zstd";
        }
        if (config.profile == null || config.profile.isBlank()) {
            config.profile = "balanced";
        }
        config.configVersion = CURRENT_VERSION;
    }

    /** Clamps every value into a range the rest of the code can rely on. */
    private static void validate(AdminConfig config, ValidationReport report) {
        validateForReport(config, report);
    }

    /**
     * Clamps every value into a range the rest of the code can rely on, recording each correction.
     *
     * <p>Public so the modular loader can validate a configuration it assembled from several files
     * with the same rules the single-file loader uses.
     */
    public static void validateForReport(AdminConfig config, ValidationReport report) {
        clampInt(report, "chatBridge.queueCapacity", config.chatBridge.queueCapacity, 16, 65_536, v -> config.chatBridge.queueCapacity = v);
        clampInt(report, "chatBridge.maxAttempts", config.chatBridge.maxAttempts, 1, 20, v -> config.chatBridge.maxAttempts = v);
        clampInt(report, "chatBridge.requestTimeoutMillis", config.chatBridge.requestTimeoutMillis, 1_000, 120_000, v -> config.chatBridge.requestTimeoutMillis = v);
        clampInt(report, "chatBridge.maxMessageLength", config.chatBridge.maxMessageLength, 64, 8_000, v -> config.chatBridge.maxMessageLength = v);
        clampLong(report, "chatBridge.antiSpam.duplicateWindowSeconds", config.chatBridge.antiSpam.duplicateWindowSeconds, 0L, 3_600L, v -> config.chatBridge.antiSpam.duplicateWindowSeconds = v);
        clampInt(report, "chatBridge.antiSpam.maxDuplicates", config.chatBridge.antiSpam.maxDuplicates, 0, 100, v -> config.chatBridge.antiSpam.maxDuplicates = v);
        clampLong(report, "chatBridge.antiSpam.minIntervalMillis", config.chatBridge.antiSpam.minIntervalMillis, 0L, 60_000L, v -> config.chatBridge.antiSpam.minIntervalMillis = v);
        clampInt(report, "chatBridge.batching.batchSize", config.chatBridge.batching.batchSize, 1, 100, v -> config.chatBridge.batching.batchSize = v);
        clampLong(report, "chatBridge.batching.batchWindowMillis", config.chatBridge.batching.batchWindowMillis, 0L, 10_000L, v -> config.chatBridge.batching.batchWindowMillis = v);
        clampLong(report, "chatBridge.batching.dedupeWindowMillis", config.chatBridge.batching.dedupeWindowMillis, 0L, 60_000L, v -> config.chatBridge.batching.dedupeWindowMillis = v);
        clampInt(report, "chatBridge.batching.dedupeMemory", config.chatBridge.batching.dedupeMemory, 0, 65_536, v -> config.chatBridge.batching.dedupeMemory = v);

        if (config.chatBridge.inbound.format == null || config.chatBridge.inbound.format.isBlank()) {
            config.chatBridge.inbound.format = "[%platform%] %user%: %message%";
        }
        clampLong(report, "chatBridge.inbound.dedupeWindowMillis", config.chatBridge.inbound.dedupeWindowMillis, 0L, 600_000L, v -> config.chatBridge.inbound.dedupeWindowMillis = v);
        clampInt(report, "chatBridge.inbound.maxMessageLength", config.chatBridge.inbound.maxMessageLength, 16, 4_096, v -> config.chatBridge.inbound.maxMessageLength = v);
        clampInt(report, "chatBridge.inbound.matrix.syncTimeoutMillis", config.chatBridge.inbound.matrix.syncTimeoutMillis, 1_000, 300_000, v -> config.chatBridge.inbound.matrix.syncTimeoutMillis = v);
        clampInt(report, "chatBridge.inbound.matrix.syncLimit", config.chatBridge.inbound.matrix.syncLimit, 1, 1_000, v -> config.chatBridge.inbound.matrix.syncLimit = v);

        // Proximity chat. The radius and cooldown floors matter: a zero radius would make local chat
        // useless, and a negative cooldown would disable the rate limit.
        if (config.localChat.globalPrefix == null) {
            config.localChat.globalPrefix = "!";
        }
        if (config.localChat.localFormat == null || config.localChat.localFormat.isBlank()) {
            config.localChat.localFormat = "[Local] &f%player%&7: &f%message%";
        }
        if (config.localChat.globalFormat == null || config.localChat.globalFormat.isBlank()) {
            config.localChat.globalFormat = "[Global] &f%player%&7: &f%message%";
        }
        if (config.localChat.soundId == null || config.localChat.soundId.isBlank()) {
            config.localChat.soundId = "minecraft:entity.experience_orb.pickup";
        }
        if (config.localChat.noRecipientsMessage == null) {
            config.localChat.noRecipientsMessage = "";
        }
        if (config.localChat.dimensions == null) {
            config.localChat.dimensions = List.of();
        }
        if (config.localChat.radius <= 0) {
            report.add("localChat.radius was not positive; using the default of 50");
            config.localChat.radius = 50.0;
        }
        clampInt(report, "localChat.staffLevel", config.localChat.staffLevel, 0, 4, v -> config.localChat.staffLevel = v);
        clampLong(report, "localChat.cooldownMillis", config.localChat.cooldownMillis, 0L, 600_000L, v -> config.localChat.cooldownMillis = v);

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
        clampLong(report, "remoteAdmin.pairingKeyTtlSeconds", config.remoteAdmin.pairingKeyTtlSeconds, 30L, 86_400L, v -> config.remoteAdmin.pairingKeyTtlSeconds = v);
        clampInt(report, "remoteAdmin.pairingKeyLength", config.remoteAdmin.pairingKeyLength, 12, 128, v -> config.remoteAdmin.pairingKeyLength = v);
        clampInt(report, "remoteAdmin.clientSecretLength", config.remoteAdmin.clientSecretLength, 24, 128, v -> config.remoteAdmin.clientSecretLength = v);
        clampInt(report, "remoteAdmin.authFailureLimit", config.remoteAdmin.authFailureLimit, 1, 1_000, v -> config.remoteAdmin.authFailureLimit = v);
        clampLong(report, "remoteAdmin.authLockoutSeconds", config.remoteAdmin.authLockoutSeconds, 10L, 86_400L, v -> config.remoteAdmin.authLockoutSeconds = v);
        clampInt(report, "remoteAdmin.authHistoryLimit", config.remoteAdmin.authHistoryLimit, 10, 10_000, v -> config.remoteAdmin.authHistoryLimit = v);
        if (config.remoteAdmin.clientRegistryFile == null || config.remoteAdmin.clientRegistryFile.isBlank()) {
            config.remoteAdmin.clientRegistryFile = new AdminConfig.RemoteAdmin().clientRegistryFile;
        }
        if (config.remoteAdmin.allowedClientBuilds == null) {
            config.remoteAdmin.allowedClientBuilds = List.of();
        }

        clampInt(report, "permissions.viewLevel", config.permissions.viewLevel, 0, 4, v -> config.permissions.viewLevel = v);
        clampInt(report, "permissions.moderateLevel", config.permissions.moderateLevel, 0, 4, v -> config.permissions.moderateLevel = v);
        clampInt(report, "permissions.adminLevel", config.permissions.adminLevel, 0, 4, v -> config.permissions.adminLevel = v);

        clampLong(report, "announcements.intervalSeconds", config.announcements.intervalSeconds, 10L, 86_400L, v -> config.announcements.intervalSeconds = v);

        clampInt(report, "general.historyMinutes", config.general.historyMinutes, 5, 10_080, v -> config.general.historyMinutes = v);

        clampInt(report, "protection.maxChatLength", config.protection.maxChatLength, 32, 32_000, v -> config.protection.maxChatLength = v);
        clampInt(report, "protection.maxPayloadBytes", config.protection.maxPayloadBytes, 256, 1_048_576, v -> config.protection.maxPayloadBytes = v);
        clampInt(report, "protection.maxBookPages", config.protection.maxBookPages, 1, 1_024, v -> config.protection.maxBookPages = v);
        clampInt(report, "protection.maxBookPageLength", config.protection.maxBookPageLength, 16, 32_000, v -> config.protection.maxBookPageLength = v);
        clampInt(report, "protection.maxBookTitleLength", config.protection.maxBookTitleLength, 1, 1_024, v -> config.protection.maxBookTitleLength = v);
        clampInt(report, "protection.maxSignLineLength", config.protection.maxSignLineLength, 16, 32_000, v -> config.protection.maxSignLineLength = v);
        clampInt(report, "protection.maxModifiedStacks", config.protection.maxModifiedStacks, 1, 4_096, v -> config.protection.maxModifiedStacks = v);
        clampInt(report, "protection.joinGraceTicks", config.protection.joinGraceTicks, 0, 1_200, v -> config.protection.joinGraceTicks = v);
        clampInt(report, "protection.chatPerMinute", config.protection.chatPerMinute, 1, 100_000, v -> config.protection.chatPerMinute = v);
        clampInt(report, "protection.commandsPerMinute", config.protection.commandsPerMinute, 1, 100_000, v -> config.protection.commandsPerMinute = v);
        clampInt(report, "protection.packetsPerSecond", config.protection.packetsPerSecond, 1, 1_000_000, v -> config.protection.packetsPerSecond = v);
        clampInt(report, "protection.packetsPerTenSeconds", config.protection.packetsPerTenSeconds, 1, 10_000_000, v -> config.protection.packetsPerTenSeconds = v);
        clampInt(report, "protection.inventoryClicksPerMinute", config.protection.inventoryClicksPerMinute, 1, 100_000, v -> config.protection.inventoryClicksPerMinute = v);
        clampInt(report, "protection.entityInteractionsPerMinute", config.protection.entityInteractionsPerMinute, 1, 100_000, v -> config.protection.entityInteractionsPerMinute = v);
        clampInt(report, "protection.violationsBeforeKick", config.protection.violationsBeforeKick, 0, 1_000, v -> config.protection.violationsBeforeKick = v);

        clampInt(report, "anticheat.connectionRatePerMinute", config.anticheat.connectionRatePerMinute, 1, 100_000, v -> config.anticheat.connectionRatePerMinute = v);
        clampInt(report, "anticheat.maxConnectionsPerAddress", config.anticheat.maxConnectionsPerAddress, 1, 1_000, v -> config.anticheat.maxConnectionsPerAddress = v);
        clampInt(report, "anticheat.maxTrackedAddresses", config.anticheat.maxTrackedAddresses, 64, 1_000_000, v -> config.anticheat.maxTrackedAddresses = v);

        clampInt(report, "antidupe.repeatClickThreshold", config.antidupe.repeatClickThreshold, 2, 1_000, v -> config.antidupe.repeatClickThreshold = v);
        clampLong(report, "antidupe.repeatClickWindowMillis", config.antidupe.repeatClickWindowMillis, 50L, 60_000L, v -> config.antidupe.repeatClickWindowMillis = v);

        clampLong(report, "performance.playerCacheMillis", config.performance.playerCacheMillis, 100L, 600_000L, v -> config.performance.playerCacheMillis = v);
        clampInt(report, "performance.playerCacheLimit", config.performance.playerCacheLimit, 16, 100_000, v -> config.performance.playerCacheLimit = v);
        clampInt(report, "performance.chatHistoryPerPlayer", config.performance.chatHistoryPerPlayer, 1, 10_000, v -> config.performance.chatHistoryPerPlayer = v);
        clampInt(report, "performance.chatHistoryPlayers", config.performance.chatHistoryPlayers, 1, 100_000, v -> config.performance.chatHistoryPlayers = v);
        clampInt(report, "performance.transactionHistory", config.performance.transactionHistory, 16, 100_000, v -> config.performance.transactionHistory = v);
        clampInt(report, "performance.eventHistory", config.performance.eventHistory, 16, 100_000, v -> config.performance.eventHistory = v);
        clampInt(report, "performance.metricHistory", config.performance.metricHistory, 10, 100_000, v -> config.performance.metricHistory = v);
        clampInt(report, "performance.sampleIntervalTicks", config.performance.sampleIntervalTicks, 1, 1_200, v -> config.performance.sampleIntervalTicks = v);
        clampInt(report, "performance.entityCountIntervalTicks", config.performance.entityCountIntervalTicks, 20, 72_000, v -> config.performance.entityCountIntervalTicks = v);
        clampInt(report, "performance.cleanupIntervalTicks", config.performance.cleanupIntervalTicks, 20, 72_000, v -> config.performance.cleanupIntervalTicks = v);

        clampDouble(report, "alerts.msptWarn", config.alerts.msptWarn, 1.0, 10_000.0, v -> config.alerts.msptWarn = v);
        clampDouble(report, "alerts.tpsWarn", config.alerts.tpsWarn, 0.0, 20.0, v -> config.alerts.tpsWarn = v);
        clampDouble(report, "alerts.memoryWarnFraction", config.alerts.memoryWarnFraction, 0.1, 1.0, v -> config.alerts.memoryWarnFraction = v);
        clampInt(report, "alerts.queueWarnDepth", config.alerts.queueWarnDepth, 1, 1_000_000, v -> config.alerts.queueWarnDepth = v);
        clampLong(report, "alerts.alertCooldownSeconds", config.alerts.alertCooldownSeconds, 1L, 86_400L, v -> config.alerts.alertCooldownSeconds = v);

        clampInt(report, "audit.retentionDays", config.audit.retentionDays, 1, 3_650, v -> config.audit.retentionDays = v);
        clampInt(report, "audit.securityRetentionDays", config.audit.securityRetentionDays, 1, 36_500, v -> config.audit.securityRetentionDays = v);
        clampInt(report, "audit.memoryBuffer", config.audit.memoryBuffer, 64, 1_000_000, v -> config.audit.memoryBuffer = v);
        clampInt(report, "audit.queueCapacity", config.audit.queueCapacity, 64, 1_000_000, v -> config.audit.queueCapacity = v);
        clampInt(report, "audit.maxQueryResults", config.audit.maxQueryResults, 1, 100_000, v -> config.audit.maxQueryResults = v);
        clampInt(report, "audit.batchSize", config.audit.batchSize, 1, 65_536, v -> config.audit.batchSize = v);
        clampInt(report, "audit.flushIntervalSeconds", config.audit.flushIntervalSeconds, 1, 3_600, v -> config.audit.flushIntervalSeconds = v);
        if (config.audit.categories == null) {
            config.audit.categories = new AdminConfig.Audit().categories;
        } else {
            config.audit.categories = normaliseCategories(config.audit.categories, report);
        }

        clampInt(report, "storage.level", config.storage.level, 0, 22, v -> config.storage.level = v);
        clampInt(report, "storage.compressIntervalSeconds", config.storage.compressIntervalSeconds, 10, 86_400, v -> config.storage.compressIntervalSeconds = v);
        clampInt(report, "storage.minArchiveAgeHours", config.storage.minArchiveAgeHours, 0, 8_760, v -> config.storage.minArchiveAgeHours = v);
        clampInt(report, "storage.maxDecompressBytes", config.storage.maxDecompressBytes, 1_024, 1_073_741_824, v -> config.storage.maxDecompressBytes = v);
        clampInt(report, "storage.workerThreads", config.storage.workerThreads, 1, 8, v -> config.storage.workerThreads = v);
        clampInt(report, "storage.maxQueue", config.storage.maxQueue, 1, 1_000_000, v -> config.storage.maxQueue = v);
        clampInt(report, "storage.batchSize", config.storage.batchSize, 1, 65_536, v -> config.storage.batchSize = v);
        clampInt(report, "storage.cacheEntries", config.storage.cacheEntries, 0, 1_000_000, v -> config.storage.cacheEntries = v);
        clampInt(report, "storage.cacheMaxBytes", config.storage.cacheMaxBytes, 0, 1_073_741_824, v -> config.storage.cacheMaxBytes = v);
        clampLong(report, "storage.maxArchiveBytes", config.storage.maxArchiveBytes, 65_536L, 2_147_483_648L, v -> config.storage.maxArchiveBytes = v);
        clampInt(report, "storage.maxArchives", config.storage.maxArchives, 1, 10_000, v -> config.storage.maxArchives = v);
        clampLong(report, "storage.dedupeWindowMillis", config.storage.dedupeWindowMillis, 0L, 600_000L, v -> config.storage.dedupeWindowMillis = v);
        clampDouble(report, "storage.pauseMsptThreshold", config.storage.pauseMsptThreshold, 1.0, 10_000.0, v -> config.storage.pauseMsptThreshold = v);
        String algorithm = config.storage.algorithm == null ? null : config.storage.algorithm.trim().toLowerCase(Locale.ROOT);
        if (algorithm == null || !java.util.Set.of("zstd", "gzip", "none").contains(algorithm)) {
            report.add("storage.algorithm was not one of zstd/gzip/none; using zstd");
            config.storage.algorithm = "zstd";
        } else {
            config.storage.algorithm = algorithm;
        }

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

    /**
     * Upper-cases and de-duplicates the audit category names, dropping unknown ones.
     *
     * <p>A typo in a category name would otherwise silently disable that category, so an unknown
     * name is reported and removed rather than kept.
     */
    private static List<String> normaliseCategories(List<String> values, ValidationReport report) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String name = value.trim().toUpperCase(Locale.ROOT);
            if (!AUDIT_CATEGORIES.contains(name)) {
                report.add("audit.categories contains unknown category '" + value + "'; it was ignored");
                continue;
            }
            if (!result.contains(name)) {
                result.add(name);
            }
        }
        return result;
    }

    /** The category names the audit log accepts, mirrored from the audit model. */
    private static final java.util.Set<String> AUDIT_CATEGORIES = java.util.Set.of(
            "PLAYER", "MOVEMENT", "BLOCK", "CONTAINER", "COMMAND", "MODERATION", "PERMISSION",
            "ANTICHEAT", "ANTIDUPE", "CONFIG", "SECURITY", "SERVER", "CHAT", "STAFF");

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

    private static void clampDouble(ValidationReport report, String key, double value, double min, double max,
            java.util.function.DoubleConsumer setter) {
        if (Double.isNaN(value) || value < min || value > max) {
            double replacement = Double.isNaN(value) ? min : Math.max(min, Math.min(max, value));
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
