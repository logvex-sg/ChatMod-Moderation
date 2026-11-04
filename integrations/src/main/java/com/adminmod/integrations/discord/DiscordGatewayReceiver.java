package com.adminmod.integrations.discord;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.security.SecretRedactor;
import com.adminmod.integrations.inbound.InboundChatBridge;
import com.adminmod.integrations.inbound.InboundChatMessage;
import com.adminmod.integrations.inbound.InboundReceiver;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads Discord channel messages over the Gateway WebSocket and feeds them to the inbound
 * bridge.
 *
 * <p>A webhook can only post, so reading messages needs a bot token and the Gateway. This class
 * implements the parts of the protocol that matter here: HELLO, IDENTIFY, heartbeats, RESUME
 * after a dropped connection, and MESSAGE_CREATE dispatch. It runs on its own daemon thread and
 * never touches the Minecraft server thread.
 *
 * <p>Loop prevention has three layers. Messages authored by a bot are ignored, messages that
 * carry a {@code webhook_id} are ignored (that is how this mod's own outbound webhook posts come
 * back), and the bridge additionally de-duplicates identical bodies.
 *
 * <p>Privileged intent {@code MESSAGE_CONTENT} must be enabled for the bot in the Discord
 * developer portal; without it Discord sends empty content for other users' messages.
 */
public final class DiscordGatewayReceiver implements InboundReceiver {

    /** GUILD_MESSAGES (1 << 9) | MESSAGE_CONTENT (1 << 15). */
    private static final int INTENTS = (1 << 9) | (1 << 15);

    private static final int OP_DISPATCH = 0;
    private static final int OP_HEARTBEAT = 1;
    private static final int OP_IDENTIFY = 2;
    private static final int OP_RESUME = 6;
    private static final int OP_RECONNECT = 7;
    private static final int OP_INVALID_SESSION = 9;
    private static final int OP_HELLO = 10;
    private static final int OP_HEARTBEAT_ACK = 11;

    private static final long MIN_BACKOFF_MILLIS = 1_000L;
    private static final long MAX_BACKOFF_MILLIS = 60_000L;

    private static final Gson GSON = new Gson();

    private final AdminConfig.DiscordInbound config;
    private final String label;
    private final InboundChatBridge bridge;
    private final HttpClient httpClient;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong sequence = new AtomicLong(-1L);

    private volatile Thread worker;
    private volatile WebSocket socket;
    private volatile String sessionId;
    private volatile String resumeGatewayUrl;
    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile long heartbeatIntervalMillis;
    private volatile boolean heartbeatAcknowledged = true;
    private volatile String lastError = "";
    private volatile boolean connected;

