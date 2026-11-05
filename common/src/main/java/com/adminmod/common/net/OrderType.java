package com.adminmod.common.net;

/**
 * Every request the client can issue. Ids are part of the wire format and must never be
 * renumbered; new orders are appended with fresh ids.
 */
public enum OrderType {
    /** Console tab: run a server command. Arguments: command. */
    EXECUTE_COMMAND(0, PermissionLevel.CONSOLE),
    /** Players tab: list online players. No arguments. */
    LIST_PLAYERS(1, PermissionLevel.VIEW),
    /** Players tab: details for one player. Arguments: player. */
    PLAYER_INFO(2, PermissionLevel.VIEW),
    /** Players tab: kick. Arguments: player, reason. */
    KICK(3, PermissionLevel.MODERATE),
    /** Players tab: mute. Arguments: player, durationSeconds, reason. */
    MUTE(4, PermissionLevel.MODERATE),
    /** Players tab: unmute. Arguments: player. */
    UNMUTE(5, PermissionLevel.MODERATE),
    /** Players tab: ban. Arguments: player, durationSeconds, reason. */
    BAN(6, PermissionLevel.MODERATE),
    /** Players tab: unban. Arguments: player. */
    UNBAN(7, PermissionLevel.MODERATE),
    /** Moderation tab: issue a warning. Arguments: player, reason. */
    WARN(8, PermissionLevel.MODERATE),
    /** Moderation tab: attach a note. Arguments: player, text. */
    NOTE_ADD(9, PermissionLevel.MODERATE),
    /** Moderation tab: full history for a player. Arguments: player. */
    MODERATION_HISTORY(10, PermissionLevel.VIEW),
    /** OP tab: grant operator. Arguments: player. */
    OP_GRANT(11, PermissionLevel.OP_MANAGE),
    /** OP tab: revoke operator. Arguments: player. */
    OP_REVOKE(12, PermissionLevel.OP_MANAGE),
    /** OP tab: operator status of one player. Arguments: player. */
    OP_STATUS(13, PermissionLevel.VIEW),
    /** OP tab: list operators. No arguments. */
    OP_LIST(14, PermissionLevel.VIEW),
    /** Chat bridge tab: integration status. No arguments. */
    BRIDGE_STATUS(15, PermissionLevel.VIEW),
    /** Settings tab: redacted server settings. No arguments. */
    SERVER_SETTINGS(16, PermissionLevel.VIEW),
    /** Settings tab: change a safe server setting. Arguments: key, value. */
    UPDATE_SETTING(17, PermissionLevel.OP_MANAGE),
    /** Moderation tab: list warnings for a player. Arguments: player. */
    LIST_WARNINGS(18, PermissionLevel.VIEW),
    /** Moderation tab: list notes for a player. Arguments: player. */
    LIST_NOTES(19, PermissionLevel.VIEW),
    /** Moderation tab: remove a punishment. Arguments: player, punishmentId. */
    PARDON(20, PermissionLevel.MODERATE),
    /** Any tab: lightweight server status. No arguments. */
    STATUS(21, PermissionLevel.VIEW),
    /** Pairing tab: pairing subsystem status. No arguments. */
    PAIRING_STATUS(22, PermissionLevel.VIEW),
    /** Pairing tab: mint a one-time pairing key. Arguments: label. */
    PAIRING_GENERATE(23, PermissionLevel.PAIRING),
    /** Pairing tab: list authorised clients. No arguments. */
    PAIRING_LIST(24, PermissionLevel.VIEW),
    /** Pairing tab: revoke a paired client. Arguments: clientId. */
    PAIRING_REVOKE(25, PermissionLevel.PAIRING),
    /** Pairing tab: active sessions. No arguments. */
    SESSION_LIST(26, PermissionLevel.VIEW),
    /** Pairing tab: authentication history. No arguments. */
    AUTH_HISTORY(27, PermissionLevel.VIEW),
    /** Pairing tab: rotate the shared password or a client secret. Arguments: target. */
    CREDENTIAL_ROTATE(28, PermissionLevel.PAIRING),
    /** Pairing tab: grant OP to the authenticated owner. No arguments. */
    OP_GRANT_OWNER(29, PermissionLevel.PAIRING),
    /** Local chat tab: proximity settings and counters. No arguments. */
    LOCAL_CHAT_STATUS(30, PermissionLevel.VIEW),
    /** Local chat tab: change a proximity setting. Arguments: key, value. */
    UPDATE_LOCAL_CHAT(31, PermissionLevel.OP_MANAGE),
    /** Diagnostics tab: live server health and ADMIN overhead. No arguments. */
    DIAGNOSTICS_STATUS(32, PermissionLevel.VIEW),
    /** Diagnostics tab: recent server and staff events. No arguments. */
    DIAGNOSTICS_HISTORY(33, PermissionLevel.VIEW),
    /** Diagnostics tab: write a diagnostic report and return its path. No arguments. */
    EXPORT_REPORT(34, PermissionLevel.OP_MANAGE),
    /** Settings tab: apply an optimisation profile. Arguments: profile. */
    SET_PROFILE(35, PermissionLevel.OP_MANAGE),
    /** Protection tab: live anti-crash thresholds, counters and recent triggers. No arguments. */
    PROTECTION_STATUS(36, PermissionLevel.VIEW),
    /** Protection tab: clear the violation counters. No arguments. */
    CLEAR_VIOLATIONS(37, PermissionLevel.OP_MANAGE),
    /** Audit tab: search the audit log. Arguments: player, category, minSeverity, from, to, item, block, pos, container, text, limit. */
    AUDIT_SEARCH(38, PermissionLevel.VIEW),
    /** Audit tab: audit store health and counters. No arguments. */
    AUDIT_STATUS(39, PermissionLevel.VIEW),
    /** Audit tab: list archive files. No arguments. */
    AUDIT_ARCHIVES(40, PermissionLevel.VIEW),
    /** Audit tab: write a search result to a file. Arguments: format, player, category, text. */
    AUDIT_EXPORT(41, PermissionLevel.OP_MANAGE),
    /** Audit tab: force a compression pass now. No arguments. */
    AUDIT_COMPRESS(42, PermissionLevel.OP_MANAGE),
    /** Config tab: the whole settings catalogue plus live values. No arguments. */
    SETTINGS_CATALOG(43, PermissionLevel.VIEW),
    /** Config tab: change one setting. Arguments: path, value. */
    SETTINGS_UPDATE(44, PermissionLevel.OP_MANAGE),
    /** Config tab: apply a batch of changes atomically. Arguments: json (path to value object). */
    SETTINGS_UPDATE_BATCH(45, PermissionLevel.OP_MANAGE),
    /** Config tab: reset a setting, a category or a profile. Arguments: scope, name. */
    SETTINGS_RESET(46, PermissionLevel.OP_MANAGE),
    /** Config tab: recent configuration changes. No arguments. */
    SETTINGS_HISTORY(47, PermissionLevel.VIEW),
    /** Config tab: restore the previous value of the last change. No arguments. */
    SETTINGS_ROLLBACK(48, PermissionLevel.OP_MANAGE),
    /** Config tab: configuration diagnostics. No arguments. */
    SETTINGS_DIAGNOSTICS(49, PermissionLevel.VIEW),
    /** Profiles tab: list profiles and presets. No arguments. */
    PROFILE_LIST(50, PermissionLevel.VIEW),
    /** Profiles tab: preview what a profile would change. Arguments: name. */
    PROFILE_PREVIEW(51, PermissionLevel.VIEW),
    /** Profiles tab: apply a profile. Arguments: name. */
    PROFILE_APPLY(52, PermissionLevel.OP_MANAGE),
    /** Profiles tab: create a profile from the current configuration. Arguments: name, description. */
    PROFILE_CREATE(53, PermissionLevel.OP_MANAGE),
    /** Profiles tab: rename a profile. Arguments: oldName, newName. */
    PROFILE_RENAME(54, PermissionLevel.OP_MANAGE),
    /** Profiles tab: duplicate a profile. Arguments: source, newName. */
    PROFILE_DUPLICATE(55, PermissionLevel.OP_MANAGE),
    /** Profiles tab: delete a custom profile. Arguments: name. */
    PROFILE_DELETE(56, PermissionLevel.OP_MANAGE),
    /** Profiles tab: export a profile as JSON. Arguments: name. */
    PROFILE_EXPORT(57, PermissionLevel.VIEW),
    /** Profiles tab: import a profile. Arguments: json, applyNow. */
    PROFILE_IMPORT(58, PermissionLevel.OP_MANAGE),
    /** Config tab: back up the configuration now. No arguments. */
    SETTINGS_BACKUP(59, PermissionLevel.OP_MANAGE);

    /** Coarse capability required to run the order; refined by the server permission model. */
    public enum PermissionLevel {
        VIEW,
        MODERATE,
        OP_MANAGE,
        CONSOLE,
        /** Access-management orders: owner or an explicitly authorised administrator. */
        PAIRING
    }

    private final int id;
    private final PermissionLevel permissionLevel;

    OrderType(int id, PermissionLevel permissionLevel) {
        this.id = id;
        this.permissionLevel = permissionLevel;
    }

    public int id() {
        return id;
    }

    public PermissionLevel permissionLevel() {
        return permissionLevel;
    }

    public static OrderType byId(int id) {
        for (OrderType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new ProtocolException("unknown order type " + id);
    }
}
