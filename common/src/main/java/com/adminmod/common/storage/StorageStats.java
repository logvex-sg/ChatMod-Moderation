package com.adminmod.common.storage;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Running totals for the storage dashboard.
 *
 * <p>Counters are plain {@link AtomicLong}s updated from the compression worker; the dashboard
 * reads a snapshot, so a concurrent update can only ever make a number slightly stale, never
 * inconsistent. The originals-versus-compressed totals are what the dashboard shows as the space
 * saved, so they are accumulated at the moment an archive is written and never recomputed by
 * scanning the disk.
 */
public final class StorageStats {

    private final AtomicLong originalBytes = new AtomicLong();
    private final AtomicLong compressedBytes = new AtomicLong();
    private final AtomicLong archivesWritten = new AtomicLong();
    private final AtomicLong recordsCompressed = new AtomicLong();
    private final AtomicLong recordsDecompressed = new AtomicLong();
    private final AtomicLong corruptArchives = new AtomicLong();
    private final AtomicLong compressionNanos = new AtomicLong();
    private final AtomicLong bytesReclaimed = new AtomicLong();

    void recordCompression(int originalLength, int compressedLength, long nanos) {
        originalBytes.addAndGet(originalLength);
        compressedBytes.addAndGet(compressedLength);
        archivesWritten.incrementAndGet();
        compressionNanos.addAndGet(nanos);
    }

    void recordRecordsCompressed(int count) {
        recordsCompressed.addAndGet(count);
    }

    void recordDecompression() {
        recordsDecompressed.incrementAndGet();
    }

    void recordCorrupt() {
        corruptArchives.incrementAndGet();
    }

    void recordReclaimed(long bytes) {
        bytesReclaimed.addAndGet(bytes);
    }

    public long originalBytes() {
        return originalBytes.get();
    }

    public long compressedBytes() {
        return compressedBytes.get();
    }

    public long archivesWritten() {
        return archivesWritten.get();
    }

    public long recordsCompressed() {
        return recordsCompressed.get();
    }

    public long recordsDecompressed() {
        return recordsDecompressed.get();
    }

    public long corruptArchives() {
        return corruptArchives.get();
    }

    public long bytesReclaimed() {
        return bytesReclaimed.get();
    }

    /** Bytes saved by compression so far; never negative. */
    public long savedBytes() {
        return Math.max(0L, originalBytes.get() - compressedBytes.get());
    }

    /** Saved fraction in the range 0..1, or 0 when nothing has been compressed yet. */
    public double savedRatio() {
        long original = originalBytes.get();
        if (original <= 0L) {
            return 0.0;
        }
        return (double) savedBytes() / (double) original;
    }

    /** Mean compression time per archive in milliseconds, or 0 when none has run. */
    public double averageCompressionMillis() {
        long count = archivesWritten.get();
        if (count <= 0L) {
            return 0.0;
        }
        return (compressionNanos.get() / 1_000_000.0) / count;
    }

    /** Resets every counter; used by tests and by a manual storage reset. */
    public void reset() {
        originalBytes.set(0L);
        compressedBytes.set(0L);
        archivesWritten.set(0L);
        recordsCompressed.set(0L);
        recordsDecompressed.set(0L);
        corruptArchives.set(0L);
        compressionNanos.set(0L);
        bytesReclaimed.set(0L);
    }
}
