package com.adminmod.server.chat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.model.Json;
import com.adminmod.integrations.ChatDispatcher;
import com.adminmod.integrations.ChatEvent;
import com.adminmod.integrations.ChatFilter;
import com.adminmod.integrations.ChatProvider;
import com.adminmod.integrations.discord.DiscordProvider;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.http.JdkHttpTransport;
import com.adminmod.integrations.http.RetryPolicy;
import com.adminmod.integrations.inbound.ChatSink;
import com.adminmod.integrations.inbound.InboundChatBridge;
import com.adminmod.integrations.inbound.InboundReceiver;
import com.adminmod.integrations.discord.DiscordGatewayReceiver;
import com.adminmod.integrations.matrix.MatrixProvider;
import com.adminmod.integrations.matrix.MatrixSyncReceiver;
import com.adminmod.integrations.webhook.GenericWebhookProvider;
import com.adminmod.server.chat.MinecraftChatSink;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Owns the public chat bridge, in both directions.
 *
 * <p>{@link #onPublicChat} is called from the server tick thread and does nothing but a policy
 * check and a bounded enqueue; all network I/O happens on the dispatcher's own worker thread. If
 * the queue is full the oldest entry is dropped rather than stalling the tick.
 *
 * <p>The inbound direction is driven by {@link InboundChatBridge} and one receiver per enabled
 * platform. A receiver runs on its own thread and hands messages to a {@link ChatSink}, which the
 * server provides so that the broadcast happens on the main thread.
 */
public final class ChatBridgeService {

    /** How long a command echoed as a system message stays suppressed, in milliseconds. */
    private static final long COMMAND_ECHO_WINDOW_MILLIS = 2_000L;

    /** How long an inbound line stays suppressed so it is not forwarded back out. */
    private static final long INBOUND_ECHO_WINDOW_MILLIS = 10_000L;

    private final AdminConfig config;
    private final ChatFilter filter;
    private final String serverName;
    private final ChatProvider provider;
    private final ChatDispatcher dispatcher;
    private final InboundChatBridge inbound;

    /**
     * Commands recently executed through the remote console.
     *
     * <p>A command is not forwarded, but the server echoes its result to chat and that echo
     * arrives as a system message. Without this the echo of a remote command would reach the
     * external platform. Entries expire after a couple of seconds and are pruned on use.
     */
    private final Map<String, Long> recentCommands = new ConcurrentHashMap<>();

    /**
     * Lines shown in chat because they arrived from a platform.
     *
     * <p>A broadcast is delivered to players as a game message, which the bridge would otherwise
     * treat as a server notice and forward back to the platform. Recognising the line here breaks
     * that loop.
     */
    private final Map<String, Long> inboundEchoes = new ConcurrentHashMap<>();

    public ChatBridgeService(AdminConfig config, String serverName, HttpTransport transport) {
        this(config, serverName, transport, null);
    }

    public ChatBridgeService(AdminConfig config, String serverName, HttpTransport transport, ChatSink sink) {
        this.config = config;
        this.filter = new ChatFilter(config.chatBridge);
        this.serverName = serverName == null || serverName.isBlank() ? "Minecraft Server" : serverName;
        this.provider = createProvider(config, transport);

        Duration timeout = Duration.ofMillis(config.chatBridge.requestTimeoutMillis);
        this.dispatcher = new ChatDispatcher(provider, config.chatBridge.queueCapacity,
                config.chatBridge.maxAttempts, timeout, RetryPolicy.defaults());

        this.inbound = new InboundChatBridge(config.chatBridge.inbound, sink == null ? message -> {
        } : sink);
        registerInboundReceivers(transport);
    }

    private void registerInboundReceivers(HttpTransport transport) {
        AdminConfig.Inbound settings = config.chatBridge.inbound;
        if (!settings.enabled) {
            return;
        }
        if (settings.discord.enabled) {
            inbound.register(new DiscordGatewayReceiver(settings.discord, settings.discordLabel, inbound));
        }
        if (settings.matrix.enabled) {
            inbound.register(new MatrixSyncReceiver(settings.matrix, settings.matrixLabel, inbound, transport));
        }
    }

    /** Starts the inbound readers. Called on server start, after the world exists. */
    public void startInbound() {
        for (InboundReceiver receiver : inbound.receivers()) {
            receiver.start();
        }
    }

    public InboundChatBridge inbound() {
        return inbound;
    }

    /** Attaches the sink that shows inbound messages in chat. */
    public void setSink(ChatSink sink) {
        inbound.setSink(sink);
    }

    /** Records a line that was just displayed because it came from a platform. */
    public void markInboundDisplayed(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        inboundEchoes.entrySet().removeIf(entry -> now - entry.getValue() > INBOUND_ECHO_WINDOW_MILLIS);
        inboundEchoes.put(line.trim(), now);
    }

    private boolean isInboundEcho(String text) {
        if (inboundEchoes.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        String trimmed = text.trim();
        Long at = inboundEchoes.get(trimmed);
        return at != null && now - at <= INBOUND_ECHO_WINDOW_MILLIS;
    }

    public static ChatBridgeService create(AdminConfig config, String serverName) {
        return new ChatBridgeService(config, serverName, new JdkHttpTransport());
    }

    /**
     * Builds the bridge with an inbound sink that broadcasts to the running server.
     *
     * @param server the dedicated server the inbound messages are shown on
     */
    public static ChatBridgeService create(AdminConfig config, String serverName,
            net.minecraft.server.MinecraftServer server) {
        ChatBridgeService bridge = new ChatBridgeService(config, serverName, new JdkHttpTransport());
        bridge.setSink(new MinecraftChatSink(server, bridge));
        return bridge;
    }

    private static ChatProvider createProvider(AdminConfig config, HttpTransport transport) {
        String id = config.chatBridge.provider == null ? "discord" : config.chatBridge.provider.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "matrix" -> new MatrixProvider(transport, config.chatBridge.matrix.homeserver,
                    config.chatBridge.matrix.roomId, config.chatBridge.matrix.accessToken,
                    config.chatBridge.matrix.messageFormat);
            case "generic", "webhook" -> new GenericWebhookProvider(transport, config.chatBridge.generic.url,
                    config.chatBridge.generic.method, config.chatBridge.generic.payloadTemplate,
                    config.chatBridge.generic.authHeader, config.chatBridge.generic.authToken,
                    config.chatBridge.generic.headers, config.chatBridge.generic.contentType);
            default -> new DiscordProvider(transport, config.chatBridge.discord.webhookUrl,
                    config.chatBridge.discord.username, config.chatBridge.discord.avatarUrl,
                    config.chatBridge.discord.useEmbeds, config.chatBridge.discord.messageFormat,
                    config.chatBridge.discord.embedColor);
        };
    }

    /**
     * Submits a public chat message for forwarding.
     *
     * @param rawMessage the message exactly as the player typed it, before any decoration
     * @return {@code true} when the message was accepted for delivery
     */
    public boolean onPublicChat(String playerName, String playerUuid, String rawMessage) {
        if (!filter.shouldForward(rawMessage)) {
            return false;
        }
        dispatcher.enqueue(new ChatEvent(playerName, playerUuid, rawMessage, System.currentTimeMillis(), serverName));
        return true;
    }

    /** Sends a synthetic message, used for join/leave notifications. */
    public void onSystemMessage(String text) {
        if (text == null || text.isBlank() || !config.chatBridge.enabled) {
            return;
        }
        if (ChatFilter.isCommand(text.trim())) {
            return;
        }
        if (isRecentCommandEcho(text)) {
            return;
        }
        if (isInboundEcho(text)) {
            return;
        }
        if (!config.chatBridge.forwardJoinLeave) {
            return;
        }
        dispatcher.enqueue(new ChatEvent(serverName, "", text, System.currentTimeMillis(), serverName));
    }

    /** Records a command run through the remote console so its echo is not forwarded. */
    public void noteExecutedCommand(String command) {
        long now = System.currentTimeMillis();
        recentCommands.entrySet().removeIf(entry -> now - entry.getValue() > COMMAND_ECHO_WINDOW_MILLIS);
        if (command != null && !command.isBlank()) {
            recentCommands.put(command.trim(), now);
        }
    }

    private boolean isRecentCommandEcho(String text) {
        if (recentCommands.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        String trimmed = text.trim();
        for (Map.Entry<String, Long> entry : recentCommands.entrySet()) {
            if (now - entry.getValue() > COMMAND_ECHO_WINDOW_MILLIS) {
                continue;
            }
            if (entry.getKey().contains(trimmed) || trimmed.contains(entry.getKey())) {
                return true;
            }
        }
        return false;
    }

    public ChatProvider provider() {
        return provider;
    }

    public ChatDispatcher dispatcher() {
        return dispatcher;
    }

    public boolean isEnabled() {
        return config.chatBridge.enabled;
    }

    /** JSON status for the Chat Bridge dashboard tab. Never contains credential material. */
    public String statusJson() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", config.chatBridge.enabled);
        root.addProperty("provider", provider.id());
        root.addProperty("configured", provider.isConfigured());
        root.addProperty("destination", provider.describe());
        root.addProperty("publicChatOnly", config.chatBridge.publicChatOnly);
        root.addProperty("forwardCommands", config.chatBridge.forwardCommands);
        root.addProperty("forwardPrivateMessages", config.chatBridge.forwardPrivateMessages);
        root.addProperty("queued", dispatcher.queuedCount());
        root.addProperty("delivered", dispatcher.deliveredCount());
        root.addProperty("failed", dispatcher.failedCount());
        root.addProperty("dropped", dispatcher.droppedCount());

        JsonArray integrations = new JsonArray();
        JsonObject active = new JsonObject();
        active.addProperty("id", provider.id());
        active.addProperty("active", true);
        active.addProperty("configured", provider.isConfigured());
        active.addProperty("destination", provider.describe());
        integrations.add(active);
        for (String other : new String[] {"discord", "matrix", "generic"}) {
            if (!other.equals(provider.id())) {
                JsonObject inactive = new JsonObject();
                inactive.addProperty("id", other);
                inactive.addProperty("active", false);
                inactive.addProperty("configured", false);
                inactive.addProperty("destination", "not selected");
                integrations.add(inactive);
            }
        }
        root.add("integrations", integrations);

        JsonArray errors = new JsonArray();
        List<String> recent = new ArrayList<>(dispatcher.recentErrors());
        for (String error : recent) {
            errors.add(error);
        }
        root.add("recentErrors", errors);
        root.addProperty("lastError", provider.lastError());

        JsonObject inboundStatus = new JsonObject();
        inboundStatus.addProperty("enabled", inbound.isEnabled());
        inboundStatus.addProperty("received", inbound.receivedCount());
        inboundStatus.addProperty("displayed", inbound.displayedCount());
        inboundStatus.addProperty("dropped", inbound.droppedCount());
        inboundStatus.addProperty("format", config.chatBridge.inbound.format);
        JsonArray readers = new JsonArray();
        for (InboundReceiver receiver : inbound.receivers()) {
            JsonObject reader = new JsonObject();
            reader.addProperty("id", receiver.id());
            reader.addProperty("running", receiver.isRunning());
            reader.addProperty("destination", receiver.describe());
            reader.addProperty("lastError", receiver.lastError());
            readers.add(reader);
        }
        inboundStatus.add("receivers", readers);
        root.add("inbound", inboundStatus);
        return Json.write(root);
    }

    public void close() {
        inbound.close();
        dispatcher.close();
    }
}
