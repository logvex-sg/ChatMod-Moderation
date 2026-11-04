package com.adminmod.server.remote;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.config.ConfigLoader;
import com.adminmod.common.config.ConfigRedactor;
import com.adminmod.common.model.DurationParser;
import com.adminmod.common.model.Json;
import com.adminmod.common.model.PlayerSummary;
import com.adminmod.common.model.Punishment;
import com.adminmod.common.model.PunishmentType;
import com.adminmod.common.net.OrderMessage;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.server.auth.CredentialService;
import com.adminmod.server.auth.PermissionService;
import com.adminmod.server.chat.ChatBridgeService;
import com.adminmod.server.moderation.ModerationService;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

/**
 * Executes remote orders on the server.
 *
 * <p>Every order is validated here, server-side, against the authenticated player's own identity
 * and the live permission level. The client cannot supply an identity, a permission level or a
 * bypass flag; it can only supply arguments. Console commands run through the server's own
 * command dispatcher with a silent output, so nothing is echoed to the console or to chat.
 */
public final class RemoteRequestHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("adminmod");

    private final MinecraftServer server;
    private final AdminConfig config;
    private final Path configPath;
    private final PermissionService permissions;
    private final ModerationService moderation;
    private final ChatBridgeService chatBridge;
    private final CredentialService credentials;

    public RemoteRequestHandler(MinecraftServer server, AdminConfig config, Path configPath,
            PermissionService permissions, ModerationService moderation, ChatBridgeService chatBridge,
            CredentialService credentials) {
        this.server = server;
        this.config = config;
        this.configPath = configPath;
        this.permissions = permissions;
        this.moderation = moderation;
        this.chatBridge = chatBridge;
        this.credentials = credentials;
    }

    /** Live permission level of a connected player: 4 for operators, otherwise 0. */
    public int permissionLevelOf(ServerPlayerEntity player) {
        return server.getPlayerManager().isOperator(player.getGameProfile()) ? server.getOpPermissionLevel() : 0;
    }

    /** Writes the live configuration back to disk. Used when ownership is claimed. */
    public void persistConfig() {
        try {
            ConfigLoader.save(configPath, config);
        } catch (IOException e) {
            LOGGER.warn("Could not persist the configuration", e);
        }
    }

    public OrderResultMessage handle(OrderMessage order, UUID staffUuid, String staffName, boolean owner) {
        try {
            return switch (order.orderType()) {
                case EXECUTE_COMMAND -> executeCommand(order, staffUuid, staffName);
                case LIST_PLAYERS -> listPlayers(order);
                case PLAYER_INFO -> playerInfo(order);
                case KICK -> kick(order, staffUuid, staffName);
                case MUTE -> mute(order, staffUuid, staffName);
                case UNMUTE -> unmute(order, staffUuid, staffName);
                case BAN -> ban(order, staffUuid, staffName);
                case UNBAN -> unban(order, staffUuid, staffName);
                case WARN -> warn(order, staffUuid, staffName);
                case NOTE_ADD -> addNote(order, staffUuid, staffName);
                case MODERATION_HISTORY -> moderationHistory(order);
                case LIST_WARNINGS -> warnings(order);
                case LIST_NOTES -> notes(order);
                case PARDON -> pardon(order, staffUuid, staffName);
                case OP_GRANT -> grantOp(order, staffUuid, staffName);
                case OP_REVOKE -> revokeOp(order, staffUuid, staffName);
                case OP_STATUS -> opStatus(order);
                case OP_LIST -> opList(order);
                case BRIDGE_STATUS -> bridgeStatus(order);
                case SERVER_SETTINGS -> serverSettings(order);
                case UPDATE_SETTING -> updateSetting(order, staffUuid, staffName);
                case STATUS -> status(order);
            };
        } catch (IllegalArgumentException e) {
            return new OrderResultMessage(order.requestId(), false, e.getMessage());
        } catch (RuntimeException e) {
            // Never surface a stack trace or internal detail to the client.
            return new OrderResultMessage(order.requestId(), false, "request failed: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ console

    private OrderResultMessage executeCommand(OrderMessage order, UUID staffUuid, String staffName) {
        if (!config.remoteAdmin.allowRemoteConsole) {
            return failure(order, "remote console is disabled");
        }
        String command = order.argument(0).trim();
        if (command.isEmpty()) {
            return failure(order, "no command given");
        }
        // The result echo of a remote command arrives as a system message; note the command first
        // so the chat bridge does not forward that echo to the external platform.
        chatBridge.noteExecutedCommand(command);
        // Commands run as the console-equivalent source with a silent output, so the execution
        // leaves no trace in the server log or in chat.
        ServerCommandSource source = server.getCommandSource()
                .withOutput(CommandOutput.DUMMY)
                .withSilent()
                .withLevel(config.permissions.adminLevel);
        try {
            ParseResults<ServerCommandSource> parse =
                    server.getCommandManager().getDispatcher().parse(command, source);
            CommandManager.throwException(parse);
            int result = server.getCommandManager().getDispatcher().execute(parse);
            return new OrderResultMessage(order.requestId(), true, "executed (result " + result + ")");
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return failure(order, e.getMessage());
        } catch (RuntimeException e) {
            return failure(order, "command failed: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ players

    private OrderResultMessage listPlayers(OrderMessage order) {
        JsonArray array = new JsonArray();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            array.add(Json.parseObject(Json.write(summaryOf(player))));
        }
        JsonObject root = new JsonObject();
        root.addProperty("count", array.size());
        root.addProperty("max", server.getPlayerManager().getMaxPlayerCount());
        root.add("players", array);
        return success(order, "ok", Json.write(root));
    }

    private PlayerSummary summaryOf(ServerPlayerEntity player) {
        UUID uuid = player.getUuid();
        return new PlayerSummary(
                uuid.toString(),
                player.getGameProfile().getName(),
                true,
                server.getPlayerManager().isOperator(player.getGameProfile()),
                moderation.isMuted(uuid),
                server.getPlayerManager().getUserBanList().contains(player.getGameProfile()),
                moderation.warningCount(uuid),
                player.networkHandler == null ? -1 : player.networkHandler.getLatency(),
                player.getIp());
    }

    private OrderResultMessage playerInfo(OrderMessage order) {
        String name = order.argument(0);
        Optional<ServerPlayerEntity> online = onlinePlayer(name);
        if (online.isPresent()) {
            return success(order, "ok", Json.write(summaryOf(online.get())));
        }
        Optional<GameProfile> profile = resolveProfile(name);
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + name);
        }
        UUID uuid = profile.get().getId();
        PlayerSummary summary = new PlayerSummary(uuid.toString(), profile.get().getName(), false,
                server.getPlayerManager().isOperator(profile.get()),
                moderation.isMuted(uuid),
                server.getPlayerManager().getUserBanList().contains(profile.get()),
                moderation.warningCount(uuid), -1, "");
        return success(order, "ok", Json.write(summary));
    }

    private OrderResultMessage kick(OrderMessage order, UUID staffUuid, String staffName) {
        String name = order.argument(0);
        String reason = order.argument(1);
        Optional<ServerPlayerEntity> target = onlinePlayer(name);
        if (target.isEmpty()) {
            return failure(order, name + " is not online");
        }
        if (reason.isBlank()) {
            reason = config.moderation.defaultKickReason;
        }
        moderation.requireReason(reason);
        ServerPlayerEntity player = target.get();
        player.networkHandler.disconnect(Text.of(reason));
        moderation.record(PunishmentType.KICK, player.getUuid(), player.getGameProfile().getName(), staffUuid,
                staffName, reason, 0L, "kick");
        saveStore();
        return success(order, "kicked " + player.getGameProfile().getName());
    }

    private OrderResultMessage mute(OrderMessage order, UUID staffUuid, String staffName) {
        String name = order.argument(0);
        long seconds = DurationParser.parseSeconds(order.argument(1));
        String reason = order.argument(2);
        Optional<GameProfile> profile = resolveProfile(name);
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + name);
        }
        if (reason.isBlank()) {
            reason = config.moderation.defaultMuteReason;
        }
        moderation.requireReason(reason);
        Punishment punishment = moderation.record(PunishmentType.MUTE, profile.get().getId(), profile.get().getName(),
                staffUuid, staffName, reason, seconds, order.argument(1));
        saveStore();
        broadcast(config.moderation.broadcastPunishments,
                profile.get().getName() + " was muted (" + DurationParser.describe(seconds) + ")" + reasonSuffix(reason));
        return success(order, "muted " + profile.get().getName(),
                Json.write(java.util.Map.of("id", punishment.id())));
    }

    private OrderResultMessage unmute(OrderMessage order, UUID staffUuid, String staffName) {
        Optional<GameProfile> profile = resolveProfile(order.argument(0));
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + order.argument(0));
        }
        Optional<Punishment> revoked = moderation.store().revokeLatest(profile.get().getId(), PunishmentType.MUTE);
        saveStore();
        if (revoked.isEmpty()) {
            return failure(order, profile.get().getName() + " is not muted");
        }
        broadcast(config.moderation.broadcastPunishments, profile.get().getName() + " was unmuted");
        return success(order, "unmuted " + profile.get().getName());
    }

    private OrderResultMessage ban(OrderMessage order, UUID staffUuid, String staffName) {
        String name = order.argument(0);
        long seconds = DurationParser.parseSeconds(order.argument(1));
        String reason = order.argument(2);
        Optional<GameProfile> profile = resolveProfile(name);
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + name);
        }
        final String finalReason = reason.isBlank() ? config.moderation.defaultBanReason : reason;
        moderation.requireReason(finalReason);

        Date now = new Date();
        Date expiry = seconds <= 0L ? null : new Date(now.getTime() + seconds * 1000L);
        BannedPlayerEntry entry = new BannedPlayerEntry(profile.get(), now, staffName, expiry, finalReason);
        server.getPlayerManager().getUserBanList().add(entry);
        try {
            server.getPlayerManager().getUserBanList().save();
        } catch (IOException e) {
            return failure(order, "the ban could not be persisted");
        }

        onlinePlayer(name).ifPresent(player -> player.networkHandler.disconnect(Text.of(finalReason)));
        moderation.record(PunishmentType.BAN, profile.get().getId(), profile.get().getName(), staffUuid, staffName,
                finalReason, seconds, order.argument(1));
        saveStore();
        broadcast(config.moderation.broadcastPunishments,
                profile.get().getName() + " was banned (" + DurationParser.describe(seconds) + ")"
                        + reasonSuffix(finalReason));
        return success(order, "banned " + profile.get().getName());
    }

    private OrderResultMessage unban(OrderMessage order, UUID staffUuid, String staffName) {
        String name = order.argument(0);
        Optional<GameProfile> profile = resolveProfile(name);
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + name);
        }
        var banList = server.getPlayerManager().getUserBanList();
        if (!banList.contains(profile.get())) {
            return failure(order, profile.get().getName() + " is not banned");
        }
        banList.remove(profile.get());
        try {
            banList.save();
        } catch (IOException e) {
            return failure(order, "could not persist the ban list");
        }
        moderation.store().revokeLatest(profile.get().getId(), PunishmentType.BAN);
        saveStore();
        broadcast(config.moderation.broadcastPunishments, profile.get().getName() + " was unbanned");
        return success(order, "unbanned " + profile.get().getName());
    }

    // ------------------------------------------------------------------ moderation

    private OrderResultMessage warn(OrderMessage order, UUID staffUuid, String staffName) {
        String name = order.argument(0);
        String reason = order.argument(1);
        Optional<GameProfile> profile = resolveProfile(name);
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + name);
        }
        moderation.requireReason(reason);
        moderation.record(PunishmentType.WARNING, profile.get().getId(), profile.get().getName(), staffUuid,
                staffName, reason, 0L, "warning");
        saveStore();
        onlinePlayer(name).ifPresent(player -> player.sendMessage(Text.of("Warning: " + reason), false));
        notifyStaff(staffUuid, profile.get().getName() + " was warned by " + staffName
                + reasonSuffix(reason));
        applyEscalation(profile.get());
        return success(order, "warned " + profile.get().getName());
    }

    /**
     * Applies the automatic escalation rule after a warning and announces the outcome.
     *
     * <p>The rule itself lives in {@link ModerationService}, which has no Minecraft dependency; this
     * method only performs the in-game notices, which do.
     */
    private void applyEscalation(GameProfile profile) {
        Optional<ModerationService.Escalation> escalation =
                moderation.applyEscalation(profile.getId(), profile.getName());
        if (escalation.isEmpty()) {
            return;
        }
        ModerationService.Escalation fired = escalation.get();
        saveStore();
        onlinePlayer(profile.getName()).ifPresent(player -> player.sendMessage(
                Text.of("You have been muted automatically: " + fired.punishment().reason()), false));
        broadcast(config.moderation.broadcastPunishments, profile.getName()
                + " was muted automatically after " + fired.recentWarnings() + " warnings"
                + reasonSuffix(fired.punishment().reason()));
    }

    /** Sends a notice to online staff, so an action taken remotely is visible in-game. */
    private void notifyStaff(UUID actorUuid, String message) {
        if (!config.moderation.notifyStaff || message == null || message.isBlank()) {
            return;
        }
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (player.getUuid().equals(actorUuid)) {
                continue;
            }
            if (player.hasPermissionLevel(config.permissions.moderateLevel)) {
                player.sendMessage(Text.of("[ADMIN] " + message), false);
            }
        }
    }

    private OrderResultMessage addNote(OrderMessage order, UUID staffUuid, String staffName) {
        String name = order.argument(0);
        String text = order.argument(1);
        Optional<GameProfile> profile = resolveProfile(name);
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + name);
        }
        if (text.isBlank()) {
            return failure(order, "the note is empty");
        }
        moderation.store().addNote(profile.get().getId(), staffUuid, staffName, text, System.currentTimeMillis());
        saveStore();
        return success(order, "note added to " + profile.get().getName());
    }

    private OrderResultMessage moderationHistory(OrderMessage order) {
        Optional<GameProfile> profile = resolveProfile(order.argument(0));
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + order.argument(0));
        }
        return success(order, "ok", moderation.historyJson(profile.get().getId()));
    }

    private OrderResultMessage warnings(OrderMessage order) {
        Optional<GameProfile> profile = resolveProfile(order.argument(0));
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + order.argument(0));
        }
        return success(order, "ok", moderation.warningsJson(profile.get().getId()));
    }

    private OrderResultMessage notes(OrderMessage order) {
        Optional<GameProfile> profile = resolveProfile(order.argument(0));
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + order.argument(0));
        }
        return success(order, "ok", moderation.notesJsonView(profile.get().getId()));
    }

    private OrderResultMessage pardon(OrderMessage order, UUID staffUuid, String staffName) {
        long id;
        try {
            id = Long.parseLong(order.argument(1).trim());
        } catch (NumberFormatException e) {
            return failure(order, "invalid punishment id");
        }
        Optional<Punishment> revoked = moderation.store().revoke(id);
        saveStore();
        if (revoked.isEmpty()) {
            return failure(order, "no active punishment with id " + id);
        }
        Punishment punishment = revoked.get();
        if (punishment.type() == PunishmentType.BAN) {
            Optional<GameProfile> profile = resolveProfile(punishment.targetName());
            profile.ifPresent(gameProfile -> {
                server.getPlayerManager().getUserBanList().remove(gameProfile);
                try {
                    server.getPlayerManager().getUserBanList().save();
                } catch (IOException ignored) {
                    // The in-memory list is already updated; the next save will retry.
                }
            });
        }
        return success(order, "pardoned punishment " + id);
    }

    // ------------------------------------------------------------------ operators

    private OrderResultMessage grantOp(OrderMessage order, UUID staffUuid, String staffName) {
        if (!config.remoteAdmin.allowOpManagement) {
            return failure(order, "OP management is disabled");
        }
        Optional<GameProfile> profile = resolveProfile(order.argument(0));
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + order.argument(0));
        }
        server.getPlayerManager().addToOperators(profile.get());
        // Apply the new level to a connected player immediately.
        onlinePlayer(order.argument(0)).ifPresent(player -> {
            server.getPlayerManager().sendCommandTree(player);
            player.sendMessage(Text.of("You are now an operator"), false);
        });
        return success(order, "granted OP to " + profile.get().getName());
    }

    private OrderResultMessage revokeOp(OrderMessage order, UUID staffUuid, String staffName) {
        if (!config.remoteAdmin.allowOpManagement) {
            return failure(order, "OP management is disabled");
        }
        Optional<GameProfile> profile = resolveProfile(order.argument(0));
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + order.argument(0));
        }
        server.getPlayerManager().removeFromOperators(profile.get());
        onlinePlayer(order.argument(0)).ifPresent(player -> {
            server.getPlayerManager().sendCommandTree(player);
            player.sendMessage(Text.of("You are no longer an operator"), false);
        });
        return success(order, "revoked OP from " + profile.get().getName());
    }

    private OrderResultMessage opStatus(OrderMessage order) {
        Optional<GameProfile> profile = resolveProfile(order.argument(0));
        if (profile.isEmpty()) {
            return failure(order, "no such player: " + order.argument(0));
        }
        JsonObject root = new JsonObject();
        root.addProperty("name", profile.get().getName());
        root.addProperty("uuid", profile.get().getId().toString());
        root.addProperty("operator", server.getPlayerManager().isOperator(profile.get()));
        return success(order, "ok", Json.write(root));
    }

    private OrderResultMessage opList(OrderMessage order) {
        JsonArray array = new JsonArray();
        for (String name : server.getPlayerManager().getOpNames()) {
            JsonObject object = new JsonObject();
            object.addProperty("name", name);
            Optional<GameProfile> profile = resolveProfile(name);
            object.addProperty("uuid", profile.map(p -> p.getId().toString()).orElse(""));
            object.addProperty("online", onlinePlayer(name).isPresent());
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.addProperty("count", array.size());
        root.add("operators", array);
        return success(order, "ok", Json.write(root));
    }

    // ------------------------------------------------------------------ bridge and settings

    private OrderResultMessage bridgeStatus(OrderMessage order) {
        return success(order, "ok", chatBridge.statusJson());
    }

    private OrderResultMessage serverSettings(OrderMessage order) {
        return success(order, "ok", Json.write(ConfigRedactor.view(config)));
    }

    private OrderResultMessage updateSetting(OrderMessage order, UUID staffUuid, String staffName) {
        String key = order.argument(0).toLowerCase(Locale.ROOT);
        String value = order.argument(1);
        boolean changed = applySetting(key, value);
        if (!changed) {
            return failure(order, "unknown or invalid setting: " + key);
        }
        try {
            ConfigLoader.save(configPath, config);
        } catch (IOException e) {
            return failure(order, "the configuration could not be written");
        }
        // A bridge setting can change which providers exist or how the queue is sized, so the
        // bridge is rebuilt and its inbound readers restarted.
        if (key.startsWith("chatbridge.") || key.startsWith("moderation.")) {
            chatBridge.reload();
        }
        return success(order, "updated " + key, Json.write(ConfigRedactor.view(config)));
    }

    private boolean applySetting(String key, String value) {
        boolean flag = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("on") || value.equals("1");
        switch (key) {
            case "chatbridge.enabled" -> config.chatBridge.enabled = flag;
            case "chatbridge.publicchatonly" -> config.chatBridge.publicChatOnly = flag;
            case "chatbridge.forwardcommands" -> config.chatBridge.forwardCommands = flag;
            case "chatbridge.forwardprivatemessages" -> config.chatBridge.forwardPrivateMessages = flag;
            case "chatbridge.forwardjoinleave" -> config.chatBridge.forwardJoinLeave = flag;
            case "chatbridge.antispam" -> config.chatBridge.antiSpam.enabled = flag;
            case "chatbridge.stripformatting" -> config.chatBridge.stripFormatting = flag;
            case "chatbridge.provider" -> {
                if (!ChatBridgeService.isValidProvider(value)) {
                    return false;
                }
                config.chatBridge.provider = value.toLowerCase(Locale.ROOT);
            }
            case "moderation.requirereasons" -> config.moderation.requireReasons = flag;
            case "moderation.broadcastpunishments" -> config.moderation.broadcastPunishments = flag;
            case "moderation.notifystaff" -> config.moderation.notifyStaff = flag;
            case "moderation.escalation.enabled" -> config.moderation.escalation.enabled = flag;
            case "remoteadmin.allowremoteconsole" -> config.remoteAdmin.allowRemoteConsole = flag;
            case "remoteadmin.allowopmanagement" -> config.remoteAdmin.allowOpManagement = flag;
            case "maintenance.enabled" -> config.maintenance.enabled = flag;
            case "announcements.enabled" -> config.announcements.enabled = flag;
            default -> {
                return false;
            }
        }
        return true;
    }

    private OrderResultMessage status(OrderMessage order) {
        JsonObject root = new JsonObject();
        root.addProperty("server", server.getName());
        root.addProperty("version", server.getVersion());
        root.addProperty("dedicated", server.isDedicated());
        root.addProperty("players", server.getPlayerManager().getPlayerList().size());
        root.addProperty("maxPlayers", server.getPlayerManager().getMaxPlayerCount());
        root.addProperty("uptimeMillis", server.getTicks() * 50L);
        root.addProperty("bridgeEnabled", config.chatBridge.enabled);
        root.addProperty("bridgeProvider", chatBridge.provider().id());
        root.addProperty("ownerConfigured", permissions.configuredOwner() != null);
        root.addProperty("credentialDirectory", credentials.directory().getFileName().toString());
        return success(order, "ok", Json.write(root));
    }

    // ------------------------------------------------------------------ helpers

    /** Reveals the generated password; only the owner may read it, and only through the handshake. */
    public String revealPassword() {
        return credentials.password();
    }

    private Optional<ServerPlayerEntity> onlinePlayer(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(server.getPlayerManager().getPlayer(name.trim()));
    }

    private Optional<GameProfile> resolveProfile(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        if (Json.isUuid(trimmed)) {
            UUID uuid = UUID.fromString(trimmed);
            ServerPlayerEntity online = server.getPlayerManager().getPlayer(uuid);
            if (online != null) {
                return Optional.of(online.getGameProfile());
            }
            if (server.getUserCache() != null) {
                Optional<GameProfile> cached = server.getUserCache().getByUuid(uuid);
                if (cached.isPresent()) {
                    return cached;
                }
            }
            return Optional.of(new GameProfile(uuid, trimmed));
        }
        ServerPlayerEntity online = server.getPlayerManager().getPlayer(trimmed);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        if (server.getUserCache() != null) {
            Optional<GameProfile> cached = server.getUserCache().findByName(trimmed);
            if (cached.isPresent()) {
                return cached;
            }
        }
        // Offline-mode servers and uncached names still resolve to a deterministic offline UUID.
        List<String> names = new ArrayList<>(List.of(trimmed));
        return Optional.of(new GameProfile(
                net.minecraft.util.Uuids.getOfflinePlayerUuid(names.get(0)), names.get(0)));
    }

    private void broadcast(boolean enabled, String message) {
        if (enabled) {
            server.getPlayerManager().broadcast(Text.of(message), false);
        }
    }

    private String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : ": " + reason;
    }

    private void saveStore() {
        try {
            moderation.store().save();
        } catch (IOException ignored) {
            // The in-memory record is authoritative for this session; the next write retries.
        }
    }

    private OrderResultMessage success(OrderMessage order, String message) {
        return new OrderResultMessage(order.requestId(), true, message);
    }

    private OrderResultMessage success(OrderMessage order, String message, String json) {
        return new OrderResultMessage(order.requestId(), true, message, json);
    }

    private OrderResultMessage failure(OrderMessage order, String message) {
        return new OrderResultMessage(order.requestId(), false, message == null ? "request failed" : message);
    }
}
