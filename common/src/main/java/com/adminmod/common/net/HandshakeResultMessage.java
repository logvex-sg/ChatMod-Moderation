package com.adminmod.common.net;

import java.util.Arrays;

/**
 * Server's answer to a {@link HandshakeMessage}.
 *
 * <p>The message is sent before a session key exists, so it must not carry anything that would be
 * dangerous to read. It therefore holds only the protocol version, the server version and build id,
 * a random nonce, a status code and the set of authentication modes the server accepts. The
 * {@code credential} field is empty in the normal case; a server may fill it in only when the
 * connecting player is already an operator and the operator explicitly enabled
 * {@code remoteAdmin.revealPasswordToOperators}. That disclosure never raises a player's
 * privileges, because an operator already has full console access, but it is off by default.
 */
public record HandshakeResultMessage(int protocolVersion, String serverVersion, String serverBuildId,
        byte[] serverNonce, int status, int allowedModes, String credential) implements Message {

    public static final int STATUS_OK = 0;
    public static final int STATUS_VERSION_MISMATCH = 1;
    public static final int STATUS_DISABLED = 2;
    public static final int STATUS_REJECTED = 3;
    /** The client build id is not on the server's allow list. */
    public static final int STATUS_BUILD_REJECTED = 4;
    /** A pairing-mode handshake named a client identity the server does not know. */
    public static final int STATUS_UNKNOWN_CLIENT = 5;
    /** Too many failed authentications from this address; try again later. */
    public static final int STATUS_LOCKED_OUT = 6;
    /** The requested authentication mode is not enabled on this server. */
    public static final int STATUS_MODE_NOT_ALLOWED = 7;

    public HandshakeResultMessage(int protocolVersion, String serverVersion, String serverBuildId,
            byte[] serverNonce, int status, int allowedModes) {
        this(protocolVersion, serverVersion, serverBuildId, serverNonce, status, allowedModes, "");
    }

    public HandshakeResultMessage {
        if (serverNonce.length != HandshakeMessage.NONCE_LENGTH) {
            throw new ProtocolException("server nonce must be " + HandshakeMessage.NONCE_LENGTH + " bytes");
        }
        if (credential == null) {
            credential = "";
        }
        if (serverVersion == null) {
            serverVersion = "";
        }
        if (serverBuildId == null) {
            serverBuildId = "";
        }
    }

    /** @return whether the server currently accepts the given mode. */
    public boolean allows(AuthMode mode) {
        return (allowedModes & (1 << mode.id())) != 0;
    }

    @Override
    public MessageType type() {
        return MessageType.HANDSHAKE_RESULT;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(protocolVersion);
        writer.writeString(serverVersion);
        writer.writeString(serverBuildId);
        writer.writeBytes(serverNonce);
        writer.writeVarInt(status);
        writer.writeVarInt(allowedModes);
        writer.writeString(credential);
    }

    static HandshakeResultMessage read(PayloadReader reader) {
        return new HandshakeResultMessage(reader.readVarInt(), reader.readString(), reader.readString(),
                reader.readBytes(), reader.readVarInt(), reader.readVarInt(), reader.readString());
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
                && allowedModes == other.allowedModes
                && serverVersion.equals(other.serverVersion)
                && serverBuildId.equals(other.serverBuildId)
                && credential.equals(other.credential)
                && Arrays.equals(serverNonce, other.serverNonce);
    }

    @Override
    public int hashCode() {
        int result = 31 * (31 * (31 * (31 * (31 * protocolVersion + serverVersion.hashCode())
                + serverBuildId.hashCode()) + Arrays.hashCode(serverNonce)) + status) + allowedModes;
        return 31 * result + credential.hashCode();
    }
}
