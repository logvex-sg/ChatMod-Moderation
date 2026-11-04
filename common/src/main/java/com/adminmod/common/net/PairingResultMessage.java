package com.adminmod.common.net;

/**
 * Server's answer to a {@link PairingRequestMessage}, sealed with the same key as the request.
 *
 * <p>On success {@code clientId} and {@code clientSecret} are the credential the client must store
 * and present on later sessions. The secret is only ever transmitted inside this encrypted frame;
 * it is never written to a log and never included in a plaintext handshake result.
 */
public record PairingResultMessage(int requestId, boolean success, String message, String clientId,
        String clientSecret) implements Message {

    public PairingResultMessage(int requestId, boolean success, String message) {
        this(requestId, success, message, "", "");
    }

    public PairingResultMessage {
        if (message == null) {
            message = "";
        }
        if (clientId == null) {
            clientId = "";
        }
        if (clientSecret == null) {
            clientSecret = "";
        }
    }

    @Override
    public MessageType type() {
        return MessageType.PAIRING_RESULT;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(requestId);
        writer.writeBoolean(success);
        writer.writeString(message);
        writer.writeString(clientId);
        writer.writeString(clientSecret);
    }

    static PairingResultMessage read(PayloadReader reader) {
        return new PairingResultMessage(reader.readVarInt(), reader.readBoolean(), reader.readString(),
                reader.readString(), reader.readString());
    }
}
