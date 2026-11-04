package com.adminmod.integrations.discord;

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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Posts chat to a Discord channel through an incoming webhook.
 *
 * <p>Mentions are neutralised and {@code allowed_mentions} is pinned to an empty parse list,
 * so a player cannot make forwarded chat ping {@code @everyone} or a role.
 */
public final class DiscordProvider implements ChatProvider {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpTransport transport;
    private final String webhookUrl;
    private final String username;
    private final String avatarUrl;
    private final boolean useEmbeds;
    private final String messageFormat;
    private final int embedColor;
    private final int maxMessageLength;

    private volatile String lastError = "";

    public DiscordProvider(HttpTransport transport, String webhookUrl, String username, String avatarUrl,
            boolean useEmbeds, String messageFormat, int embedColor) {
        this(transport, webhookUrl, username, avatarUrl, useEmbeds, messageFormat, embedColor, 1500);
    }

    public DiscordProvider(HttpTransport transport, String webhookUrl, String username, String avatarUrl,
            boolean useEmbeds, String messageFormat, int embedColor, int maxMessageLength) {
        this.transport = transport;
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.username = username == null || username.isBlank() ? "Minecraft Server" : username;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl.trim();
        this.useEmbeds = useEmbeds;
        this.messageFormat = messageFormat == null || messageFormat.isBlank()
                ? "**%player%**: %message%"
                : messageFormat;
        this.embedColor = embedColor;
        this.maxMessageLength = Math.max(64, maxMessageLength);
    }

    @Override
    public String id() {
        return "discord";
    }

    /** Discord batches up to ten embeds, or a newline-joined block of content, in one request. */
    @Override
    public boolean supportsBatch() {
        return true;
    }

    /** Most events one batched request may carry. */
    public static int batchLimit(boolean useEmbeds) {
        return useEmbeds ? 10 : 8;
    }

    @Override
    public HttpResult deliverBatch(java.util.List<ChatEvent> events, Duration timeout) {
        if (!isConfigured()) {
            lastError = "webhook URL is not configured";
            return HttpResult.failure(lastError);
        }
        if (events == null || events.isEmpty()) {
            return HttpResult.failure("empty batch");
        }
        String body = GSON.toJson(buildBatchPayload(events));
        return post(body, timeout);
    }

    private HttpResult post(String body, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "ADMIN-Mod/1.0 (+https://fabricmc.net)")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
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
            lastError = SecretRedactor.redact(describe(e));
            return HttpResult.failure(lastError);
        }
    }

    JsonObject buildBatchPayload(java.util.List<ChatEvent> events) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", truncate(username, 80));
        if (!avatarUrl.isBlank()) {
            payload.addProperty("avatar_url", avatarUrl);
        }
        if (useEmbeds) {
            JsonArray embeds = new JsonArray();
            for (ChatEvent event : events) {
                embeds.add(buildEmbed(event));
            }
            payload.add("embeds", embeds);
        } else {
            StringBuilder content = new StringBuilder();
            for (ChatEvent event : events) {
                String rendered = Template.escapeDiscordMentions(
                        Template.render(messageFormat, templateValues(event)));
                if (content.length() > 0) {
                    content.append('\n');
                }
                content.append(rendered);
            }
            payload.addProperty("content", truncate(content.toString(), 1900));
        }
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);
        return payload;
    }

    private JsonObject buildEmbed(ChatEvent event) {
        Map<String, String> values = templateValues(event);
        String rendered = Template.escapeDiscordMentions(Template.render(messageFormat, values));
        JsonObject embed = new JsonObject();
        embed.addProperty("description", truncate(rendered, Math.min(maxMessageLength, 4000)));
        embed.addProperty("color", embedColor);
        JsonObject footer = new JsonObject();
        footer.addProperty("text", event.serverName());
        embed.add("footer", footer);
        return embed;
    }

    @Override
    public boolean isConfigured() {
        return webhookUrl.startsWith("https://") && webhookUrl.contains("/api/webhooks/");
    }

    @Override
    public HttpResult deliver(ChatEvent event, Duration timeout) {
        if (!isConfigured()) {
            lastError = "webhook URL is not configured";
            return HttpResult.failure(lastError);
        }
        return post(GSON.toJson(buildPayload(event)), timeout);
    }

    JsonObject buildPayload(ChatEvent event) {
        JsonObject payload = new JsonObject();
        payload.addProperty("username", truncate(username, 80));
        if (!avatarUrl.isBlank()) {
            payload.addProperty("avatar_url", avatarUrl);
        }

        Map<String, String> values = templateValues(event);
        String rendered = Template.render(messageFormat, values);
        rendered = Template.escapeDiscordMentions(rendered);

        if (useEmbeds) {
            JsonObject embed = new JsonObject();
            embed.addProperty("description", truncate(rendered, Math.min(maxMessageLength, 4000)));
            embed.addProperty("color", embedColor);
            JsonObject footer = new JsonObject();
            footer.addProperty("text", event.serverName());
            embed.add("footer", footer);
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            payload.add("embeds", embeds);
        } else {
            payload.addProperty("content", truncate(rendered, Math.min(maxMessageLength, 1900)));
        }

        // Pin mentions to nothing: forwarded chat must never ping a channel or a role.
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", allowedMentions);
        return payload;
    }

    private Map<String, String> templateValues(ChatEvent event) {
        Map<String, String> values = new HashMap<>();
        values.put("player", Template.escapeJson(event.playerName()));
        values.put("uuid", event.playerUuid());
        values.put("message", Template.escapeJson(event.message()));
        values.put("timestamp", TIME.format(Instant.ofEpochMilli(event.timestampMillis()).atZone(ZoneId.systemDefault())));
        values.put("server", Template.escapeJson(event.serverName()));
        // The proximity channel, so a Discord format can render "[Local] Alex: hi" or branch on it.
        values.put("channel", event.channel().name());
        values.put("channel_label", event.channelLabel());
        return values;
    }

    @Override
    public String describe() {
        String host = SecretRedactor.safeHost(webhookUrl);
        return host.isEmpty() ? "Discord (not configured)" : "Discord " + host;
    }

    @Override
    public String lastError() {
        return lastError;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** @return {@code true} when the string is a syntactically valid Discord webhook URL. */
    public static boolean isValidWebhookUrl(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("https://")
                && (trimmed.contains("discord.com/api/webhooks/") || trimmed.contains("discordapp.com/api/webhooks/"));
    }
}
