package com.adminmod.server;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.config.ConfigLoader;
import com.adminmod.common.net.AdminProtocol;
import com.adminmod.server.auth.CredentialService;
import com.adminmod.server.auth.PermissionService;
import com.adminmod.server.chat.ChatBridgeService;
import com.adminmod.server.command.AdminCommand;
import com.adminmod.server.moderation.ModerationService;
import com.adminmod.server.moderation.ModerationStore;
import com.adminmod.server.net.AdminPayload;
import com.adminmod.server.remote.RemoteRequestHandler;
import com.adminmod.server.remote.SessionManager;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Server distribution entrypoint.
 *
 * <p>This class never touches a client-only type, so the jar loads on a dedicated server. It wires
 * four subsystems: the public chat bridge, the moderation store, the credential store, and the
 * remote administration endpoint. Everything is created on server start and torn down on stop, so
 * a restart does not leave threads or file handles behind.
 */
public final class AdminServerMod implements ModInitializer {

    public static final String MOD_ID = "adminmod";
    public static final String VERSION = "1.0.0";
    private static final Logger LOGGER = LoggerFactory.getLogger("adminmod");

    private static AdminConfig config;
    private static Path configPath;
    private static CredentialService credentials;
    private static ModerationService moderation;
    private static ChatBridgeService chatBridge;
    private static SessionManager sessions;
    private static RemoteRequestHandler requestHandler;
    private static ServerTicker ticker;

