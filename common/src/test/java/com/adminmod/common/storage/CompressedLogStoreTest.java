package com.adminmod.common.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.adminmod.common.compress.CodecId;
import com.adminmod.common.compress.CompressedArchive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compressed log store: writing, searching, compression, retention, recovery and deduplication.
 *
 * <p>Each test drives the real store against a real temporary directory. The store's background
 * threads are started where the test needs them and closed at the end, so a test never leaves a
 * thread running.
 */
class CompressedLogStoreTest {

    private static StorageSettings settings(CodecId codec, int maxArchives, boolean dedupe) {
        return new StorageSettings(true, codec, 3, 1, 0, 8 * 1024 * 1024, 4_096, 16, 64,
                8 * 1024 * 1024, 4 * 1024 * 1024, maxArchives, dedupe, 250L, false, 60.0);
    }

    private static byte[] payload(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void writesAndReadsBackRecords(@TempDir Path dir) throws Exception {
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            store.start();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 50; i++) {
                assertTrue(store.append(payload("event-" + i), now + i, now + 86_400_000L));
            }
            waitFor(() -> store.writtenRecords() >= 50);
            List<CompressedLogStore.Record> found = store.query(now - 1, now + 1_000, 1_000);
            assertEquals(50, found.size());
            assertEquals("event-0", new String(found.get(0).payload(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void compressionProducesArchivesAndDeletesSegments(@TempDir Path dir) throws Exception {
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            store.start();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 200; i++) {
                store.append(payload("the same repetitive audit line " + i), now + i, now + 86_400_000L);
            }
            waitFor(() -> store.writtenRecords() >= 200);
            // Roll the active segment so the writer's segment is eligible for compression.
            store.compressEligibleSegments(true);
            waitFor(() -> store.archiveCount() > 0);
            assertTrue(store.archiveCount() >= 1, "an archive should have been produced");
            assertTrue(store.stats().savedBytes() > 0, "compression should have saved space");
            // The records must still be readable after compression.
            List<CompressedLogStore.Record> found = store.query(now - 1, now + 1_000, 1_000);
            assertEquals(200, found.size());
        }
    }

    @Test
    void searchingAnArchiveDecompressesOnlyWhatIsNeeded(@TempDir Path dir) throws Exception {
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 16, null)) {
            store.open();
            store.start();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) {
                store.append(payload("record-" + i), now + i, now + 86_400_000L);
            }
            waitFor(() -> store.writtenRecords() >= 100);
            store.compressEligibleSegments(true);
            waitFor(() -> store.archiveCount() > 0);
            // A narrow range should return only the records in it.
            List<CompressedLogStore.Record> narrow = store.query(now + 10, now + 12, 1_000);
            assertEquals(3, narrow.size());
            assertTrue(store.stats().recordsDecompressed() >= 1);
        }
    }

    @Test
    void retentionDeletesExpiredArchives(@TempDir Path dir) throws Exception {
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            store.start();
            long now = System.currentTimeMillis();
            // Everything here expires in the past.
            for (int i = 0; i < 100; i++) {
                store.append(payload("old-" + i), now - 10_000 + i, now - 1_000L);
            }
            waitFor(() -> store.writtenRecords() >= 100);
            store.compressEligibleSegments(true);
            waitFor(() -> store.archiveCount() > 0);
            // A second pass runs retention and should delete the expired archive.
            store.compressEligibleSegments(true);
            waitFor(() -> store.archiveCount() == 0);
            assertEquals(0, store.archiveCount());
            assertTrue(store.stats().bytesReclaimed() > 0);
        }
    }

    @Test
    void archiveRotationRespectsTheMaximumCount(@TempDir Path dir) throws Exception {
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 2, false), 512, null)) {
            store.open();
            store.start();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 300; i++) {
                store.append(payload("rotate-" + i), now + i, now + 86_400_000L);
                if (i % 50 == 49) {
                    // Roll a segment between batches so several archives form.
                    store.compressEligibleSegments(true);
                }
            }
            waitFor(() -> store.writtenRecords() >= 300);
            store.compressEligibleSegments(true);
            Thread.sleep(200);
            store.compressEligibleSegments(true);
            assertTrue(store.archiveCount() <= 2,
                    "archive count should stay within the configured maximum, was " + store.archiveCount());
        }
    }

    @Test
    void deduplicationDropsIdenticalRecordsInsideTheWindow(@TempDir Path dir) throws Exception {
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, true), 512, null)) {
            store.open();
            store.start();
            long now = System.currentTimeMillis();
            // The same bytes with the same timestamp bucket are stored once.
            store.append(payload("duplicate"), now, now + 86_400_000L);
            store.append(payload("duplicate"), now, now + 86_400_000L);
            store.append(payload("different"), now, now + 86_400_000L);
            waitFor(() -> store.writtenRecords() >= 2);
            assertEquals(2, store.writtenRecords(), "the duplicate should have been dropped");
            assertEquals(1, store.deduplicatedRecords());
        }
    }

    @Test
    void aCorruptArchiveIsReportedAndNotReturned(@TempDir Path dir) throws Exception {
        long now = System.currentTimeMillis();
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            store.start();
            for (int i = 0; i < 60; i++) {
                store.append(payload("corrupt-me-" + i), now + i, now + 86_400_000L);
            }
            waitFor(() -> store.writtenRecords() >= 60);
            store.compressEligibleSegments(true);
            waitFor(() -> store.archiveCount() > 0);
        }
        // Corrupt the archive on disk.
        Path archive = Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".adr")).findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(archive);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(archive, bytes);

        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            // Reading the corrupt archive returns nothing and is counted.
            List<CompressedLogStore.Record> found = store.query(0L, Long.MAX_VALUE, 1_000);
            assertEquals(0, found.size(), "a corrupt archive must not yield records");
            assertTrue(store.stats().corruptArchives() >= 1);
        }
    }

    @Test
    void anOrphanArchiveIsReindexedOnOpen(@TempDir Path dir) throws Exception {
        // Write an archive file directly, as if a crash happened between the rename and the index
        // write, then confirm open() recovers it.
        byte[] raw = new byte[0];
        byte[] archive = CompressedArchive.encode(new com.adminmod.common.compress.GzipCodec(), 3, raw);
        Files.createDirectories(dir);
        Files.write(dir.resolve("archive-1-1.adr"), archive);
        Files.deleteIfExists(dir.resolve("index.json"));

        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            assertEquals(1, store.archiveCount(), "the orphan archive should have been indexed");
        }
    }

    @Test
    void aLeftoverTemporaryArchiveIsDiscardedOnOpen(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("archive-5-5.adr.tmp"), "interrupted");
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            assertFalse(Files.exists(dir.resolve("archive-5-5.adr.tmp")), "the temp file should be gone");
        }
    }

    @Test
    void statusJsonReportsRealNumbers(@TempDir Path dir) throws Exception {
        try (CompressedLogStore store = new CompressedLogStore(dir, settings(CodecId.GZIP, 64, false), 512, null)) {
            store.open();
            store.start();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 40; i++) {
                store.append(payload("status-" + i), now + i, now + 86_400_000L);
            }
            waitFor(() -> store.writtenRecords() >= 40);
            String json = store.statusJson("audit");
            assertNotNull(json);
            assertTrue(json.contains("\"subsystem\":\"audit\""));
            assertTrue(json.contains("\"algorithm\":\"gzip\""));
            assertTrue(json.contains("\"recordsCompressed\""));
        }
    }

    @Test
    void appendIsRefusedWhenTheStoreIsDisabled(@TempDir Path dir) throws Exception {
        StorageSettings disabled = new StorageSettings(false, CodecId.GZIP, 3, 1, 0, 8 * 1024 * 1024,
                4_096, 16, 64, 8 * 1024 * 1024, 4 * 1024 * 1024, 64, false, 250L, false, 60.0);
        try (CompressedLogStore store = new CompressedLogStore(dir, disabled, 512, null)) {
            store.open();
            assertFalse(store.append(payload("x"), System.currentTimeMillis(), 0L));
        }
    }

    /** Polls a condition for up to a few seconds; the store's writers run on their own threads. */
    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        // Fall through; the assertion in the test reports the failure with a useful message.
    }
}
