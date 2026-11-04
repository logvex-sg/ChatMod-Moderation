package com.adminmod.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.adminmod.integrations.discord.DiscordProvider;
import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.http.RetryPolicy;
import com.adminmod.integrations.matrix.MatrixProvider;
import com.adminmod.integrations.webhook.GenericWebhookProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

class ProvidersTest {

    private static final ChatEvent EVENT = new ChatEvent("Alex", "uuid-1", "hello \"world\"", 1_700_000_000_000L, "Test Server");

    /** Captures the last request body and returns a canned response. */
    private static final class RecordingTransport implements HttpTransport {
        final List<String> bodies = new CopyOnWriteArrayList<>();
        final List<HttpRequest> requests = new CopyOnWriteArrayList<>();
        private final HttpResult response;

        RecordingTransport(HttpResult response) {
            this.response = response;
        }

        @Override
        public HttpResult send(HttpRequest request, Duration timeout) {
            requests.add(request);
            if (request != null) {
                request.bodyPublisher().ifPresent(publisher -> {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    try {
                        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }

                            public void onNext(java.nio.ByteBuffer item) {
                                byte[] chunk = new byte[item.remaining()];
                                item.get(chunk);
                                out.writeBytes(chunk);
                            }

                            public void onError(Throwable t) {
                            }

                            public void onComplete() {
                            }
                        });
                    } catch (Exception ignored) {
                        // Body capture is best-effort.
                    }
                    bodies.add(out.toString(java.nio.charset.StandardCharsets.UTF_8));
                });
            }
            return response;
        }
    }

    @Test
    void discordPayloadHasNoMentionsAndPinsAllowedMentions() {
        RecordingTransport transport = new RecordingTransport(new HttpResult(204, ""));
        DiscordProvider provider = new DiscordProvider(transport,
                "https://discord.com/api/webhooks/1/token", "Minecraft Server", "", true, "**%player%**: %message%",
                0x5865F2);

        assertTrue(provider.isConfigured());
        HttpResult result = provider.deliver(EVENT, Duration.ofSeconds(1));
        assertTrue(result.isSuccess());

        JsonObject payload = JsonParser.parseString(transport.bodies.get(0)).getAsJsonObject();
        assertTrue(payload.has("allowed_mentions"));
        assertTrue(payload.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").isEmpty());
        assertTrue(payload.has("embeds"));
        JsonArray embeds = payload.getAsJsonArray("embeds");
        assertEquals(1, embeds.size());
        assertEquals("Test Server", embeds.get(0).getAsJsonObject().getAsJsonObject("footer").get("text").getAsString());
    }

    @Test
    void discordNeutralisesAtMentions() {
        RecordingTransport transport = new RecordingTransport(new HttpResult(204, ""));
        DiscordProvider provider = new DiscordProvider(transport,
                "https://discord.com/api/webhooks/1/token", "Server", "", false, "%message%", 0);
        provider.deliver(new ChatEvent("Alex", "u", "@everyone ping", 0L, "S"), Duration.ofSeconds(1));
        String body = transport.bodies.get(0);
        assertFalse(body.contains("@everyone"), "raw mention must not survive: " + body);
    }

    @Test
    void discordRejectsNonWebhookUrls() {
        DiscordProvider provider = new DiscordProvider(new RecordingTransport(new HttpResult(204, "")),
                "https://example.com/not-a-webhook", "S", "", false, "%message%", 0);
        assertFalse(provider.isConfigured());
        assertFalse(DiscordProvider.isValidWebhookUrl("https://example.com/x"));
        assertTrue(DiscordProvider.isValidWebhookUrl("https://discord.com/api/webhooks/1/abc"));
    }

    @Test
    void discordErrorsAreRedacted() {
        // A transport failure commonly echoes the URL it failed to reach.
        RecordingTransport transport = new RecordingTransport(
                HttpResult.failure("connect to https://discord.com/api/webhooks/1/super-secret-token failed"));
        DiscordProvider provider = new DiscordProvider(transport,
                "https://discord.com/api/webhooks/1/super-secret-token", "S", "", false, "%message%", 0);
        provider.deliver(EVENT, Duration.ofSeconds(1));
        assertFalse(provider.lastError().contains("super-secret-token"), provider.lastError());
    }

    @Test
    void matrixUsesBearerHeaderAndTransactionId() {
        RecordingTransport transport = new RecordingTransport(new HttpResult(200, "{}"));
        MatrixProvider provider = new MatrixProvider(transport, "https://matrix.example.org", "!room:example.org",
                "syt_token", "%player%: %message%");
        assertTrue(provider.isConfigured());
        provider.deliver(EVENT, Duration.ofSeconds(1));

        HttpRequest request = transport.requests.get(0);
        assertEquals("PUT", request.method());
        assertTrue(request.uri().toString().contains("/_matrix/client/v3/rooms/"));
        assertTrue(request.uri().toString().contains("/send/m.room.message/"));
        assertTrue(request.headers().firstValue("Authorization").orElse("").startsWith("Bearer "));
        // The token must never appear in the URL.
        assertFalse(request.uri().toString().contains("syt_token"));
    }

    @Test
    void matrixRequiresAllThreeFields() {
        assertFalse(new MatrixProvider(new RecordingTransport(new HttpResult(200, "")), "", "!r:x", "t", "%m%").isConfigured());
        assertFalse(new MatrixProvider(new RecordingTransport(new HttpResult(200, "")), "https://m", "", "t", "%m%").isConfigured());
        assertFalse(new MatrixProvider(new RecordingTransport(new HttpResult(200, "")), "https://m", "!r", "", "%m%").isConfigured());
    }

    @Test
    void genericWebhookRendersTemplateAndHeaders() {
        RecordingTransport transport = new RecordingTransport(new HttpResult(200, "ok"));
        GenericWebhookProvider provider = new GenericWebhookProvider(transport,
                "https://hooks.example.org/endpoint", "POST",
                "{\"player\":\"%player%\",\"message\":\"%message%\",\"ts\":\"%timestamp%\"}",
                "X-Auth-Token", "tok", Map.of("X-Custom", "value"), "application/json");

        assertTrue(provider.isConfigured());
        provider.deliver(EVENT, Duration.ofSeconds(1));

        HttpRequest request = transport.requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("tok", request.headers().firstValue("X-Auth-Token").orElse(""));
        assertEquals("value", request.headers().firstValue("X-Custom").orElse(""));

        JsonObject payload = JsonParser.parseString(transport.bodies.get(0)).getAsJsonObject();
        assertEquals("Alex", payload.get("player").getAsString());
        assertEquals("hello \"world\"", payload.get("message").getAsString());
    }

    @Test
    void genericWebhookTemplateCannotBeBrokenByPlayerInput() {
        GenericWebhookProvider provider = new GenericWebhookProvider(new RecordingTransport(new HttpResult(200, "")),
                "https://h.example.org", "POST", "{\"m\":\"%message%\"}", "", "", Map.of(), "application/json");
        String rendered = provider.render(new ChatEvent("A", "u", "\",\"injected\":\"yes", 0L, "S"));
        JsonObject parsed = JsonParser.parseString(rendered).getAsJsonObject();
        assertEquals("\",\"injected\":\"yes", parsed.get("m").getAsString());
        assertFalse(parsed.has("injected"));
    }

    @Test
    void retryPolicyHonoursRetryAfter() {
        RetryPolicy policy = new RetryPolicy(100L, 10_000L);
        assertEquals(5_000L, policy.delayMillis(1, 5_000L));
        long jittered = policy.delayMillis(1);
        assertTrue(jittered >= 1L && jittered <= 100L, "delay " + jittered);
        long capped = policy.delayMillis(30);
        assertTrue(capped <= 10_000L);
    }
}
