package com.adminmod.common.net;

import java.util.Arrays;

/**
 * First message of a session. It carries no secrets: only version information and the two
 * random nonces that later feed the key derivation on both sides.
 */
public record HandshakeMessage(int protocolVersion, String clientVersion, byte[] clientNonce) implements Message {

    public static final int NONCE_LENGTH = 16;

    public HandshakeMessage {
        if (clientNonce.length != NONCE_LENGTH) {
            throw new ProtocolException("client nonce must be " + NONCE_LENGTH + " bytes");
        }
    }

    @Override
    public MessageType type() {
        return MessageType.HANDSHAKE;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(protocolVersion);
        writer.writeString(clientVersion);
        writer.writeBytes(clientNonce);
    }

    static HandshakeMessage read(PayloadReader reader) {
        return new HandshakeMessage(reader.readVarInt(), reader.readString(), reader.readBytes());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HandshakeMessage other)) {
            return false;
        }
        return protocolVersion == other.protocolVersion
                && clientVersion.equals(other.clientVersion)
                && Arrays.equals(clientNonce, other.clientNonce);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * protocolVersion + clientVersion.hashCode()) + Arrays.hashCode(clientNonce);
    }
}
