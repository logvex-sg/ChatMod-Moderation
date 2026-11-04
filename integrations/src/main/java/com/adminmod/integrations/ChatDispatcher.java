package com.adminmod.integrations;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.model.Json;
import com.adminmod.common.security.SecretRedactor;
import com.adminmod.integrations.http.HttpResult;
import com.adminmod.integrations.http.RetryPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Bounded, asynchronous delivery queue fanning out to one or more providers.
 *
 * <p>Producers only ever enqueue; a single worker thread performs the blocking HTTP calls, so the
 * Minecraft server tick thread is never blocked by network I/O. Each provider has its own queue, so
 * a webhook that is down or slow cannot stall delivery to the others, and each queue is bounded:
 * when it is full the oldest entry is dropped rather than growing without limit.
 *
 * <p>Two behaviours keep a busy server inside a webhook's rate limit. <b>Priorities</b> mean a
 * critical message (an alert, a moderation action) is drained before ordinary chat, so it does not
 * sit behind a backlog. <b>Batching</b> means a provider that supports it receives several chat
 * lines in one request, which turns a burst of a hundred messages into a dozen requests. A provider
 * that cannot batch simply receives them one at a time.
 *
 * <p>Duplicate suppression drops an identical (player, message) pair seen twice inside a short
 * window, which catches a double-fired event without punishing a player who legitimately repeats a
 * line after the window has passed.
 *
 * <p>A single worker is deliberate: it bounds the thread count regardless of how many destinations
 * are configured and keeps ordering stable per destination. The worker is a daemon and is stopped by
 * {@link #close()}.
 */
public final class ChatDispatcher implements AutoCloseable {

    /** Bounded ring of recent failures kept for the dashboard. */
    public static final int ERROR_HISTORY = 16;

    /** Per-provider counters and queues. */
    private static final class ProviderState {
        final ChatProvider provider;
        final Deque<ChatEvent> normal;
        final Deque<ChatEvent> critical;
        final AtomicLong delivered = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong retries = new AtomicLong();
        final Deque<String> recentErrors = new ArrayDeque<>();
        volatile String lastDeliveryStatus = "idle";
        volatile long lastDeliveryAt;

        ProviderState(ChatProvider provider, int capacity) {
            this.provider = provider;
            this.normal = new ArrayDeque<>(capacity);
            this.critical = new ArrayDeque<>(capacity);
        }

        int size() {
            return normal.size() + critical.size();
        }
    }

    private final List<ProviderState> states = new ArrayList<>();
    private final AdminConfig.ChatBridge config;
    private final Duration timeout;
    private final RetryPolicy retryPolicy;
    private final int capacity;
    private final int maxAttempts;

    private final Thread worker;
    private final Object lock = new Object();
    private volatile boolean running = true;

    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final Deque<String> recentErrors = new ArrayDeque<>();

    /** Bounded signature map for duplicate suppression, insertion ordered for eviction. */
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

    private volatile String lastDeliveryStatus = "idle";
    private volatile long lastDeliveryAt;

    /** Convenience constructor used by tests: default batching settings. */
    public ChatDispatcher(ChatProvider provider, int capacity, int maxAttempts, Duration timeout,
            RetryPolicy retryPolicy) {
        this(defaultConfig(), List.of(provider), capacity, maxAttempts, timeout, retryPolicy);
    }

    /** Convenience constructor used by tests: default batching settings. */
    public ChatDispatcher(List<ChatProvider> providers, int capacity, int maxAttempts, Duration timeout,
            RetryPolicy retryPolicy) {
        this(defaultConfig(), providers, capacity, maxAttempts, timeout, retryPolicy);
    }

    public ChatDispatcher(AdminConfig.ChatBridge config, List<ChatProvider> providers, int capacity,
            int maxAttempts, Duration timeout, RetryPolicy retryPolicy) {
        if (providers == null || providers.isEmpty()) {
            throw new IllegalArgumentException("at least one provider is required");
        }
        this.config = config == null ? defaultConfig() : config;
        this.capacity = Math.max(16, capacity);
        this.maxAttempts = Math.max(1, maxAttempts);
        this.timeout = timeout;
        this.retryPolicy = retryPolicy;
        for (ChatProvider provider : providers) {
            states.add(new ProviderState(provider, this.capacity));
        }
        String name = states.size() == 1 ? states.get(0).provider.id() : "fanout";
        this.worker = new Thread(this::run, "adminmod-chat-" + name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    private static AdminConfig.ChatBridge defaultConfig() {
        return new AdminConfig.ChatBridge();
    }

    /** Queues an event at the priority it carries. Never blocks and never throws. */
    public void enqueue(ChatEvent event) {
        if (event == null) {
            return;
        }
        enqueue(event, event.priority());
    }

    /** Queues an event at an explicit priority. Never blocks and never throws. */
    public void enqueue(ChatEvent event, Priority priority) {
        if (event == null || !running) {
            return;
        }
        Priority effective = priority == null ? Priority.NORMAL : priority;
        synchronized (lock) {
            if (isDuplicate(event)) {
                duplicates.incrementAndGet();
                return;
            }
            for (ProviderState state : states) {
                Deque<ChatEvent> queue = effective == Priority.CRITICAL ? state.critical : state.normal;
                if (queue.size() >= capacity) {
                    queue.pollFirst();
                    state.dropped.incrementAndGet();
                    dropped.incrementAndGet();
                }
                queue.addLast(event);
            }
            lock.notifyAll();
        }
    }

    /** Records a signature and reports whether it was already seen inside the de-duplication window. */
    private boolean isDuplicate(ChatEvent event) {
        long window = config.batching.dedupeWindowMillis;
        int memory = config.batching.dedupeMemory;
        if (window <= 0L || memory <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        String signature = signatureOf(event);
        seen.values().removeIf(at -> now - at > window);
        Long previous = seen.get(signature);
        seen.put(signature, now);
        while (seen.size() > memory) {
            seen.remove(seen.keySet().iterator().next());
        }
        return previous != null && now - previous <= window;
    }

    private static String signatureOf(ChatEvent event) {
        return (event.playerUuid() == null ? event.playerName() : event.playerUuid())
                + '\u0000' + event.message();
    }

    private void run() {
        while (running || hasWork()) {
            ProviderState state;
            ChatEvent next;
            synchronized (lock) {
                state = null;
                next = null;
                while (running && !hasWork()) {
                    try {
                        lock.wait(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                for (ProviderState candidate : states) {
                    ChatEvent event = pollNext(candidate);
                    if (event != null) {
                        state = candidate;
                        next = event;
                        break;
                    }
                }
            }
            if (state == null || next == null) {
                continue;
            }
            if (config.batching.enabled && state.provider.supportsBatch()) {
                deliverBatch(state, drainBatch(state, next));
            } else {
                deliverWithRetries(state, next);
            }
        }
    }

    private boolean hasWork() {
        for (ProviderState state : states) {
            if (!state.critical.isEmpty() || !state.normal.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Critical first, then normal. Called while holding {@link #lock}. */
    private ChatEvent pollNext(ProviderState state) {
        ChatEvent event = state.critical.pollFirst();
        return event != null ? event : state.normal.pollFirst();
    }

    /**
     * Collects a batch, waiting up to the configured window for it to fill.
     *
     * <p>The first event is always included; further events are taken while the batch is below the
     * cap and the window has not elapsed. The wait is bounded by the window, so a quiet server sends
     * a single message after at most one window rather than holding it indefinitely.
     */
    private List<ChatEvent> drainBatch(ProviderState state, ChatEvent first) {
        int cap = Math.max(1, Math.min(config.batching.batchSize, batchCeiling(state.provider)));
        List<ChatEvent> batch = new ArrayList<>(cap);
        batch.add(first);
        long deadline = System.currentTimeMillis() + Math.max(0L, config.batching.batchWindowMillis);
        synchronized (lock) {
            while (batch.size() < cap) {
                ChatEvent next = pollNext(state);
                if (next != null) {
                    batch.add(next);
                    continue;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L || !running) {
                    break;
                }
                try {
                    lock.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return batch;
    }

    /** The provider's own batch ceiling. */
    private static int batchCeiling(ChatProvider provider) {
        return provider instanceof com.adminmod.integrations.discord.DiscordProvider ? 10 : 8;
    }

    private void deliverBatch(ProviderState state, List<ChatEvent> batch) {
        if (batch.size() == 1) {
            deliverWithRetries(state, batch.get(0));
            return;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!running && attempt > 1) {
                return;
            }
            HttpResult result;
            try {
                result = state.provider.deliverBatch(batch, timeout);
            } catch (RuntimeException e) {
                result = HttpResult.failure(SecretRedactor.redact(
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            if (result == null) {
                result = HttpResult.failure("provider returned no result");
            }
            if (result.isSuccess()) {
                state.delivered.addAndGet(batch.size());
                delivered.addAndGet(batch.size());
                state.lastDeliveryStatus = "delivered " + batch.size() + " via " + state.provider.id();
                state.lastDeliveryAt = System.currentTimeMillis();
                lastDeliveryStatus = state.lastDeliveryStatus;
                lastDeliveryAt = state.lastDeliveryAt;
                return;
            }
            if (!result.isRetryable() || attempt == maxAttempts) {
                state.failed.addAndGet(batch.size());
                failed.addAndGet(batch.size());
                recordError(state, "batch of " + batch.size() + " attempt " + attempt + "/" + maxAttempts
                        + ": " + describe(result));
                return;
            }
            retries.incrementAndGet();
            state.retries.incrementAndGet();
            long delay = retryPolicy.delayMillis(attempt, result.retryAfterMillis());
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void deliverWithRetries(ProviderState state, ChatEvent event) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!running && attempt > 1) {
                return;
            }
            HttpResult result;
            try {
                result = state.provider.deliver(event, timeout);
            } catch (RuntimeException e) {
                // A provider is required not to throw, but a broken one must not kill the worker.
                result = HttpResult.failure(SecretRedactor.redact(
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            if (result == null) {
                result = HttpResult.failure("provider returned no result");
            }
            if (result.isSuccess()) {
                state.delivered.incrementAndGet();
                delivered.incrementAndGet();
                state.lastDeliveryStatus = "delivered via " + state.provider.id();
                state.lastDeliveryAt = System.currentTimeMillis();
                lastDeliveryStatus = state.lastDeliveryStatus;
                lastDeliveryAt = state.lastDeliveryAt;
                return;
            }
            if (!result.isRetryable() || attempt == maxAttempts) {
                state.failed.incrementAndGet();
                failed.incrementAndGet();
                recordError(state, "attempt " + attempt + "/" + maxAttempts + ": " + describe(result));
                return;
            }
            retries.incrementAndGet();
            state.retries.incrementAndGet();
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

    private void recordError(ProviderState state, String message) {
        state.lastDeliveryStatus = "failed via " + state.provider.id() + ": " + message;
        state.lastDeliveryAt = System.currentTimeMillis();
        lastDeliveryStatus = state.lastDeliveryStatus;
        lastDeliveryAt = state.lastDeliveryAt;
        synchronized (lock) {
            if (state.recentErrors.size() >= ERROR_HISTORY) {
                state.recentErrors.pollFirst();
            }
            state.recentErrors.addLast(message);
            if (recentErrors.size() >= ERROR_HISTORY) {
                recentErrors.pollFirst();
            }
            recentErrors.addLast(state.provider.id() + ": " + message);
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

    public long duplicateCount() {
        return duplicates.get();
    }

    public int queuedCount() {
        synchronized (lock) {
            int total = 0;
            for (ProviderState state : states) {
                total += state.size();
            }
            return total;
        }
    }

    /** Queue depth for one provider, by id. */
    public int queuedCount(String providerId) {
        synchronized (lock) {
            for (ProviderState state : states) {
                if (state.provider.id().equals(providerId)) {
                    return state.size();
                }
            }
            return 0;
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
        List<ChatProvider> providers = new ArrayList<>(states.size());
        for (ProviderState state : states) {
            providers.add(state.provider);
        }
        return providers;
    }

    /** The primary provider, used for the single-provider status fields. */
    public ChatProvider provider() {
        return states.get(0).provider;
    }

    /** Drains the queues, then stops the worker. Safe to call more than once. */
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

    /** Used by tests to wait for the queues to drain. */
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
        for (ProviderState state : states) {
            if (state.provider.isConfigured()) {
                configured.add(state.provider);
            }
        }
        return configured;
    }

    /** JSON view of per-provider queues and counters for the diagnostics dashboard. */
    public String statsJson() {
        JsonObject root = new JsonObject();
        root.addProperty("queued", queuedCount());
        root.addProperty("delivered", deliveredCount());
        root.addProperty("failed", failedCount());
        root.addProperty("dropped", droppedCount());
        root.addProperty("retries", retryCount());
        root.addProperty("duplicates", duplicateCount());
        root.addProperty("batching", config.batching.enabled);
        root.addProperty("batchSize", config.batching.batchSize);
        JsonArray providers = new JsonArray();
        synchronized (lock) {
            for (ProviderState state : states) {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", state.provider.id());
                entry.addProperty("configured", state.provider.isConfigured());
                entry.addProperty("destination", state.provider.describe());
                entry.addProperty("queued", state.size());
                entry.addProperty("delivered", state.delivered.get());
                entry.addProperty("failed", state.failed.get());
                entry.addProperty("dropped", state.dropped.get());
                entry.addProperty("retries", state.retries.get());
                entry.addProperty("lastStatus", state.lastDeliveryStatus);
                entry.addProperty("lastError", state.provider.lastError());
                providers.add(entry);
            }
        }
        root.add("providers", providers);
        return Json.write(root);
    }
}
