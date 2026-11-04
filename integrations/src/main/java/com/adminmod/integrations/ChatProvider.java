package com.adminmod.integrations;

import java.time.Duration;

import com.adminmod.integrations.http.HttpResult;

/**
 * A destination that can receive a chat message. Implementations must be safe to call from a
 * background thread and must not retain the {@link ChatEvent} beyond the call.
 */
public interface ChatProvider {

    /** Stable identifier used in configuration, logs and the dashboard. */
    String id();

    /** @return {@code true} when the provider holds enough configuration to attempt delivery. */
    boolean isConfigured();

    /**
     * Attempts one delivery.
     *
     * <p>Implementations must not throw: transport problems are reported through the returned
     * {@link HttpResult} so the dispatcher can decide whether to retry.
     */
    HttpResult deliver(ChatEvent event, Duration timeout);

    /** Non-secret description of the destination, suitable for the dashboard. */
    String describe();

    /** Redacted, non-throwing description of the most recent failure. */
    String lastError();
}
