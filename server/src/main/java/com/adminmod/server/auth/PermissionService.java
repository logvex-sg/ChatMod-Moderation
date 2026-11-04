package com.adminmod.server.auth;

import java.util.UUID;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.model.Json;

/**
 * Server-side authorisation rules for remote requests.
 *
 * <p>Every check is performed here, on the server, against the authenticated player's own UUID
 * and the live permission level read from the server. Nothing the client sends can raise its own
 * privileges: a request carries only its arguments, never an identity or a claimed level.
 */
public final class PermissionService {

    private final AdminConfig config;

    public PermissionService(AdminConfig config) {
        this.config = config;
    }

    /**
     * The configured owner, or {@code null} when none is set.
     *
     * <p>When {@code ownerUuid} is empty the first player whose Minecraft permission level is at
     * least the configured admin level becomes the owner, and the UUID is written back to the
     * configuration so the choice is stable across restarts.
     */
    public UUID configuredOwner() {
        String configured = config.remoteAdmin.ownerUuid;
        if (configured == null || configured.isBlank() || !Json.isUuid(configured)) {
            return null;
        }
        return UUID.fromString(configured.trim());
    }

    /**
     * Claims ownership for a player when no owner has been set yet.
     *
     * @return {@code true} when the player is the owner after this call
     */
    public boolean claimOwnershipIfVacant(UUID playerUuid, int permissionLevel) {
        if (configuredOwner() != null) {
            return configuredOwner().equals(playerUuid);
        }
        if (permissionLevel >= config.permissions.adminLevel) {
            config.remoteAdmin.ownerUuid = playerUuid.toString();
            return true;
        }
        return false;
    }

    public boolean isOwner(UUID playerUuid) {
        UUID owner = configuredOwner();
        return owner != null && owner.equals(playerUuid);
    }

    /** @return the effective permission level for a player, taking the moderator list into account. */
    public int effectiveLevel(UUID playerUuid, String playerName, int minecraftLevel) {
        if (isOwner(playerUuid)) {
            return Math.max(minecraftLevel, config.permissions.adminLevel);
        }
        for (String entry : config.permissions.moderators) {
            if (entry != null && (entry.equalsIgnoreCase(playerUuid.toString())
                    || entry.equalsIgnoreCase(playerName))) {
                return Math.max(minecraftLevel, config.permissions.moderateLevel);
            }
        }
        return minecraftLevel;
    }

    /** Whether a request of the given level is permitted for a player with the given level. */
    public boolean allows(com.adminmod.common.net.OrderType order, int effectiveLevel, boolean owner) {
        return switch (order.permissionLevel()) {
            case VIEW -> effectiveLevel >= config.permissions.viewLevel;
            case MODERATE -> effectiveLevel >= config.permissions.moderateLevel;
            case OP_MANAGE -> owner && config.remoteAdmin.allowOpManagement
                    && effectiveLevel >= config.permissions.adminLevel;
            case CONSOLE -> owner && config.remoteAdmin.allowRemoteConsole
                    && effectiveLevel >= config.permissions.adminLevel;
        };
    }
}
