package com.adminmod.integrations.matrix;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.adminmod.common.security.SecretRedactor;
import com.adminmod.integrations.ChatEvent;
import com.adminmod.integrations.ChatProvider;
import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.template.Template;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

/**
 * Posts chat into a Matrix room using the client-server API.
 *
 * <p>Every message is sent with a fresh transaction id, which is what makes Matrix de-duplicate
 * a retried request instead of posting it twice. The access token travels in the
 * {@code Authorization} header rather than the query string so it does not end up in server
 * access logs.
 */
public final class MatrixProvider implements ChatProvider {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final HttpTransport transport;
    private final String homeserver;
    private final String roomId;
    private final String accessToken;
    private final String messageFormat;
    private final int maxMessageLength;

    private volatile String lastError = "";

    public MatrixProvider(HttpTransport transport, String homeserver, String roomId, String accessToken,
            String messageFormat) {
        this(transport, homeserver, roomId, accessToken, messageFormat, 1500);
    }

    public MatrixProvider(HttpTransport transport, String homeserver, String roomId, String accessToken,
            String messageFormat, int maxMessageLength) {
        this.transport = transport;
        this.homeserver = trimTrailingSlash(homeserver);
        this.roomId = roomId == null ? "" : roomId.trim();
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.messageFormat = messageFormat == null || messageFormat.isBlank() ? "%player%: %message%" : messageFormat;
        this.maxMessageLength = Math.max(64, maxMessageLength);
    }

    @Override
    public String id() {
        return "matrix";
    }

    @Override
    public boolean isConfigured() {
        return homeserver.startsWith("https://") && !roomId.isBlank() && !accessToken.isBlank();
    }

    @Override
    public HttpResult deliver(ChatEvent event, Duration timeout) {
        if (!isConfigured()) {
            lastError = "homeserver, room id or access token is not configured";
            return HttpResult.failure(lastError);
        }
        try {
            String url = sendUrl(UUID.randomUUID().toString());
            JsonObject body = new JsonObject();
            body.addProperty("msgtype", "m.text");
            body.addProperty("body", render(event));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + accessToken)
                    .PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();
            HttpResult result = transport.send(request, timeout);
            if (!result.isSuccess()) {
                lastError = "HTTP " + result.status() + ": " + SecretRedactor.redact(result.body());
            } else {
                lastError = "";
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastError = "interrupted";
            return HttpResult.failure(lastError);
        } catch (Exception e) {
            lastError = SecretRedactor.redact(e.getClass().getSimpleName() + ": " + e.getMessage());
            return HttpResult.failure(lastError);
        }
    }

    String sendUrl(String transactionId) {
        return homeserver
                + "/_matrix/client/v3/rooms/"
                + urlEncode(roomId)
                + "/send/m.room.message/"
                + urlEncode(transactionId);
    }

    String render(ChatEvent event) {
        Map<String, String> values = new HashMap<>();
        values.put("player", com.adminmod.integrations.text.TextSanitizer.clean(event.playerName(), 64));
        values.put("uuid", event.playerUuid());
        values.put("message", com.adminmod.integrations.text.TextSanitizer.clean(event.message(), maxMessageLength));
        values.put("timestamp", Long.toString(event.timestampMillis()));
        values.put("server", com.adminmod.integrations.text.TextSanitizer.clean(event.serverName(), 64));
        return com.adminmod.integrations.text.TextSanitizer.truncate(
                Template.render(messageFormat, values), maxMessageLength);
    }

    @Override
    public String describe() {
        String host = SecretRedactor.safeHost(homeserver);
        return host.isEmpty() ? "Matrix (not configured)" : "Matrix " + host + " " + roomId;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
