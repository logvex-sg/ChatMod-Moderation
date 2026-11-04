package com.adminmod.integrations;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.RetryPolicy;

/**
 * Bounded, asynchronous delivery queue.
 *
 * <p>Producers only ever enqueue; a single worker thread performs the blocking HTTP calls, so
 * the Minecraft server tick thread is never blocked by network I/O. When the queue is full the
 * oldest entry is dropped rather than growing without limit or blocking the producer.
 */
public final class ChatDispatcher implements AutoCloseable {

    /** Bounded ring of recent failures kept for the dashboard. */
    public static final int ERROR_HISTORY = 16;

    private final ChatProvider provider;
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
    private final Deque<String> recentErrors = new ArrayDeque<>();

    public ChatDispatcher(ChatProvider provider, int capacity, int maxAttempts, Duration timeout, RetryPolicy retryPolicy) {
        this.provider = provider;
        this.capacity = Math.max(16, capacity);
        this.queue = new ArrayDeque<>(this.capacity);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.timeout = timeout;
        this.retryPolicy = retryPolicy;
        this.worker = new Thread(this::run, "adminmod-chat-" + provider.id());
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** Queues an event. Never blocks and never throws. */
    public void enqueue(ChatEvent event) {
        if (!running) {
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
            deliverWithRetries(event);
        }
    }

    private void deliverWithRetries(ChatEvent event) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!running && attempt > 1) {
                return;
            }
            HttpResult result = provider.deliver(event, timeout);
            if (result.isSuccess()) {
                delivered.incrementAndGet();
                return;
            }
            if (!result.isRetryable() || attempt == maxAttempts) {
                failed.incrementAndGet();
                recordError("attempt " + attempt + "/" + maxAttempts + ": " + describe(result));
                return;
            }
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
            return com.adminmod.common.security.SecretRedactor.redact(result.failure());
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

    public int queuedCount() {
        synchronized (lock) {
            return queue.size();
        }
    }

    /** Snapshot of recent delivery failures, newest last. */
    public java.util.List<String> recentErrors() {
        synchronized (lock) {
            return java.util.List.copyOf(recentErrors);
        }
    }

    public ChatProvider provider() {
        return provider;
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
    void onError(Consumer<String> consumer) {
        for (String error : recentErrors()) {
            consumer.accept(error);
        }
    }
}
