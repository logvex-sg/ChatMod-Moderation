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
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;

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

    @Override
    public void onInitialize() {
        // Payload types must be registered before any receiver, and on both sides.
        PayloadTypeRegistry.playC2S().register(AdminPayload.ID, AdminPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(AdminPayload.ID, AdminPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTING.register(this::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);

        ServerPlayNetworking.registerGlobalReceiver(AdminPayload.ID, (payload, context) -> {
            SessionManager active = sessions;
            if (active == null) {
                return;
            }
            byte[] response = active.handleFrame(context.player(), payload.data());
            if (response != null) {
                context.responseSender().sendPacket(new AdminPayload(response));
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SessionManager active = sessions;
            if (active != null) {
                active.close(handler.getPlayer().getUuid());
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

        AdminCommand.register(server);

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
    }

    public static AdminConfig config() {
        return config;
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
