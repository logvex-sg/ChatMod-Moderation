package com.adminmod.server.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.model.Json;
import com.adminmod.common.model.PlayerNote;
import com.adminmod.common.model.Punishment;
import com.adminmod.common.model.PunishmentType;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Records moderation actions and renders the JSON views the dashboard consumes.
 *
 * <p>All Minecraft-facing effects (kicking a player, adding a ban entry) are performed by
 * {@link com.adminmod.server.remote.RemoteRequestHandler}, which owns the server reference. This
 * class only decides what is recorded, so its behaviour can be unit tested without a server.
 */
public final class ModerationService {

    private final ModerationStore store;
    private final AdminConfig config;
    private com.adminmod.server.audit.AuditManager audit;

    public ModerationService(ModerationStore store, AdminConfig config) {
        this.store = store;
        this.config = config;
    }

    /** Injected after construction so every recorded punishment is mirrored to the audit log. */
    public void setAudit(com.adminmod.server.audit.AuditManager audit) {
        this.audit = audit;
    }

    public ModerationStore store() {
        return store;
    }

    /** @throws IllegalArgumentException when a reason is required but missing. */
    public void requireReason(String reason) {
        if (config.moderation.requireReasons && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("a reason is required");
        }
    }

    public Punishment record(PunishmentType type, UUID targetUuid, String targetName, UUID staffUuid,
            String staffName, String reason, long durationSeconds, String durationInput) {
        long now = System.currentTimeMillis();
        long expiresAt = durationSeconds <= 0L ? 0L : now + durationSeconds * 1000L;
        Punishment punishment = store.record(type, targetUuid, targetName, staffUuid, staffName,
                reason == null ? "" : reason.trim(), now, expiresAt, durationInput);
        if (audit != null) {
            audit.moderation(staffName == null ? "console" : staffName,
                    staffUuid == null ? "" : staffUuid.toString(), type.name(), targetName,
                    reason == null ? "" : reason.trim());
        }
        return punishment;
    }

    public int expireDue() {
        return store.expireDue(System.currentTimeMillis());
    }

    public Optional<Punishment> activeMute(UUID uuid) {
        return store.activePunishment(uuid, PunishmentType.MUTE, System.currentTimeMillis());
    }

    public boolean isMuted(UUID uuid) {
        return activeMute(uuid).isPresent();
    }

    public int warningCount(UUID uuid) {
        return store.countWarnings(uuid);
    }

    /** An automatic escalation that fired, with the warning count that triggered it. */
    public record Escalation(Punishment punishment, int recentWarnings) {
    }

    /**
     * Applies the automatic escalation rule for a player.
     *
     * <p>When the player has accumulated the configured number of warnings inside the window and is
     * not already muted, the configured mute is recorded as an ordinary punishment, so it appears
     * in the history and can be lifted with the normal unmute path.
     *
     * <p>Kept here rather than in the request handler because it is pure bookkeeping: it reads the
     * store and writes a punishment, with no Minecraft type involved, which makes the rule
     * testable without a server.
     *
     * @return the escalation that fired, or empty when the rule did not
     */
    public Optional<Escalation> applyEscalation(UUID targetUuid, String targetName) {
        AdminConfig.Escalation rule = config.moderation.escalation;
        if (!rule.enabled || isMuted(targetUuid)) {
            return Optional.empty();
        }
        long since = rule.windowSeconds <= 0L ? 0L : System.currentTimeMillis() - rule.windowSeconds * 1000L;
        int recent = store.countWarningsSince(targetUuid, since);
        if (recent < rule.warnThreshold) {
            return Optional.empty();
        }
        Punishment punishment = record(PunishmentType.MUTE, targetUuid, targetName, null, "ADMIN (automatic)",
                rule.reason, rule.muteSeconds, "escalation");
        return Optional.of(new Escalation(punishment, recent));
    }

    /** JSON view of a player's full moderation history. */
    public String historyJson(UUID uuid) {
        JsonObject root = new JsonObject();
        root.addProperty("uuid", uuid.toString());
        root.add("punishments", punishmentsJson(store.history(uuid)));
        root.add("notes", notesJson(store.notes(uuid)));
        return Json.write(root);
    }

    public String warningsJson(UUID uuid) {
        List<Punishment> warnings = new ArrayList<>();
        for (Punishment punishment : store.history(uuid)) {
            if (punishment.type() == PunishmentType.WARNING) {
                warnings.add(punishment);
            }
        }
        JsonObject root = new JsonObject();
        root.add("punishments", punishmentsJson(warnings));
        return Json.write(root);
    }

    public String notesJsonView(UUID uuid) {
        JsonObject root = new JsonObject();
        root.add("notes", notesJson(store.notes(uuid)));
        return Json.write(root);
    }

    private JsonArray punishmentsJson(List<Punishment> punishments) {
        JsonArray array = new JsonArray();
        for (Punishment punishment : punishments) {
            JsonObject object = new JsonObject();
            object.addProperty("id", punishment.id());
            object.addProperty("type", punishment.type().name());
            object.addProperty("targetName", punishment.targetName());
            object.addProperty("staffName", punishment.staffName());
            object.addProperty("reason", punishment.reason());
            object.addProperty("createdAt", punishment.createdAt());
            object.addProperty("expiresAt", punishment.expiresAt());
            object.addProperty("active", punishment.active());
            object.addProperty("permanent", punishment.isPermanent());
            object.addProperty("duration", com.adminmod.common.model.DurationParser.describe(
                    punishment.isPermanent() ? 0L : Math.max(0L, (punishment.expiresAt() - punishment.createdAt()) / 1000L)));
            array.add(object);
        }
        return array;
    }

    private JsonArray notesJson(List<PlayerNote> notes) {
        JsonArray array = new JsonArray();
        for (PlayerNote note : notes) {
            JsonObject object = new JsonObject();
            object.addProperty("id", note.id());
            object.addProperty("authorName", note.authorName());
            object.addProperty("text", note.text());
            object.addProperty("createdAt", note.createdAt());
            array.add(object);
        }
        return array;
    }
}
