package com.adminmod.integrations.matrix;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.security.SecretRedactor;
import com.adminmod.integrations.inbound.InboundChatBridge;
import com.adminmod.integrations.inbound.InboundChatMessage;
import com.adminmod.integrations.inbound.InboundReceiver;
import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.HttpTransport;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads Matrix room messages with the client-server {@code /sync} endpoint and feeds them to the
 * inbound bridge.
 *
 * <p>Matrix has no separate gateway: a bot reads messages by long-polling {@code /sync}, carrying
 * a {@code since} token from one call to the next. The first call is made without a token so the
 * server answers immediately with a starting position; every later call waits up to
 * {@code timeout} for new events, which keeps the loop quiet without busy-waiting.
 *
 * <p>The access token travels in the {@code Authorization} header, never in the query string.
 * Events whose sender is the bot's own MXID are ignored, which is what stops this mod's own
 * outbound messages from being echoed back into Minecraft.
 */
public final class MatrixSyncReceiver implements InboundReceiver {

    private static final long MIN_BACKOFF_MILLIS = 2_000L;
    private static final long MAX_BACKOFF_MILLIS = 60_000L;

    private final AdminConfig.MatrixInbound config;
    private final String label;
    private final InboundChatBridge bridge;
    private final HttpTransport transport;

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong received = new AtomicLong();

    private volatile Thread worker;
    private volatile String since;
    private volatile String lastError = "";
    private volatile boolean connected;

    public MatrixSyncReceiver(AdminConfig.MatrixInbound config, String label, InboundChatBridge bridge,
            HttpTransport transport) {
        this.config = config;
        this.label = label == null || label.isBlank() ? "Matrix" : label;
        this.bridge = bridge;
        this.transport = transport;
    }

    @Override
    public String id() {
        return "matrix-sync";
    }

    public boolean isConfigured() {
        return homeserver().startsWith("https://") && !config.accessToken.isBlank() && !config.roomId.isBlank();
    }

    private String homeserver() {
        String value = config.homeserver == null ? "" : config.homeserver.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (!isConfigured()) {
            lastError = "homeserver, room id or access token is not configured";
            running.set(false);
            return;
        }
        worker = new Thread(this::pollLoop, "adminmod-matrix-sync");
        worker.setDaemon(true);
        worker.start();
    }

    private void pollLoop() {
        long backoff = MIN_BACKOFF_MILLIS;
        while (running.get()) {
            boolean progressed = false;
            try {
                progressed = syncOnce();
                if (progressed) {
                    backoff = MIN_BACKOFF_MILLIS;
                    connected = true;
                    lastError = "";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                connected = false;
                lastError = SecretRedactor.redact(e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (!running.get()) {
                break;
            }
            if (!progressed) {
                connected = false;
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    /**
     * Performs one sync request and handles whatever it returns.
     *
     * @return {@code true} when the request completed, {@code false} when it failed and the caller
     *         should back off
     */
    boolean syncOnce() throws Exception {
        String url = syncUrl(since);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(config.syncTimeoutMillis + 15_000L))
                .header("Authorization", "Bearer " + config.accessToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResult result = transport.send(request, Duration.ofMillis(config.syncTimeoutMillis + 15_000L));
        if (result.status() == 429) {
            long delay = Math.max(MIN_BACKOFF_MILLIS, result.retryAfterMillis());
            lastError = "rate limited by the homeserver";
            Thread.sleep(Math.min(delay, MAX_BACKOFF_MILLIS));
            return false;
        }
        if (!result.isSuccess()) {
            lastError = "HTTP " + result.status() + ": " + SecretRedactor.redact(result.body());
            return false;
        }
        handleSync(result.body());
        return true;
    }

    String syncUrl(String sinceToken) {
        StringBuilder url = new StringBuilder(homeserver())
                .append("/_matrix/client/v3/sync?timeout=").append(config.syncTimeoutMillis)
                .append("&limit=").append(config.syncLimit)
                .append("&filter=").append(urlEncode(roomFilter()));
        if (sinceToken != null && !sinceToken.isBlank()) {
            url.append("&since=").append(urlEncode(sinceToken));
        }
        return url.toString();
    }

    /** A filter that asks the homeserver for timeline messages of the one configured room. */
    String roomFilter() {
        return "{\"room\":{\"rooms\":{\"" + config.roomId
                + "\":{\"timeline\":{\"types\":[\"m.room.message\"],\"limit\":" + config.syncLimit + "}}}}}";
    }

    /** Parses a sync response. Visible for testing. */
    void handleSync(String body) {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            lastError = "the homeserver returned an unreadable sync response";
            return;
        }
        JsonElement nextBatch = root.get("next_batch");
        if (nextBatch != null && !nextBatch.isJsonNull()) {
            since = nextBatch.getAsString();
        }
        JsonObject rooms = root.has("rooms") && root.get("rooms").isJsonObject() ? root.getAsJsonObject("rooms") : null;
        if (rooms == null || !rooms.has("join") || !rooms.get("join").isJsonObject()) {
            return;
        }
        JsonObject join = rooms.getAsJsonObject("join");
        JsonObject room = join.has(config.roomId) && join.get(config.roomId).isJsonObject()
                ? join.getAsJsonObject(config.roomId) : null;
        if (room == null) {
            return;
        }
        JsonObject timeline = room.has("timeline") && room.get("timeline").isJsonObject()
                ? room.getAsJsonObject("timeline") : null;
        if (timeline == null || !timeline.has("events") || !timeline.get("events").isJsonArray()) {
            return;
        }
        for (JsonElement element : timeline.getAsJsonArray("events")) {
            if (element.isJsonObject()) {
                onEvent(element.getAsJsonObject());
            }
        }
    }

    private void onEvent(JsonObject event) {
        String type = string(event, "type");
        if (!"m.room.message".equals(type)) {
            return;
        }
        String sender = string(event, "sender");
        if (!config.userId.isBlank() && config.userId.equals(sender)) {
            return;
        }
        JsonObject content = event.has("content") && event.get("content").isJsonObject()
                ? event.getAsJsonObject("content") : null;
        if (content == null) {
            return;
        }
        String msgtype = string(content, "msgtype");
        if (!"m.text".equals(msgtype) && !"m.notice".equals(msgtype) && !"m.emote".equals(msgtype)) {
            return;
        }
        String body = string(content, "body");
        if (body.isBlank()) {
            return;
        }
        String display = displayName(sender);
        String eventId = string(event, "event_id");
        long timestamp = event.has("origin_server_ts") && !event.get("origin_server_ts").isJsonNull()
                ? event.get("origin_server_ts").getAsLong() : System.currentTimeMillis();
        received.incrementAndGet();
        bridge.deliver(new InboundChatMessage(label, display, body, timestamp, eventId));
    }

    /** Turns {@code @alice:example.org} into {@code alice}. */
    static String displayName(String mxid) {
        if (mxid == null || mxid.isBlank()) {
            return "unknown";
        }
        String name = mxid.startsWith("@") ? mxid.substring(1) : mxid;
        int colon = name.indexOf(':');
        return colon > 0 ? name.substring(0, colon) : name;
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public boolean isRunning() {
        return connected;
    }

    @Override
    public String describe() {
        if (!isConfigured()) {
            return "Matrix sync (not configured)";
        }
        return "Matrix " + SecretRedactor.safeHost(homeserver()) + " " + config.roomId;
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
}
