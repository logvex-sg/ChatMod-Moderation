package com.adminmod.common.net;

/** Server-to-client push carrying a JSON snapshot used to synchronise the dashboard. */
public record SnapshotMessage(String scope, String json) implements Message {

    @Override
    public MessageType type() {
        return MessageType.SNAPSHOT;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeString(scope);
        writer.writeString(json);
    }

    static SnapshotMessage read(PayloadReader reader) {
        return new SnapshotMessage(reader.readString(), reader.readString());
    }
}
