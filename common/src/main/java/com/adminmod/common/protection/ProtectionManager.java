package com.adminmod.common.protection;

import java.util.List;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.diagnostics.ErrorReporter;
import com.adminmod.common.model.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The single decision point for every anti-crash and abuse check.
 *
 * <p>It holds the limiters and the violation log, reads its thresholds from the live configuration
 * (so a profile change or a hot reload takes effect immediately) and exposes one small method per
 * check. Each method returns a boolean or a {@link PayloadGuard.Verdict}; the caller — a mixin or
 * an event handler on the server thread — decides what to do with a negative answer.
 *
 * <p>Nothing here touches Minecraft, which is what makes the whole protection layer unit-testable.
 * The class is thread-safe because a few checks run off the main thread.
 *
 * <p>A per-player grace period after joining means login traffic, resource-pack exchange and the
 * initial chunk burst are never counted against a player, which is the main source of false
 * positives for a packet-rate check.
 */
public final class ProtectionManager {

    private static final long TICK_MILLIS = 50L;

    private final AdminConfig config;
    private final ErrorReporter errors;
    private final ViolationLog violations;

    private KeyedRateLimiter chatLimiter;
    private KeyedRateLimiter commandLimiter;
    private KeyedRateLimiter inventoryLimiter;
    private KeyedRateLimiter interactionLimiter;
    private PacketFrequencyMonitor packetMonitor;
    private RepeatClickTracker repeatClicks;
    private ConnectionLimiter connections;

    /** Join time per player, used for the grace period. Bounded by the connection limiter's sweep. */
    private final java.util.Map<String, Long> joinedAt = new java.util.concurrent.ConcurrentHashMap<>();

    public ProtectionManager(AdminConfig config, ErrorReporter errors, ViolationLog violations) {
        this.config = config;
        this.errors = errors;
        this.violations = violations;
        buildLimiters();
    }

    /** Rebuilds the limiters from the current configuration; called after a profile change. */
    public synchronized void reload() {
        buildLimiters();
        joinedAt.clear();
    }

    private void buildLimiters() {
        AdminConfig.Protection protection = config.protection;
        AdminConfig.Anticheat anticheat = config.anticheat;
        this.chatLimiter = new KeyedRateLimiter(protection.chatPerMinute, 60_000L, 4_096);
        this.commandLimiter = new KeyedRateLimiter(protection.commandsPerMinute, 60_000L, 4_096);
        this.inventoryLimiter = new KeyedRateLimiter(protection.inventoryClicksPerMinute, 60_000L, 4_096);
        this.interactionLimiter = new KeyedRateLimiter(protection.entityInteractionsPerMinute, 60_000L, 4_096);
        this.packetMonitor = new PacketFrequencyMonitor(anticheat.maxTrackedAddresses);
        this.repeatClicks = new RepeatClickTracker(4_096);
        this.connections = new ConnectionLimiter(anticheat.connectionRatePerMinute,
                anticheat.maxConnectionsPerAddress, anticheat.maxTrackedAddresses);
    }

    /** Records that a player joined, starting their grace period. */
    public void onJoin(String playerKey, long now) {
        joinedAt.put(playerKey, now);
    }

    /** Drops every per-player structure for a player who left. */
    public void onLeave(String playerKey, String address) {
        joinedAt.remove(playerKey);
        chatLimiter.forget(playerKey);
        commandLimiter.forget(playerKey);
        inventoryLimiter.forget(playerKey);
        interactionLimiter.forget(playerKey);
        packetMonitor.forget(playerKey);
        repeatClicks.forget(playerKey);
        if (address != null) {
            connections.release(address, System.currentTimeMillis());
        }
    }

    /** Clears all transient state, used on shutdown and reload. */
    public void clear() {
        joinedAt.clear();
        chatLimiter.clear();
        commandLimiter.clear();
        inventoryLimiter.clear();
        interactionLimiter.clear();
        packetMonitor.clear();
        repeatClicks.clear();
        connections.clear();
    }

    private boolean inGrace(String playerKey, long now) {
        Long joined = joinedAt.get(playerKey);
        if (joined == null) {
            return false;
        }
        return now - joined < config.protection.joinGraceTicks * TICK_MILLIS;
    }

