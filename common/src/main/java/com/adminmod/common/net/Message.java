package com.adminmod.common.net;

/** A message that can travel inside an {@link Envelope}. */
public interface Message {
    MessageType type();

    void write(PayloadWriter writer);

    static Message decode(byte[] body) {
        PayloadReader reader = new PayloadReader(body);
        MessageType type = MessageType.byId(reader.readVarInt());
        Message message = switch (type) {
            case HANDSHAKE -> HandshakeMessage.read(reader);
            case HANDSHAKE_RESULT -> HandshakeResultMessage.read(reader);
            case ORDER -> OrderMessage.read(reader);
            case ORDER_RESULT -> OrderResultMessage.read(reader);
            case SNAPSHOT -> SnapshotMessage.read(reader);
            case CHAT_BRIDGE_STATUS -> ChatBridgeStatusMessage.read(reader);
            case PAIRING_REQUEST -> PairingRequestMessage.read(reader);
            case PAIRING_RESULT -> PairingResultMessage.read(reader);
        };
        if (reader.hasRemaining()) {
            throw new ProtocolException("trailing bytes in " + type);
        }
        return message;
    }

    static byte[] encode(Message message) {
        PayloadWriter writer = new PayloadWriter();
        writer.writeVarInt(message.type().id());
        message.write(writer);
        return writer.toByteArray();
    }
}
