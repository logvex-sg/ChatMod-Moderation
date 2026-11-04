package com.adminmod.common.localchat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import com.adminmod.common.config.AdminConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Reads and writes the standalone {@code config/admin/localchat.json} file.
 *
 * <p>The proximity settings live in their own file so an operator can tune the feature without
 * editing the large {@code admin-mod.json}. The in-memory shape is {@link
 * AdminConfig.LocalChat}, which {@code admin-mod.json} also carries for compatibility; the file is
 * the source of truth when it exists, and a missing or malformed file is replaced with the
 * supplied defaults so the server always starts with usable settings.
 */
public final class LocalChatFile {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private LocalChatFile() {
    }

    /**
     * Loads the proximity settings, writing the supplied defaults when the file is absent.
     *
     * @return the settings to use, never {@code null}
     */
    public static AdminConfig.LocalChat load(Path file, AdminConfig.LocalChat defaults) {
        AdminConfig.LocalChat fallback = defaults == null ? new AdminConfig.LocalChat() : defaults;
        if (Files.notExists(file)) {
            try {
                save(file, fallback);
            } catch (IOException ignored) {
                // A read-only config directory must not stop the server; defaults stay in memory.
            }
            return fallback;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            AdminConfig.LocalChat parsed = GSON.fromJson(reader, AdminConfig.LocalChat.class);
            if (parsed == null) {
                return fallback;
            }
            normalise(parsed, fallback);
            return parsed;
        } catch (IOException | RuntimeException e) {
            return fallback;
        }
    }

    public static void save(Path file, AdminConfig.LocalChat config) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(config, writer);
        }
        restrict(file);
    }

    /**
     * Fills in values a partially written file may have left null.
     *
     * <p>Gson leaves a missing field at its class default only when the whole object is present, so
     * a hand-edited file can still arrive with a null list or a null string. Anything unusable is
     * replaced from the fallback.
     */
    private static void normalise(AdminConfig.LocalChat parsed, AdminConfig.LocalChat fallback) {
        if (parsed.globalPrefix == null) {
            parsed.globalPrefix = fallback.globalPrefix;
        }
        if (parsed.localFormat == null || parsed.localFormat.isBlank()) {
            parsed.localFormat = fallback.localFormat;
        }
        if (parsed.globalFormat == null || parsed.globalFormat.isBlank()) {
            parsed.globalFormat = fallback.globalFormat;
        }
        if (parsed.soundId == null || parsed.soundId.isBlank()) {
            parsed.soundId = fallback.soundId;
        }
        if (parsed.noRecipientsMessage == null) {
            parsed.noRecipientsMessage = fallback.noRecipientsMessage;
        }
        if (parsed.dimensions == null) {
            parsed.dimensions = fallback.dimensions == null ? java.util.List.of() : fallback.dimensions;
        }
        if (parsed.radius <= 0) {
            parsed.radius = fallback.radius;
        }
        if (parsed.staffLevel < 0) {
            parsed.staffLevel = fallback.staffLevel;
        }
        if (parsed.soundVolume <= 0 || parsed.soundVolume > 4) {
            parsed.soundVolume = fallback.soundVolume;
        }
        if (parsed.soundPitch <= 0 || parsed.soundPitch > 4) {
            parsed.soundPitch = fallback.soundPitch;
        }
        if (parsed.cooldownMillis < 0) {
            parsed.cooldownMillis = fallback.cooldownMillis;
        }
    }

    private static void restrict(Path file) {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system; nothing further can be enforced here.
        }
    }
}
