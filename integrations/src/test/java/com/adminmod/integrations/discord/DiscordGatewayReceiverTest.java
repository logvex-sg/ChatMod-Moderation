package com.adminmod.integrations.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.integrations.inbound.ChatSink;
import com.adminmod.integrations.inbound.InboundChatBridge;
import com.adminmod.integrations.inbound.InboundChatMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

/**
 * Message filtering for the Discord gateway, driven with raw MESSAGE_CREATE payloads so no
 * WebSocket is needed.
 */
class DiscordGatewayReceiverTest {

    private static AdminConfig.DiscordInbound config() {
        AdminConfig.DiscordInbound config = new AdminConfig.DiscordInbound();
        config.enabled = true;
        config.botToken = "bot-token";
        config.channelId = "111";
        return config;
    }

    private static AdminConfig.Inbound inboundConfig() {
        AdminConfig.Inbound inbound = new AdminConfig.Inbound();
        inbound.enabled = true;
        return inbound;
    }

    private static final class RecordingSink implements ChatSink {
        final List<InboundChatMessage> received = new CopyOnWriteArrayList<>();

        @Override
        public void accept(InboundChatMessage message) {
            received.add(message);
        }
    }

    private static DiscordGatewayReceiver receiver(RecordingSink sink, AdminConfig.DiscordInbound config) {
        return new DiscordGatewayReceiver(config, "Discord", new InboundChatBridge(inboundConfig(), sink));
    }

    private static JsonObject payload(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static String message(String channel, String authorJson, String content, String id) {
        return "{\"id\":\"" + id + "\",\"channel_id\":\"" + channel + "\",\"guild_id\":\"999\","
                + "\"timestamp\":\"2023-11-14T22:13:20Z\",\"author\":" + authorJson + ",\"content\":\"" + content + "\"}";
    }

    @Test
    void aUserMessageBecomesAChatLine() {
        RecordingSink sink = new RecordingSink();
        receiver(sink, config()).onMessageCreate(payload(
                message("111", "{\"username\":\"Alex\",\"bot\":false}", "hello", "1")));

        assertEquals(1, sink.received.size());
        assertEquals("[Discord] Alex: hello", sink.received.get(0).message());
        assertEquals("1", sink.received.get(0).sourceId());
    }

    @Test
    void aGlobalNameIsPreferredOverTheUsername() {
        RecordingSink sink = new RecordingSink();
        receiver(sink, config()).onMessageCreate(payload(
                message("111", "{\"username\":\"alex123\",\"global_name\":\"Alex\",\"bot\":false}", "hi", "2")));

        assertEquals("Alex", sink.received.get(0).user());
    }

    @Test
    void aBotMessageIsIgnored() {
        RecordingSink sink = new RecordingSink();
        receiver(sink, config()).onMessageCreate(payload(
                message("111", "{\"username\":\"SomeBot\",\"bot\":true}", "beep", "3")));

        assertTrue(sink.received.isEmpty(), "a bot message must not be shown");
    }

    @Test
    void aWebhookMessageIsIgnored() {
        RecordingSink sink = new RecordingSink();
        String json = "{\"id\":\"4\",\"channel_id\":\"111\",\"webhook_id\":\"42\",\"author\":{\"username\":\"MC\"},"
                + "\"content\":\"echo\",\"timestamp\":\"2023-11-14T22:13:20Z\"}";
        receiver(sink, config()).onMessageCreate(payload(json));

        assertTrue(sink.received.isEmpty(), "our own webhook post must not be re-injected");
    }

    @Test
    void aMessageFromAnotherChannelIsIgnored() {
        RecordingSink sink = new RecordingSink();
        receiver(sink, config()).onMessageCreate(payload(
                message("222", "{\"username\":\"Alex\"}", "hi", "5")));

        assertTrue(sink.received.isEmpty());
    }

    @Test
    void aGuildRestrictionIsHonoured() {
        RecordingSink sink = new RecordingSink();
        AdminConfig.DiscordInbound config = config();
        config.guildId = "12345";
        receiver(sink, config).onMessageCreate(payload(
                message("111", "{\"username\":\"Alex\"}", "hi", "6")));

        assertTrue(sink.received.isEmpty(), "message from a different guild must be ignored");
    }

    @Test
    void anEmptyChannelIdAcceptsEveryChannel() {
        RecordingSink sink = new RecordingSink();
        AdminConfig.DiscordInbound config = config();
        config.channelId = "";
        receiver(sink, config).onMessageCreate(payload(
                message("777", "{\"username\":\"Alex\"}", "hi", "7")));

        assertEquals(1, sink.received.size());
    }

    @Test
    void isConfiguredRequiresABotToken() {
        AdminConfig.DiscordInbound config = config();
        assertTrue(receiver(new RecordingSink(), config).isConfigured());
        config.botToken = "";
        assertFalse(receiver(new RecordingSink(), config).isConfigured());
    }

    @Test
    void theTimestampIsParsedFromThePayload() {
        RecordingSink sink = new RecordingSink();
        receiver(sink, config()).onMessageCreate(payload(
                message("111", "{\"username\":\"Alex\"}", "hi", "8")));
        assertEquals(1_700_000_000_000L, sink.received.get(0).timestampMillis());
    }
}
