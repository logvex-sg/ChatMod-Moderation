package com.adminmod.common.net;

import java.util.Arrays;

/**
 * Server's answer to a {@link HandshakeMessage}.
 *
 * <p>The message is sent before a session key exists, so it must not carry anything that would be
 * dangerous to read. It therefore holds only the protocol version, the server version, a random
 * nonce and a status code. The {@code credential} field is empty in the normal case; a server may
 * fill it in only when the connecting player is already an operator and the operator explicitly
 * enabled {@code remoteAdmin.revealPasswordToOperators}, which lets the client's authentication
 * panel show the generated password. That disclosure never raises a player's privileges, because
 * an operator already has full console access, but it is off by default.
 */
public record HandshakeResultMessage(int protocolVersion, String serverVersion, byte[] serverNonce, int status,
        String credential) implements Message {

    public static final int STATUS_OK = 0;
    public static final int STATUS_VERSION_MISMATCH = 1;
    public static final int STATUS_DISABLED = 2;
    public static final int STATUS_REJECTED = 3;

    public HandshakeResultMessage(int protocolVersion, String serverVersion, byte[] serverNonce, int status) {
        this(protocolVersion, serverVersion, serverNonce, status, "");
    }

    public HandshakeResultMessage {
        if (serverNonce.length != HandshakeMessage.NONCE_LENGTH) {
            throw new ProtocolException("server nonce must be " + HandshakeMessage.NONCE_LENGTH + " bytes");
        }
        if (credential == null) {
            credential = "";
        }
    }

    @Override
    public MessageType type() {
        return MessageType.HANDSHAKE_RESULT;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(protocolVersion);
        writer.writeString(serverVersion);
        writer.writeBytes(serverNonce);
        writer.writeVarInt(status);
        writer.writeString(credential);
    }

    static HandshakeResultMessage read(PayloadReader reader) {
        return new HandshakeResultMessage(reader.readVarInt(), reader.readString(), reader.readBytes(),
                reader.readVarInt(), reader.readString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HandshakeResultMessage other)) {
            return false;
        }
        return protocolVersion == other.protocolVersion
                && status == other.status
                && serverVersion.equals(other.serverVersion)
                && credential.equals(other.credential)
                && Arrays.equals(serverNonce, other.serverNonce);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * (31 * protocolVersion + serverVersion.hashCode())
                + Arrays.hashCode(serverNonce)) + status) + credential.hashCode();
    }
}
