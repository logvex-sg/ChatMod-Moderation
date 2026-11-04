package com.adminmod.integrations.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.adminmod.common.config.AdminConfig;

/**
 * Receives messages from every enabled platform, filters them and hands the rendered line to the
 * game.
 *
 * <p>This is the single funnel for the inbound direction. It applies, in order: the global
 * enable switch, a blank-body check, the duplicate/loop guard, formatting, and finally the sink.
 * Keeping the order fixed means a disabled bridge never formats and a duplicate never reaches
 * the game.
 *
 * <p>Callers are platform receiver threads, so the counters are atomic and the loop guard is
 * synchronised. The sink is responsible for getting onto the main thread.
 */
public final class InboundChatBridge {

    private final AdminConfig.Inbound config;
    private volatile ChatSink sink;
    private final LoopGuard loopGuard;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong displayed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final List<InboundReceiver> receivers = new ArrayList<>();

    public InboundChatBridge(AdminConfig.Inbound config, ChatSink sink) {
        this.config = config;
        this.sink = sink == null ? message -> {
        } : sink;
        this.loopGuard = new LoopGuard(config.dedupeWindowMillis);
    }

    /**
     * Replaces the sink.
     *
     * <p>The server sink needs a reference back to the bridge so it can mark what it displayed, so
     * the bridge is built first and the sink attached afterwards.
     */
    public void setSink(ChatSink sink) {
        this.sink = sink == null ? message -> {
        } : sink;
    }

    /** Registers a platform receiver so its state can be reported and it can be shut down. */
    public void register(InboundReceiver receiver) {
        synchronized (receivers) {
            receivers.add(receiver);
        }
    }

    public boolean isEnabled() {
        return config.enabled;
    }

    /** @return {@code true} when the message was handed to the sink. */
    public boolean deliver(InboundChatMessage message) {
        if (message == null) {
            return false;
        }
        received.incrementAndGet();
        if (!config.enabled) {
            dropped.incrementAndGet();
            return false;
        }
        String body = message.message();
        if (body == null || body.isBlank()) {
            dropped.incrementAndGet();
            return false;
        }
        long now = System.currentTimeMillis();
        if (!loopGuard.accept(message.platform(), message.user(), body, now)) {
            dropped.incrementAndGet();
            return false;
        }
        String rendered = InboundFormatter.format(config.format, message.platform(), message.user(), body,
                config.maxMessageLength);
        try {
            sink.accept(new InboundChatMessage(message.platform(), message.user(), rendered,
                    message.timestampMillis(), message.sourceId()));
            displayed.incrementAndGet();
            return true;
        } catch (RuntimeException e) {
            // A failing sink must not take down the receiver thread.
            dropped.incrementAndGet();
            return false;
        }
    }

    public long receivedCount() {
        return received.get();
    }

    public long displayedCount() {
        return displayed.get();
    }

    public long droppedCount() {
        return dropped.get();
    }

    public List<InboundReceiver> receivers() {
        synchronized (receivers) {
            return List.copyOf(receivers);
        }
    }

    /** Stops every registered receiver. Safe to call more than once. */
    public void close() {
        for (InboundReceiver receiver : receivers()) {
            try {
                receiver.close();
            } catch (RuntimeException ignored) {
                // Shutdown must continue even if one receiver misbehaves.
            }
        }
    }
}
