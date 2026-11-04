package com.adminmod.common.model;

/**
 * Snapshot of a player as shown in the dashboard.
 *
 * @param uuid        player UUID
 * @param name        current or last known username
 * @param online      whether the player is connected right now
 * @param operator    whether the player holds operator status
 * @param muted       whether a mute is currently in force
 * @param banned      whether a ban is currently in force
 * @param warnings    number of active warnings
 * @param ping        last measured latency in milliseconds, or -1 when offline
 * @param address     player IP address, or an empty string when offline
 */
public record PlayerSummary(
        String uuid,
        String name,
        boolean online,
        boolean operator,
        boolean muted,
        boolean banned,
        int warnings,
        int ping,
        String address) {
}
