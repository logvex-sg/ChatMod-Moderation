package com.adminmod.common.net;

/**
 * Server answer to a single {@link OrderMessage}. The optional JSON payload carries structured
 * data for requests such as "list players"; requests that only mutate state return an empty
 * payload.
 */
public record OrderResultMessage(int requestId, boolean success, String message, String json) implements Message {

    public OrderResultMessage(int requestId, boolean success, String message) {
        this(requestId, success, message, "");
    }

    @Override
    public MessageType type() {
        return MessageType.ORDER_RESULT;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(requestId);
        writer.writeBoolean(success);
        writer.writeString(message);
        writer.writeString(json);
    }

    static OrderResultMessage read(PayloadReader reader) {
        return new OrderResultMessage(reader.readVarInt(), reader.readBoolean(), reader.readString(), reader.readString());
    }
}
