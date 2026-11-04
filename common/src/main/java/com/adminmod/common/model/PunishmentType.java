package com.adminmod.common.model;

/** Kind of moderation action recorded in the history. */
public enum PunishmentType {
    WARNING,
    MUTE,
    BAN,
    KICK,
    NOTE;

    public boolean isPermanentCapable() {
        return this == MUTE || this == BAN;
    }
}
