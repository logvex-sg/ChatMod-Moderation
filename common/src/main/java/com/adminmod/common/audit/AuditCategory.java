package com.adminmod.common.audit;

import java.util.Locale;

/**
 * The kind of thing an audit event records.
 *
 * <p>The name is part of the wire format and of the stored records, so an existing value must never
 * be renamed. New categories are appended. The configuration lists the categories that are
 * recorded, so an operator can drop the noisy ones without losing the security-relevant ones.
 */
public enum AuditCategory {
    /** Join, leave, death, respawn and dimension change. */
    PLAYER,
    /** Position samples and movement anomalies, recorded coarsely. */
    MOVEMENT,
    /** Block placement and breaking. */
    BLOCK,
    /** Container opening, closing and item transfers. */
    CONTAINER,
    /** Commands a player or the remote console ran. */
    COMMAND,
    /** Warnings, mutes, bans, kicks, notes and pardons. */
    MODERATION,
    /** Operator grants, revokes and permission changes. */
    PERMISSION,
    /** Anti-cheat triggers: packet floods, spam, oversized payloads. */
    ANTICHEAT,
    /** Anti-dupe triggers: invalid clicks, repeat clicks, suspicious transfers. */
    ANTIDUPE,
    /** Configuration changes made through ADMIN, including profile switches. */
    CONFIG,
    /** Authentication and remote-administration security events. */
    SECURITY,
    /** Server lifecycle: start, stop, reload, export. */
    SERVER,
    /** Player chat, only when explicitly enabled. Never private messages. */
    CHAT,
    /** Staff activity that is not a punishment, such as a remote console action. */
    STAFF;

    /** Whether a category holds security-relevant events that retention must protect. */
    public boolean isSecurityRelevant() {
        return this == MODERATION || this == PERMISSION || this == SECURITY || this == ANTICHEAT;
    }

    /** @return the category for a configured name, or {@code null} when it is not recognised. */
    public static AuditCategory parse(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
