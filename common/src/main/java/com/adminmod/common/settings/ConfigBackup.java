package com.adminmod.common.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies the configuration aside before anything rewrites it.
 *
 * <p>The one moment a hand-edited configuration can be lost is when ADMIN rewrites it: an upgrade
 * migrating the schema, an imported profile replacing values, a reset. This class takes a timestamped
 * copy into {@code config/admin/backups} first, so those operations are always reversible and a
 * custom configuration is never overwritten silently.
 *
 * <p>Backups are pruned to a configurable maximum so the directory cannot grow without bound.
 */
public final class ConfigBackup {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path backupDirectory;
    private final int maxBackups;

    public ConfigBackup(Path backupDirectory, int maxBackups) {
        this.backupDirectory = backupDirectory;
        this.maxBackups = Math.max(1, maxBackups);
    }

    /**
     * Copies every {@code .json} file in {@code configDirectory} into a new timestamped folder.
     *
     * <p>A folder per backup rather than a file per setting, so a set of module files is restored
     * together and a half-restored configuration cannot result.
     *
     * @param reason short label recorded in the folder name, e.g. {@code migration} or {@code import}
     * @return the folder that was written, or {@code null} when there was nothing to back up
     */
    public Path snapshot(Path configDirectory, String reason) throws IOException {
        if (Files.notExists(configDirectory)) {
            return null;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(configDirectory)) {
            files = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .toList();
        }
        if (files.isEmpty()) {
            return null;
        }
        Files.createDirectories(backupDirectory);
        Path target = backupDirectory.resolve(STAMP.format(Instant.now()) + "-" + sanitise(reason));
        Files.createDirectories(target);
        for (Path file : files) {
            Files.copy(file, target.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        prune();
        return target;
    }

    /** The existing backups, newest first. */
    public List<Path> list() {
        if (Files.notExists(backupDirectory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(backupDirectory)) {
            List<Path> result = new ArrayList<>(stream.filter(Files::isDirectory).toList());
            result.sort(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed());
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Removes the oldest backups beyond the configured maximum. */
    public void prune() {
        List<Path> backups = list();
        for (int i = maxBackups; i < backups.size(); i++) {
            deleteRecursively(backups.get(i));
        }
    }

    private static void deleteRecursively(Path directory) {
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort; a file that cannot be removed is retried on the next prune.
                }
            });
        } catch (IOException ignored) {
            // As above.
        }
    }

    private static String sanitise(String reason) {
        if (reason == null || reason.isBlank()) {
            return "manual";
        }
        return reason.replaceAll("[^a-zA-Z0-9_-]", "").substring(0, Math.min(24, reason.length()));
    }

    public Path directory() {
        return backupDirectory;
    }
}
