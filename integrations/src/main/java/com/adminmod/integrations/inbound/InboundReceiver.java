package com.adminmod.integrations.inbound;

/**
 * A long-lived reader for one external platform.
 *
 * <p>Implementations own a background thread, or a scheduled loop, that reads events from the
 * platform and pushes them into the {@link InboundChatBridge}. Lifecycle mirrors
 * {@link java.io.Closeable}: {@link #start()} is called once when the server is ready and
 * {@link #close()} when it stops.
 */
public interface InboundReceiver extends AutoCloseable {

    /** Stable identifier used in configuration and on the dashboard. */
    String id();

    /** Starts reading. Calling it more than once has no additional effect. */
    void start();

    /** @return {@code true} while the reader believes it is connected. */
    boolean isRunning();

    /** Non-secret description of the connection, suitable for the dashboard. */
    String describe();

    /** Redacted, non-throwing description of the most recent failure. */
    String lastError();

    @Override
    void close();
}
