package com.adminmod.integrations.matrix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.integrations.inbound.ChatSink;
import com.adminmod.integrations.inbound.InboundChatBridge;
import com.adminmod.integrations.inbound.InboundChatMessage;

import org.junit.jupiter.api.Test;

/**
 * The sync response is parsed with plain JSON so the room handling can be tested without a
 * homeserver.
 */
class MatrixSyncReceiverTest {

    private static AdminConfig.MatrixInbound config() {
        AdminConfig.MatrixInbound config = new AdminConfig.MatrixInbound();
        config.enabled = true;
        config.homeserver = "https://matrix.example.org";
        config.roomId = "!room:example.org";
        config.accessToken = "syt_token";
        config.userId = "@adminbot:example.org";
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

    private static MatrixSyncReceiver receiver(RecordingSink sink) {
        return new MatrixSyncReceiver(config(), "Matrix", new InboundChatBridge(inboundConfig(), sink), null);
    }

    private static String syncWith(String... events) {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"next_batch\":\"s1\",\"rooms\":{\"join\":{\"!room:example.org\":{\"timeline\":{\"events\":[");
        for (int i = 0; i < events.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(events[i]);
        }
        builder.append("]}}}}}");
        return builder.toString();
    }

    private static String message(String sender, String body, String msgtype, String eventId) {
        return "{\"type\":\"m.room.message\",\"sender\":\"" + sender + "\",\"event_id\":\"" + eventId
                + "\",\"origin_server_ts\":1700000000000,\"content\":{\"msgtype\":\"" + msgtype
                + "\",\"body\":\"" + body + "\"}}";
    }

    @Test
    void aTextMessageBecomesAChatLine() {
        RecordingSink sink = new RecordingSink();
        receiver(sink).handleSync(syncWith(message("@alice:example.org", "hello", "m.text", "$1")));

        assertEquals(1, sink.received.size());
        assertEquals("[Matrix] alice: hello", sink.received.get(0).message());
        assertEquals("$1", sink.received.get(0).sourceId());
    }

    @Test
    void theBotsOwnMessageIsIgnored() {
        RecordingSink sink = new RecordingSink();
        receiver(sink).handleSync(syncWith(message("@adminbot:example.org", "echo", "m.text", "$2")));

        assertTrue(sink.received.isEmpty(), "the bot's own message must not be echoed into chat");
    }

    @Test
    void nonTextMessagesAreIgnored() {
        RecordingSink sink = new RecordingSink();
        receiver(sink).handleSync(syncWith(message("@alice:example.org", "img.png", "m.image", "$3")));

        assertTrue(sink.received.isEmpty());
    }

    @Test
    void eventsFromOtherRoomsAreIgnored() {
        RecordingSink sink = new RecordingSink();
        receiver(sink).handleSync("{\"next_batch\":\"s2\",\"rooms\":{\"join\":{\"!other:example.org\":{\"timeline\":"
                + "{\"events\":[" + message("@alice:example.org", "hi", "m.text", "$4") + "]}}}}}");

        assertTrue(sink.received.isEmpty());
    }

    @Test
    void anMxidBecomesAPlainName() {
        assertEquals("alice", MatrixSyncReceiver.displayName("@alice:example.org"));
        assertEquals("bob", MatrixSyncReceiver.displayName("bob"));
        assertEquals("unknown", MatrixSyncReceiver.displayName(""));
    }

    @Test
    void theSyncUrlCarriesTheRoomFilterAndTokenIsNotInTheUrl() {
        MatrixSyncReceiver receiver = new MatrixSyncReceiver(config(), "Matrix",
                new InboundChatBridge(inboundConfig(), message -> {
                }), null);
        String url = receiver.syncUrl(null);
        assertTrue(url.startsWith("https://matrix.example.org/_matrix/client/v3/sync?"), url);
        assertTrue(url.contains("timeout=30000"), url);
        assertTrue(url.contains("room%3Aexample.org"), url);
        assertFalse(url.contains("syt_token"), "the token must never appear in the URL");
    }

    @Test
    void isConfiguredRequiresHomeserverRoomAndToken() {
        MatrixSyncReceiver configured = new MatrixSyncReceiver(config(), "Matrix",
                new InboundChatBridge(inboundConfig(), message -> {
                }), null);
        assertTrue(configured.isConfigured());

        AdminConfig.MatrixInbound missing = config();
        missing.accessToken = "";
        MatrixSyncReceiver unconfigured = new MatrixSyncReceiver(missing, "Matrix",
                new InboundChatBridge(inboundConfig(), message -> {
                }), null);
        assertFalse(unconfigured.isConfigured());
    }
}
