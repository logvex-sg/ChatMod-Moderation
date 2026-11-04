package com.adminmod.common.net;

/** Server-to-client push describing chat bridge delivery health. */
public record ChatBridgeStatusMessage(String json) implements Message {

    @Override
    public MessageType type() {
        return MessageType.CHAT_BRIDGE_STATUS;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeString(json);
    }

    static ChatBridgeStatusMessage read(PayloadReader reader) {
        return new ChatBridgeStatusMessage(reader.readString());
    }
}
