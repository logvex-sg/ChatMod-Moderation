package com.adminmod.common.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class WireFormatTest {

    @Test
    void varIntRoundTripsAcrossBoundaries() {
        int[] values = {0, 1, 127, 128, 255, 300, 16_383, 16_384, Integer.MAX_VALUE, -1, Integer.MIN_VALUE};
        for (int value : values) {
            byte[] encoded = new PayloadWriter().writeVarInt(value).toByteArray();
            assertEquals(value, new PayloadReader(encoded).readVarInt(), "value " + value);
        }
    }

    @Test
    void varLongRoundTripsAcrossBoundaries() {
        long[] values = {0L, 1L, 127L, 128L, Long.MAX_VALUE, -1L, Long.MIN_VALUE};
        for (long value : values) {
            byte[] encoded = new PayloadWriter().writeVarLong(value).toByteArray();
            assertEquals(value, new PayloadReader(encoded).readVarLong(), "value " + value);
        }
    }

    @Test
    void primitivesRoundTrip() {
        byte[] encoded = new PayloadWriter()
                .writeInt(12345)
                .writeLong(987654321L)
                .writeBoolean(true)
                .writeBoolean(false)
                .writeString("hello \u00e9\u00e8 world")
                .toByteArray();
        PayloadReader reader = new PayloadReader(encoded);
        assertEquals(12345, reader.readInt());
        assertEquals(987654321L, reader.readLong());
        assertTrue(reader.readBoolean());
        assertFalse(reader.readBoolean());
        assertEquals("hello \u00e9\u00e8 world", reader.readString());
        assertFalse(reader.hasRemaining());
    }

    @Test
    void uuidRoundTrips() {
        UUID uuid = UUID.randomUUID();
        byte[] encoded = new PayloadWriter().writeUuid(uuid).toByteArray();
        assertEquals(uuid, new PayloadReader(encoded).readUuid());
    }

    @Test
    void truncatedPayloadIsRejected() {
        byte[] encoded = new PayloadWriter().writeString("a longer string value").toByteArray();
        byte[] truncated = new byte[encoded.length - 4];
        System.arraycopy(encoded, 0, truncated, 0, truncated.length);
        assertThrows(ProtocolException.class, () -> new PayloadReader(truncated).readString());
    }

    @Test
    void oversizedStringIsRejected() {
        byte[] bad = new PayloadWriter().writeVarInt(PayloadReader.MAX_STRING + 1).toByteArray();
        assertThrows(ProtocolException.class, () -> new PayloadReader(bad).readString());
    }

    @Test
    void envelopeRoundTrips() {
        Envelope envelope = new Envelope(42L, 1_700_000_000_000L, new byte[] {1, 2, 3, 4});
        Envelope decoded = Envelope.decode(envelope.encode());
        assertEquals(envelope, decoded);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, decoded.body());
    }

    @Test
    void messageTypeIsCarriedInTheBody() {
        OrderMessage order = new OrderMessage(7, OrderType.EXECUTE_COMMAND, List.of("say hi"));
        byte[] body = Message.encode(order);
        Message decoded = Message.decode(body);
        assertEquals(MessageType.ORDER, decoded.type());
        OrderMessage decodedOrder = (OrderMessage) decoded;
        assertEquals(7, decodedOrder.requestId());
        assertEquals(OrderType.EXECUTE_COMMAND, decodedOrder.orderType());
        assertEquals(List.of("say hi"), decodedOrder.arguments());
    }

    @Test
    void everyMessageTypeRoundTrips() {
        assertRoundTrip(new HandshakeMessage(1, "adminmod/1.0.0", AuthMode.PASSWORD.id(), "",
                new byte[HandshakeMessage.NONCE_LENGTH]));
        assertRoundTrip(new HandshakeResultMessage(1, "1.0.0", "adminmod/1.0.0",
                new byte[HandshakeMessage.NONCE_LENGTH], HandshakeResultMessage.STATUS_OK,
                (1 << AuthMode.PASSWORD.id()) | (1 << AuthMode.CLIENT_SECRET.id())));
        assertRoundTrip(new OrderMessage(3, OrderType.BAN, List.of("Steve", "3600", "spam")));
        assertRoundTrip(new OrderResultMessage(3, true, "banned", "{\"id\":5}"));
        assertRoundTrip(new SnapshotMessage("players", "[{\"name\":\"Alex\"}]"));
        assertRoundTrip(new ChatBridgeStatusMessage("{\"ok\":true}"));
        assertRoundTrip(new PairingRequestMessage(4, "Alex@localhost", "adminmod/1.0.0"));
        assertRoundTrip(new PairingResultMessage(4, true, "paired as c1", "c1", "secret-value"));
    }

    private void assertRoundTrip(Message message) {
        Message decoded = Message.decode(Message.encode(message));
        assertEquals(message, decoded, message.getClass().getSimpleName());
    }

    @Test
    void unknownMessageTypeIsRejected() {
        byte[] body = new PayloadWriter().writeVarInt(9999).toByteArray();
        assertThrows(ProtocolException.class, () -> Message.decode(body));
    }

    @Test
    void trailingBytesAreRejected() {
        byte[] body = new PayloadWriter()
                .writeVarInt(MessageType.CHAT_BRIDGE_STATUS.id())
                .writeString("{}")
                .writeInt(1)
                .toByteArray();
        assertThrows(ProtocolException.class, () -> Message.decode(body));
    }

    @Test
    void orderIdsAreStable() {
        // The ids are part of the wire format; renumbering them would silently break clients.
        assertEquals(0, OrderType.EXECUTE_COMMAND.id());
        assertEquals(21, OrderType.STATUS.id());
        for (OrderType type : OrderType.values()) {
            assertEquals(type, OrderType.byId(type.id()));
        }
    }

    @Test
    void tooManyArgumentsAreRejected() {
        List<String> arguments = java.util.Collections.nCopies(OrderMessage.MAX_ARGUMENTS + 1, "x");
        assertThrows(ProtocolException.class, () -> new OrderMessage(1, OrderType.STATUS, arguments));
    }
}