    public DiscordGatewayReceiver(AdminConfig.DiscordInbound config, String label, InboundChatBridge bridge) {
        this.config = config;
        this.label = label == null || label.isBlank() ? "Discord" : label;
        this.bridge = bridge;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String id() {
        return "discord-gateway";
    }

    public boolean isConfigured() {
        return !config.botToken.isBlank() && !config.gatewayUrl.isBlank();
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (!isConfigured()) {
            lastError = "bot token is not configured";
            running.set(false);
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "adminmod-discord-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        worker = new Thread(this::connectionLoop, "adminmod-discord-gateway");
        worker.setDaemon(true);
        worker.start();
    }

    private void connectionLoop() {
        long backoff = MIN_BACKOFF_MILLIS;
        while (running.get()) {
            try {
                connectAndBlock();
                backoff = MIN_BACKOFF_MILLIS;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                lastError = SecretRedactor.redact(e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                connected = false;
                stopHeartbeat();
            }
            if (!running.get()) {
                break;
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
        }
    }

    private void connectAndBlock() throws Exception {
        String url = sessionId != null && resumeGatewayUrl != null
                ? resumeGatewayUrl + "/?v=10&encoding=json"
                : config.gatewayUrl;
        CountDownLatch closed = new CountDownLatch(1);
        GatewayListener listener = new GatewayListener(closed);
        WebSocket webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(url), listener)
                .get(20, TimeUnit.SECONDS);
        // The listener assigns this field in onOpen, which is guaranteed to run before any frame
        // is delivered. Assigning here as well is harmless but the onOpen assignment is what
        // makes IDENTIFY safe: HELLO can arrive before this line executes.
        this.socket = webSocket;
        this.connected = true;
        lastError = "";
        closed.await();
    }

    // ------------------------------------------------------------------ protocol

    private void onPayload(String text) {
        JsonObject payload;
        try {
            payload = JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        int op = payload.has("op") ? payload.get("op").getAsInt() : -1;
        if (payload.has("s") && !payload.get("s").isJsonNull()) {
            sequence.set(payload.get("s").getAsLong());
        }
        switch (op) {
            case OP_HELLO -> onHello(payload);
            case OP_HEARTBEAT -> sendHeartbeat();
            case OP_HEARTBEAT_ACK -> heartbeatAcknowledged = true;
            case OP_RECONNECT -> closeSocket();
            case OP_INVALID_SESSION -> {
                // The session cannot be resumed; start a fresh one on the next connect.
                sessionId = null;
                resumeGatewayUrl = null;
                closeSocket();
            }
            case OP_DISPATCH -> onDispatch(payload);
            default -> {
                // Unknown opcodes are ignored so a new gateway feature cannot break the reader.
            }
        }
    }

    private void onHello(JsonObject payload) {
        long interval = 45_000L;
        JsonObject data = payload.has("d") && payload.get("d").isJsonObject() ? payload.getAsJsonObject("d") : null;
        if (data != null && data.has("heartbeat_interval")) {
            interval = data.get("heartbeat_interval").getAsLong();
        }
        this.heartbeatIntervalMillis = Math.max(1_000L, interval);
        startHeartbeat();

        if (sessionId != null) {
            sendResume();
        } else {
            sendIdentify();
        }
    }

    private void sendIdentify() {
        JsonObject properties = new JsonObject();
        properties.addProperty("os", System.getProperty("os.name", "unknown"));
        properties.addProperty("browser", "ADMIN-Mod");
        properties.addProperty("device", "ADMIN-Mod");

        JsonObject data = new JsonObject();
        data.addProperty("token", config.botToken);
        data.addProperty("intents", INTENTS);
        data.add("properties", properties);

        send(jsonEnvelope(OP_IDENTIFY, data));
    }

    private void sendResume() {
        JsonObject data = new JsonObject();
        data.addProperty("token", config.botToken);
        data.addProperty("session_id", sessionId);
        long seq = sequence.get();
        if (seq >= 0L) {
            data.addProperty("seq", seq);
        } else {
            data.add("seq", com.google.gson.JsonNull.INSTANCE);
        }
        send(jsonEnvelope(OP_RESUME, data));
    }

    private void onDispatch(JsonObject payload) {
        String type = payload.has("t") && !payload.get("t").isJsonNull() ? payload.get("t").getAsString() : "";
        if (!payload.has("d") || !payload.get("d").isJsonObject()) {
            return;
        }
        JsonObject data = payload.getAsJsonObject("d");
        switch (type) {
            case "READY" -> {
                sessionId = data.has("session_id") ? data.get("session_id").getAsString() : null;
                resumeGatewayUrl = data.has("resume_gateway_url") ? data.get("resume_gateway_url").getAsString() : null;
                lastError = "";
            }
            case "RESUMED" -> lastError = "";
            case "MESSAGE_CREATE" -> onMessageCreate(data);
            default -> {
            }
        }
    }

    /** Handles one MESSAGE_CREATE payload. Visible for testing. */
    void onMessageCreate(JsonObject data) {
        if (!channelAccepted(data)) {
            return;
        }
        // A webhook message is this mod's own outbound post coming back; never re-inject it.
        if (data.has("webhook_id") && !data.get("webhook_id").isJsonNull()) {
            return;
        }
        JsonObject author = data.has("author") && data.get("author").isJsonObject()
                ? data.getAsJsonObject("author") : null;
        if (author == null) {
            return;
        }
        if (author.has("bot") && !author.get("bot").isJsonNull() && author.get("bot").getAsBoolean()) {
            return;
        }
        String username = displayName(author);
        String content = data.has("content") && !data.get("content").isJsonNull() ? data.get("content").getAsString() : "";
        String id = data.has("id") && !data.get("id").isJsonNull() ? data.get("id").getAsString() : "";
        long timestamp = parseTimestamp(data);
        bridge.deliver(new InboundChatMessage(label, username, content, timestamp, id));
    }

    /** @return {@code true} when the message belongs to a channel and guild this reader accepts. */
    boolean channelAccepted(JsonObject data) {
        String channel = data.has("channel_id") && !data.get("channel_id").isJsonNull()
                ? data.get("channel_id").getAsString() : "";
        if (!config.channelId.isBlank() && !config.channelId.equals(channel)) {
            return false;
        }
        if (!config.guildId.isBlank()) {
            String guild = data.has("guild_id") && !data.get("guild_id").isJsonNull()
                    ? data.get("guild_id").getAsString() : "";
            if (!config.guildId.equals(guild)) {
                return false;
            }
        }
        return true;
    }

    private static String displayName(JsonObject author) {
        for (String key : new String[] {"global_name", "display_name", "username"}) {
            JsonElement element = author.get(key);
            if (element != null && !element.isJsonNull() && !element.getAsString().isBlank()) {
                return element.getAsString();
            }
        }
        return "unknown";
    }

    private static long parseTimestamp(JsonObject data) {
        JsonElement element = data.get("timestamp");
        if (element != null && !element.isJsonNull()) {
            try {
                return java.time.Instant.parse(element.getAsString()).toEpochMilli();
            } catch (RuntimeException ignored) {
                // Fall through to the local clock.
            }
        }
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ heartbeat

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatAcknowledged = true;
        ScheduledExecutorService executor = scheduler;
        if (executor == null) {
            return;
        }
        heartbeatTask = executor.scheduleAtFixedRate(this::heartbeatTick, heartbeatIntervalMillis,
                heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(false);
            heartbeatTask = null;
        }
    }

    private void heartbeatTick() {
        if (!running.get() || socket == null) {
            return;
        }
        // Discord closes a connection whose heartbeat went unanswered; do it ourselves so the
        // reconnect path runs instead of the socket lingering half-dead.
        if (!heartbeatAcknowledged) {
            closeSocket();
            return;
        }
        heartbeatAcknowledged = false;
        sendHeartbeat();
    }

    private void sendHeartbeat() {
        JsonObject data = new JsonObject();
        long seq = sequence.get();
        if (seq >= 0L) {
            data.addProperty("seq", seq);
        } else {
            data.add("seq", com.google.gson.JsonNull.INSTANCE);
        }
        send(jsonEnvelope(OP_HEARTBEAT, data));
    }

    // ------------------------------------------------------------------ transport

    private static String jsonEnvelope(int op, JsonObject data) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("op", op);
        envelope.add("d", data);
        return GSON.toJson(envelope);
    }

    private void send(String text) {
        WebSocket webSocket = socket;
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.sendText(text, true);
        } catch (RuntimeException e) {
            closeSocket();
        }
    }

    private void closeSocket() {
        WebSocket webSocket = socket;
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
            } catch (RuntimeException ignored) {
                // Already closing.
            }
        }
    }

    @Override
    public boolean isRunning() {
        return connected;
    }

    @Override
    public String describe() {
        if (!isConfigured()) {
            return "Discord gateway (not configured)";
        }
        String channel = config.channelId.isBlank() ? "all channels" : "channel " + config.channelId;
        return "Discord gateway " + channel;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        stopHeartbeat();
        closeSocket();
        ScheduledExecutorService executor = scheduler;
        if (executor != null) {
            executor.shutdownNow();
        }
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Frames arrive in fragments; this reassembles them before parsing. */
    private final class GatewayListener implements WebSocket.Listener {

        private final CountDownLatch closed;
        private final StringBuilder buffer = new StringBuilder();

        GatewayListener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            // Publishing the socket here, rather than after buildAsync completes, closes a race:
            // HELLO can be delivered before the connecting thread resumes, and without this the
            // IDENTIFY reply would be dropped and the bot would never come online.
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String text = buffer.toString();
                buffer.setLength(0);
                try {
                    onPayload(text);
                } catch (RuntimeException e) {
                    lastError = SecretRedactor.redact(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            connected = false;
            closed.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            connected = false;
            lastError = SecretRedactor.redact(
                    error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage()));
            closed.countDown();
        }
    }
}
