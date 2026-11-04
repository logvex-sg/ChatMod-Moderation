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
 *
 * <p>Four roles are recognised. They are derived, never supplied: the owner is the configured (or
 * first-claiming) operator, an administrator is a player whose live Minecraft permission level is
 * at least {@code permissions.adminLevel}, a moderator is at least {@code permissions.moderateLevel}
 * or is named in {@code permissions.moderators}, and everyone else is a viewer.
 */
public final class PermissionService {

    /** The role a request is evaluated against. */
    public enum Role {
        OWNER,
        ADMINISTRATOR,
        MODERATOR,
        VIEWER
    }

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

    /**
     * Resolves the role for a player from their live permission level.
     *
     * <p>This is the single place the role is decided, so "what can a moderator do" has one answer
     * rather than one per order. It takes the already-computed effective level so the owner and the
     * moderator list are honoured, and it never reads anything the client supplied.
     */
    public Role role(UUID playerUuid, int effectiveLevel) {
        if (isOwner(playerUuid)) {
            return Role.OWNER;
        }
        if (effectiveLevel >= config.permissions.adminLevel) {
            return Role.ADMINISTRATOR;
        }
        if (effectiveLevel >= config.permissions.moderateLevel) {
            return Role.MODERATOR;
        }
        return Role.VIEWER;
    }

    /**
     * Whether an order is permitted for a role.
     *
     * <p>Access-management orders (pairing keys, revocation, rotation) require the owner or an
     * administrator; OP management and the console require the owner; moderation requires a
     * moderator or above; reads require any authenticated role.
     */
    public boolean allows(com.adminmod.common.net.OrderType order, Role role) {
        return switch (order.permissionLevel()) {
            case VIEW -> true;
            case MODERATE -> role == Role.OWNER || role == Role.ADMINISTRATOR || role == Role.MODERATOR;
            case OP_MANAGE -> role == Role.OWNER && config.remoteAdmin.allowOpManagement;
            case CONSOLE -> role == Role.OWNER && config.remoteAdmin.allowRemoteConsole;
            case PAIRING -> role == Role.OWNER || role == Role.ADMINISTRATOR;
        };
    }
}
