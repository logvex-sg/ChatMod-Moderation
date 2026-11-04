package com.adminmod.common.net;

import java.util.Arrays;

/**
 * First message of a session. It carries no secrets: only version information, a build id, the
 * chosen authentication mode, an optional client identity and the two random nonces that later
 * feed the key derivation on both sides.
 *
 * <p>{@code clientId} is the identity issued during pairing and is empty for the password mode.
 * It is not trusted on its own: the server only uses it to look up the stored client secret, and
 * the first frame that decrypts under the derived key is what actually proves the caller holds
 * that secret.
 */
public record HandshakeMessage(int protocolVersion, String buildId, int authMode, String clientId,
        byte[] clientNonce) implements Message {

    public static final int NONCE_LENGTH = 16;

    public HandshakeMessage {
        if (clientNonce.length != NONCE_LENGTH) {
            throw new ProtocolException("client nonce must be " + NONCE_LENGTH + " bytes");
        }
        if (buildId == null) {
            buildId = "";
        }
        if (clientId == null) {
            clientId = "";
        }
    }

    /** Convenience for the password mode, which has no client identity. */
    public HandshakeMessage(int protocolVersion, String buildId, byte[] clientNonce) {
        this(protocolVersion, buildId, AuthMode.PASSWORD.id(), "", clientNonce);
    }

    public AuthMode mode() {
        return AuthMode.byId(authMode);
    }

    @Override
    public MessageType type() {
        return MessageType.HANDSHAKE;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(protocolVersion);
        writer.writeString(buildId);
        writer.writeVarInt(authMode);
        writer.writeString(clientId);
        writer.writeBytes(clientNonce);
    }

    static HandshakeMessage read(PayloadReader reader) {
        return new HandshakeMessage(reader.readVarInt(), reader.readString(), reader.readVarInt(),
                reader.readString(), reader.readBytes());
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
                && authMode == other.authMode
                && buildId.equals(other.buildId)
                && clientId.equals(other.clientId)
                && Arrays.equals(clientNonce, other.clientNonce);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * protocolVersion + buildId.hashCode()) + authMode)
                + clientId.hashCode()) + Arrays.hashCode(clientNonce);
    }
}
