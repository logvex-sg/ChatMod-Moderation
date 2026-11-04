package com.adminmod.integrations.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.adminmod.common.config.AdminConfig;

import org.junit.jupiter.api.Test;

/**
 * The inbound funnel is the only place platform messages are turned into chat lines, so these
 * tests pin down the formatting, the loop guard and the enable switch.
 */
class InboundChatBridgeTest {

    private static AdminConfig.Inbound enabled() {
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
    void formatsWithThePlatformPrefixAndUserName() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        bridge.deliver(new InboundChatMessage("Discord", "Alex", "hello", 1L, "1"));
        assertEquals(1, sink.received.size());
        assertEquals("[Discord] Alex: hello", sink.received.get(0).message());
        assertEquals("Discord", sink.received.get(0).platform());
        assertEquals("Alex", sink.received.get(0).user());
    }

    @Test
    void matrixLabelIsUsedForMatrixMessages() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        bridge.deliver(new InboundChatMessage("Matrix", "alice", "hi", 1L, "$1"));
        assertEquals("[Matrix] alice: hi", sink.received.get(0).message());
    }

    @Test
    void aDisabledBridgeShowsNothing() {
        RecordingSink sink = new RecordingSink();
        AdminConfig.Inbound config = new AdminConfig.Inbound();
        config.enabled = false;
        InboundChatBridge bridge = new InboundChatBridge(config, sink);
        assertFalse(bridge.deliver(new InboundChatMessage("Discord", "Alex", "hello", 1L, "1")));
        assertTrue(sink.received.isEmpty());
        assertEquals(1L, bridge.droppedCount());
    }

    @Test
    void anIdenticalMessageInsideTheWindowIsShownOnce() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        assertTrue(bridge.deliver(new InboundChatMessage("Discord", "Alex", "hello", 1L, "1")));
        assertFalse(bridge.deliver(new InboundChatMessage("Discord", "Alex", "hello", 2L, "2")));
        assertEquals(1, sink.received.size());
    }

    @Test
    void differentSendersWithTheSameBodyAreBothShown() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        bridge.deliver(new InboundChatMessage("Discord", "Alex", "hello", 1L, "1"));
        bridge.deliver(new InboundChatMessage("Discord", "Sam", "hello", 2L, "2"));
        assertEquals(2, sink.received.size());
    }

    @Test
    void theSameBodyOnDifferentPlatformsIsBothShown() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        bridge.deliver(new InboundChatMessage("Discord", "Alex", "hello", 1L, "1"));
        bridge.deliver(new InboundChatMessage("Matrix", "Alex", "hello", 2L, "2"));
        assertEquals(2, sink.received.size());
    }

    @Test
    void aBlankBodyIsDropped() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        assertFalse(bridge.deliver(new InboundChatMessage("Discord", "Alex", "   ", 1L, "1")));
        assertTrue(sink.received.isEmpty());
    }

    @Test
    void aFailingSinkDoesNotPropagate() {
        InboundChatBridge bridge = new InboundChatBridge(enabled(), message -> {
            throw new IllegalStateException("boom");
        });
        assertFalse(bridge.deliver(new InboundChatMessage("Discord", "Alex", "hello", 1L, "1")));
        assertEquals(1L, bridge.droppedCount());
    }

    @Test
    void countersTrackWhatWasShownAndWhatWasNot() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        bridge.deliver(new InboundChatMessage("Discord", "Alex", "one", 1L, "1"));
        bridge.deliver(new InboundChatMessage("Discord", "Alex", "one", 2L, "2"));
        bridge.deliver(new InboundChatMessage("Discord", "Alex", "two", 3L, "3"));
        assertEquals(3L, bridge.receivedCount());
        assertEquals(2L, bridge.displayedCount());
        assertEquals(1L, bridge.droppedCount());
    }

    @Test
    void registeredReceiversAreReportedAndClosed() {
        RecordingSink sink = new RecordingSink();
        InboundChatBridge bridge = new InboundChatBridge(enabled(), sink);
        List<String> closed = new ArrayList<>();
        bridge.register(new InboundReceiver() {
            public String id() {
                return "test";
            }

            public void start() {
            }

            public boolean isRunning() {
                return true;
            }

            public String describe() {
                return "test reader";
            }

            public String lastError() {
                return "";
            }

            public void close() {
                closed.add("test");
            }
        });
        assertEquals(1, bridge.receivers().size());
        bridge.close();
        assertEquals(List.of("test"), closed);
    }
}
