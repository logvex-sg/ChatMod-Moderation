package com.adminmod.common.storage;

import com.adminmod.common.compress.CodecId;
import com.adminmod.common.compress.CompressionRegistry;

/**
 * The storage settings the {@link CompressedLogStore} needs, as an immutable snapshot.
 *
 * <p>The store takes this rather than the live configuration so it can be unit tested without a
 * config file, and so a hot reload can hand the store a new snapshot without the store having to
 * know how the configuration is loaded.
 */
public record StorageSettings(
        boolean enabled,
        CodecId algorithm,
        int level,
        int compressIntervalSeconds,
        int minArchiveAgeHours,
        int maxDecompressBytes,
        int maxQueue,
        int batchSize,
        int cacheEntries,
        int cacheMaxBytes,
        long maxArchiveBytes,
        int maxArchives,
        boolean deduplicate,
        long dedupeWindowMillis,
        boolean pauseDuringLag,
        double pauseMsptThreshold) {

    /**
     * Builds a snapshot from the configuration's storage section.
     *
     * <p>The configured algorithm is resolved through the registry, so a host without the Zstandard
     * native library transparently falls back to GZIP rather than failing on the first archive.
     */
    public static StorageSettings from(com.adminmod.common.config.AdminConfig.Storage storage) {
        CodecId requested = CompressionRegistry.parse(storage.algorithm, CodecId.ZSTD);
        CodecId resolved = CompressionRegistry.codec(requested).available() ? requested : CodecId.GZIP;
        return new StorageSettings(
                storage.enabled,
                resolved,
                storage.level,
                storage.compressIntervalSeconds,
                storage.minArchiveAgeHours,
                storage.maxDecompressBytes,
                storage.maxQueue,
                storage.batchSize,
                storage.cacheEntries,
                storage.cacheMaxBytes,
                storage.maxArchiveBytes,
                storage.maxArchives,
                storage.deduplicate,
                storage.dedupeWindowMillis,
                storage.pauseDuringLag,
                storage.pauseMsptThreshold);
    }
}
