package com.adminmod.server.moderation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.adminmod.common.model.Json;
import com.adminmod.common.model.PlayerNote;
import com.adminmod.common.model.Punishment;
import com.adminmod.common.model.PunishmentType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Durable record of every moderation action.
 *
 * <p>This class holds no Minecraft types, which keeps the history logic unit testable and makes
 * it impossible for a rendering change to affect what is recorded. The store is written
 * owner-only: the file is the audit trail, so it must not be editable by other local accounts.
 */
public final class ModerationStore {

    private final Path file;
    private final List<Punishment> punishments = new ArrayList<>();
    private final List<PlayerNote> notes = new ArrayList<>();

    private long nextPunishmentId = 1L;
    private long nextNoteId = 1L;
    private boolean dirty;

    public ModerationStore(Path file) {
        this.file = file;
    }

    public synchronized void load() throws IOException {
        punishments.clear();
        notes.clear();
        nextPunishmentId = 1L;
        nextNoteId = 1L;
        if (Files.notExists(file)) {
            return;
        }
        JsonObject root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
        nextPunishmentId = Json.lng(root, "nextPunishmentId", 1L);
        nextNoteId = Json.lng(root, "nextNoteId", 1L);
        JsonArray punishmentArray = root.getAsJsonArray("punishments");
        if (punishmentArray != null) {
            for (JsonElement element : punishmentArray) {
                punishments.add(readPunishment(element.getAsJsonObject()));
            }
        }
        JsonArray noteArray = root.getAsJsonArray("notes");
        if (noteArray != null) {
            for (JsonElement element : noteArray) {
                notes.add(readNote(element.getAsJsonObject()));
            }
        }
        // Guard against a hand-edited file reusing an id.
        for (Punishment punishment : punishments) {
            nextPunishmentId = Math.max(nextPunishmentId, punishment.id() + 1);
        }
        for (PlayerNote note : notes) {
            nextNoteId = Math.max(nextNoteId, note.id() + 1);
        }
        dirty = false;
    }

    public synchronized void save() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("nextPunishmentId", nextPunishmentId);
        root.addProperty("nextNoteId", nextNoteId);
        JsonArray punishmentArray = new JsonArray();
        for (Punishment punishment : punishments) {
            punishmentArray.add(writePunishment(punishment));
        }
        root.add("punishments", punishmentArray);
        JsonArray noteArray = new JsonArray();
        for (PlayerNote note : notes) {
            noteArray.add(writeNote(note));
        }
        root.add("notes", noteArray);

