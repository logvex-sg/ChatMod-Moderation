package com.adminmod.integrations.inbound;

/**
 * Destination for messages that arrive from an external platform.
 *
 * <p>The integration layer knows nothing about Minecraft. It hands a formatted message to a
 * sink, and the server module provides the implementation that broadcasts it to chat. That
 * keeps the {@code integrations} module free of Minecraft classes and unit testable on a
 * plain JVM.
 *
 * <p>An implementation must be safe to call from the platform's receiver thread. It must not
 * block: the server implementation schedules the broadcast onto the main thread.
 */
@FunctionalInterface
public interface ChatSink {

    /** Hands one inbound message to the game. Must never throw. */
    void accept(InboundChatMessage message);
}
