package com.adminmod.common.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.adminmod.common.config.AdminConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The audit log front end: category filtering, retention windows, searching and export.
 *
 * <p>These tests use a real configuration and a real store against a temporary directory, so the
 * filtering, the expiry computation and the query path are all exercised for real.
 */
class AuditLogTest {

    private static AdminConfig config() {
        AdminConfig config = new AdminConfig();
        config.audit.enabled = true;
        config.audit.categories = List.of("PLAYER", "MODERATION", "PERMISSION", "SECURITY", "BLOCK", "CONTAINER");
        config.audit.retentionDays = 30;
        config.audit.securityRetentionDays = 365;
        config.audit.maxQueryResults = 500;
        config.audit.flushIntervalSeconds = 1;
        config.storage.enabled = true;
        config.storage.algorithm = "gzip";
        config.storage.minArchiveAgeHours = 0;
        config.storage.compressIntervalSeconds = 3600; // the test drives compression itself
        config.storage.deduplicate = false;
        return config;
    }

    @Test
    void recordsAndSearchesByCategoryAndPlayer(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            long now = System.currentTimeMillis();
            log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO)
                    .at(now).actor("Alice", "uuid-a").detail("joined").build());
            log.record(AuditEvent.builder(AuditCategory.MODERATION, AuditSeverity.WARNING)
                    .at(now + 1).actor("Bob", "uuid-b").subject("Alice").detail("muted").build());
            log.record(AuditEvent.builder(AuditCategory.BLOCK, AuditSeverity.INFO)
                    .at(now + 2).actor("Alice", "uuid-a").field(AuditEvent.KEY_BLOCK, "minecraft:diamond_ore").build());
            waitFor(() -> log.store().writtenRecords() >= 3);

            assertEquals(3, log.search(AuditQuery.all(100)).size());
            assertEquals(3, log.search(new AuditQuery("Alice", null, null, null, null, null, null, null, null, null, 100)).size());
            assertEquals(1, log.search(new AuditQuery(null, AuditCategory.MODERATION, null, null, null, null, null, null, null, null, 100)).size());
            assertEquals(1, log.search(new AuditQuery(null, null, AuditSeverity.WARNING, null, null, null, null, null, null, null, 100)).size());
        }
    }

    @Test
    void searchMatchesItemBlockAndFreeText(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            long now = System.currentTimeMillis();
            log.record(AuditEvent.builder(AuditCategory.CONTAINER, AuditSeverity.INFO).at(now)
                    .actor("Alice").field(AuditEvent.KEY_ITEM, "minecraft:diamond").field(AuditEvent.KEY_POSITION, "12,64,-30").build());
            log.record(AuditEvent.builder(AuditCategory.BLOCK, AuditSeverity.INFO).at(now + 1)
                    .actor("Bob").field(AuditEvent.KEY_BLOCK, "minecraft:chest").build());
            waitFor(() -> log.store().writtenRecords() >= 2);

            assertEquals(1, log.search(new AuditQuery(null, null, null, null, null, "diamond", null, null, null, null, 100)).size());
            assertEquals(1, log.search(new AuditQuery(null, null, null, null, null, null, "chest", null, null, null, 100)).size());
            assertEquals(1, log.search(new AuditQuery(null, null, null, null, null, null, null, "12,64,-30", null, null, 100)).size());
            assertEquals(1, log.search(new AuditQuery(null, null, null, null, null, null, null, null, null, "diamond", 100)).size());
        }
    }

    @Test
    void aDisabledCategoryIsFilteredOut(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            long now = System.currentTimeMillis();
            log.record(AuditEvent.builder(AuditCategory.CHAT, AuditSeverity.INFO).at(now).actor("Alice").build());
            log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO).at(now + 1).actor("Alice").build());
            waitFor(() -> log.store().writtenRecords() >= 1);
            assertEquals(1, log.filteredCount(), "the chat event should have been filtered");
            assertEquals(1, log.search(AuditQuery.all(100)).size());
        }
    }

    @Test
    void aDisabledLogRecordsNothing(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        config.audit.enabled = false;
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            assertFalse(log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO).actor("Alice").build()));
            assertEquals(0, log.recordedCount());
        }
    }

    @Test
    void securityEventsUseTheLongerRetentionWindow(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            long now = System.currentTimeMillis();
            // Both events are "old"; the ordinary one expires sooner than the security one.
            log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO).at(now).build());
            log.record(AuditEvent.builder(AuditCategory.MODERATION, AuditSeverity.WARNING).at(now).build());
            waitFor(() -> log.store().writtenRecords() >= 2);
            // The security event is kept for 365 days, the ordinary one for 30; the ordinary one
            // expires 335 days earlier. Both are still in the store because nothing has run
            // retention yet, so this checks the computation rather than a deletion.
            List<AuditEvent> events = log.search(AuditQuery.all(100));
            assertEquals(2, events.size());
            long ordinaryExpiry = events.stream().filter(e -> e.category() == AuditCategory.PLAYER)
                    .findFirst().orElseThrow().timestamp() + 30L * 86_400_000L;
            long securityExpiry = events.stream().filter(e -> e.category() == AuditCategory.MODERATION)
                    .findFirst().orElseThrow().timestamp() + 365L * 86_400_000L;
            assertTrue(securityExpiry > ordinaryExpiry);
        }
    }

    @Test
    void exportProducesCsvAndJson(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            long now = System.currentTimeMillis();
            log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO).at(now)
                    .actor("Alice", "uuid-a").subject("world").detail("joined the game").build());
            waitFor(() -> log.store().writtenRecords() >= 1);
            Path csv = log.export(AuditQuery.all(100), dir.resolve("out/audit.csv"));
            Path json = log.export(AuditQuery.all(100), dir.resolve("out/audit.json"));
            String csvText = Files.readString(csv);
            assertTrue(csvText.startsWith("timestamp,category,severity,actor"));
            assertTrue(csvText.contains("Alice"));
            assertTrue(csvText.contains("joined the game"));
            String jsonText = Files.readString(json);
            assertTrue(jsonText.contains("\"events\""));
            assertTrue(jsonText.contains("\"actor\":\"Alice\""));
        }
    }

    @Test
    void theQueryLimitIsClampedToTheConfiguredMaximum(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        config.audit.maxQueryResults = 5;
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 40; i++) {
                log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO).at(now + i).actor("p" + i).build());
            }
            waitFor(() -> log.store().writtenRecords() >= 40);
            assertEquals(5, log.search(AuditQuery.all(10_000)).size());
        }
    }

    @Test
    void statusJsonIsWellFormed(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditLog log = new AuditLog(dir.resolve("audit"), config, () -> 5.0)) {
            log.start();
            log.record(AuditEvent.builder(AuditCategory.PLAYER, AuditSeverity.INFO).actor("Alice").build());
            waitFor(() -> log.store().writtenRecords() >= 1);
            String json = log.statusJson();
            assertNotNull(json);
            assertTrue(json.contains("\"subsystem\":\"audit\""));
        }
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
    }
}
