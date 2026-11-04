package com.adminmod.common.net;

/** Identifies the concrete payload carried inside an {@link Envelope}. */
public enum MessageType {
    HANDSHAKE(0),
    HANDSHAKE_RESULT(1),
    ORDER(2),
    ORDER_RESULT(3),
    SNAPSHOT(4),
    CHAT_BRIDGE_STATUS(5),
    /** Encrypted client-to-server pairing request. */
    PAIRING_REQUEST(6),
    /** Encrypted server-to-client pairing answer, carrying the issued client credential. */
    PAIRING_RESULT(7);

    private final int id;

    MessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static MessageType byId(int id) {
        for (MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new ProtocolException("unknown message type " + id);
    }
}