    /**
     * Validates a chat message before it is processed.
     *
     * @return {@code false} when the message must be dropped (too long, malformed or spammy)
     */
    public boolean allowChat(String playerKey, String message, long now) {
        if (!config.protection.enabled) {
            return true;
        }
        if (PayloadGuard.checkChat(message == null ? 0 : message.length(), config.protection.maxChatLength)
                != PayloadGuard.Verdict.OK) {
            flag(ViolationLog.Kind.OVERSIZED_CHAT, playerKey, "chat length "
                    + (message == null ? 0 : message.length()) + " > " + config.protection.maxChatLength);
            return false;
        }
        if (PayloadGuard.checkTextContent(message) != PayloadGuard.Verdict.OK) {
            flag(ViolationLog.Kind.SUSPICIOUS_NBT, playerKey, "chat contained control characters");
            return false;
        }
        if (inGrace(playerKey, now)) {
            return true;
        }
        if (!chatLimiter.tryAcquire(playerKey, now)) {
            flag(ViolationLog.Kind.CHAT_SPAM, playerKey, "over " + config.protection.chatPerMinute + " messages/minute");
            return false;
        }
        return true;
    }

    /** Validates a command before it runs. */
    public boolean allowCommand(String playerKey, String command, long now) {
        if (!config.protection.enabled || inGrace(playerKey, now)) {
            return true;
        }
        if (command != null && PayloadGuard.checkChat(command.length(), config.protection.maxChatLength * 4)
                != PayloadGuard.Verdict.OK) {
            flag(ViolationLog.Kind.OVERSIZED_CHAT, playerKey, "command length " + command.length());
            return false;
        }
        if (!commandLimiter.tryAcquire(playerKey, now)) {
            flag(ViolationLog.Kind.COMMAND_SPAM, playerKey, "over " + config.protection.commandsPerMinute + " commands/minute");
            return false;
        }
        return true;
    }

    /**
     * Counts one inbound packet and reports a sustained flood.
     *
     * @return {@code true} when the packet should be rejected as part of a flood
     */
    public boolean allowPacket(String playerKey, long now) {
        if (!config.protection.enabled || inGrace(playerKey, now)) {
            return true;
        }
        boolean flood = packetMonitor.observe(playerKey, now, config.protection.packetsPerSecond,
                config.protection.packetsPerTenSeconds);
        if (flood) {
            flag(ViolationLog.Kind.PACKET_FLOOD, playerKey,
                    "packet rate over " + config.protection.packetsPerSecond + "/s and "
                            + config.protection.packetsPerTenSeconds + "/10s");
            return false;
        }
        return true;
    }

    /** Validates book content. */
    public PayloadGuard.Verdict checkBook(String playerKey, List<String> pages, String title) {
        PayloadGuard.Verdict verdict = PayloadGuard.checkBook(pages, title, config.protection.maxBookPages,
                config.protection.maxBookPageLength, config.protection.maxBookTitleLength);
        if (verdict != PayloadGuard.Verdict.OK) {
            flag(ViolationLog.Kind.OVERSIZED_BOOK, playerKey, "book verdict " + verdict);
        }
        return verdict;
    }

    /** Validates sign content. */
    public PayloadGuard.Verdict checkSign(String playerKey, String[] lines) {
        PayloadGuard.Verdict verdict = PayloadGuard.checkSign(lines, config.protection.maxSignLineLength);
        if (verdict != PayloadGuard.Verdict.OK) {
            flag(ViolationLog.Kind.OVERSIZED_SIGN, playerKey, "sign verdict " + verdict);
        }
        return verdict;
    }

    /** Validates a custom payload declared length. */
    public PayloadGuard.Verdict checkPayload(String playerKey, int declaredLength) {
        PayloadGuard.Verdict verdict = PayloadGuard.checkPayload(declaredLength, config.protection.maxPayloadBytes);
        if (verdict != PayloadGuard.Verdict.OK) {
            flag(ViolationLog.Kind.MALFORMED_PACKET, playerKey, "payload length " + declaredLength);
        }
        return verdict;
    }

    /** Whether the player may open another container interaction this minute. */
    public boolean allowInventoryClick(String playerKey, long now) {
        if (!config.antidupe.enabled || !config.antidupe.monitorClickRate || inGrace(playerKey, now)) {
            return true;
        }
        if (!inventoryLimiter.tryAcquire(playerKey, now)) {
            flag(ViolationLog.Kind.INVENTORY_ABUSE, playerKey,
                    "over " + config.protection.inventoryClicksPerMinute + " clicks/minute");
            return false;
        }
        return true;
    }

