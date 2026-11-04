package com.adminmod.server.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.inbound.InboundChatMessage;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;

/**
 * Loop prevention for the outbound direction. A line shown because it arrived from a platform
 * must not be forwarded back to that platform, and neither must the echo of a remote command.
 */
class ChatBridgeServiceTest {

    private static AdminConfig config() {
        AdminConfig config = new AdminConfig();
        config.chatBridge.discord.webhookUrl = "https://discord.com/api/webhooks/1/token";
        return config;
    }

    /** Records the deliveries that were attempted. */
    private static final class CountingTransport implements HttpTransport {
        final List<String> uris = new CopyOnWriteArrayList<>();

        @Override
        public HttpResult send(HttpRequest request, Duration timeout) {
            uris.add(request.uri().toString());
            return new HttpResult(204, "");
        }
    }

    @Test
    void anInboundLineIsNotForwardedBackOut() throws Exception {
        CountingTransport transport = new CountingTransport();
        ChatBridgeService bridge = new ChatBridgeService(config(), "Server", transport);
        String line = "[Discord] Alex: hello";

        bridge.markInboundDisplayed(line);
        // The broadcast is delivered to players as a game message, which is what the bridge sees.
        bridge.onSystemMessage(line);

        // Give the dispatcher worker a chance to run before asserting nothing was sent.
        Thread.sleep(500L);
        assertTrue(transport.uris.isEmpty(), "the inbound echo must not be forwarded outbound");
        bridge.close();
    }

    @Test
    void anOrdinaryServerNoticeIsStillForwarded() {
        CountingTransport transport = new CountingTransport();
        ChatBridgeService bridge = new ChatBridgeService(config(), "Server", transport);

        bridge.onSystemMessage("Server is restarting soon");

        long deadline = System.currentTimeMillis() + 3_000L;
        while (transport.uris.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(transport.uris.isEmpty(), "a genuine server notice should reach the platform");
        bridge.close();
    }

    @Test
    void aCommandEchoIsNotForwarded() throws Exception {
        CountingTransport transport = new CountingTransport();
        ChatBridgeService bridge = new ChatBridgeService(config(), "Server", transport);

        bridge.noteExecutedCommand("say hello world");
        bridge.onSystemMessage("hello world");

        Thread.sleep(500L);
        assertTrue(transport.uris.isEmpty(), "the echo of a remote command must not be forwarded");
        bridge.close();
    }

    @Test
    void theInboundSinkReceivesFormattedLines() {
        AdminConfig config = config();
        config.chatBridge.inbound.enabled = true;
        ChatBridgeService bridge = new ChatBridgeService(config, "Server", new CountingTransport());
        List<InboundChatMessage> shown = new CopyOnWriteArrayList<>();
        bridge.setSink(shown::add);

        bridge.inbound().deliver(new InboundChatMessage("Discord", "Alex", "hello", 1L, "1"));

        assertEquals(1, shown.size());
        assertEquals("[Discord] Alex: hello", shown.get(0).message());
        bridge.close();
    }
}
