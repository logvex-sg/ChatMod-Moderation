package com.adminmod.common.model;

/**
 * One recorded moderation action.
 *
 * @param id            monotonically increasing identifier, unique per server
 * @param type          the kind of action
 * @param targetUuid    UUID of the punished player
 * @param targetName    last known name of the punished player
 * @param staffUuid     UUID of the staff member who acted
 * @param staffName     name of the staff member who acted
 * @param reason        the recorded reason; may be empty only when reasons are not required
 * @param createdAt     epoch milliseconds when the action was issued
 * @param expiresAt     epoch milliseconds when the action lapses, or 0 for permanent
 * @param active        whether the action is still in force
 * @param durationInput the raw duration the staff member typed, kept for the audit trail
 */
public record Punishment(
        long id,
        PunishmentType type,
        String targetUuid,
        String targetName,
        String staffUuid,
        String staffName,
        String reason,
        long createdAt,
        long expiresAt,
        boolean active,
        String durationInput) {

    public boolean isPermanent() {
        return expiresAt == 0L;
    }

    public boolean isExpired(long now) {
        return !isPermanent() && now >= expiresAt;
    }
}