    /** Validates the shape of a click against the open container. */
    public boolean validateClickShape(String playerKey, int slot, int containerSize, int modifiedStacks) {
        if (!config.antidupe.enabled) {
            return true;
        }
        if (config.antidupe.validateSlots && !PayloadGuard.validSlot(slot, containerSize)) {
            flag(ViolationLog.Kind.INVALID_INVENTORY, playerKey, "slot " + slot + " outside 0.." + containerSize);
            return false;
        }
        if (config.antidupe.validateModifiedStacks
                && !PayloadGuard.validModifiedStacks(modifiedStacks, config.protection.maxModifiedStacks)) {
            flag(ViolationLog.Kind.INVALID_INVENTORY, playerKey, "modified stacks " + modifiedStacks);
            return false;
        }
        return true;
    }

    /** Records a click signature and reports a repeat burst. */
    public boolean observeClickRepeat(String playerKey, long signature, long now) {
        if (!config.antidupe.enabled || !config.antidupe.monitorRepeatClicks) {
            return true;
        }
        boolean repeat = repeatClicks.observe(playerKey, signature, now, config.antidupe.repeatClickThreshold,
                config.antidupe.repeatClickWindowMillis);
        if (repeat) {
            flag(ViolationLog.Kind.INVENTORY_ABUSE, playerKey, "repeated identical click");
            return false;
        }
        return true;
    }

    /** Whether the player may interact with an entity this minute. */
    public boolean allowEntityInteraction(String playerKey, long now) {
        if (!config.protection.enabled || inGrace(playerKey, now)) {
            return true;
        }
        if (!interactionLimiter.tryAcquire(playerKey, now)) {
            flag(ViolationLog.Kind.ENTITY_INTERACTION, playerKey,
                    "over " + config.protection.entityInteractionsPerMinute + " interactions/minute");
            return false;
        }
        return true;
    }

    /** Decides whether a new connection from {@code address} may proceed. */
    public boolean allowConnection(String address, long now) {
        if (!config.anticheat.enabled) {
            return true;
        }
        boolean allowed = connections.accept(address, now);
        if (!allowed) {
            flag(ViolationLog.Kind.CONNECTION_RATE, address, "connection refused from address");
        }
        return allowed;
    }

    /** Records that a connection closed. */
    public void onConnectionClosed(String address, long now) {
        connections.release(address, now);
    }

    private void flag(ViolationLog.Kind kind, String subject, String detail) {
        if (config.protection.logViolations) {
            violations.record(kind, subject, detail);
        }
    }

    /** JSON view for the protection dashboard. */
    public String statusJson() {
        JsonObject root = new JsonObject();
        root.addProperty("enabled", config.protection.enabled);
        root.addProperty("profile", config.profile);
        root.addProperty("violations", violations.total());
        root.addProperty("trackedAddresses", connections.trackedAddresses());
        root.addProperty("restrictedAddresses", connections.restrictedCount());
        root.addProperty("trackedPackets", packetMonitor.trackedPlayers());

        JsonObject thresholds = new JsonObject();
        thresholds.addProperty("maxChatLength", config.protection.maxChatLength);
        thresholds.addProperty("maxPayloadBytes", config.protection.maxPayloadBytes);
        thresholds.addProperty("chatPerMinute", config.protection.chatPerMinute);
        thresholds.addProperty("commandsPerMinute", config.protection.commandsPerMinute);
        thresholds.addProperty("packetsPerSecond", config.protection.packetsPerSecond);
        thresholds.addProperty("packetsPerTenSeconds", config.protection.packetsPerTenSeconds);
        thresholds.addProperty("inventoryClicksPerMinute", config.protection.inventoryClicksPerMinute);
        thresholds.addProperty("entityInteractionsPerMinute", config.protection.entityInteractionsPerMinute);
        thresholds.addProperty("violationsBeforeKick", config.protection.violationsBeforeKick);
        root.add("thresholds", thresholds);

        JsonArray recent = new JsonArray();
        for (ViolationLog.Violation violation : violations.recent()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("at", violation.at());
            entry.addProperty("kind", violation.kind().name().toLowerCase(java.util.Locale.ROOT));
            entry.addProperty("subject", violation.subject());
            entry.addProperty("detail", violation.detail());
            recent.add(entry);
        }
        root.add("recent", recent);
        return Json.write(root);
    }

    public ViolationLog violations() {
        return violations;
    }

    public ConnectionLimiter connections() {
        return connections;
    }
}
