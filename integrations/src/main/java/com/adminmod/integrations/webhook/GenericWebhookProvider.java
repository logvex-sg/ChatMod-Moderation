package com.adminmod.integrations.webhook;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import com.adminmod.common.security.SecretRedactor;
import com.adminmod.integrations.ChatEvent;
import com.adminmod.integrations.ChatProvider;
import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.template.Template;

/**
 * Posts chat to an arbitrary HTTP endpoint using a configurable JSON body template.
 *
 * <p>The template is rendered with pre-escaped values, so a player name or message containing a
 * quote cannot break the JSON structure. Extra headers and an optional bearer token are
 * supported; neither is ever echoed back to a client.
 */
public final class GenericWebhookProvider implements ChatProvider {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final HttpTransport transport;
    private final String url;
    private final String method;
    private final String payloadTemplate;
    private final String authHeader;
    private final String authToken;
    private final Map<String, String> headers;
    private final String contentType;

    private volatile String lastError = "";

    public GenericWebhookProvider(HttpTransport transport, String url, String method, String payloadTemplate,
            String authHeader, String authToken, Map<String, String> headers, String contentType) {
        this.transport = transport;
        this.url = url == null ? "" : url.trim();
        this.method = method == null || method.isBlank() ? "POST" : method.trim().toUpperCase(Locale.ROOT);
        this.payloadTemplate = payloadTemplate == null || payloadTemplate.isBlank()
                ? "{\"player\":\"%player%\",\"message\":\"%message%\"}"
                : payloadTemplate;
        this.authHeader = authHeader == null ? "" : authHeader.trim();
        this.authToken = authToken == null ? "" : authToken.trim();
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.contentType = contentType == null || contentType.isBlank() ? "application/json" : contentType;
    }

    @Override
    public String id() {
        return "generic";
    }

    @Override
    public boolean isConfigured() {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    @Override
    public HttpResult deliver(ChatEvent event, Duration timeout) {
        if (!isConfigured()) {
            lastError = "endpoint URL is not configured";
            return HttpResult.failure(lastError);
        }
        try {
            String body = render(event);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", contentType)
                    .header("User-Agent", "ADMIN-Mod/1.0");

            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (!header.getKey().isBlank()) {
                    builder.header(header.getKey(), header.getValue());
                }
            }
            if (!authToken.isBlank() && !authHeader.isBlank()) {
                builder.header(authHeader, authToken);
            }

            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            HttpResult result = transport.send(builder.build(), timeout);
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

    /** Renders the configured template for the event. Visible for testing. */
    public String render(ChatEvent event) {
        Map<String, String> values = new HashMap<>();
        values.put("player", Template.escapeJson(event.playerName()));
        values.put("uuid", Template.escapeJson(event.playerUuid()));
        values.put("message", Template.escapeJson(event.message()));
        values.put("timestamp", Template.escapeJson(ISO.format(Instant.ofEpochMilli(event.timestampMillis()).atZone(ZoneId.systemDefault()))));
        values.put("server", Template.escapeJson(event.serverName()));
        values.put("raw_player", event.playerName());
        values.put("raw_message", event.message());
        return Template.render(payloadTemplate, values);
    }

    @Override
    public String describe() {
        String host = SecretRedactor.safeHost(url);
        return host.isEmpty() ? "Generic webhook (not configured)" : "Generic " + method + " " + host;
    }

    @Override
    public String lastError() {
        return lastError;
    }
}
