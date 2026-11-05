package com.adminmod.common.audit;

import java.util.Locale;

/**
 * How serious an audit event is.
 *
 * <p>The ordinal is stored in the archive records, so the order must never change. The dashboard
 * filters on this, and retention treats {@link #HIGH} and above as security-relevant.
 */
public enum AuditSeverity {
    /** Routine activity: a join, a block break, an ordinary command. */
    INFO(0),
    /** Worth noticing: a failed action, a rate limit approached. */
    NOTICE(1),
    /** A moderation action or a protection trigger. */
    WARNING(2),
    /** A security-relevant event: a ban, a permission change, an authentication failure. */
    HIGH(3),
    /** A serious security event: a bypass attempt, a repeated authentication failure. */
    CRITICAL(4);

    private final int id;

    AuditSeverity(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    /** Whether retention must protect an event of this severity from the ordinary sweep. */
    public boolean isSecurityRelevant() {
        return this.ordinal() >= WARNING.ordinal();
    }

    public static AuditSeverity byId(int id) {
        for (AuditSeverity severity : values()) {
            if (severity.id == id) {
                return severity;
            }
        }
        return INFO;
    }

    public static AuditSeverity parse(String name) {
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
