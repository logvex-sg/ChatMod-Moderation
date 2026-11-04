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

    /** Waits until the dispatcher worker has had a chance to attempt at least one delivery. */
    private static void awaitDelivery(CountingTransport transport) {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (transport.uris.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    @Test
    void aRepeatedMessageIsDroppedByTheAntiSpamGuard() {
        AdminConfig config = config();
        config.chatBridge.antiSpam.duplicateWindowSeconds = 60L;
        config.chatBridge.antiSpam.maxDuplicates = 1;
        config.chatBridge.antiSpam.minIntervalMillis = 0L;
        CountingTransport transport = new CountingTransport();
        ChatBridgeService bridge = new ChatBridgeService(config, "Server", transport);

        bridge.onPublicChat("Alex", "uuid", "buy my stuff");
        awaitDelivery(transport);
        assertEquals(1, transport.uris.size(), "the first copy is forwarded");

        // The same body from the same player is over the duplicate cap, so it is dropped before
        // it ever reaches the queue.
        bridge.onPublicChat("Alex", "uuid", "buy my stuff");
        bridge.onPublicChat("Alex", "uuid", "buy my stuff");
        try {
            Thread.sleep(300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertEquals(1, transport.uris.size(), "the repeats must not be forwarded");
        bridge.close();
    }

    @Test
    void antiSpamIsPerPlayer() {
        AdminConfig config = config();
        config.chatBridge.antiSpam.duplicateWindowSeconds = 60L;
        config.chatBridge.antiSpam.maxDuplicates = 1;
        config.chatBridge.antiSpam.minIntervalMillis = 0L;
        CountingTransport transport = new CountingTransport();
        ChatBridgeService bridge = new ChatBridgeService(config, "Server", transport);

        bridge.onPublicChat("Alex", "uuid-a", "hello");
        bridge.onPublicChat("Steve", "uuid-b", "hello");
        long deadline = System.currentTimeMillis() + 3_000L;
        while (transport.uris.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(2, transport.uris.size(), "a different player's identical line is not spam");
        bridge.close();
    }

    @Test
    void aReloadRebuildsTheDispatcherWithoutLeakingIt() {
        AdminConfig config = config();
        CountingTransport transport = new CountingTransport();
        ChatBridgeService bridge = new ChatBridgeService(config, "Server", transport);
        long deliveredBefore = bridge.dispatcher().deliveredCount();

        int readers = bridge.reload();

        assertEquals(0, readers, "inbound is disabled, so no readers should start");
        assertEquals(deliveredBefore, bridge.dispatcher().deliveredCount(), "counters start fresh");
        // The rebuilt dispatcher still works: a message is delivered after the reload.
        bridge.onPublicChat("Alex", "uuid", "after reload");
        awaitDelivery(transport);
        assertEquals(1, transport.uris.size());
        bridge.close();
    }
}
