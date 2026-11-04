package com.adminmod.server.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.UUID;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.model.Punishment;
import com.adminmod.common.model.PunishmentType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Moderation decisions are pure bookkeeping, so they are exercised here without a server.
 * Anything that touches a live player (kicking, banning) lives in the request handler instead.
 */
class ModerationServiceTest {

    private static final UUID STAFF = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TARGET = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static ModerationService service(Path dir) {
        return new ModerationService(new ModerationStore(dir.resolve("history.json")), new AdminConfig());
    }

    @Test
    void aReasonIsRequiredByDefault(@TempDir Path dir) {
        ModerationService service = service(dir);
        assertThrows(IllegalArgumentException.class, () -> service.requireReason(""));
        assertThrows(IllegalArgumentException.class, () -> service.requireReason("   "));
        service.requireReason("spam");
    }

    @Test
    void reasonsCanBeOptional(@TempDir Path dir) {
        AdminConfig config = new AdminConfig();
        config.moderation.requireReasons = false;
        ModerationService service = new ModerationService(new ModerationStore(dir.resolve("history.json")), config);
        service.requireReason("");
    }

    @Test
    void aPermanentMuteHasNoExpiryAndIsActive(@TempDir Path dir) {
        ModerationService service = service(dir);
        Punishment punishment = service.record(PunishmentType.MUTE, TARGET, "Alex", STAFF, "Admin", "spam", 0L,
                "permanent");
        assertTrue(punishment.isPermanent());
        assertTrue(service.isMuted(TARGET));
    }

    @Test
    void aTemporaryMuteExpires(@TempDir Path dir) {
        ModerationService service = service(dir);
        service.record(PunishmentType.MUTE, TARGET, "Alex", STAFF, "Admin", "spam", 1L, "1s");
        assertTrue(service.isMuted(TARGET));
        assertTrue(service.store().expireDue(System.currentTimeMillis() + 5_000L) >= 1);
        assertFalse(service.isMuted(TARGET));
    }

    @Test
    void warningsAreCountedPerPlayer(@TempDir Path dir) {
        ModerationService service = service(dir);
        service.record(PunishmentType.WARNING, TARGET, "Alex", STAFF, "Admin", "first", 0L, "");
        service.record(PunishmentType.WARNING, TARGET, "Alex", STAFF, "Admin", "second", 0L, "");
        assertEquals(2, service.warningCount(TARGET));
        assertEquals(0, service.warningCount(UUID.randomUUID()));
    }

    @Test
    void historyIsScopedToThePlayer(@TempDir Path dir) {
        ModerationService service = service(dir);
        service.record(PunishmentType.WARNING, TARGET, "Alex", STAFF, "Admin", "first", 0L, "");
        UUID other = UUID.randomUUID();
        service.record(PunishmentType.KICK, other, "Steve", STAFF, "Admin", "second", 0L, "");
        assertEquals(1, service.store().history(TARGET).size());
        assertEquals(1, service.store().history(other).size());
    }

    @Test
    void aPardonedPunishmentIsNoLongerActive(@TempDir Path dir) {
        ModerationService service = service(dir);
        Punishment punishment = service.record(PunishmentType.MUTE, TARGET, "Alex", STAFF, "Admin", "spam", 0L, "");
        assertTrue(service.isMuted(TARGET));
        assertTrue(service.store().revoke(punishment.id()).isPresent());
        assertFalse(service.isMuted(TARGET));
    }

    @Test
    void theRecordSurvivesAReload(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("history.json");
        ModerationStore store = new ModerationStore(file);
        ModerationService service = new ModerationService(store, new AdminConfig());
        service.record(PunishmentType.BAN, TARGET, "Alex", STAFF, "Admin", "griefing", 0L, "");
        store.saveIfDirty();

        ModerationStore reloaded = new ModerationStore(file);
        reloaded.load();
        assertEquals(1, reloaded.history(TARGET).size());
        assertEquals(PunishmentType.BAN, reloaded.history(TARGET).get(0).type());
    }

    @Test
    void notesAreAttachedToThePlayer(@TempDir Path dir) {
        ModerationService service = service(dir);
        service.store().addNote(TARGET, STAFF, "Admin", "watch this player", System.currentTimeMillis());
        assertEquals(1, service.store().notes(TARGET).size());
        assertEquals("watch this player", service.store().notes(TARGET).get(0).text());
    }
}
