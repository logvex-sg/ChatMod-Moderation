package com.adminmod.common.net;

import java.util.List;

/**
 * A single client request. {@code requestId} lets the client match an asynchronous answer to
 * the request it issued; {@code arguments} is an ordered, type-agnostic parameter list whose
 * meaning depends on {@link OrderType}.
 */
public record OrderMessage(int requestId, OrderType orderType, List<String> arguments) implements Message {

    public static final int MAX_ARGUMENTS = 16;
    public static final int MAX_ARGUMENT_LENGTH = 4096;

    public OrderMessage {
        arguments = List.copyOf(arguments);
        if (arguments.size() > MAX_ARGUMENTS) {
            throw new ProtocolException("too many arguments: " + arguments.size());
        }
        for (String argument : arguments) {
            if (argument.length() > MAX_ARGUMENT_LENGTH) {
                throw new ProtocolException("argument too long");
            }
        }
    }

    public OrderMessage(int requestId, OrderType orderType, String... arguments) {
        this(requestId, orderType, List.of(arguments));
    }

    public String argument(int index) {
        return index >= 0 && index < arguments.size() ? arguments.get(index) : "";
    }

    @Override
    public MessageType type() {
        return MessageType.ORDER;
    }

    @Override
    public void write(PayloadWriter writer) {
        writer.writeVarInt(requestId);
        writer.writeVarInt(orderType.id());
        writer.writeVarInt(arguments.size());
        for (String argument : arguments) {
            writer.writeString(argument);
        }
    }

    static OrderMessage read(PayloadReader reader) {
        int requestId = reader.readVarInt();
        OrderType orderType = OrderType.byId(reader.readVarInt());
        int count = reader.readVarInt();
        if (count < 0 || count > MAX_ARGUMENTS) {
            throw new ProtocolException("invalid argument count " + count);
        }
        java.util.ArrayList<String> arguments = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            arguments.add(reader.readString());
        }
        return new OrderMessage(requestId, orderType, arguments);
    }
}
