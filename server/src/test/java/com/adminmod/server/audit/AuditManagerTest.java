package com.adminmod.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import com.adminmod.common.audit.AuditCategory;
import com.adminmod.common.audit.AuditEvent;
import com.adminmod.common.audit.AuditQuery;
import com.adminmod.common.audit.AuditSeverity;
import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.protection.ViolationLog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The audit manager is the single place that decides the category and severity of each kind of
 * event, and the place that enforces the two privacy rules: a command is recorded by name only and
 * chat is recorded only when opted in. Both are checked here against a real store on a temporary
 * directory, so the filtering and the query path run for real rather than against a stub.
 */
class AuditManagerTest {

    private static AdminConfig config() {
        AdminConfig config = new AdminConfig();
        config.audit.enabled = true;
        config.audit.categories = List.of("PLAYER", "BLOCK", "COMMAND", "MODERATION", "PERMISSION",
                "SECURITY", "CONFIG", "SERVER", "ANTICHEAT", "CHAT");
        config.audit.recordChat = true;
        config.audit.maxQueryResults = 500;
        config.audit.flushIntervalSeconds = 1;
        config.storage.algorithm = "gzip";
        config.storage.compressIntervalSeconds = 3600;
        config.storage.deduplicate = false;
        return config;
    }

    @Test
    void aCommandIsRecordedByNameOnlyNeverItsArguments(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditManager manager = new AuditManager(dir.resolve("audit"), config, () -> 5.0)) {
            manager.start();
            manager.command("Alice", "uuid-a", "/login hunter2 secret-token", false);
            waitFor(() -> manager.log().store().writtenRecords() >= 1);

            List<AuditEvent> events = manager.log().search(AuditQuery.all(10));
            assertEquals(1, events.size());
            assertEquals(AuditCategory.COMMAND, events.get(0).category());
            assertEquals("login", events.get(0).detailValue("command"));
            assertFalse(events.get(0).toJson().toString().contains("hunter2"),
                    "the command arguments must never reach the log");
            assertFalse(events.get(0).toJson().toString().contains("secret-token"));
        }
    }

    @Test
    void chatIsRecordedOnlyWhenOptedIn(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        config.audit.recordChat = false;
        try (AuditManager manager = new AuditManager(dir.resolve("audit"), config, () -> 5.0)) {
            manager.start();
            manager.chat("Alice", "uuid-a", "hello world");
            manager.join("Alice", "uuid-a", "127.0.0.1");
            waitFor(() -> manager.log().store().writtenRecords() >= 1);

            List<AuditEvent> events = manager.log().search(AuditQuery.all(10));
            assertEquals(1, events.size(), "only the non-chat event should be recorded");
            assertEquals(AuditCategory.PLAYER, events.get(0).category());
        }
    }

    @Test
    void eachEventKindLandsInItsOwnCategory(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditManager manager = new AuditManager(dir.resolve("audit"), config, () -> 5.0)) {
            manager.start();
            manager.blockBreak("Alice", "uuid-a", "minecraft:diamond_ore", 10, 64, -20);
            manager.moderation("Bob", "uuid-b", "MUTE", "Alice", "spam");
            manager.permission("Bob", "uuid-b", "grant", "Alice");
            manager.protection("uuid-a", "packet_flood", "over the limit");
            manager.server("server started");
            waitFor(() -> manager.log().store().writtenRecords() >= 5);

            assertEquals(1, count(manager, AuditCategory.BLOCK));
            assertEquals(1, count(manager, AuditCategory.MODERATION));
            assertEquals(1, count(manager, AuditCategory.PERMISSION));
            assertEquals(1, count(manager, AuditCategory.ANTICHEAT));
            assertEquals(1, count(manager, AuditCategory.SERVER));
        }
    }

    @Test
    void aProtectionTriggerIsMirroredFromTheViolationLog(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        try (AuditManager manager = new AuditManager(dir.resolve("audit"), config, () -> 5.0)) {
            manager.start();
            ViolationLog violations = new ViolationLog(16);
            violations.setListener((kind, subject, detail, at) ->
                    manager.protection(subject, kind.name().toLowerCase(java.util.Locale.ROOT), detail));

            violations.record(ViolationLog.Kind.PACKET_FLOOD, "uuid-a", "over the limit");
            waitFor(() -> manager.log().store().writtenRecords() >= 1);

            List<AuditEvent> events = manager.log().search(AuditQuery.all(10));
            assertEquals(1, events.size());
            assertEquals(AuditCategory.ANTICHEAT, events.get(0).category());
            assertEquals("packet_flood", events.get(0).detailValue("kind"));
        }
    }

    @Test
    void aDisabledAuditLogRecordsNothing(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        config.audit.enabled = false;
        try (AuditManager manager = new AuditManager(dir.resolve("audit"), config, () -> 5.0)) {
            manager.start();
            manager.server("server started");
            manager.command("Alice", "uuid-a", "/say hi", false);
            Thread.sleep(200);
            assertEquals(0, manager.log().store().writtenRecords());
            assertEquals(0, manager.log().search(AuditQuery.all(10)).size());
        }
    }

    @Test
    void aSecurityEventIsRecordedAndGivenTheLongerRetention(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        config.audit.retentionDays = 2;
        config.audit.securityRetentionDays = 365;
        try (AuditManager manager = new AuditManager(dir.resolve("audit"), config, () -> 5.0)) {
            manager.start();
            manager.security("Alice", "uuid-a", AuditSeverity.WARNING, "authentication failed from 10.0.0.5");
            manager.server("server started");
            waitFor(() -> manager.log().store().writtenRecords() >= 2);

            List<AuditEvent> events = manager.log().search(AuditQuery.all(10));
            assertEquals(2, events.size());

            List<com.adminmod.common.storage.CompressedLogStore.Record> records =
                    manager.log().store().query(0L, Long.MAX_VALUE, 10);
            long securityExpiry = -1L;
            long routineExpiry = -1L;
            for (com.adminmod.common.storage.CompressedLogStore.Record record : records) {
                AuditEvent event = AuditEvent.decode(record.payload());
                if (event == null) {
                    continue;
                }
                if (event.category() == AuditCategory.SECURITY) {
                    assertEquals(AuditSeverity.WARNING, event.severity());
                    assertEquals("authentication failed from 10.0.0.5", event.detail());
                    securityExpiry = record.expiresAt();
                } else if (event.category() == AuditCategory.SERVER) {
                    routineExpiry = record.expiresAt();
                }
            }
            assertTrue(securityExpiry > 0L, "the security event must be stored");
            assertTrue(routineExpiry > 0L, "the routine event must be stored");
            assertTrue(securityExpiry - routineExpiry > 300L * 86_400_000L,
                    "the security event must outlive the routine one by the security window");
        }
    }

    @Test
    void aDisabledCategoryIsNotRecorded(@TempDir Path dir) throws Exception {
        AdminConfig config = config();
        config.audit.categories = List.of("PLAYER");
        try (AuditManager manager = new AuditManager(dir.resolve("audit"), config, () -> 5.0)) {
            manager.start();
            manager.command("Alice", "uuid-a", "/say hi", false);
            manager.join("Alice", "uuid-a", "127.0.0.1");
            waitFor(() -> manager.log().store().writtenRecords() >= 1);

            List<AuditEvent> events = manager.log().search(AuditQuery.all(10));
            assertEquals(1, events.size());
            assertEquals(AuditCategory.PLAYER, events.get(0).category());
        }
    }

    private static int count(AuditManager manager, AuditCategory category) {
        return manager.log().search(new AuditQuery(null, category, null, null, null, null, null, null,
                null, null, 10)).size();
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
