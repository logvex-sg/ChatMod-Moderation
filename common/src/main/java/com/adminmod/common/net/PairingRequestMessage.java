package com.adminmod.common.net;

/**
 * Client-to-server pairing request, sent as the first encrypted frame of a session whose handshake
 * used {@link AuthMode#PAIRING_KEY}.
 *
 * <p>It is encrypted with the key derived from the one-time pairing key, so a network observer
 * never sees the key itself. The server only acts on it once that key has decrypted, which is what
 * proves the caller holds the pairing key. The requested label is a human-readable name for the
 * client, shown in the dashboard's authorized-client list.
 */
public record PairingRequestMessage(int requestId, String label, String clientBuildId) implements Message {

    public PairingRequestMessage {
        if (label == null) {
            label = "";
        }
        if (clientBuildId == null) {
            clientBuildId = "";
        }
    }

    @Override
    public MessageType type() {
        return MessageType.PAIRING_REQUEST;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(requestId);
        writer.writeString(label);
        writer.writeString(clientBuildId);
    }

    static PairingRequestMessage read(PayloadReader reader) {
        return new PairingRequestMessage(reader.readVarInt(), reader.readString(), reader.readString());
    }
}
