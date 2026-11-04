package com.adminmod.server;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adminmod.common.cache.PlayerCache;
import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.config.ConfigLoader;
import com.adminmod.common.config.ModularConfigLoader;
import com.adminmod.common.diagnostics.ErrorReporter;
import com.adminmod.common.diagnostics.EventHistory;
import com.adminmod.common.diagnostics.MetricsRecorder;
import com.adminmod.common.localchat.LocalChatFile;
import com.adminmod.common.net.AdminProtocol;
import com.adminmod.common.protection.ProtectionManager;
import com.adminmod.common.protection.ViolationLog;
import com.adminmod.common.model.PlayerSummary;
import com.adminmod.server.auth.CredentialService;
import com.adminmod.server.auth.PairingService;
import com.adminmod.server.auth.PermissionService;
import com.adminmod.server.chat.ChatBridgeService;
import com.adminmod.server.chat.LocalChatService;
import com.adminmod.server.command.AdminCommand;
import com.adminmod.server.diagnostics.DiagnosticsService;
import com.adminmod.server.diagnostics.PerformanceMonitor;
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
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
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
    private static Path configDirectory;
    private static Path localChatPath;
    private static CredentialService credentials;
    private static PairingService pairing;
    private static ModerationService moderation;
    private static ChatBridgeService chatBridge;
    private static LocalChatService localChat;
    private static SessionManager sessions;
    private static RemoteRequestHandler requestHandler;
    private static ServerTicker ticker;
    private static ProtectionManager protection;
    private static PlayerCache<PlayerSummary> playerCache;
    private static ErrorReporter errors;
    private static EventHistory events;
    private static MetricsRecorder metrics;
    private static PerformanceMonitor performance;
    private static DiagnosticsService diagnostics;
    private static long startedAt = System.currentTimeMillis();

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
            ProtectionManager guard = protection;
            if (guard != null) {
                ServerPlayerEntity player = handler.getPlayer();
                guard.onLeave(player.getUuidAsString(), player.getIp());
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerTicker active = ticker;
            if (active != null) {
                active.allowJoin(handler.getPlayer());
            }
            ProtectionManager guard = protection;
            if (guard != null) {
                guard.onJoin(handler.getPlayer().getUuidAsString(), System.currentTimeMillis());
            }
        });

        // Connection-level anti-crash: an address that opens connections faster than the configured
        // rate is refused before it ever reaches the login handler. This runs on a netty thread, so
        // it only touches the limiter, never the server.
        ServerLoginConnectionEvents.INIT.register((handler, server) -> {
            ProtectionManager guard = protection;
            if (guard == null) {
                return;
            }
            String address = handler.getConnectionInfo();
            if (!guard.allowConnection(address, System.currentTimeMillis())) {
                handler.disconnect(net.minecraft.text.Text.literal(
                        "Too many connection attempts; please wait a moment and try again."));
            }
        });

        ServerLoginConnectionEvents.DISCONNECT.register((handler, server) -> {
            ProtectionManager guard = protection;
            if (guard != null) {
                guard.onConnectionClosed(handler.getConnectionInfo(), System.currentTimeMillis());
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long start = System.nanoTime();
            ServerTicker active = ticker;
            if (active != null) {
                active.tick(server);
            }
            PerformanceMonitor monitor = performance;
            if (monitor != null) {
                monitor.recordAdminTickNanos(System.nanoTime() - start);
                monitor.tick(server);
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

        // Proximity routing runs inside the broadcast, before delivery and before CHAT_MESSAGE. When
        // the service takes the message over it returns false, which cancels the vanilla broadcast so
        // nobody receives the message twice, and forwards it itself with its channel tag.
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            LocalChatService local = localChat;
            if (local == null) {
                return true;
            }
            return !local.dispatch(sender, message.getSignedContent());
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
        configDirectory = runDirectory.resolve("config").resolve("admin");
        configPath = runDirectory.resolve("config").resolve("admin-mod.json");

        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        try {
            config = ModularConfigLoader.load(configDirectory, configPath, report);
        } catch (IOException e) {
            LOGGER.error("Could not read the configuration under {}; falling back to defaults", configDirectory, e);
            config = new AdminConfig();
        }
        for (String note : report.notes()) {
            LOGGER.info("Configuration: {}", note);
        }
        startedAt = System.currentTimeMillis();

        // Diagnostics are built first: every other subsystem reports through them.
        errors = new ErrorReporter(64, 32, null);
        events = new EventHistory(config.performance.eventHistory);
        metrics = new MetricsRecorder(config.performance.metricHistory);
        protection = new ProtectionManager(config, errors, new ViolationLog(256));
        playerCache = new PlayerCache<>(config.performance.playerCacheLimit, config.performance.playerCacheMillis);
        performance = new PerformanceMonitor(config, metrics, events);

        try {
            credentials = CredentialService.open(runDirectory, config);
            // A generated folder name is persisted so the location is stable across restarts.
            if (config.remoteAdmin.vaultDirectory == null || config.remoteAdmin.vaultDirectory.isBlank()) {
                config.remoteAdmin.vaultDirectory = credentials.directory().getFileName().toString();
                try {
                    ModularConfigLoader.save(configDirectory, config);
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

        // Proximity settings live in their own file so they can be tuned without touching the
        // module files. When that file exists it is authoritative; otherwise the section in
        // localchat.json supplies the defaults and the file is created from it.
        Path localChatPath = configDirectory.resolve("localchat.json");
        AdminServerMod.localChatPath = localChatPath;
        config.localChat = LocalChatFile.load(localChatPath, config.localChat);

        chatBridge = ChatBridgeService.create(config, server.getSaveProperties().getLevelName(), server);
        localChat = new LocalChatService(config, server, chatBridge, moderation);

        PermissionService permissions = new PermissionService(config);
        requestHandler = new RemoteRequestHandler(server, config, configPath, permissions, moderation, chatBridge,
                credentials);
        requestHandler.setLocalChat(localChat, localChatPath);

        PairingService localPairing = null;
        if (credentials != null) {
            try {
                Path registryPath = runDirectory.resolve(config.remoteAdmin.clientRegistryFile).normalize();
                localPairing = new PairingService(config, registryPath);
                requestHandler.setPairing(localPairing);
            } catch (IOException e) {
                LOGGER.error("Could not open the paired-client registry; pairing is disabled", e);
            }
        }
        pairing = localPairing;

        sessions = new SessionManager(credentials, config, permissions, requestHandler, localPairing, VERSION);
        requestHandler.setSessions(sessions);

        Path reportsDirectory = configDirectory.resolve("reports");
        diagnostics = new DiagnosticsService(config, server, performance, errors, events, metrics, reportsDirectory);
        requestHandler.setDiagnostics(diagnostics);
        requestHandler.setProtection(protection);

        ticker = new ServerTicker(config, sessions);

        // Inbound readers are started only now: the sink broadcasts through the server, which
        // must be fully constructed before the first message can arrive.
        chatBridge.startInbound();

        LOGGER.info("ADMIN Mod {} ready (remote admin: {}, chat bridge: {}, inbound chat: {}, local chat: {})", VERSION,
                config.remoteAdmin.enabled && credentials != null, config.chatBridge.enabled,
                config.chatBridge.inbound.enabled, config.localChat.enabled);
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
        localChat = null;
        moderation = null;
        credentials = null;
        pairing = null;
        requestHandler = null;
        ticker = null;
        diagnostics = null;
        performance = null;
        playerCache = null;
        if (protection != null) {
            protection.clear();
        }
        protection = null;
        errors = null;
        events = null;
        metrics = null;
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
        if (configDirectory == null || config == null) {
            return new ReloadOutcome(false, "the server is not running");
        }
        ConfigLoader.ValidationReport report = new ConfigLoader.ValidationReport();
        AdminConfig fresh;
        try {
            fresh = ModularConfigLoader.load(configDirectory, configPath, report);
        } catch (IOException e) {
            return new ReloadOutcome(false, "the configuration could not be read");
        }
        copyInto(config, fresh);
        // The proximity file is authoritative when present, so a reload reads it rather than the
        // stale copy inside the module files.
        if (localChatPath != null) {
            config.localChat = LocalChatFile.load(localChatPath, config.localChat);
        }
        if (protection != null) {
            protection.reload();
        }
        if (chatBridge != null) {
            chatBridge.reload();
        }
        if (localChat != null) {
            localChat.reload();
        }
        if (ticker != null) {
            ticker.resetAnnouncements();
        }
        if (events != null) {
            events.server("configuration reloaded (" + (report.isEmpty() ? "no corrections" : report.notes().size()
                    + " correction(s)") + ")");
        }
        String detail = report.isEmpty() ? "no corrections were needed"
                : report.notes().size() + " correction(s) applied";
        return new ReloadOutcome(true, detail);
    }

    /** Copies every top-level section of {@code source} onto {@code target}, keeping its identity. */
    private static void copyInto(AdminConfig target, AdminConfig source) {
        target.configVersion = source.configVersion;
        target.profile = source.profile;
        target.general = source.general;
        target.chatBridge = source.chatBridge;
        target.localChat = source.localChat;
        target.moderation = source.moderation;
        target.remoteAdmin = source.remoteAdmin;
        target.permissions = source.permissions;
        target.announcements = source.announcements;
        target.maintenance = source.maintenance;
        target.anticheat = source.anticheat;
        target.antidupe = source.antidupe;
        target.protection = source.protection;
        target.performance = source.performance;
        target.alerts = source.alerts;
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

    public static LocalChatService localChat() {
        return localChat;
    }

    /** The file the proximity settings are stored in, or {@code null} before the server starts. */
    public static Path localChatPath() {
        return localChatPath;
    }

    public static SessionManager sessions() {
        return sessions;
    }

    /** The pairing registry, or {@code null} when pairing could not be opened. */
    public static PairingService pairing() {
        return pairing;
    }

    public static RemoteRequestHandler requestHandler() {
        return requestHandler;
    }

    public static String protocolVersion() {
        return Integer.toString(AdminProtocol.PROTOCOL_VERSION);
    }

    /** The anti-crash guard, or {@code null} before the server has started. */
    public static ProtectionManager protection() {
        return protection;
    }

    /** The bounded per-player summary cache, or {@code null} before the server has started. */
    public static PlayerCache<PlayerSummary> playerCache() {
        return playerCache;
    }

    public static ErrorReporter errors() {
        return errors;
    }

    public static EventHistory events() {
        return events;
    }

    public static MetricsRecorder metrics() {
        return metrics;
    }

    public static PerformanceMonitor performance() {
        return performance;
    }

    public static DiagnosticsService diagnostics() {
        return diagnostics;
    }

    public static long startedAt() {
        return startedAt;
    }
}
