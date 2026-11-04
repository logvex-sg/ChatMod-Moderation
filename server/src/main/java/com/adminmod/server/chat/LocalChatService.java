package com.adminmod.server.chat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.common.localchat.Channel;
import com.adminmod.common.localchat.ChatFormat;
import com.adminmod.common.localchat.CooldownTracker;
import com.adminmod.common.localchat.DimensionRules;
import com.adminmod.common.localchat.LocalChatRouter;
import com.adminmod.common.localchat.Position;
import com.adminmod.common.localchat.Recipient;
import com.adminmod.common.model.Json;
import com.adminmod.integrations.ChatFilter;
import com.adminmod.server.moderation.ModerationService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * Server-authoritative proximity chat.
 *
 * <p>When enabled, an ordinary chat message is delivered only to players near the sender in the
 * same dimension; a message starting with the configured prefix is broadcast instead. The
 * recipient set is computed here, from positions the server already knows, and never from anything
 * a client sends, so a modified client cannot widen its own reach.
 *
 * <p>{@link #dispatch} runs on the server tick thread and does no I/O. The external bridge
 * delivery it triggers is queued by {@link ChatBridgeService} and performed on its worker thread.
 */
public final class LocalChatService {

    private final AdminConfig config;
    private final MinecraftServer server;
    private final ChatBridgeService bridge;
    private final ModerationService moderation;
    private final ChatFilter filter;
    private final CooldownTracker cooldown = new CooldownTracker();

    /** Sound ids are resolved once and kept, because registry lookups are not free. */
    private final Map<String, SoundEvent> soundCache = new HashMap<>();

    private long localCount;
    private long globalCount;
    private long blockedCount;
    private long throttledCount;
    private long mutedCount;

    public LocalChatService(AdminConfig config, MinecraftServer server, ChatBridgeService bridge,
            ModerationService moderation) {
        this.config = config;
        this.server = server;
        this.bridge = bridge;
        this.moderation = moderation;
        this.filter = new ChatFilter(config.chatBridge);
    }

    /**
     * Delivers a player's chat message under the proximity rules.
     *
     * @param sender     the player who typed the message
     * @param rawMessage the message exactly as the player typed it, before decoration
     * @return {@code true} when this service took over delivery; {@code false} when the caller
     *         should let vanilla handle the message
     */
    public boolean dispatch(ServerPlayerEntity sender, String rawMessage) {
        if (sender == null || rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        String dimensionId = dimensionId(sender);
        DimensionRules.Effective effective = DimensionRules.resolve(config.localChat, dimensionId);
        if (!effective.enabled()) {
            return false;
        }

        // A mute is enforced here as well as in the vanilla broadcast path, so a muted player
        // cannot reach nearby players even though proximity delivery bypasses the vanilla broadcast.
        if (moderation != null && moderation.isMuted(sender.getUuid())) {
            mutedCount++;
            sender.sendMessage(Text.literal("You are muted and cannot send messages.").formatted(Formatting.RED),
                    false);
            return true;
        }

        boolean global = DimensionRules.isGlobal(effective, rawMessage);
        String body = DimensionRules.stripPrefix(effective, rawMessage).strip();
        if (body.isEmpty()) {
            sender.sendMessage(Text.literal("Your message was empty after the channel prefix.")
                    .formatted(Formatting.GRAY), false);
            return true;
        }
        // A command must never be turned into a chat message by proximity delivery.
        if (ChatFilter.isCommand(body)) {
            return false;
        }

        if (config.localChat.filterMessages && filter.isBlocked(body)) {
            blockedCount++;
            sender.sendMessage(Text.literal("That message was blocked by the server filter.")
                    .formatted(Formatting.RED), false);
            return true;
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = config.localChat.cooldownMillis;
        if (!cooldown.tryAcquire(sender.getUuidAsString(), now, cooldownMillis)) {
            throttledCount++;
            long remaining = cooldown.retryAfterMillis(sender.getUuidAsString(), now, cooldownMillis);
            sender.sendMessage(Text.literal("You are sending messages too quickly; wait "
                    + String.format(Locale.ROOT, "%.1f", remaining / 1000.0) + "s.").formatted(Formatting.RED), false);
            return true;
        }

        Channel channel = global ? Channel.GLOBAL : Channel.LOCAL;
        List<Recipient> candidates = candidates();
        Recipient senderView = view(sender, dimensionId);
        List<Recipient> recipients = LocalChatRouter.recipients(channel, senderView, candidates,
                effective.radius(), effective.globalReachesAll(), config.localChat.staffBypass);

        Map<String, ServerPlayerEntity> online = onlinePlayers();
        Text rendered = render(effective, channel, sender.getGameProfile().getName(), body, dimensionId,
                recipients.size());
        int delivered = 0;
        for (Recipient recipient : recipients) {
            ServerPlayerEntity player = online.get(recipient.id());
            if (player == null) {
                continue;
            }
            player.sendMessage(rendered, false);
            playSound(player);
            delivered++;
        }

        if (channel == Channel.LOCAL) {
            localCount++;
            if (config.localChat.showRecipientCount) {
                if (delivered <= 1) {
                    if (config.localChat.noRecipientsMessage != null && !config.localChat.noRecipientsMessage.isBlank()) {
                        sender.sendMessage(Text.literal(ChatFormat.toLegacy(config.localChat.noRecipientsMessage)), false);
                    }
                } else {
                    sender.sendMessage(Text.literal("Heard by " + (delivered - 1) + " player(s).")
                            .formatted(Formatting.DARK_GRAY), false);
                }
            }
        } else {
            globalCount++;
        }

        if (config.localChat.forwardToBridge && bridge != null) {
            bridge.onPublicChat(sender.getGameProfile().getName(), sender.getUuidAsString(), body, channel);
        }
        return true;
    }

    private Text render(DimensionRules.Effective effective, Channel channel, String playerName, String body,
            String dimensionId, int recipientCount) {
        String template = channel == Channel.GLOBAL ? effective.globalFormat() : effective.localFormat();
        Map<String, String> values = new HashMap<>();
        values.put("player", playerName);
        values.put("message", body);
        values.put("radius", String.valueOf(Math.round(effective.radius())));
        values.put("count", String.valueOf(Math.max(0, recipientCount - 1)));
        values.put("channel", channel.label());
        values.put("world", dimensionId);

        MutableText out = Text.empty();
        for (ChatFormat.Part part : ChatFormat.parse(template)) {
            if (part.placeholder()) {
                String value = values.get(part.text());
                // A substituted value is added as literal text, so a player cannot inject a colour
                // code or a fake prefix through their name or message.
                out.append(Text.literal(value == null ? "" : value));
            } else {
                out.append(Text.literal(ChatFormat.toLegacy(part.text())));
            }
        }
        return out;
    }

    private void playSound(ServerPlayerEntity player) {
        if (!config.localChat.soundEnabled) {
            return;
        }
        SoundEvent event = resolveSound(config.localChat.soundId);
        if (event == null) {
            return;
        }
        player.playSoundToPlayer(event, SoundCategory.PLAYERS, config.localChat.soundVolume, config.localChat.soundPitch);
    }

    private SoundEvent resolveSound(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (soundCache.containsKey(id)) {
            return soundCache.get(id);
        }
        SoundEvent resolved = null;
        try {
            Identifier identifier = Identifier.tryParse(id.trim());
            if (identifier != null) {
                resolved = Registries.SOUND_EVENT.get(identifier);
            }
        } catch (RuntimeException ignored) {
            resolved = null;
        }
        soundCache.put(id, resolved);
        return resolved;
    }

    private List<Recipient> candidates() {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        List<Recipient> out = new ArrayList<>(players.size());
        for (ServerPlayerEntity player : players) {
            out.add(view(player, dimensionId(player)));
        }
        return out;
    }

    private Map<String, ServerPlayerEntity> onlinePlayers() {
        Map<String, ServerPlayerEntity> out = new HashMap<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            out.put(player.getUuidAsString(), player);
        }
        return out;
    }

    private Recipient view(ServerPlayerEntity player, String dimensionId) {
        return new Recipient(player.getUuidAsString(), player.getGameProfile().getName(), dimensionId,
                new Position(player.getX(), player.getY(), player.getZ()),
                player.hasPermissionLevel(config.localChat.staffLevel));
    }

    private static String dimensionId(ServerPlayerEntity player) {
        return player.getServerWorld().getRegistryKey().getValue().toString();
    }

    /** Resets cached state after a configuration reload. */
    public void reload() {
        cooldown.clear();
        soundCache.clear();
    }

    /** Dashboard view of the proximity settings and counters. */
    public String statusJson() {
        AdminConfig.LocalChat local = config.localChat;
        JsonObject root = new JsonObject();
        root.addProperty("enabled", local.enabled);
        root.addProperty("radius", local.radius);
        root.addProperty("globalPrefix", local.globalPrefix);
        root.addProperty("localFormat", local.localFormat);
        root.addProperty("globalFormat", local.globalFormat);
        root.addProperty("globalReachesAllDimensions", local.globalReachesAllDimensions);
        root.addProperty("staffBypass", local.staffBypass);
        root.addProperty("staffLevel", local.staffLevel);
        root.addProperty("soundEnabled", local.soundEnabled);
        root.addProperty("soundId", local.soundId);
        root.addProperty("cooldownMillis", local.cooldownMillis);
        root.addProperty("showRecipientCount", local.showRecipientCount);
        root.addProperty("filterMessages", local.filterMessages);
        root.addProperty("forwardToBridge", local.forwardToBridge);
        root.addProperty("localCount", localCount);
        root.addProperty("globalCount", globalCount);
        root.addProperty("blockedCount", blockedCount);
        root.addProperty("throttledCount", throttledCount);
        root.addProperty("mutedCount", mutedCount);

        JsonArray dimensions = new JsonArray();
        for (AdminConfig.DimensionRule rule : local.dimensions) {
            JsonObject entry = new JsonObject();
            entry.addProperty("dimension", rule.dimension);
            entry.addProperty("enabled", rule.enabled);
            entry.addProperty("radius", rule.radius);
            dimensions.add(entry);
        }
        root.add("dimensions", dimensions);
        return Json.write(root);
    }

    /** The proximity settings, exposed so the request handler can serve them. */
    public AdminConfig.LocalChat settings() {
        return config.localChat;
    }
}
