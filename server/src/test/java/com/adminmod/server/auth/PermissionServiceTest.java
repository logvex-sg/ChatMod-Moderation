package com.adminmod.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.net.OrderType;

import org.junit.jupiter.api.Test;

/**
 * Authorisation is the single most security-relevant piece of the server jar, so it is tested
 * directly. The property that matters: nothing the client sends can raise its own level.
 */
class PermissionServiceTest {

    private static final UUID OPERATOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PLAYER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static AdminConfig config() {
        return new AdminConfig();
    }

    @Test
    void theFirstOperatorToConnectClaimsOwnership() {
        AdminConfig config = config();
        PermissionService permissions = new PermissionService(config);
        assertTrue(permissions.claimOwnershipIfVacant(OPERATOR, 4));
        assertEquals(OPERATOR, permissions.configuredOwner());
    }

    @Test
    void ownershipCannotBeTakenOverByALaterConnection() {
        AdminConfig config = config();
        PermissionService permissions = new PermissionService(config);
        permissions.claimOwnershipIfVacant(OPERATOR, 4);
        assertFalse(permissions.claimOwnershipIfVacant(PLAYER, 4));
        assertTrue(permissions.isOwner(OPERATOR));
        assertFalse(permissions.isOwner(PLAYER));
    }

    @Test
    void aNonOperatorCannotClaimOwnership() {
        PermissionService permissions = new PermissionService(config());
        assertFalse(permissions.claimOwnershipIfVacant(PLAYER, 0));
        assertNull(permissions.configuredOwner());
    }

    @Test
    void aConfiguredOwnerIsHonouredVerbatim() {
        AdminConfig config = config();
        config.remoteAdmin.ownerUuid = PLAYER.toString();
        PermissionService permissions = new PermissionService(config);
        assertTrue(permissions.isOwner(PLAYER));
        assertFalse(permissions.isOwner(OPERATOR));
        assertFalse(permissions.claimOwnershipIfVacant(OPERATOR, 4));
    }

    @Test
    void aMalformedOwnerUuidIsIgnoredRatherThanCrashing() {
        AdminConfig config = config();
        config.remoteAdmin.ownerUuid = "not-a-uuid";
        PermissionService permissions = new PermissionService(config);
        assertNull(permissions.configuredOwner());
    }

    @Test
    void listedModeratorsGetModerationRights() {
        AdminConfig config = config();
        config.permissions.moderators = java.util.List.of(PLAYER.toString());
        PermissionService permissions = new PermissionService(config);
        assertEquals(config.permissions.moderateLevel, permissions.effectiveLevel(PLAYER, "Alex", 0));
    }

    @Test
    void moderatorsCanBeListedByName() {
        AdminConfig config = config();
        config.permissions.moderators = java.util.List.of("Alex");
        PermissionService permissions = new PermissionService(config);
        assertEquals(config.permissions.moderateLevel, permissions.effectiveLevel(PLAYER, "Alex", 0));
    }

    @Test
    void anOrdinaryPlayerHasNoRights() {
        AdminConfig config = config();
        PermissionService permissions = new PermissionService(config);
        int level = permissions.effectiveLevel(PLAYER, "Alex", 0);
        assertEquals(0, level);
        // Read-only views are available to any authenticated session (the handshake already
        // required the operator password), but no state-changing order is.
        assertTrue(permissions.allows(OrderType.LIST_PLAYERS, level, false));
        assertFalse(permissions.allows(OrderType.BAN, level, false));
        assertFalse(permissions.allows(OrderType.EXECUTE_COMMAND, level, false));
        assertFalse(permissions.allows(OrderType.OP_GRANT, level, false));
    }

    @Test
    void anOperatorMayModerateButNotUseTheConsole() {
        AdminConfig config = config();
        PermissionService permissions = new PermissionService(config);
        assertTrue(permissions.allows(OrderType.BAN, 4, false));
        // Console and OP management are reserved for the owner, not merely for an operator.
        assertFalse(permissions.allows(OrderType.EXECUTE_COMMAND, 4, false));
        assertFalse(permissions.allows(OrderType.OP_GRANT, 4, false));
    }

    @Test
    void theOwnerMayUseTheConsoleAndManageOperators() {
        AdminConfig config = config();
        PermissionService permissions = new PermissionService(config);
        assertTrue(permissions.allows(OrderType.EXECUTE_COMMAND, 4, true));
        assertTrue(permissions.allows(OrderType.OP_GRANT, 4, true));
    }

    @Test
    void disablingRemoteConsoleTakesEffectImmediately() {
        AdminConfig config = config();
        PermissionService permissions = new PermissionService(config);
        assertTrue(permissions.allows(OrderType.EXECUTE_COMMAND, 4, true));
        config.remoteAdmin.allowRemoteConsole = false;
        assertFalse(permissions.allows(OrderType.EXECUTE_COMMAND, 4, true));
    }

    @Test
    void disablingOpManagementTakesEffectImmediately() {
        AdminConfig config = config();
        PermissionService permissions = new PermissionService(config);
        assertTrue(permissions.allows(OrderType.OP_GRANT, 4, true));
        config.remoteAdmin.allowOpManagement = false;
        assertFalse(permissions.allows(OrderType.OP_GRANT, 4, true));
    }
}