        Files.createDirectories(file.getParent());
        com.adminmod.common.security.PasswordVault.writeRestricted(file,
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));
        dirty = false;
    }

    /** Persists only when something changed since the last write. */
    public synchronized void saveIfDirty() throws IOException {
        if (dirty) {
            save();
        }
    }

    public synchronized Punishment record(PunishmentType type, UUID targetUuid, String targetName,
            UUID staffUuid, String staffName, String reason, long createdAt, long expiresAt, String durationInput) {
        Punishment punishment = new Punishment(nextPunishmentId++, type, targetUuid.toString(), targetName,
                staffUuid == null ? "" : staffUuid.toString(), staffName, reason, createdAt, expiresAt, true,
                durationInput);
        punishments.add(punishment);
        dirty = true;
        return punishment;
    }

    public synchronized PlayerNote addNote(UUID targetUuid, UUID authorUuid, String authorName, String text, long createdAt) {
        PlayerNote note = new PlayerNote(nextNoteId++, targetUuid.toString(),
                authorUuid == null ? "" : authorUuid.toString(), authorName, text, createdAt);
        notes.add(note);
        dirty = true;
        return note;
    }

    /** Deactivates every punishment that has lapsed. @return the number revoked. */
    public synchronized int expireDue(long now) {
        int revoked = 0;
        for (int i = 0; i < punishments.size(); i++) {
            Punishment punishment = punishments.get(i);
            if (punishment.active() && punishment.isExpired(now)) {
                punishments.set(i, withActive(punishment, false));
                revoked++;
            }
        }
        if (revoked > 0) {
            dirty = true;
        }
        return revoked;
    }

    /** Revokes a punishment by id. @return the revoked punishment, if it existed. */
    public synchronized Optional<Punishment> revoke(long id) {
        for (int i = 0; i < punishments.size(); i++) {
            Punishment punishment = punishments.get(i);
            if (punishment.id() == id && punishment.active()) {
                Punishment revoked = withActive(punishment, false);
                punishments.set(i, revoked);
                dirty = true;
                return Optional.of(revoked);
            }
        }
        return Optional.empty();
    }

    /** Revokes the most recent active punishment of a type for a player. */
    public synchronized Optional<Punishment> revokeLatest(UUID targetUuid, PunishmentType type) {
        OptionalLong latestId = punishments.stream()
                .filter(p -> p.active() && p.type() == type && p.targetUuid().equals(targetUuid.toString()))
                .max(Comparator.comparingLong(Punishment::id))
                .map(Punishment::id)
                .map(OptionalLong::of)
                .orElse(OptionalLong.empty());
        return latestId.isPresent() ? revoke(latestId.getAsLong()) : Optional.empty();
    }

    public synchronized Optional<Punishment> activePunishment(UUID targetUuid, PunishmentType type, long now) {
        return punishments.stream()
                .filter(p -> p.active() && p.type() == type && p.targetUuid().equals(targetUuid.toString()))
                .filter(p -> !p.isExpired(now))
                .max(Comparator.comparingLong(Punishment::id));
    }

    public synchronized int countWarnings(UUID targetUuid) {
        return (int) punishments.stream()
                .filter(p -> p.active() && p.type() == PunishmentType.WARNING && p.targetUuid().equals(targetUuid.toString()))
                .count();
    }

    public synchronized List<Punishment> history(UUID targetUuid) {
        List<Punishment> result = new ArrayList<>();
        for (Punishment punishment : punishments) {
            if (punishment.targetUuid().equals(targetUuid.toString())) {
                result.add(punishment);
            }
        }
        result.sort(Comparator.comparingLong(Punishment::id).reversed());
        return result;
    }

    public synchronized List<Punishment> allPunishments() {
        List<Punishment> result = new ArrayList<>(punishments);
        result.sort(Comparator.comparingLong(Punishment::id).reversed());
        return result;
    }

    public synchronized List<PlayerNote> notes(UUID targetUuid) {
        List<PlayerNote> result = new ArrayList<>();
        for (PlayerNote note : notes) {
            if (note.playerUuid().equals(targetUuid.toString())) {
                result.add(note);
            }
        }
        result.sort(Comparator.comparingLong(PlayerNote::id).reversed());
        return result;
    }

    public synchronized List<PlayerNote> allNotes() {
        List<PlayerNote> result = new ArrayList<>(notes);
        result.sort(Comparator.comparingLong(PlayerNote::id).reversed());
        return result;
    }

    /** Most recent name seen for a UUID, used to render offline players in the dashboard. */
    public synchronized Optional<String> lastKnownName(UUID uuid) {
        String target = uuid.toString();
        return punishments.stream()
                .filter(p -> p.targetUuid().equals(target) && !p.targetName().isBlank())
                .max(Comparator.comparingLong(Punishment::id))
                .map(Punishment::targetName);
    }

    private static Punishment withActive(Punishment source, boolean active) {
        return new Punishment(source.id(), source.type(), source.targetUuid(), source.targetName(), source.staffUuid(),
                source.staffName(), source.reason(), source.createdAt(), source.expiresAt(), active,
                source.durationInput());
    }

    private static JsonObject writePunishment(Punishment punishment) {
        JsonObject object = new JsonObject();
        object.addProperty("id", punishment.id());
        object.addProperty("type", punishment.type().name());
        object.addProperty("targetUuid", punishment.targetUuid());
        object.addProperty("targetName", punishment.targetName());
        object.addProperty("staffUuid", punishment.staffUuid());
        object.addProperty("staffName", punishment.staffName());
        object.addProperty("reason", punishment.reason());
        object.addProperty("createdAt", punishment.createdAt());
        object.addProperty("expiresAt", punishment.expiresAt());
        object.addProperty("active", punishment.active());
        object.addProperty("durationInput", punishment.durationInput());
        return object;
    }

    private static Punishment readPunishment(JsonObject object) {
        PunishmentType type;
        try {
            type = PunishmentType.valueOf(Json.str(object, "type", "WARNING"));
        } catch (IllegalArgumentException e) {
            type = PunishmentType.WARNING;
        }
        return new Punishment(
                Json.lng(object, "id", 0L),
                type,
                Json.str(object, "targetUuid", ""),
                Json.str(object, "targetName", ""),
                Json.str(object, "staffUuid", ""),
                Json.str(object, "staffName", ""),
                Json.str(object, "reason", ""),
                Json.lng(object, "createdAt", 0L),
                Json.lng(object, "expiresAt", 0L),
                Json.bool(object, "active", true),
                Json.str(object, "durationInput", ""));
    }

    private static JsonObject writeNote(PlayerNote note) {
        JsonObject object = new JsonObject();
        object.addProperty("id", note.id());
        object.addProperty("playerUuid", note.playerUuid());
        object.addProperty("authorUuid", note.authorUuid());
        object.addProperty("authorName", note.authorName());
        object.addProperty("text", note.text());
        object.addProperty("createdAt", note.createdAt());
        return object;
    }

    private static PlayerNote readNote(JsonObject object) {
        return new PlayerNote(
                Json.lng(object, "id", 0L),
                Json.str(object, "playerUuid", ""),
                Json.str(object, "authorUuid", ""),
                Json.str(object, "authorName", ""),
                Json.str(object, "text", ""),
                Json.lng(object, "createdAt", 0L));
    }
}
