package com.adminmod.server.chat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.localchat.Channel;
import com.adminmod.common.model.Json;
import com.adminmod.integrations.AntiSpamGuard;
import com.adminmod.integrations.ChatDispatcher;
import com.adminmod.integrations.ChatEvent;
import com.adminmod.integrations.ChatFilter;
import com.adminmod.integrations.ChatProvider;
import com.adminmod.integrations.ChatProviderFactory;
import com.adminmod.integrations.Priority;
import com.adminmod.integrations.discord.DiscordGatewayReceiver;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.http.JdkHttpTransport;
import com.adminmod.integrations.http.RetryPolicy;
import com.adminmod.integrations.inbound.ChatSink;
import com.adminmod.integrations.inbound.InboundChatBridge;
import com.adminmod.integrations.inbound.InboundReceiver;
import com.adminmod.integrations.matrix.MatrixSyncReceiver;
import com.adminmod.integrations.text.TextSanitizer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Owns the public chat bridge, in both directions.
 *
 * <p>{@link #onPublicChat} is called from the server tick thread and does nothing but a policy
 * check and a bounded enqueue; all network I/O happens on the dispatcher's own worker thread. If
 * the queue is full the oldest entry is dropped rather than stalling the tick.
 *
 * <p>The outbound destination list and the inbound readers are rebuilt by {@link #reload()} when
 * the configuration changes through the dashboard. Rebuilding closes the old dispatcher, which
 * drains its queue and stops its worker, so a reload cannot leak a thread or an HTTP connection.
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

    /** Upper bound on remembered command echoes and inbound lines, guarding against a flood. */
    private static final int ECHO_MEMORY_LIMIT = 512;

    private final AdminConfig config;
    private final HttpTransport transport;
    private final String serverName;

    private volatile ChatFilter filter;
    private volatile ChatDispatcher dispatcher;
    private volatile InboundChatBridge inbound;
    private volatile AntiSpamGuard antiSpam;
    private volatile ChatSink sink = message -> {
    };

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
        this.transport = transport == null ? new JdkHttpTransport() : transport;
        this.serverName = serverName == null || serverName.isBlank() ? "Minecraft Server" : serverName;
        if (sink != null) {
            this.sink = sink;
        }
        build();
    }

    /** Builds the outbound dispatcher and the inbound readers from the current configuration. */
    private synchronized void build() {
        this.filter = new ChatFilter(config.chatBridge);
        this.antiSpam = new AntiSpamGuard(config.chatBridge.antiSpam);

        List<ChatProvider> providers = ChatProviderFactory.all(config, transport);
        Duration timeout = Duration.ofMillis(config.chatBridge.requestTimeoutMillis);
        this.dispatcher = new ChatDispatcher(config.chatBridge, providers, config.chatBridge.queueCapacity,
                config.chatBridge.maxAttempts, timeout, RetryPolicy.defaults());

        InboundChatBridge bridge = new InboundChatBridge(config.chatBridge.inbound, sink);
        AdminConfig.Inbound settings = config.chatBridge.inbound;
        if (settings.enabled) {
            if (settings.discord.enabled) {
                bridge.register(new DiscordGatewayReceiver(settings.discord, settings.discordLabel, bridge));
            }
            if (settings.matrix.enabled) {
                bridge.register(new MatrixSyncReceiver(settings.matrix, settings.matrixLabel, bridge, transport));
            }
        }
        this.inbound = bridge;
    }

    /**
     * Rebuilds the bridge after the configuration changed.
     *
     * <p>The old inbound readers and dispatcher are closed first, so their threads stop before the
     * replacements start. The new inbound readers are started here because, unlike the initial
     * construction, the server and its sink already exist by the time a reload happens.
     *
     * @return the number of inbound readers that were started
     */
    public synchronized int reload() {
        InboundChatBridge oldInbound = inbound;
        ChatDispatcher oldDispatcher = dispatcher;
        if (oldInbound != null) {
            oldInbound.close();
        }
        if (oldDispatcher != null) {
            oldDispatcher.close();
        }
        recentCommands.clear();
        inboundEchoes.clear();
        build();
        startInbound();
        return inbound.receivers().size();
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
        this.sink = sink == null ? message -> {
        } : sink;
        if (inbound != null) {
            inbound.setSink(this.sink);
        }
    }

    /** Records a line that was just displayed because it came from a platform. */
    public void markInboundDisplayed(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        prune(inboundEchoes, now, INBOUND_ECHO_WINDOW_MILLIS);
        inboundEchoes.put(line.trim(), now);
    }

    private boolean isInboundEcho(String text) {
        if (inboundEchoes.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long at = inboundEchoes.get(text.trim());
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

    /**
     * Submits a public chat message for forwarding.
     *
     * @param rawMessage the message exactly as the player typed it, before any decoration
     * @return {@code true} when the message was accepted for delivery
     */
    public boolean onPublicChat(String playerName, String playerUuid, String rawMessage) {
        return onPublicChat(playerName, playerUuid, rawMessage, Channel.PUBLIC);
    }

    /**
     * Submits a public chat message for forwarding, tagged with the channel it was sent on.
     *
     * <p>A proximity message that the feature chose not to forward is dropped here, before the
     * queue, so it costs nothing on the network. Everything else follows the ordinary policy:
     * commands and private messages are still refused by default.
     *
     * @param rawMessage the message exactly as the player typed it, before any decoration
     * @param channel    the proximity channel, used to label the forwarded payload
     * @return {@code true} when the message was accepted for delivery
     */
    public boolean onPublicChat(String playerName, String playerUuid, String rawMessage, Channel channel) {
        Channel effective = channel == null ? Channel.PUBLIC : channel;
        if (effective.isProximity() && !config.localChat.forwardToBridge) {
            return false;
        }
        ChatFilter policy = filter;
        if (!policy.shouldForward(rawMessage)) {
            return false;
        }
        String body = config.chatBridge.stripFormatting
                ? TextSanitizer.clean(rawMessage, config.chatBridge.maxMessageLength)
                : TextSanitizer.truncate(rawMessage, config.chatBridge.maxMessageLength);
        if (body.isBlank()) {
            return false;
        }
        String key = playerUuid == null || playerUuid.isBlank() ? playerName : playerUuid;
        if (!antiSpam.accept(key, body, System.currentTimeMillis())) {
            return false;
        }
        dispatcher.enqueue(new ChatEvent(playerName, playerUuid, body, System.currentTimeMillis(), serverName, effective));
        return true;
    }

    /** Sends a synthetic message, used for join/leave and announcement notifications. */
    public void onSystemMessage(String text) {
        onSystemMessage(text, false);
    }

    /**
     * Sends a synthetic message.
     *
     * @param critical when {@code true} the message jumps the queue, which is what an alert or a
     *                 moderation action needs
     */
    public void onSystemMessage(String text, boolean critical) {
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
        if (!config.chatBridge.forwardJoinLeave && !critical) {
            return;
        }
        String body = TextSanitizer.truncate(text, config.chatBridge.maxMessageLength);
        ChatEvent event = new ChatEvent(serverName, "", body, System.currentTimeMillis(), serverName,
                Channel.SYSTEM, critical ? Priority.CRITICAL : Priority.NORMAL);
        dispatcher.enqueue(event);
    }

    /** Records a command run through the remote console so its echo is not forwarded. */
    public void noteExecutedCommand(String command) {
        long now = System.currentTimeMillis();
        prune(recentCommands, now, COMMAND_ECHO_WINDOW_MILLIS);
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

    /** Keeps a timestamp map bounded: expired entries first, then the oldest if still too large. */
    private static void prune(Map<String, Long> map, long now, long windowMillis) {
        map.entrySet().removeIf(entry -> now - entry.getValue() > windowMillis);
        if (map.size() > ECHO_MEMORY_LIMIT) {
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue())
                    .limit(map.size() - ECHO_MEMORY_LIMIT)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(map::remove);
        }
    }

    public ChatProvider provider() {
        return dispatcher.provider();
    }

    public ChatDispatcher dispatcher() {
        return dispatcher;
    }

    public boolean isEnabled() {
        return config.chatBridge.enabled;
    }

    /** JSON status for the Chat Bridge dashboard tab. Never contains credential material. */
    public String statusJson() {
        ChatDispatcher current = dispatcher;
        ChatProvider primary = current.provider();
        JsonObject root = new JsonObject();
        root.addProperty("enabled", config.chatBridge.enabled);
        root.addProperty("provider", primary.id());
        root.addProperty("configured", primary.isConfigured());
        root.addProperty("destination", primary.describe());
        root.addProperty("publicChatOnly", config.chatBridge.publicChatOnly);
        root.addProperty("forwardCommands", config.chatBridge.forwardCommands);
        root.addProperty("forwardPrivateMessages", config.chatBridge.forwardPrivateMessages);
        root.addProperty("forwardJoinLeave", config.chatBridge.forwardJoinLeave);
        root.addProperty("queued", current.queuedCount());
        root.addProperty("delivered", current.deliveredCount());
        root.addProperty("failed", current.failedCount());
        root.addProperty("dropped", current.droppedCount());
        root.addProperty("retries", current.retryCount());
        root.addProperty("duplicates", current.duplicateCount());
        root.addProperty("batching", config.chatBridge.batching.enabled);
        root.addProperty("lastDeliveryStatus", current.lastDeliveryStatus());
        root.addProperty("antiSpamEnabled", config.chatBridge.antiSpam.enabled);

        JsonArray integrations = new JsonArray();
        for (ChatProvider provider : current.providers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", provider.id());
            entry.addProperty("active", true);
            entry.addProperty("configured", provider.isConfigured());
            entry.addProperty("destination", provider.describe());
            entry.addProperty("lastError", provider.lastError());
            integrations.add(entry);
        }
        for (String other : new String[] {"discord", "matrix", "generic"}) {
            if (!other.equals(primary.id())) {
                JsonObject inactive = new JsonObject();
                inactive.addProperty("id", other);
                inactive.addProperty("active", false);
                inactive.addProperty("configured", false);
                inactive.addProperty("destination", "not selected");
                inactive.addProperty("lastError", "");
                integrations.add(inactive);
            }
        }
        root.add("integrations", integrations);

        JsonArray errors = new JsonArray();
        for (String error : current.recentErrors()) {
            errors.add(error);
        }
        root.add("recentErrors", errors);
        root.addProperty("lastError", primary.lastError());

        InboundChatBridge currentInbound = inbound;
        JsonObject inboundStatus = new JsonObject();
        inboundStatus.addProperty("enabled", currentInbound.isEnabled());
        inboundStatus.addProperty("received", currentInbound.receivedCount());
        inboundStatus.addProperty("displayed", currentInbound.displayedCount());
        inboundStatus.addProperty("dropped", currentInbound.droppedCount());
        inboundStatus.addProperty("format", config.chatBridge.inbound.format);
        JsonArray readers = new JsonArray();
        for (InboundReceiver receiver : currentInbound.receivers()) {
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
        InboundChatBridge currentInbound = inbound;
        ChatDispatcher currentDispatcher = dispatcher;
        if (currentInbound != null) {
            currentInbound.close();
        }
        if (currentDispatcher != null) {
            currentDispatcher.close();
        }
        recentCommands.clear();
        inboundEchoes.clear();
    }

    /** Exposed for the settings validator. */
    public static boolean isValidProvider(String value) {
        if (value == null) {
            return false;
        }
        String normalised = value.toLowerCase(Locale.ROOT);
        return normalised.equals("discord") || normalised.equals("matrix")
                || normalised.equals("generic") || normalised.equals("webhook");
    }
}
