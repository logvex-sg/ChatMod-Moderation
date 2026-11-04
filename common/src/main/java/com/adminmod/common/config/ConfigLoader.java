package com.adminmod.common.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Reads and writes {@code config/admin-mod.json}. A missing file is materialised with the
 * documented defaults, and the file is written owner-only so webhook URLs and access tokens
 * are not world readable on a shared host.
 */
public final class ConfigLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigLoader() {
    }

    public static AdminConfig load(Path file) throws IOException {
        if (Files.notExists(file)) {
            AdminConfig defaults = new AdminConfig();
            save(file, defaults);
            return defaults;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            AdminConfig config = GSON.fromJson(reader, AdminConfig.class);
            if (config == null) {
                config = new AdminConfig();
            }
            config = normalise(config);
            return config;
        }
    }

    public static void save(Path file, AdminConfig config) throws IOException {
        Files.createDirectories(file.getParent());
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        restrict(file);
    }

    /** Fills in structures that were absent from an older configuration file. */
    private static AdminConfig normalise(AdminConfig config) {
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
        if (config.chatBridge.inbound == null) {
            config.chatBridge.inbound = new AdminConfig.Inbound();
        }
        if (config.chatBridge.inbound.discord == null) {
            config.chatBridge.inbound.discord = new AdminConfig.DiscordInbound();
        }
        if (config.chatBridge.inbound.matrix == null) {
            config.chatBridge.inbound.matrix = new AdminConfig.MatrixInbound();
        }
        if (config.moderation == null) {
            config.moderation = new AdminConfig.Moderation();
        }
        if (config.remoteAdmin == null) {
            config.remoteAdmin = new AdminConfig.RemoteAdmin();
        }
        if (config.permissions == null) {
            config.permissions = new AdminConfig.Permissions();
        }
        if (config.permissions.moderators == null) {
            config.permissions.moderators = java.util.List.of();
        }
        if (config.chatBridge.queueCapacity < 16) {
            config.chatBridge.queueCapacity = 16;
        }
        if (config.chatBridge.maxAttempts < 1) {
            config.chatBridge.maxAttempts = 1;
        }
        if (config.chatBridge.requestTimeoutMillis < 1000) {
            config.chatBridge.requestTimeoutMillis = 1000;
        }
        if (config.chatBridge.inbound.format == null || config.chatBridge.inbound.format.isBlank()) {
            config.chatBridge.inbound.format = "[%platform%] %user%: %message%";
        }
        if (config.chatBridge.inbound.dedupeWindowMillis < 0L) {
            config.chatBridge.inbound.dedupeWindowMillis = 0L;
        }
        if (config.chatBridge.inbound.maxMessageLength < 16) {
            config.chatBridge.inbound.maxMessageLength = 512;
        }
        if (config.chatBridge.inbound.matrix.syncTimeoutMillis < 1000) {
            config.chatBridge.inbound.matrix.syncTimeoutMillis = 30_000;
        }
        if (config.chatBridge.inbound.matrix.syncLimit < 1) {
            config.chatBridge.inbound.matrix.syncLimit = 50;
        }
        if (config.remoteAdmin.requestsPerMinute < 1) {
            config.remoteAdmin.requestsPerMinute = 1;
        }
        return config;
    }

    private static void restrict(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system; the parent directory permission is the remaining guard.
        }
    }
}
