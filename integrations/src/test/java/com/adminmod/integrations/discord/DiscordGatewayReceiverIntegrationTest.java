package com.adminmod.integrations.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.integrations.inbound.ChatSink;
import com.adminmod.integrations.inbound.InboundChatBridge;
import com.adminmod.integrations.inbound.InboundChatMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * End-to-end test of the Discord Gateway reader against a real WebSocket connection.
 *
 * <p>The receiver connects to {@link FakeDiscordGateway}, identifies, and the fake server pushes a
 * MESSAGE_CREATE dispatch back. That exercises the WebSocket handshake, the IDENTIFY frame, and the
 * dispatch parsing, which unit tests on raw JSON cannot.
 */
class DiscordGatewayReceiverIntegrationTest {

    private static AdminConfig.DiscordInbound config(String gatewayUrl) {
        AdminConfig.DiscordInbound config = new AdminConfig.DiscordInbound();
        config.enabled = true;
        config.botToken = "test-bot-token";
        config.channelId = "111";
        config.gatewayUrl = gatewayUrl;
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

    @Test
    @Timeout(30)
    void identifiesThenShowsAChannelMessage() throws Exception {
        try (FakeDiscordGateway gateway = new FakeDiscordGateway()) {
            RecordingSink sink = new RecordingSink();
            InboundChatBridge bridge = new InboundChatBridge(inboundConfig(), sink);
            DiscordGatewayReceiver receiver = new DiscordGatewayReceiver(
                    config(gateway.url() + "/?v=10&encoding=json"), "Discord", bridge);

            receiver.start();
            try {
                assertTrue(gateway.awaitFirstFrame(15, TimeUnit.SECONDS),
                        "the client must send IDENTIFY; lastError=" + receiver.lastError());

                String identify = gateway.clientFrames().get(0);
                assertTrue(identify.contains("\"op\":2"), identify);
                assertTrue(identify.contains("test-bot-token"), identify);
                assertTrue(identify.contains("\"intents\":"), identify);
                // GUILD_MESSAGES (512) | MESSAGE_CONTENT (32768) = 33280.
                assertTrue(identify.contains("33280"), identify);

                gateway.sendDispatch("READY", "{\"session_id\":\"sess\",\"resume_gateway_url\":\"ws://127.0.0.1:1\"}");
                gateway.sendDispatch("MESSAGE_CREATE", "{\"id\":\"1\",\"channel_id\":\"111\","
                        + "\"guild_id\":\"999\",\"author\":{\"username\":\"Alex\"},\"content\":\"hello\","
                        + "\"timestamp\":\"2023-11-14T22:13:20Z\"}");
                long deadline = System.currentTimeMillis() + 15_000L;
                while (sink.received.isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(20L);
                }
                assertEquals(1, sink.received.size(), "the dispatch must reach the sink");
                assertEquals("[Discord] Alex: hello", sink.received.get(0).message());
            } finally {
                receiver.close();
            }
        }
    }

    @Test
    @Timeout(30)
    void aBotMessageOnTheWireIsIgnored() throws Exception {
        try (FakeDiscordGateway gateway = new FakeDiscordGateway()) {
            RecordingSink sink = new RecordingSink();
            InboundChatBridge bridge = new InboundChatBridge(inboundConfig(), sink);
            DiscordGatewayReceiver receiver = new DiscordGatewayReceiver(
                    config(gateway.url() + "/?v=10&encoding=json"), "Discord", bridge);

            receiver.start();
            try {
                assertTrue(gateway.awaitFirstFrame(15, TimeUnit.SECONDS),
                        "IDENTIFY expected; lastError=" + receiver.lastError());
                gateway.sendDispatch("MESSAGE_CREATE", "{\"id\":\"2\",\"channel_id\":\"111\","
                        + "\"author\":{\"username\":\"SomeBot\",\"bot\":true},\"content\":\"beep\"}");

                Thread.sleep(750L);
                assertTrue(sink.received.isEmpty(), "a bot message must not reach the sink");
            } finally {
                receiver.close();
            }
        }
    }

    @Test
    @Timeout(30)
    void anUnconfiguredReceiverDoesNotConnect() throws Exception {
        AdminConfig.DiscordInbound config = config("ws://127.0.0.1:1");
        config.botToken = "";
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(inboundConfig(), sink);
        DiscordGatewayReceiver receiver = new DiscordGatewayReceiver(config, "Discord", bridge);

        receiver.start();
        assertFalse(receiver.isRunning());
        assertTrue(receiver.lastError().contains("bot token"), receiver.lastError());
        receiver.close();
    }
}
