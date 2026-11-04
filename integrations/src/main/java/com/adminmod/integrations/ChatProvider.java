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

    /**
     * Whether the provider can combine several events into one request.
     *
     * <p>Only Discord webhooks can, and only when batching is enabled; the default is no, so a
     * provider that does not override this is never handed a batch it cannot render.
     */
    default boolean supportsBatch() {
        return false;
    }

    /**
     * Attempts one delivery of several events.
     *
     * <p>Called only when {@link #supportsBatch()} is {@code true}. The default falls back to
     * delivering each event on its own, so a provider never has to implement this.
     *
     * @return the outcome of the combined request
     */
    default HttpResult deliverBatch(java.util.List<ChatEvent> events, Duration timeout) {
        HttpResult last = null;
        for (ChatEvent event : events) {
            last = deliver(event, timeout);
            if (!last.isSuccess()) {
                return last;
            }
        }
        return last == null ? HttpResult.failure("empty batch") : last;
    }
}
