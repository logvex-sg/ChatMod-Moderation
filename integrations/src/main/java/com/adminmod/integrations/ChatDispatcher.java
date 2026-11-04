package com.adminmod.integrations;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.adminmod.common.security.SecretRedactor;
import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.RetryPolicy;

/**
 * Bounded, asynchronous delivery queue fanning out to one or more providers.
 *
 * <p>Producers only ever enqueue; a single worker thread performs the blocking HTTP calls, so
 * the Minecraft server tick thread is never blocked by network I/O. When the queue is full the
 * oldest entry is dropped rather than growing without limit or blocking the producer.
 *
 * <p>A single worker is deliberate. Several webhooks can be targeted, but they are delivered
 * sequentially by one thread, which bounds the thread count regardless of how many destinations
 * are configured and keeps ordering stable per destination.
 */
public final class ChatDispatcher implements AutoCloseable {

    /** Bounded ring of recent failures kept for the dashboard. */
    public static final int ERROR_HISTORY = 16;

    private final List<ChatProvider> providers;
    private final Duration timeout;
    private final RetryPolicy retryPolicy;
    private final int maxAttempts;
    private final int capacity;

    private final Deque<ChatEvent> queue;
    private final Thread worker;
    private final Object lock = new Object();
    private volatile boolean running = true;

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final Deque<String> recentErrors = new ArrayDeque<>();
    /** Delivery outcome of the most recent attempt, for the dashboard's delivery status. */
    private volatile String lastDeliveryStatus = "idle";
    private volatile long lastDeliveryAt;

    public ChatDispatcher(ChatProvider provider, int capacity, int maxAttempts, Duration timeout,
            RetryPolicy retryPolicy) {
        this(List.of(provider), capacity, maxAttempts, timeout, retryPolicy);
    }

    public ChatDispatcher(List<ChatProvider> providers, int capacity, int maxAttempts, Duration timeout,
            RetryPolicy retryPolicy) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("at least one provider is required");
        }
        this.providers = List.copyOf(providers);
        this.capacity = Math.max(16, capacity);
        this.queue = new ArrayDeque<>(this.capacity);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.timeout = timeout;
        this.retryPolicy = retryPolicy;
        String name = this.providers.size() == 1 ? this.providers.get(0).id() : "fanout";
        this.worker = new Thread(this::run, "adminmod-chat-" + name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Queues an event. Never blocks and never throws. */
    public void enqueue(ChatEvent event) {
        if (event == null || !running) {
            return;
        }
        synchronized (lock) {
            if (queue.size() >= capacity) {
                queue.pollFirst();
                dropped.incrementAndGet();
            }
            queue.addLast(event);
            lock.notifyAll();
        }
    }

    private void run() {
        while (running || !queue.isEmpty()) {
            ChatEvent event;
            synchronized (lock) {
                while (queue.isEmpty() && running) {
                    try {
                        lock.wait(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                event = queue.pollFirst();
            }
            if (event == null) {
                continue;
            }
            deliverToAll(event);
        }
    }

    /** Delivers one event to every provider. A failure on one destination does not stop the rest. */
    private void deliverToAll(ChatEvent event) {
        for (ChatProvider provider : providers) {
            deliverWithRetries(provider, event);
        }
    }

    private void deliverWithRetries(ChatProvider provider, ChatEvent event) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!running && attempt > 1) {
                return;
            }
            HttpResult result;
            try {
                result = provider.deliver(event, timeout);
            } catch (RuntimeException e) {
                // A provider is required not to throw, but a broken one must not kill the worker.
                result = HttpResult.failure(SecretRedactor.redact(
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            if (result == null) {
                result = HttpResult.failure("provider returned no result");
            }
            if (result.isSuccess()) {
                delivered.incrementAndGet();
                lastDeliveryStatus = "delivered via " + provider.id();
                lastDeliveryAt = System.currentTimeMillis();
                return;
            }
            if (!result.isRetryable() || attempt == maxAttempts) {
                failed.incrementAndGet();
                String message = "attempt " + attempt + "/" + maxAttempts + ": " + describe(result);
                recordError(provider.id() + ": " + message);
                lastDeliveryStatus = "failed via " + provider.id() + ": " + describe(result);
                lastDeliveryAt = System.currentTimeMillis();
                return;
            }
            retries.incrementAndGet();
            long delay = retryPolicy.delayMillis(attempt, result.retryAfterMillis());
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String describe(HttpResult result) {
        if (result.failure() != null && !result.failure().isBlank()) {
            return SecretRedactor.redact(result.failure());
        }
        return "HTTP " + result.status();
    }

    private void recordError(String message) {
        synchronized (lock) {
            if (recentErrors.size() >= ERROR_HISTORY) {
                recentErrors.pollFirst();
            }
            recentErrors.addLast(message);
        }
    }

    public long deliveredCount() {
        return delivered.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long failedCount() {
        return failed.get();
    }

    public long retryCount() {
        return retries.get();
    }

    public int queuedCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Snapshot of recent delivery failures, newest last. */
    public List<String> recentErrors() {
        synchronized (lock) {
            return List.copyOf(recentErrors);
        }
    }

    /** Human-readable outcome of the most recent delivery attempt. */
    public String lastDeliveryStatus() {
        return lastDeliveryStatus;
    }

    public long lastDeliveryAt() {
        return lastDeliveryAt;
    }

    public List<ChatProvider> providers() {
        return providers;
    }

    /** The primary provider, used for the single-provider status fields. */
    public ChatProvider provider() {
        return providers.get(0);
    }

    /** Drains the queue, then stops the worker. Safe to call more than once. */
    @Override
    public void close() {
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        worker.interrupt();
        try {
            worker.join(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Used by tests to wait for the queue to drain. */
    boolean awaitIdle(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (queuedCount() == 0) {
                return true;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queuedCount() == 0;
    }

    /** Convenience for tests that want to observe errors through a callback. */
    void onError(java.util.function.Consumer<String> consumer) {
        for (String error : recentErrors()) {
            consumer.accept(error);
        }
    }

    /** Providers that are configured and will therefore be attempted. */
    public List<ChatProvider> configuredProviders() {
        List<ChatProvider> configured = new ArrayList<>();
        for (ChatProvider provider : providers) {
            if (provider.isConfigured()) {
                configured.add(provider);
            }
        }
        return configured;
    }
}
