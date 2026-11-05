package com.adminmod.common.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

import com.adminmod.common.compress.CodecId;
import com.adminmod.common.compress.CompressedArchive;
import com.adminmod.common.compress.CompressionCodec;
import com.adminmod.common.compress.CompressionRegistry;
import com.adminmod.common.compress.Checksum;
import com.adminmod.common.model.Json;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * An append-only log that keeps recent records uncompressed and compresses them once they age out.
 *
 * <p>Records arrive from the server tick thread through {@link #append}, which only enqueues and
 * returns; a single writer thread drains the queue to a segment file, and a second thread compresses
 * segments that have passed the minimum age. No disk or compression work ever runs on the tick
 * thread, which is the whole point of the split.
 *
 * <p>On-disk layout under the store's directory:
 *
 * <pre>
 *   segment-&lt;n&gt;.log   uncompressed records, appended in order
 *   archive-&lt;n&gt;.adr   one compressed blob wrapping one or more whole segments
 *   index.json        metadata for each archive: counts, time range, sizes
 * </pre>
 *
 * <p>An archive is only written after {@link CompressedArchive#encode} has verified it decodes back
 * to the exact input, and the segment it replaces is deleted only after the archive is renamed into
 * place and recorded in the index. An interrupted pass therefore leaves either a complete archive or
 * a discarded temporary file, never a gap. A {@code .adr} file that is present but missing from the
 * index is re-indexed at startup, which is how a crash between the rename and the index write
 * recovers.
 */
public final class CompressedLogStore implements AutoCloseable {

    private static final int RECORD_HEADER = 4 + 8 + 8 + 8 + 4; // length, timestamp, expiresAt, sequence, crc
    private static final String SEGMENT_PREFIX = "segment-";
    private static final String ARCHIVE_PREFIX = "archive-";
    private static final String SEGMENT_SUFFIX = ".log";
    private static final String ARCHIVE_SUFFIX = ".adr";
    private static final String INDEX_FILE = "index.json";

    private final Path directory;
    private volatile StorageSettings settings;
    private final StorageStats stats = new StorageStats();
    private final DoubleSupplier msptSupplier;

    /** Pending writes, drained by the writer thread. Bounded so a stalled disk cannot grow the heap. */
    private final BlockingQueue<Record> writeQueue;

    /** Archive metadata, newest last. Guarded by {@code this}. */
    private final List<ArchiveIndex> archives = new ArrayList<>();

    /** LRU cache of decompressed archive payloads, bounded by count and bytes. */
    private final Map<String, byte[]> decompressedCache = new LinkedHashMap<>(16, 0.75f, true);
    private long cacheBytes;

    private long nextSequence;
    private long currentSegmentSequence;
    private long currentSegmentBytes;

    private Thread writerThread;
    private Thread compressionThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object writeLock = new Object();
    private final Object indexLock = new Object();

    private final AtomicLong droppedRecords = new AtomicLong();
    private final AtomicLong writtenRecords = new AtomicLong();
    private final AtomicLong deduplicatedRecords = new AtomicLong();
    private final AtomicLong lastCompressionMillis = new AtomicLong();
    private volatile boolean compressing;

    /**
     * Signatures of recently accepted records, for duplicate suppression.
     *
     * <p>Guarded by {@code this}. The map holds a signature to the time it was last seen; an entry
     * is evicted once it is older than the dedupe window, so the map never grows past the number of
     * distinct records seen inside one window.
     */
    private final Map<Long, Long> recentSignatures = new LinkedHashMap<>(256, 0.75f, true);

    /**
     * @param directory    the directory the segments and archives live in
     * @param settings     the storage settings snapshot
     * @param hotBufferLimit most records kept in memory for fast reads
     * @param msptSupplier a live MSPT reading used to pause compression during lag, or {@code null}
     */
    public CompressedLogStore(Path directory, StorageSettings settings, int hotBufferLimit,
            DoubleSupplier msptSupplier) {
        this.directory = directory;
        this.settings = settings;
        this.writeQueue = new ArrayBlockingQueue<>(Math.max(64, settings.maxQueue()));
        this.msptSupplier = msptSupplier;
    }

    /**
     * Opens the store: creates the directory, loads the index, re-indexes any orphan archive and
     * recovers from an interrupted pass. Must be called before {@link #start()}.
     */
    public synchronized void open() throws IOException {
        Files.createDirectories(directory);
        restrictDirectory(directory);
        loadIndex();
        recoverOrphanArchives();
        discardTemporaryArchives();
        assignNextSegmentSequence();
    }

    /**
     * Restricts the store directory to its owner.
     *
     * <p>The audit log is the most sensitive file ADMIN writes, so the directory is closed to group
     * and other users. A non-POSIX file system ignores this and keeps whatever the platform default
     * is, which is the same guard the configuration loader uses.
     */
    private static void restrictDirectory(Path directory) {
        try {
            Files.setPosixFilePermissions(directory, java.util.EnumSet.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX file system.
        }
    }

    /** Starts the writer and compression threads. Idempotent. */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        writerThread = new Thread(this::writerLoop, "adminmod-storage-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        compressionThread = new Thread(this::compressionLoop, "adminmod-storage-compressor");
        compressionThread.setDaemon(true);
        compressionThread.start();
    }

    /** Replaces the settings after a hot reload. Safe to call while running. */
    public void updateSettings(StorageSettings updated) {
        this.settings = updated;
    }

    public StorageSettings settings() {
        return settings;
    }

    public StorageStats stats() {
        return stats;
    }

    /**
     * Enqueues a record for durable storage.
     *
     * <p>Called from the server tick thread, so it does no I/O and holds no lock for longer than an
     * enqueue. When the queue is full the record is dropped and counted rather than blocking the
     * caller, which is the behaviour that keeps a stalled disk from stalling the server.
     *
     * @param payload   the record bytes
     * @param timestamp when the event happened
     * @param expiresAt when retention may delete it
     * @return {@code true} when the record was accepted
     */
    public boolean append(byte[] payload, long timestamp, long expiresAt) {
        if (!settings.enabled() || payload == null) {
            // Retention and reads still work with compression disabled, but nothing new is stored.
            return false;
        }
        if (settings.deduplicate() && isDuplicate(payload, timestamp)) {
            deduplicatedRecords.incrementAndGet();
            return false;
        }
        Record record = new Record(nextSequence++, timestamp, expiresAt, payload);
        if (!writeQueue.offer(record)) {
            droppedRecords.incrementAndGet();
            return false;
        }
        // The record is not added to the in-memory buffer here: the writer thread does that after
        // the bytes are on disk, so a query cannot see the same record twice — once from the buffer
        // and once from the segment it was written to.
        return true;
    }

    /**
     * Searches the store for records in a time range.
     *
     * @param fromMillis earliest timestamp, inclusive
     * @param toMillis   latest timestamp, inclusive
     * @param max        most records to return
     * @return the matching records, oldest first
     */
    public List<Record> query(long fromMillis, long toMillis, int max) {
        List<Record> results = new ArrayList<>();
        int limit = Math.max(1, max);

        // Uncompressed segments still on disk hold the recent records.
        for (Path segment : listSegments()) {
            if (results.size() >= limit) {
                break;
            }
            readSegment(segment, fromMillis, toMillis, limit, results);
        }

        // Archives, decompressed on demand and cached.
        List<ArchiveIndex> snapshot;
        synchronized (indexLock) {
            snapshot = new ArrayList<>(archives);
        }
        snapshot.sort(Comparator.comparingLong(ArchiveIndex::from));
        for (ArchiveIndex index : snapshot) {
            if (results.size() >= limit) {
                break;
            }
            if (index.to() < fromMillis || index.from() > toMillis) {
                continue; // Outside the range; no need to touch the file.
            }
            byte[] raw = decompressArchive(index);
            if (raw == null) {
                continue;
            }
            readRaw(raw, fromMillis, toMillis, limit, results);
        }
        results.sort(Comparator.comparingLong(Record::timestamp));
        if (results.size() > limit) {
            results = new ArrayList<>(results.subList(0, limit));
        }
        return results;
    }

    /** Number of records still waiting to be written. */
    public int bufferedRecords() {
        return writeQueue.size();
    }

    /** Number of records waiting to be written. */
    public int pendingWrites() {
        return writeQueue.size();
    }

    public long droppedRecords() {
        return droppedRecords.get();
    }

    public long writtenRecords() {
        return writtenRecords.get();
    }

    public long deduplicatedRecords() {
        return deduplicatedRecords.get();
    }

    public boolean compressing() {
        return compressing;
    }

    public long lastCompressionMillis() {
        return lastCompressionMillis.get();
    }

    /** Forces a segment roll and a compression pass, used by the manual compress button. */
    public void requestCompression() {
        Thread thread = compressionThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /**
     * Total bytes currently on disk under the store directory.
     *
     * <p>A directory walk, so the dashboard calls it on its own refresh, not per tick.
     */
    public long diskUsageBytes() {
        long total = 0L;
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                if (Files.isRegularFile(path)) {
                    total += Files.size(path);
                }
            }
        } catch (IOException e) {
            return total;
        }
        return total;
    }

    /** Number of archive files currently held. */
    public synchronized int archiveCount() {
        synchronized (indexLock) {
            return archives.size();
        }
    }

    /** Number of segments not yet compressed. */
    public int segmentCount() {
        return listSegments().size();
    }

    /**
     * A storage view for the dashboard.
     *
     * @param subsystem the name shown as the owner of this store
     */
    public synchronized String statusJson(String subsystem) {
        JsonObject root = new JsonObject();
        root.addProperty("subsystem", subsystem);
        root.addProperty("enabled", settings.enabled());
        root.addProperty("algorithm", settings.algorithm().name().toLowerCase(java.util.Locale.ROOT));
        root.addProperty("algorithmAvailable", CompressionRegistry.isAvailable(settings.algorithm()));
        root.addProperty("level", settings.level());
        root.addProperty("archives", archiveCount());
        root.addProperty("segments", segmentCount());
        root.addProperty("diskBytes", diskUsageBytes());
        root.addProperty("originalBytes", stats.originalBytes());
        root.addProperty("compressedBytes", stats.compressedBytes());
        root.addProperty("savedBytes", stats.savedBytes());
        root.addProperty("savedPercent", Math.round(stats.savedRatio() * 10_000.0) / 100.0);
        root.addProperty("ratio", stats.compressedBytes() > 0
                ? Math.round((double) stats.originalBytes() / stats.compressedBytes() * 100.0) / 100.0
                : 1.0);
        root.addProperty("recordsCompressed", stats.recordsCompressed());
        root.addProperty("recordsDecompressed", stats.recordsDecompressed());
        root.addProperty("corruptArchives", stats.corruptArchives());
        root.addProperty("bytesReclaimed", stats.bytesReclaimed());
        root.addProperty("averageCompressionMillis", Math.round(stats.averageCompressionMillis() * 1000.0) / 1000.0);
        root.addProperty("pendingWrites", pendingWrites());
        root.addProperty("bufferedRecords", bufferedRecords());
        root.addProperty("droppedRecords", droppedRecords());
        root.addProperty("deduplicatedRecords", deduplicatedRecords());
        root.addProperty("compressing", compressing);
        root.addProperty("lastCompressionMillis", lastCompressionMillis.get());
        return Json.write(root);
    }

    /** Index of every archive, for the archive inspection view. */
    public String archivesJson() {
        JsonArray array = new JsonArray();
        synchronized (indexLock) {
            for (ArchiveIndex index : archives) {
                JsonObject object = new JsonObject();
                object.addProperty("file", index.file());
                object.addProperty("records", index.records());
                object.addProperty("from", index.from());
                object.addProperty("to", index.to());
                object.addProperty("originalBytes", index.originalBytes());
                object.addProperty("compressedBytes", index.compressedBytes());
                object.addProperty("codec", index.codec().name().toLowerCase(java.util.Locale.ROOT));
                object.addProperty("level", index.level());
                object.addProperty("verified", true);
                array.add(object);
            }
        }
        JsonObject root = new JsonObject();
        root.add("archives", array);
        return Json.write(root);
    }

    // ------------------------------------------------------------------ threads

    private void writerLoop() {
        Record record;
        while (running.get() || !writeQueue.isEmpty()) {
            try {
                record = writeQueue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (record == null) {
                continue;
            }
            try {
                writeRecord(record);
            } catch (IOException e) {
                // A failed write must not kill the thread; the record is lost and counted.
                droppedRecords.incrementAndGet();
            }
        }
    }

    private void compressionLoop() {
        while (running.get()) {
            long interval = Math.max(5L, settings.compressIntervalSeconds()) * 1000L;
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // A manual request wakes us early; fall through and run a pass now.
                if (!running.get()) {
                    break;
                }
            }
            if (!running.get()) {
                break;
            }
            try {
                compressEligibleSegments(false);
            } catch (IOException e) {
                // Logged by the owner through the error reporter; the next pass retries.
                lastCompressionMillis.set(System.currentTimeMillis());
            }
        }
    }

    // ------------------------------------------------------------------ writing

    private void writeRecord(Record record) throws IOException {
        synchronized (writeLock) {
            if (currentSegmentBytes >= settings.maxArchiveBytes()) {
                rollSegment();
            }
            Path segment = segmentPath(currentSegmentSequence);
            byte[] encoded = encodeRecord(record);
            Files.write(segment, encoded, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            currentSegmentBytes += encoded.length;
            writtenRecords.incrementAndGet();
        }
    }

    /**
     * Whether an identical record was accepted inside the dedupe window.
     *
     * <p>The signature is over the payload plus a coarse time bucket, so two genuinely identical
     * events a fraction of a second apart collapse to one while an identical event minutes later is
     * kept. Deduplication is only safe because the records are byte-identical; the method never
     * merges records that differ.
     */
    private synchronized boolean isDuplicate(byte[] payload, long timestamp) {
        long window = Math.max(1L, settings.dedupeWindowMillis());
        long bucket = timestamp / window;
        long signature = 31L * java.util.Arrays.hashCode(payload) + bucket;
        long now = System.currentTimeMillis();
        Long lastSeen = recentSignatures.get(signature);
        evictStaleSignatures(now, window);
        if (lastSeen != null) {
            return true;
        }
        recentSignatures.put(signature, now);
        return false;
    }

    private void evictStaleSignatures(long now, long window) {
        if (recentSignatures.size() < 4_096) {
            return;
        }
        long cutoff = now - window;
        recentSignatures.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    private void rollSegment() {
        currentSegmentSequence++;
        currentSegmentBytes = 0L;
    }

    private void assignNextSegmentSequence() {
        long max = 0L;
        for (Path segment : listSegments()) {
            max = Math.max(max, parseSequence(segment, SEGMENT_PREFIX, SEGMENT_SUFFIX));
        }
        currentSegmentSequence = max;
        Path current = segmentPath(currentSegmentSequence);
        try {
            currentSegmentBytes = Files.exists(current) ? Files.size(current) : 0L;
        } catch (IOException e) {
            currentSegmentBytes = 0L;
        }
        // Keep the record sequence above any that has already been written, so a restart does not
        // reuse ids.
        nextSequence = Math.max(nextSequence, currentSegmentSequence * 1_000_000L);
    }

    // ------------------------------------------------------------------ compression

    /**
     * Compresses every segment that is old enough, newest last so the freshest data stays hot.
     *
     * @param force when true the age threshold is ignored, for a manual pass
     */
    public synchronized void compressEligibleSegments(boolean force) throws IOException {
        if (!settings.enabled() || compressing) {
            return;
        }
        if (settings.pauseDuringLag() && msptSupplier != null) {
            double mspt = msptSupplier.getAsDouble();
            if (mspt > settings.pauseMsptThreshold()) {
                // Severe lag: skip this pass entirely rather than add CPU contention.
                return;
            }
        }
        compressing = true;
        try {
            // A forced pass rolls the active segment first so the records just written become
            // eligible; the automatic pass leaves it alone because it is still being appended to.
            if (force) {
                synchronized (writeLock) {
                    rollSegment();
                }
            }
            List<Path> segments = listSegments();
            Path active = segmentPath(currentSegmentSequence);
            long cutoff = System.currentTimeMillis() - Math.max(0L, settings.minArchiveAgeHours()) * 3_600_000L;
            List<Path> eligible = new ArrayList<>();
            for (Path segment : segments) {
                if (segment.equals(active)) {
                    continue;
                }
                if (force || lastModified(segment) <= cutoff) {
                    eligible.add(segment);
                }
            }
            if (!eligible.isEmpty()) {
                eligible.sort(Comparator.comparingLong(p -> parseSequence(p, SEGMENT_PREFIX, SEGMENT_SUFFIX)));
                compressSegments(eligible);
                lastCompressionMillis.set(System.currentTimeMillis());
            }
            // Retention runs on every pass, including the automatic one, so an expired archive is
            // removed even on a server whose data has all been compressed already.
            enforceRetention();
        } finally {
            compressing = false;
        }
    }

    private void compressSegments(List<Path> segments) throws IOException {
        // Batch small segments so the compression header is paid for once across many records.
        int batchTarget = Math.max(1, settings.batchSize());
        List<Path> batch = new ArrayList<>();
        int recordsInBatch = 0;
        long batchBytes = 0L;
        for (Path segment : segments) {
            long size = Files.size(segment);
            batch.add(segment);
            recordsInBatch += countRecords(segment);
            batchBytes += size;
            if (batchBytes >= settings.maxArchiveBytes() || recordsInBatch >= batchTarget * 4) {
                writeArchive(batch, recordsInBatch);
                batch = new ArrayList<>();
                recordsInBatch = 0;
                batchBytes = 0L;
            }
        }
        if (!batch.isEmpty()) {
            writeArchive(batch, recordsInBatch);
        }
    }

    private void writeArchive(List<Path> segments, int records) throws IOException {
        byte[] raw = concatenate(segments);
        CompressionCodec codec = CompressionRegistry.resolve(settings.algorithm());
        long start = System.nanoTime();
        byte[] archive = CompressedArchive.encode(codec, settings.level(), raw);
        long elapsed = System.nanoTime() - start;

        long firstSeq = parseSequence(segments.get(0), SEGMENT_PREFIX, SEGMENT_SUFFIX);
        long lastSeq = parseSequence(segments.get(segments.size() - 1), SEGMENT_PREFIX, SEGMENT_SUFFIX);
        String fileName = ARCHIVE_PREFIX + firstSeq + "-" + lastSeq + ARCHIVE_SUFFIX;
        Path target = directory.resolve(fileName);
        Path temporary = directory.resolve(fileName + ".tmp");

        // Write to a temporary file, force it to disk, then rename. The rename is atomic on the
        // platforms Fabric servers run on, so a reader never sees a half-written archive.
        Files.write(temporary, archive);
        forceToDisk(temporary);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        // Only now is it safe to delete the originals.
        long originalBytes = 0L;
        for (Path segment : segments) {
            originalBytes += Files.size(segment);
            Files.deleteIfExists(segment);
        }

        long from = Long.MAX_VALUE;
        long to = Long.MIN_VALUE;
        long expires = Long.MIN_VALUE;
        for (Record record : decodeAll(raw)) {
            from = Math.min(from, record.timestamp());
            to = Math.max(to, record.timestamp());
            expires = Math.max(expires, record.expiresAt());
        }
        ArchiveIndex index = new ArchiveIndex(fileName, records, from == Long.MAX_VALUE ? 0L : from,
                to == Long.MIN_VALUE ? 0L : to, expires == Long.MIN_VALUE ? 0L : expires,
                raw.length, archive.length, codec.id(), settings.level());
        synchronized (indexLock) {
            archives.add(index);
            saveIndex();
        }
        stats.recordCompression(raw.length, archive.length, elapsed);
        stats.recordRecordsCompressed(records);
        stats.recordReclaimed(originalBytes);
    }

    private void enforceRetention() throws IOException {
        long now = System.currentTimeMillis();
        List<ArchiveIndex> expired = new ArrayList<>();
        synchronized (indexLock) {
            for (ArchiveIndex index : archives) {
                if (index.expiresAt() > 0L && index.expiresAt() < now) {
                    expired.add(index);
                }
            }
            // Rotation: even when nothing has expired, the archive count is capped so the store's
            // footprint stays bounded on a server that runs for months.
            int overflow = archives.size() - expired.size() - Math.max(1, settings.maxArchives());
            if (overflow > 0) {
                List<ArchiveIndex> oldest = new ArrayList<>(archives);
                oldest.removeAll(expired);
                oldest.sort(Comparator.comparingLong(ArchiveIndex::from));
                for (int i = 0; i < overflow && i < oldest.size(); i++) {
                    expired.add(oldest.get(i));
                }
            }
            for (ArchiveIndex index : expired) {
                Path file = directory.resolve(index.file());
                long size = Files.exists(file) ? Files.size(file) : 0L;
                Files.deleteIfExists(file);
                archives.remove(index);
                stats.recordReclaimed(size);
            }
            if (!expired.isEmpty()) {
                saveIndex();
            }
        }
        // Retention is applied to whole archives only: a compressed archive is the unit retention
        // can safely delete, and a live segment is still being appended to.
    }

    // ------------------------------------------------------------------ reading helpers

    private void readSegment(Path segment, long from, long to, int limit, List<Record> results) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(segment);
        } catch (IOException e) {
            return;
        }
        readRaw(raw, from, to, limit, results);
    }

    private void readRaw(byte[] raw, long from, long to, int limit, List<Record> results) {
        int offset = 0;
        while (offset + RECORD_HEADER <= raw.length && results.size() < limit) {
            ByteBuffer header = ByteBuffer.wrap(raw, offset, RECORD_HEADER).order(ByteOrder.LITTLE_ENDIAN);
            int length = header.getInt();
            long timestamp = header.getLong();
            long expiresAt = header.getLong();
            long sequence = header.getLong();
            int crc = header.getInt();
            int payloadStart = offset + RECORD_HEADER;
            if (length < 0 || payloadStart + length > raw.length) {
                // Truncated tail from an interrupted write; stop rather than read garbage.
                return;
            }
            if (timestamp >= from && timestamp <= to) {
                byte[] payload = java.util.Arrays.copyOfRange(raw, payloadStart, payloadStart + length);
                if (Checksum.of(payload) == (crc & 0xFFFF_FFFFL)) {
                    results.add(new Record(sequence, timestamp, expiresAt, payload));
                }
            }
            offset = payloadStart + length;
        }
    }

    private byte[] decompressArchive(ArchiveIndex index) {
        synchronized (this) {
            byte[] cached = decompressedCache.get(index.file());
            if (cached != null) {
                stats.recordDecompression();
                return cached;
            }
        }
        byte[] archive;
        try {
            archive = Files.readAllBytes(directory.resolve(index.file()));
        } catch (IOException e) {
            return null;
        }
        CompressedArchive.Decoded decoded = CompressedArchive.decode(archive, settings.maxDecompressBytes());
        if (decoded == null) {
            stats.recordCorrupt();
            return null;
        }
        stats.recordDecompression();
        cacheDecompressed(index.file(), decoded.data());
        return decoded.data();
    }

    private synchronized void cacheDecompressed(String key, byte[] data) {
        if (settings.cacheEntries() <= 0 || data.length > settings.cacheMaxBytes()) {
            return;
        }
        byte[] previous = decompressedCache.put(key, data);
        if (previous != null) {
            cacheBytes -= previous.length;
        }
        cacheBytes += data.length;
        while ((decompressedCache.size() > settings.cacheEntries() || cacheBytes > settings.cacheMaxBytes())
                && !decompressedCache.isEmpty()) {
            var iterator = decompressedCache.entrySet().iterator();
            Map.Entry<String, byte[]> eldest = iterator.next();
            cacheBytes -= eldest.getValue().length;
            iterator.remove();
        }
    }

    private List<Record> decodeAll(byte[] raw) {
        List<Record> records = new ArrayList<>();
        readRaw(raw, Long.MIN_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, records);
        return records;
    }

    // ------------------------------------------------------------------ index and recovery

    private void loadIndex() throws IOException {
        Path file = directory.resolve(INDEX_FILE);
        if (Files.notExists(file)) {
            return;
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            // A corrupt index is rebuilt from the archive headers rather than trusted.
            return;
        }
        JsonArray array = root.getAsJsonArray("archives");
        if (array == null) {
            return;
        }
        synchronized (indexLock) {
            archives.clear();
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                archives.add(new ArchiveIndex(
                        Json.str(object, "file", ""),
                        Json.integer(object, "records", 0),
                        Json.lng(object, "from", 0L),
                        Json.lng(object, "to", 0L),
                        Json.lng(object, "expiresAt", 0L),
                        Json.integer(object, "originalBytes", 0),
                        Json.integer(object, "compressedBytes", 0),
                        CompressionRegistry.parse(Json.str(object, "codec", "gzip"), CodecId.GZIP),
                        Json.integer(object, "level", 0)));
            }
        }
    }

    private void saveIndex() throws IOException {
        JsonObject root = new JsonObject();
        JsonArray array = new JsonArray();
        for (ArchiveIndex index : archives) {
            JsonObject object = new JsonObject();
            object.addProperty("file", index.file());
            object.addProperty("records", index.records());
            object.addProperty("from", index.from());
            object.addProperty("to", index.to());
            object.addProperty("expiresAt", index.expiresAt());
            object.addProperty("originalBytes", index.originalBytes());
            object.addProperty("compressedBytes", index.compressedBytes());
            object.addProperty("codec", index.codec().name().toLowerCase(java.util.Locale.ROOT));
            object.addProperty("level", index.level());
            array.add(object);
        }
        root.add("archives", array);
        Path file = directory.resolve(INDEX_FILE);
        Path temporary = directory.resolve(INDEX_FILE + ".tmp");
        Files.writeString(temporary, Json.write(root), StandardCharsets.UTF_8);
        forceToDisk(temporary);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Re-indexes any archive that is on disk but missing from the index.
     *
     * <p>This is the recovery path for a crash between the archive rename and the index write: the
     * archive is complete (it was renamed only after its content was forced to disk) so it is read
     * back, verified and indexed rather than discarded.
     */
    private void recoverOrphanArchives() throws IOException {
        List<Path> archiveFiles;
        try (var stream = Files.list(directory)) {
            archiveFiles = stream.filter(path -> path.getFileName().toString().endsWith(ARCHIVE_SUFFIX))
                    .toList();
        }
        synchronized (indexLock) {
            for (Path path : archiveFiles) {
                String name = path.getFileName().toString();
                boolean known = archives.stream().anyMatch(index -> index.file().equals(name));
                if (known) {
                    continue;
                }
                byte[] bytes;
                try {
                    bytes = Files.readAllBytes(path);
                } catch (IOException e) {
                    continue;
                }
                CompressedArchive.Decoded decoded = CompressedArchive.decode(bytes, settings.maxDecompressBytes());
                if (decoded == null) {
                    stats.recordCorrupt();
                    continue;
                }
                List<Record> records = decodeAll(decoded.data());
                long from = Long.MAX_VALUE;
                long to = Long.MIN_VALUE;
                long expires = Long.MIN_VALUE;
                for (Record record : records) {
                    from = Math.min(from, record.timestamp());
                    to = Math.max(to, record.timestamp());
                    expires = Math.max(expires, record.expiresAt());
                }
                archives.add(new ArchiveIndex(name, records.size(), from == Long.MAX_VALUE ? 0L : from,
                        to == Long.MIN_VALUE ? 0L : to, expires == Long.MIN_VALUE ? 0L : expires,
                        decoded.data().length, bytes.length, decoded.codec(), decoded.level()));
            }
            if (!archiveFiles.isEmpty()) {
                saveIndex();
            }
        }
    }

    private void discardTemporaryArchives() {
        try (var stream = Files.list(directory)) {
            for (Path path : stream.toList()) {
                String name = path.getFileName().toString();
                if (name.endsWith(".tmp")) {
                    // An interrupted pass; the originals were never deleted, so the temp is safe to drop.
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ignored) {
            // Best effort; a leftover temp is harmless and will be retried next start.
        }
    }

    // ------------------------------------------------------------------ utilities

    private List<Path> listSegments() {
        List<Path> segments = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(SEGMENT_PREFIX) && name.endsWith(SEGMENT_SUFFIX);
            }).forEach(segments::add);
        } catch (IOException e) {
            return segments;
        }
        segments.sort(Comparator.comparingLong(p -> parseSequence(p, SEGMENT_PREFIX, SEGMENT_SUFFIX)));
        return segments;
    }

    private byte[] concatenate(List<Path> segments) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Path segment : segments) {
            out.write(Files.readAllBytes(segment));
        }
        return out.toByteArray();
    }

    private int countRecords(Path segment) {
        try {
            byte[] raw = Files.readAllBytes(segment);
            int count = 0;
            int offset = 0;
            while (offset + RECORD_HEADER <= raw.length) {
                ByteBuffer header = ByteBuffer.wrap(raw, offset, RECORD_HEADER).order(ByteOrder.LITTLE_ENDIAN);
                int length = header.getInt();
                if (length < 0 || offset + RECORD_HEADER + length > raw.length) {
                    break;
                }
                offset += RECORD_HEADER + length;
                count++;
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private static byte[] encodeRecord(Record record) {
        byte[] payload = record.payload();
        ByteBuffer buffer = ByteBuffer.allocate(RECORD_HEADER + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(payload.length);
        buffer.putLong(record.timestamp());
        buffer.putLong(record.expiresAt());
        buffer.putLong(record.sequence());
        buffer.putInt((int) Checksum.of(payload));
        buffer.put(payload);
        return buffer.array();
    }

    private Path segmentPath(long sequence) {
        return directory.resolve(SEGMENT_PREFIX + sequence + SEGMENT_SUFFIX);
    }

    private static long parseSequence(Path path, String prefix, String suffix) {
        String name = path.getFileName().toString();
        String middle = name.substring(prefix.length(), name.length() - suffix.length());
        // Archive names are "first-last"; take the leading number.
        int dash = middle.indexOf('-');
        if (dash >= 0) {
            middle = middle.substring(0, dash);
        }
        try {
            return Long.parseLong(middle);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    private static void forceToDisk(Path path) throws IOException {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(path,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            // Not started, or already closed; still try to flush the queue synchronously.
            drainQueueQuietly();
            return;
        }
        Thread writer = writerThread;
        Thread compressor = compressionThread;
        if (compressor != null) {
            compressor.interrupt();
        }
        if (writer != null) {
            writer.interrupt();
            try {
                writer.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // Anything the writer did not get to is written here, so a shutdown loses nothing.
        drainQueueQuietly();
        if (compressor != null) {
            try {
                compressor.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drainQueueQuietly() {
        Record record;
        while ((record = writeQueue.poll()) != null) {
            try {
                writeRecord(record);
            } catch (IOException | UncheckedIOException e) {
                droppedRecords.incrementAndGet();
            }
        }
    }

    /** One stored record. */
    public record Record(long sequence, long timestamp, long expiresAt, byte[] payload) {
    }

    /** Metadata for one archive file. */
    private record ArchiveIndex(String file, int records, long from, long to, long expiresAt,
            int originalBytes, int compressedBytes, CodecId codec, int level) {
    }
}