    @Override
    public void onInitialize() {
        // Payload types must be registered before any receiver, and on both sides.
        PayloadTypeRegistry.playC2S().register(AdminPayload.ID, AdminPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AdminPayload.ID, AdminPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        // Registered here, during mod initialisation, so the command is present even though the
        // callback fires while the server builds its dispatcher.
        AdminCommand.register();

        ServerPlayNetworking.registerGlobalReceiver(AdminPayload.ID, (payload, context) -> {
            SessionManager active = sessions;
            if (active == null) {
                return;
            }
            // This callback runs on a Netty I/O thread. Orders execute commands, mutate the player
            // list and write files, none of which is safe off the main thread, so the whole request
            // is scheduled onto the server thread. Decoding and decryption stay off-thread inside
            // handleFrame, so a malformed or unauthenticated frame is still rejected without ever
            // touching the server.
            ServerPlayerEntity player = context.player();
            byte[] data = payload.data();
            MinecraftServer server = context.server();
            server.execute(() -> {
                byte[] response = active.handleFrame(player, data);
                if (response == null) {
                    return;
                }
                // The player may have left while the task was queued; sending to a disconnected
                // player throws, and the answer is worthless by then anyway.
                if (server.getPlayerManager().getPlayer(player.getUuid()) == null) {
                    return;
                }
                ServerPlayNetworking.send(player, new AdminPayload(response));
            });
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SessionManager active = sessions;
            if (active != null) {
                active.close(handler.getPlayer().getUuid());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerTicker active = ticker;
            if (active != null) {
                active.allowJoin(handler.getPlayer());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ServerTicker active = ticker;
            if (active != null) {
                active.tick(server);
            }
        });

        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            ChatBridgeService bridge = chatBridge;
            if (bridge == null) {
                return;
            }
            bridge.onPublicChat(sender.getGameProfile().getName(), sender.getUuidAsString(),
                    message.getContent().getString());
        });

        ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
            ChatBridgeService bridge = chatBridge;
            if (bridge == null || overlay) {
                return;
            }
            bridge.onSystemMessage(message.getString());
        });
    }

    private void onServerStarting(MinecraftServer server) {
        Path runDirectory = server.getRunDirectory();
        configPath = runDirectory.resolve("config").resolve("admin-mod.json");

        try {
            config = ConfigLoader.load(configPath);
        } catch (IOException e) {
            LOGGER.error("Could not read {}; falling back to defaults", configPath, e);
            config = new AdminConfig();
        }

        try {
            credentials = CredentialService.open(runDirectory, config);
            // A generated folder name is persisted so the location is stable across restarts.
            if (config.remoteAdmin.vaultDirectory == null || config.remoteAdmin.vaultDirectory.isBlank()) {
                config.remoteAdmin.vaultDirectory = credentials.directory().getFileName().toString();
                try {
                    ConfigLoader.save(configPath, config);
                } catch (IOException e) {
                    LOGGER.error("Could not persist the credential directory name", e);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Could not open the credential store; remote administration is disabled", e);
            credentials = null;
        }

        ModerationStore store = new ModerationStore(runDirectory.resolve("config").resolve("adminmod-history.json"));
        try {
            store.load();
        } catch (IOException e) {
            LOGGER.error("Could not read the moderation history; starting with an empty record", e);
        }
        moderation = new ModerationService(store, config);

        chatBridge = ChatBridgeService.create(config, server.getSaveProperties().getLevelName(), server);

        PermissionService permissions = new PermissionService(config);
        requestHandler = new RemoteRequestHandler(server, config, configPath, permissions, moderation, chatBridge,
                credentials);
        sessions = new SessionManager(credentials, config, permissions, requestHandler, VERSION);
        ticker = new ServerTicker(config, sessions);

        // Inbound readers are started only now: the sink broadcasts through the server, which
        // must be fully constructed before the first message can arrive.
        chatBridge.startInbound();

        LOGGER.info("ADMIN Mod {} ready (remote admin: {}, chat bridge: {}, inbound chat: {})", VERSION,
                config.remoteAdmin.enabled && credentials != null, config.chatBridge.enabled,
                config.chatBridge.inbound.enabled);
    }

    private void onServerStopping(MinecraftServer server) {
        if (chatBridge != null) {
            chatBridge.close();
        }
        if (sessions != null) {
            sessions.closeAll();
        }
        if (moderation != null) {
            try {
                moderation.store().saveIfDirty();
            } catch (IOException e) {
                LOGGER.error("Could not write the moderation history on shutdown", e);
            }
        }
        sessions = null;
        chatBridge = null;
        moderation = null;
        credentials = null;
        requestHandler = null;
        ticker = null;
    }

    public static AdminConfig config() {
        return config;
    }

    /** Result of a live configuration reload, surfaced to the operator. */
    public record ReloadOutcome(boolean success, String message) {
    }

    /**
     * Re-reads the configuration file and applies it without a restart.
     *
     * <p>The existing {@link AdminConfig} instance is updated in place rather than replaced, so the
     * subsystems that hold a reference to it (moderation, sessions, the ticker) observe the new
     * values. The chat bridge is rebuilt because a provider change or a queue resize cannot be
     * applied incrementally; its inbound readers are restarted so a reader that was enabled or
     * disabled takes effect.
     *
     * @return whether the reload succeeded, with a short description for the operator
     */
    public static ReloadOutcome reloadConfiguration() {
        if (configPath == null || config == null) {
            return new ReloadOutcome(false, "the server is not running");
        }
        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        AdminConfig fresh;
        try {
            fresh = ConfigLoader.load(configPath, report);
        } catch (IOException e) {
            return new ReloadOutcome(false, "the configuration could not be read");
        }
        copyInto(config, fresh);
        if (chatBridge != null) {
            chatBridge.reload();
        }
        if (ticker != null) {
            ticker.resetAnnouncements();
        }
        String detail = report.isEmpty() ? "no corrections were needed"
                : report.notes().size() + " correction(s) applied";
        return new ReloadOutcome(true, detail);
    }

    /** Copies every top-level section of {@code source} onto {@code target}, keeping its identity. */
    private static void copyInto(AdminConfig target, AdminConfig source) {
        target.configVersion = source.configVersion;
        target.chatBridge = source.chatBridge;
        target.moderation = source.moderation;
        target.remoteAdmin = source.remoteAdmin;
        target.permissions = source.permissions;
        target.announcements = source.announcements;
        target.maintenance = source.maintenance;
        target.logging = source.logging;
    }

    public static Path configPath() {
        return configPath;
    }

    public static CredentialService credentials() {
        return credentials;
    }

    public static ModerationService moderation() {
        return moderation;
    }

    public static ChatBridgeService chatBridge() {
        return chatBridge;
    }

    public static SessionManager sessions() {
        return sessions;
    }

    public static RemoteRequestHandler requestHandler() {
        return requestHandler;
    }

    public static String protocolVersion() {
        return Integer.toString(AdminProtocol.PROTOCOL_VERSION);
    }
}
