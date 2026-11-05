package com.adminmod.common.settings;

import static com.adminmod.common.settings.SettingCategory.ANTI_CHEAT;
import static com.adminmod.common.settings.SettingCategory.ANTI_DUPE;
import static com.adminmod.common.settings.SettingCategory.AUTOMATION;
import static com.adminmod.common.settings.SettingCategory.BACKUPS;
import static com.adminmod.common.settings.SettingCategory.CHAT_BRIDGE;
import static com.adminmod.common.settings.SettingCategory.GENERAL;
import static com.adminmod.common.settings.SettingCategory.INTEGRATIONS;
import static com.adminmod.common.settings.SettingCategory.LOCAL_CHAT;
import static com.adminmod.common.settings.SettingCategory.MODERATION;
import static com.adminmod.common.settings.SettingCategory.NOTIFICATIONS;
import static com.adminmod.common.settings.SettingCategory.PERFORMANCE;
import static com.adminmod.common.settings.SettingCategory.REMOTE_ADMIN;
import static com.adminmod.common.settings.SettingCategory.SECURITY;
import static com.adminmod.common.settings.SettingCategory.STORAGE;
import static com.adminmod.common.settings.SettingType.BOOLEAN;
import static com.adminmod.common.settings.SettingType.COLOR;
import static com.adminmod.common.settings.SettingType.DECIMAL;
import static com.adminmod.common.settings.SettingType.ENUM;
import static com.adminmod.common.settings.SettingType.INTEGER;
import static com.adminmod.common.settings.SettingType.LONG;
import static com.adminmod.common.settings.SettingType.STRING;
import static com.adminmod.common.settings.SettingType.STRING_LIST;

import java.util.ArrayList;
import java.util.List;

/**
 * The metadata table for every configurable ADMIN setting.
 *
 * <p>This is the one place a subsystem's settings are described: which category they appear under,
 * what control edits them, the range that is accepted, whether they are advanced, dangerous, secret
 * or restart-required, and a description that explains the effect of changing the value.
 *
 * <p>A field that is missing here still works — {@link SettingRegistry#createDefault()} adds a
 * conservative placeholder and reports the gap — so this table can be filled in incrementally
 * without ever making a setting unreachable.
 */
final class SettingsCatalog {

    private SettingsCatalog() {
    }

    static List<SettingSpec> specs() {
        List<SettingSpec> specs = new ArrayList<>();
        general(specs);
        moderation(specs);
        anticheat(specs);
        antidupe(specs);
        localChat(specs);
        chatBridge(specs);
        integrations(specs);
        remoteAdmin(specs);
        security(specs);
        performance(specs);
        storage(specs);
        backups(specs);
        automation(specs);
        notifications(specs);
        return specs;
    }

    private static void general(List<SettingSpec> s) {
        s.add(spec("general.enabled", GENERAL, BOOLEAN)
                .label("Mod enabled")
                .description("Master switch for the chat bridge and the remote administration endpoint together.")
                .restartRequired()
                .build());
        s.add(spec("general.serverName", GENERAL, STRING)
                .label("Server name")
                .description("Name shown in bridge payloads and on the diagnostics dashboard. Empty uses the level name.")
                .maxLength(64)
                .build());
        s.add(spec("general.historyMinutes", GENERAL, INTEGER)
                .label("Event history (minutes)")
                .description("How long the in-memory event history is retained. Larger values use more memory.")
                .range(5, 10_080).unit("min").step(5)
                .recommend("small", "120").recommend("large", "720")
                .build());
        s.add(spec("logging.level", GENERAL, ENUM)
                .label("Log level")
                .description("Diagnostics verbosity. Debug is for troubleshooting and is noisy on a busy server.")
                .options("off", "error", "warn", "info", "debug")
                .build());
        s.add(spec("maintenance.enabled", GENERAL, BOOLEAN)
                .label("Maintenance mode")
                .description("Show a maintenance notice and optionally keep non-operators out.")
                .build());
        s.add(spec("maintenance.message", GENERAL, STRING)
                .label("Maintenance message")
                .description("Shown to players while maintenance mode is on.")
                .maxLength(256)
                .build());
        s.add(spec("maintenance.kickNonOperators", GENERAL, BOOLEAN)
                .label("Kick non-operators on join")
                .description("When on, players who are not operators are disconnected during maintenance.")
                .dangerous()
                .build());
        s.add(spec("maintenance.allowList", GENERAL, STRING_LIST)
                .label("Maintenance allow-list")
                .description("One UUID per line that may join during maintenance even when kicking is on.")
                .advanced()
                .build());
        s.add(spec("announcements.enabled", GENERAL, BOOLEAN)
                .label("Scheduled announcements")
                .description("Rotate the announcement lines in chat on an interval. They are also forwarded to the bridge.")
                .build());
        s.add(spec("announcements.intervalSeconds", GENERAL, LONG)
                .label("Announcement interval")
                .description("Seconds between two rotating announcements.")
                .range(10, 86_400).unit("s").step(10)
                .build());
        s.add(spec("announcements.messages", GENERAL, STRING_LIST)
                .label("Announcement lines")
                .description("One message per line, shown in order.")
                .build());
    }

    private static void moderation(List<SettingSpec> s) {
        s.add(spec("moderation.enabled", MODERATION, BOOLEAN)
                .label("Moderation enabled")
                .description("Master switch for warnings, mutes, bans and kicks.")
                .build());
        s.add(spec("moderation.requireReasons", MODERATION, BOOLEAN)
                .label("Require reasons")
                .description("Refuse a punishment that has no reason. Recommended for accountability.")
                .recommend("large", "true")
                .build());
        s.add(spec("moderation.broadcastPunishments", MODERATION, BOOLEAN)
                .label("Broadcast punishments")
                .description("Announce each punishment in public chat. Turn off to keep moderation quiet.")
                .build());
        s.add(spec("moderation.notifyStaff", MODERATION, BOOLEAN)
                .label("Notify staff")
                .description("Send a private notice to online staff when an action is taken.")
                .build());
        s.add(spec("moderation.announceJoinLeave", MODERATION, BOOLEAN)
                .label("Announce joins and leaves")
                .description("Show join and leave messages in chat, which the bridge can then forward.")
                .build());
        s.add(spec("moderation.defaultKickReason", MODERATION, STRING)
                .label("Default kick reason").maxLength(160).build());
        s.add(spec("moderation.defaultBanReason", MODERATION, STRING)
                .label("Default ban reason").maxLength(160).build());
        s.add(spec("moderation.defaultMuteReason", MODERATION, STRING)
                .label("Default mute reason").maxLength(160).build());
        s.add(spec("moderation.defaultTempBanSeconds", MODERATION, LONG)
                .label("Default temporary ban")
                .description("Length of a temporary ban when no duration is given.")
                .range(0, 31_536_000).unit("s").step(3600)
                .build());
        s.add(spec("moderation.defaultTempMuteSeconds", MODERATION, LONG)
                .label("Default temporary mute")
                .description("Length of a temporary mute when no duration is given.")
                .range(0, 31_536_000).unit("s").step(600)
                .build());
        s.add(spec("moderation.maxPunishmentSeconds", MODERATION, LONG)
                .label("Maximum punishment")
                .description("Longest duration a moderator may set. Zero-length means permanent where the type allows it.")
                .range(60, 315_360_000).unit("s").step(3600)
                .build());
        s.add(spec("moderation.escalation.enabled", MODERATION, BOOLEAN)
                .label("Warning escalation")
                .description("Automatically mute a player who accumulates too many warnings in a window.")
                .build());
        s.add(spec("moderation.escalation.warnThreshold", MODERATION, INTEGER)
                .label("Warnings before escalation")
                .description("Active warnings inside the window that trigger the automatic mute.")
                .range(1, 1_000).step(1)
                .build());
        s.add(spec("moderation.escalation.windowSeconds", MODERATION, LONG)
                .label("Escalation window")
                .description("Warnings older than this do not count. Zero means no time limit.")
                .range(0, 31_536_000).unit("s").step(3600)
                .build());
        s.add(spec("moderation.escalation.muteSeconds", MODERATION, LONG)
                .label("Escalation mute length")
                .description("Duration of the automatic mute. Zero means permanent.")
                .range(0, 31_536_000).unit("s").step(600)
                .build());
        s.add(spec("moderation.escalation.reason", MODERATION, STRING)
                .label("Escalation reason").maxLength(160).build());
    }

    private static void anticheat(List<SettingSpec> s) {
        s.add(spec("protection.enabled", ANTI_CHEAT, BOOLEAN)
                .label("Anti-crash protection")
                .description("Reject oversized payloads, malformed text and out-of-range values before they reach the game.")
                .build());
        s.add(spec("protection.maxChatLength", ANTI_CHEAT, INTEGER)
                .label("Max chat length").range(32, 32_000).step(32).build());
        s.add(spec("protection.maxPayloadBytes", ANTI_CHEAT, INTEGER)
                .label("Max payload size").range(256, 1_048_576).unit("bytes").step(256)
                .advanced().build());
        s.add(spec("protection.maxBookPages", ANTI_CHEAT, INTEGER).range(1, 1_024).advanced().build());
        s.add(spec("protection.maxBookPageLength", ANTI_CHEAT, INTEGER).range(16, 32_000).advanced().build());
        s.add(spec("protection.maxBookTitleLength", ANTI_CHEAT, INTEGER).range(1, 1_024).advanced().build());
        s.add(spec("protection.maxSignLineLength", ANTI_CHEAT, INTEGER).range(16, 32_000).advanced().build());
        s.add(spec("protection.maxModifiedStacks", ANTI_CHEAT, INTEGER)
                .label("Max modified stacks").range(1, 4_096).step(1).advanced().build());
        s.add(spec("protection.joinGraceTicks", ANTI_CHEAT, INTEGER)
                .label("Join grace period")
                .description("Ticks after a join during which the rate checks are suspended, so login traffic is not counted.")
                .range(0, 1_200).unit("ticks").step(20).build());
        s.add(spec("protection.chatPerMinute", ANTI_CHEAT, INTEGER)
                .label("Chat rate limit")
                .description("Chat messages allowed per minute per player. A real player never reaches this.")
                .range(1, 100_000).step(5)
                .recommend("maximum", "20").recommend("performance", "60")
                .build());
        s.add(spec("protection.commandsPerMinute", ANTI_CHEAT, INTEGER)
                .label("Command rate limit").range(1, 100_000).step(5)
                .recommend("maximum", "30").recommend("performance", "90").build());
        s.add(spec("protection.packetsPerSecond", ANTI_CHEAT, INTEGER)
                .label("Packets per second").range(1, 1_000_000).step(50)
                .recommend("maximum", "250").recommend("performance", "600").build());
        s.add(spec("protection.packetsPerTenSeconds", ANTI_CHEAT, INTEGER)
                .label("Packets per 10 seconds").range(1, 10_000_000).step(100).advanced().build());
        s.add(spec("protection.inventoryClicksPerMinute", ANTI_CHEAT, INTEGER)
                .label("Inventory clicks per minute").range(1, 100_000).step(20).build());
        s.add(spec("protection.entityInteractionsPerMinute", ANTI_CHEAT, INTEGER)
                .label("Entity interactions per minute").range(1, 100_000).step(50).build());
        s.add(spec("protection.violationsBeforeKick", ANTI_CHEAT, INTEGER)
                .label("Violations before kick")
                .description("Consecutive violations before the player is kicked. Zero never kicks automatically.")
                .range(0, 1_000).step(1)
                .recommend("maximum", "5").recommend("balanced", "0")
                .build());
        s.add(spec("protection.logViolations", ANTI_CHEAT, BOOLEAN)
                .label("Log violations")
                .description("Record each detected violation in the bounded violation log shown on the Protection tab.")
                .build());
        s.add(spec("anticheat.enabled", ANTI_CHEAT, BOOLEAN)
                .label("Connection protection")
                .description("Limit connection attempts and concurrent connections per address.")
                .build());
        s.add(spec("anticheat.connectionRatePerMinute", ANTI_CHEAT, INTEGER)
                .label("Connections per minute").range(1, 100_000).step(5).build());
        s.add(spec("anticheat.maxConnectionsPerAddress", ANTI_CHEAT, INTEGER)
                .label("Concurrent connections per address").range(1, 1_000).step(1).build());
        s.add(spec("anticheat.maxTrackedAddresses", ANTI_CHEAT, INTEGER)
                .label("Tracked addresses").range(64, 1_000_000).step(64).advanced().build());
        s.add(spec("anticheat.restrictOnFlood", ANTI_CHEAT, BOOLEAN)
                .label("Restrict on flood")
                .description("Temporarily restrict an address that floods connections, rather than only refusing one attempt.")
                .build());
    }

    private static void antidupe(List<SettingSpec> s) {
        s.add(spec("antidupe.enabled", ANTI_DUPE, BOOLEAN)
                .label("Anti-dupe enabled")
                .description("Validate inventory transactions and flag dupe attempts.")
                .build());
        s.add(spec("antidupe.validateSlots", ANTI_DUPE, BOOLEAN)
                .label("Validate slot indices")
                .description("Reject a click against a slot that does not exist in the open container.")
                .build());
        s.add(spec("antidupe.validateModifiedStacks", ANTI_DUPE, BOOLEAN)
                .label("Validate modified stacks")
                .description("Reject a click that claims to modify more stacks than the window can hold.")
                .build());
        s.add(spec("antidupe.monitorClickRate", ANTI_DUPE, BOOLEAN)
                .label("Monitor click rate")
                .description("Track a rolling per-player click rate and flag a sustained burst.")
                .build());
        s.add(spec("antidupe.monitorRepeatClicks", ANTI_DUPE, BOOLEAN)
                .label("Monitor repeat clicks")
                .description("Flag repeated identical clicks, a common signature of a dupe attempt.")
                .build());
        s.add(spec("antidupe.repeatClickThreshold", ANTI_DUPE, INTEGER)
                .label("Repeat click threshold")
                .description("Identical clicks inside the window before the player is flagged.")
                .range(2, 1_000).step(1)
                .recommend("maximum", "8").recommend("balanced", "12")
                .build());
        s.add(spec("antidupe.repeatClickWindowMillis", ANTI_DUPE, LONG)
                .label("Repeat click window").range(50, 60_000).unit("ms").step(50).build());
    }

    private static void localChat(List<SettingSpec> s) {
        s.add(spec("localChat.enabled", LOCAL_CHAT, BOOLEAN)
                .label("Proximity chat")
                .description("Deliver ordinary chat only to nearby players. When off, chat behaves exactly as vanilla.")
                .build());
        s.add(spec("localChat.radius", LOCAL_CHAT, DECIMAL)
                .label("Local radius")
                .description("Distance in blocks a local message reaches. Larger values make chat feel more global.")
                .range(1, 512).unit("blocks").step(5)
                .recommend("small", "100").recommend("anarchy", "32")
                .build());
        s.add(spec("localChat.globalPrefix", LOCAL_CHAT, STRING)
                .label("Global prefix")
                .description("A message beginning with this is broadcast to everyone instead of locally.")
                .maxLength(8)
                .build());
        s.add(spec("localChat.localFormat", LOCAL_CHAT, STRING)
                .label("Local format")
                .description("Placeholders: %player% %message% %radius% %world% %count%. Colour codes use &.")
                .maxLength(256)
                .build());
        s.add(spec("localChat.globalFormat", LOCAL_CHAT, STRING)
                .label("Global format")
                .description("Rendered for a message sent with the global prefix. Same placeholders as the local format.")
                .maxLength(256)
                .build());
        s.add(spec("localChat.globalReachesAllDimensions", LOCAL_CHAT, BOOLEAN)
                .label("Global reaches all dimensions")
                .description("When on, a global message reaches every online player regardless of dimension.")
                .build());
        s.add(spec("localChat.staffBypass", LOCAL_CHAT, BOOLEAN)
                .label("Staff bypass radius")
                .description("Staff always receive local chat, so moderation stays possible.")
                .build());
        s.add(spec("localChat.staffLevel", LOCAL_CHAT, INTEGER)
                .label("Staff permission level").range(0, 4).step(1).build());
        s.add(spec("localChat.soundEnabled", LOCAL_CHAT, BOOLEAN)
                .label("Sound on receipt").build());
        s.add(spec("localChat.soundId", LOCAL_CHAT, STRING)
                .label("Receipt sound")
                .description("Sound id played when a local message is received, e.g. minecraft:entity.experience_orb.pickup.")
                .maxLength(128)
                .build());
        s.add(spec("localChat.soundVolume", LOCAL_CHAT, DECIMAL).range(0, 2).step(0.1).advanced().build());
        s.add(spec("localChat.soundPitch", LOCAL_CHAT, DECIMAL).range(0.5, 2).step(0.1).advanced().build());
        s.add(spec("localChat.cooldownMillis", LOCAL_CHAT, LONG)
                .label("Chat cooldown")
                .description("Minimum gap between two chat messages from one player. Zero disables the limit.")
                .range(0, 600_000).unit("ms").step(50)
                .build());
        s.add(spec("localChat.showRecipientCount", LOCAL_CHAT, BOOLEAN)
                .label("Show recipient count").build());
        s.add(spec("localChat.noRecipientsMessage", LOCAL_CHAT, STRING)
                .label("No-recipients message")
                .description("Shown to the sender when nobody is within range. Empty says nothing.")
                .maxLength(160)
                .build());
        s.add(spec("localChat.filterMessages", LOCAL_CHAT, BOOLEAN)
                .label("Filter local chat")
                .description("Apply the keyword filter and the anti-spam guard to local chat.")
                .build());
        s.add(spec("localChat.forwardToBridge", LOCAL_CHAT, BOOLEAN)
                .label("Forward local chat to bridge")
                .description("Send local and global messages to the external bridge with their channel tag.")
                .build());
    }

    private static void chatBridge(List<SettingSpec> s) {
        s.add(spec("chatBridge.enabled", CHAT_BRIDGE, BOOLEAN)
                .label("Chat bridge enabled")
                .description("Forward public chat to the configured external platform.")
                .build());
        s.add(spec("chatBridge.provider", CHAT_BRIDGE, ENUM)
                .label("Provider")
                .description("Which platform receives forwarded chat. Changing this does not delete the other providers' credentials.")
                .options("discord", "matrix", "webhook")
                .build());
        s.add(spec("chatBridge.publicChatOnly", CHAT_BRIDGE, BOOLEAN)
                .label("Public chat only")
                .description("Only forward public player chat. Private messages are never forwarded when this is on.")
                .recommend("large", "true")
                .build());
        s.add(spec("chatBridge.forwardCommands", CHAT_BRIDGE, BOOLEAN)
                .label("Forward commands")
                .description("Forward command execution to the platform. Off by default: commands may contain secrets.")
                .dangerous()
                .build());
        s.add(spec("chatBridge.forwardPrivateMessages", CHAT_BRIDGE, BOOLEAN)
                .label("Forward private messages")
                .description("Forward /msg and /tell to the platform. Off by default for privacy.")
                .dangerous()
                .build());
        s.add(spec("chatBridge.forwardJoinLeave", CHAT_BRIDGE, BOOLEAN)
                .label("Forward join/leave").build());
        s.add(spec("chatBridge.stripFormatting", CHAT_BRIDGE, BOOLEAN)
                .label("Strip formatting")
                .description("Remove Minecraft colour and formatting codes before the message leaves the server.")
                .build());
        s.add(spec("chatBridge.maxMessageLength", CHAT_BRIDGE, INTEGER)
                .label("Max forwarded length").range(64, 8_000).step(64).build());
        s.add(spec("chatBridge.queueCapacity", CHAT_BRIDGE, INTEGER)
                .label("Queue capacity")
                .description("Messages buffered before the queue starts dropping the oldest. Raise on a very busy server.")
                .range(16, 65_536).step(16)
                .recommend("large", "2048")
                .build());
        s.add(spec("chatBridge.maxAttempts", CHAT_BRIDGE, INTEGER)
                .label("Max delivery attempts").range(1, 20).step(1).build());
        s.add(spec("chatBridge.requestTimeoutMillis", CHAT_BRIDGE, INTEGER)
                .label("HTTP timeout").range(1_000, 120_000).unit("ms").step(500).build());
        s.add(spec("chatBridge.blockedKeywords", CHAT_BRIDGE, STRING_LIST)
                .label("Blocked keywords")
                .description("One per line. A matching message is dropped from the bridge entirely; it is a filter, not a punishment.")
                .build());
        s.add(spec("chatBridge.extraMatrixRooms", CHAT_BRIDGE, STRING_LIST)
                .label("Extra Matrix rooms")
                .description("Additional room ids mirrored to, one per line, sharing the homeserver and token.")
                .build());
        s.add(spec("chatBridge.antiSpam.enabled", CHAT_BRIDGE, BOOLEAN)
                .label("Anti-spam guard")
                .description("Drop repeated messages so a macro cannot flood the external channel.")
                .build());
        s.add(spec("chatBridge.antiSpam.duplicateWindowSeconds", CHAT_BRIDGE, LONG)
                .label("Duplicate window").range(0, 3_600).unit("s").step(1).build());
        s.add(spec("chatBridge.antiSpam.maxDuplicates", CHAT_BRIDGE, INTEGER)
                .label("Max duplicates").range(0, 100).step(1).build());
        s.add(spec("chatBridge.antiSpam.minIntervalMillis", CHAT_BRIDGE, LONG)
                .label("Min interval").range(0, 60_000).unit("ms").step(50).build());
        s.add(spec("chatBridge.batching.enabled", CHAT_BRIDGE, BOOLEAN)
                .label("Batch messages")
                .description("Combine several chat lines into one HTTP request, which keeps a busy server under the platform's rate limit.")
                .build());
        s.add(spec("chatBridge.batching.batchSize", CHAT_BRIDGE, INTEGER).range(1, 100).step(1).advanced().build());
        s.add(spec("chatBridge.batching.batchWindowMillis", CHAT_BRIDGE, LONG)
                .range(0, 10_000).unit("ms").step(50).advanced().build());
        s.add(spec("chatBridge.batching.dedupeWindowMillis", CHAT_BRIDGE, LONG)
                .range(0, 60_000).unit("ms").step(100).advanced().build());
        s.add(spec("chatBridge.batching.dedupeMemory", CHAT_BRIDGE, INTEGER).range(0, 65_536).step(64).advanced().build());
        s.add(spec("chatBridge.inbound.enabled", CHAT_BRIDGE, BOOLEAN)
                .label("Inbound (platform to Minecraft)")
                .description("Show platform messages in Minecraft chat. Off by default so an upgrade never changes existing behaviour.")
                .restartRequired()
                .build());
        s.add(spec("chatBridge.inbound.format", CHAT_BRIDGE, STRING)
                .label("Inbound format")
                .description("Placeholders: %platform% %user% %message%.")
                .maxLength(256)
                .build());
        s.add(spec("chatBridge.inbound.discordLabel", CHAT_BRIDGE, STRING).maxLength(32).build());
        s.add(spec("chatBridge.inbound.matrixLabel", CHAT_BRIDGE, STRING).maxLength(32).build());
        s.add(spec("chatBridge.inbound.dedupeWindowMillis", CHAT_BRIDGE, LONG)
                .range(0, 600_000).unit("ms").step(500).advanced().build());
        s.add(spec("chatBridge.inbound.maxMessageLength", CHAT_BRIDGE, INTEGER)
                .range(16, 4_096).step(16).advanced().build());
    }

    private static void integrations(List<SettingSpec> s) {
        s.add(spec("chatBridge.discord.webhookUrl", INTEGRATIONS, STRING)
                .label("Discord webhook URL")
                .description("The webhook that receives forwarded chat. This is a secret and is never sent to a client.")
                .maxLength(512).secret().restartRequired()
                .build());
        s.add(spec("chatBridge.discord.username", INTEGRATIONS, STRING)
                .label("Discord username").maxLength(80).build());
        s.add(spec("chatBridge.discord.avatarUrl", INTEGRATIONS, STRING)
                .label("Discord avatar URL").maxLength(512).build());
        s.add(spec("chatBridge.discord.useEmbeds", INTEGRATIONS, BOOLEAN)
                .label("Use embeds").build());
        s.add(spec("chatBridge.discord.messageFormat", INTEGRATIONS, STRING)
                .label("Discord message format")
                .description("Placeholders: %player% %message% %timestamp% %server% %channel%. Markdown is allowed.")
                .maxLength(512)
                .build());
        s.add(spec("chatBridge.discord.embedColor", INTEGRATIONS, COLOR)
                .label("Discord embed colour").build());
        s.add(spec("chatBridge.matrix.homeserver", INTEGRATIONS, STRING)
                .label("Matrix homeserver")
                .description("Base URL of the Matrix homeserver, e.g. https://matrix.org.")
                .maxLength(256).restartRequired()
                .build());
        s.add(spec("chatBridge.matrix.roomId", INTEGRATIONS, STRING)
                .label("Matrix room id").maxLength(128).build());
        s.add(spec("chatBridge.matrix.accessToken", INTEGRATIONS, STRING)
                .label("Matrix access token")
                .description("Access token for the Matrix bot. This is a secret and is never sent to a client.")
                .maxLength(512).secret().restartRequired()
                .build());
        s.add(spec("chatBridge.matrix.messageFormat", INTEGRATIONS, STRING)
                .label("Matrix message format").maxLength(512).build());
        s.add(spec("chatBridge.generic.url", INTEGRATIONS, STRING)
                .label("Generic webhook URL").maxLength(512).secret().restartRequired().build());
        s.add(spec("chatBridge.generic.method", INTEGRATIONS, ENUM)
                .label("Generic method").options("POST", "PUT", "PATCH").build());
        s.add(spec("chatBridge.generic.payloadTemplate", INTEGRATIONS, STRING)
                .label("Generic payload template")
                .description("JSON template. Placeholders: %player% %message% %timestamp% %server%.")
                .maxLength(4_096)
                .build());
        s.add(spec("chatBridge.generic.authHeader", INTEGRATIONS, STRING)
                .label("Auth header name").maxLength(80).build());
        s.add(spec("chatBridge.generic.authToken", INTEGRATIONS, STRING)
                .label("Auth token")
                .description("Sent in the auth header. This is a secret and is never sent to a client.")
                .maxLength(512).secret()
                .build());
        s.add(spec("chatBridge.generic.contentType", INTEGRATIONS, STRING)
                .label("Content type").maxLength(80).build());
        s.add(spec("chatBridge.inbound.discord.botToken", INTEGRATIONS, STRING)
                .label("Discord bot token")
                .description("Needed to read messages from a channel. This is a secret and is never sent to a client.")
                .maxLength(512).secret().restartRequired()
                .build());
        s.add(spec("chatBridge.inbound.discord.enabled", INTEGRATIONS, BOOLEAN)
                .label("Discord inbound").restartRequired().build());
        s.add(spec("chatBridge.inbound.discord.extraChannelIds", INTEGRATIONS, STRING_LIST)
                .label("Extra Discord channels")
                .description("Additional channel ids to read from, one per line.")
                .build());
        s.add(spec("chatBridge.inbound.matrix.enabled", INTEGRATIONS, BOOLEAN)
                .label("Matrix inbound").restartRequired().build());
        s.add(spec("chatBridge.inbound.matrix.extraRoomIds", INTEGRATIONS, STRING_LIST)
                .label("Extra Matrix rooms")
                .description("Additional room ids to read from, one per line.")
                .build());
        s.add(spec("chatBridge.inbound.discord.channelId", INTEGRATIONS, STRING)
                .label("Discord channel id").maxLength(64).build());
        s.add(spec("chatBridge.inbound.discord.guildId", INTEGRATIONS, STRING)
                .label("Discord guild id")
                .description("Optional. Empty accepts every guild the bot is a member of.")
                .maxLength(64)
                .build());
        s.add(spec("chatBridge.inbound.discord.gatewayUrl", INTEGRATIONS, STRING)
                .label("Discord gateway URL").maxLength(256).advanced().build());
        s.add(spec("chatBridge.inbound.matrix.homeserver", INTEGRATIONS, STRING)
                .label("Matrix inbound homeserver").maxLength(256).build());
        s.add(spec("chatBridge.inbound.matrix.roomId", INTEGRATIONS, STRING)
                .label("Matrix inbound room id").maxLength(128).build());
        s.add(spec("chatBridge.inbound.matrix.accessToken", INTEGRATIONS, STRING)
                .label("Matrix inbound access token").maxLength(512).secret().restartRequired().build());
        s.add(spec("chatBridge.inbound.matrix.userId", INTEGRATIONS, STRING)
                .label("Matrix bot user id")
                .description("The bot's own MXID, used to ignore its echo of outbound messages.")
                .maxLength(128)
                .build());
        s.add(spec("chatBridge.inbound.matrix.syncTimeoutMillis", INTEGRATIONS, INTEGER)
                .range(1_000, 300_000).unit("ms").step(1_000).advanced().build());
        s.add(spec("chatBridge.inbound.matrix.syncLimit", INTEGRATIONS, INTEGER)
                .range(1, 1_000).step(1).advanced().build());
    }

    private static void remoteAdmin(List<SettingSpec> s) {
        s.add(spec("remoteAdmin.enabled", REMOTE_ADMIN, BOOLEAN)
                .label("Remote administration")
                .description("Enable the authenticated remote dashboard endpoint.")
                .restartRequired()
                .build());
        s.add(spec("remoteAdmin.requireAuthentication", REMOTE_ADMIN, BOOLEAN)
                .label("Require authentication")
                .description("Refuse every request that is not from an authenticated session.")
                .dangerous().recommend("large", "true")
                .build());
        s.add(spec("remoteAdmin.ownerUuid", REMOTE_ADMIN, STRING)
                .label("Owner UUID")
                .description("The account allowed to use the console and OP management. Empty claims the first operator to connect.")
                .maxLength(64)
                .build());
        s.add(spec("remoteAdmin.allowRemoteConsole", REMOTE_ADMIN, BOOLEAN)
                .label("Allow remote console").build());
        s.add(spec("remoteAdmin.allowOpManagement", REMOTE_ADMIN, BOOLEAN)
                .label("Allow OP management").build());
        s.add(spec("remoteAdmin.requestsPerMinute", REMOTE_ADMIN, INTEGER)
                .label("Requests per minute").range(1, 100_000).step(10)
                .recommend("large", "240")
                .build());
        s.add(spec("remoteAdmin.consoleRequestsPerMinute", REMOTE_ADMIN, INTEGER)
                .label("Console requests per minute").range(1, 100_000).step(5).build());
        s.add(spec("remoteAdmin.sessionTimeoutSeconds", REMOTE_ADMIN, LONG)
                .label("Session timeout")
                .description("An authenticated session is dropped after this much idle time.")
                .range(30, 86_400).unit("s").step(30)
                .build());
        s.add(spec("remoteAdmin.blockedCommands", REMOTE_ADMIN, STRING_LIST)
                .label("Blocked commands")
                .description("One command name per line the remote console may not run. Matching is on the first word.")
                .build());
        s.add(spec("remoteAdmin.revealPasswordToOperators", REMOTE_ADMIN, BOOLEAN)
                .label("Reveal password to operators")
                .description("Send the generated password to an operator so the client panel can show it. The password file is the intended channel.")
                .dangerous()
                .build());
    }

    private static void security(List<SettingSpec> s) {
        s.add(spec("remoteAdmin.allowPasswordAuth", SECURITY, BOOLEAN)
                .label("Allow password authentication")
                .description("Permit the shared operator password to start a session. Disable once a client is paired.")
                .dangerous()
                .build());
        s.add(spec("remoteAdmin.allowPairing", SECURITY, BOOLEAN)
                .label("Allow pairing keys")
                .description("Permit one-time pairing keys to enrol a new client.").build());
        s.add(spec("remoteAdmin.requirePairedClient", SECURITY, BOOLEAN)
                .label("Require paired client")
                .description("Only a paired client may authenticate; the shared password is refused.")
                .recommend("large", "true")
                .build());
        s.add(spec("remoteAdmin.pairingKeyTtlSeconds", SECURITY, LONG)
                .label("Pairing key lifetime").range(30, 86_400).unit("s").step(30).build());
        s.add(spec("remoteAdmin.pairingKeyLength", SECURITY, INTEGER)
                .label("Pairing key length").range(12, 128).step(4).advanced().build());
        s.add(spec("remoteAdmin.clientSecretLength", SECURITY, INTEGER)
                .label("Client secret length").range(24, 128).step(4).advanced().build());
        s.add(spec("remoteAdmin.authFailureLimit", SECURITY, INTEGER)
                .label("Auth failure limit")
                .description("Failed authentications from one address before it is briefly locked out.")
                .range(1, 1_000).step(1)
                .build());
        s.add(spec("remoteAdmin.authLockoutSeconds", SECURITY, LONG)
                .label("Lockout duration").range(10, 86_400).unit("s").step(10).build());
        s.add(spec("remoteAdmin.allowedClientBuilds", SECURITY, STRING_LIST)
                .label("Allowed client builds")
                .description("One build id per line. Empty accepts every build; this is a compatibility gate, not authorisation.")
                .advanced()
                .build());
        s.add(spec("remoteAdmin.vaultDirectory", SECURITY, STRING)
                .label("Credential directory")
                .description("Folder holding the credential vault and password file. Changing it moves where credentials are looked for.")
                .maxLength(128).advanced().restartRequired()
                .build());
        s.add(spec("remoteAdmin.clientRegistryFile", SECURITY, STRING)
                .label("Client registry file").maxLength(256).advanced().restartRequired().build());
        s.add(spec("remoteAdmin.authHistoryLimit", SECURITY, INTEGER)
                .label("Auth history size").range(10, 10_000).step(10).advanced().build());
        s.add(spec("permissions.viewLevel", SECURITY, INTEGER)
                .label("View permission level")
                .description("Minecraft permission level required for read-only requests.")
                .range(0, 4).step(1)
                .build());
        s.add(spec("permissions.moderateLevel", SECURITY, INTEGER)
                .label("Moderate permission level")
                .description("Minecraft permission level required for moderation requests.")
                .range(0, 4).step(1)
                .build());
        s.add(spec("permissions.adminLevel", SECURITY, INTEGER)
                .label("Admin permission level")
                .description("Minecraft permission level required for console and OP management.")
                .range(0, 4).step(1)
                .build());
        s.add(spec("permissions.moderators", SECURITY, STRING_LIST)
                .label("Extra moderators")
                .description("One UUID or name per line, granted moderation rights regardless of permission level.")
                .build());
    }

    private static void performance(List<SettingSpec> s) {
        s.add(spec("profile", PERFORMANCE, ENUM)
                .label("Optimisation profile")
                .description("Rewrites the protection and performance thresholds to a profile's values. Individual values stay editable.")
                .options("performance", "balanced", "maximum")
                .build());
        s.add(spec("performance.sampleIntervalTicks", PERFORMANCE, INTEGER)
                .label("Sample interval").range(1, 1_200).unit("ticks").step(5)
                .recommend("performance", "100")
                .build());
        s.add(spec("performance.metricHistory", PERFORMANCE, INTEGER)
                .label("Metric history").range(10, 100_000).step(10).build());
        s.add(spec("performance.eventHistory", PERFORMANCE, INTEGER)
                .label("Event history size").range(16, 100_000).step(16).build());
        s.add(spec("performance.collectCpu", PERFORMANCE, BOOLEAN)
                .label("Collect CPU load")
                .description("Sample the JVM's CPU load. Costs a syscall per sample.").build());
        s.add(spec("performance.collectChunkActivity", PERFORMANCE, BOOLEAN)
                .label("Collect chunk activity")
                .description("Walk loaded chunks for the chunk-activity metric. Off by default because it is a full iteration.")
                .build());
        s.add(spec("performance.collectEntityCounts", PERFORMANCE, BOOLEAN)
                .label("Collect entity counts").build());
        s.add(spec("performance.entityCountIntervalTicks", PERFORMANCE, INTEGER)
                .label("Entity count interval").range(20, 72_000).unit("ticks").step(20).build());
        s.add(spec("performance.playerCacheMillis", PERFORMANCE, LONG)
                .label("Player cache lifetime").range(100, 600_000).unit("ms").step(100).build());
        s.add(spec("performance.playerCacheLimit", PERFORMANCE, INTEGER)
                .label("Player cache size").range(16, 100_000).step(16).build());
        s.add(spec("performance.chatHistoryPerPlayer", PERFORMANCE, INTEGER)
                .label("Chat history per player").range(1, 10_000).step(1).build());
        s.add(spec("performance.chatHistoryPlayers", PERFORMANCE, INTEGER)
                .label("Players with chat history").range(1, 100_000).step(8).build());
        s.add(spec("performance.transactionHistory", PERFORMANCE, INTEGER)
                .label("Transaction history").range(16, 100_000).step(16).build());
        s.add(spec("performance.cleanupIntervalTicks", PERFORMANCE, INTEGER)
                .label("Cleanup interval").range(20, 72_000).unit("ticks").step(20).build());
        s.add(spec("alerts.enabled", PERFORMANCE, BOOLEAN)
                .label("Alerts")
                .description("Raise an event when a threshold is crossed.").build());
        s.add(spec("alerts.msptWarn", PERFORMANCE, DECIMAL)
                .label("MSPT warning threshold")
                .description("Milliseconds per tick above which a warning is raised.")
                .range(1, 10_000).unit("ms").step(1)
                .build());
        s.add(spec("alerts.tpsWarn", PERFORMANCE, DECIMAL)
                .label("TPS warning threshold")
                .description("Ticks per second below which a warning is raised.")
                .range(0, 20).unit("tps").step(0.5)
                .build());
        s.add(spec("alerts.memoryWarnFraction", PERFORMANCE, DECIMAL)
                .label("Heap warning fraction")
                .description("Fraction of the heap used above which a warning is raised.")
                .range(0.1, 1.0).step(0.05)
                .build());
        s.add(spec("alerts.queueWarnDepth", PERFORMANCE, INTEGER)
                .label("Queue warning depth").range(1, 1_000_000).step(50).build());
        s.add(spec("alerts.alertCooldownSeconds", PERFORMANCE, LONG)
                .label("Alert cooldown").range(1, 86_400).unit("s").step(5).build());
    }

    private static void storage(List<SettingSpec> s) {
        s.add(spec("audit.enabled", STORAGE, BOOLEAN)
                .label("Audit log")
                .description("Record joins, leaves, commands, moderation actions and configuration changes.")
                .build());
        s.add(spec("audit.recordChat", STORAGE, BOOLEAN)
                .label("Record chat")
                .description("Record public player chat in the audit log. Off by default: it is the most sensitive category.")
                .dangerous()
                .build());
        s.add(spec("audit.categories", STORAGE, STRING_LIST)
                .label("Audited categories")
                .description("One category per line. Removing one stops it being written without losing the rest of the log.")
                .build());
        s.add(spec("audit.retentionDays", STORAGE, INTEGER)
                .label("Retention")
                .description("Days a non-security event is kept before it is deleted.")
                .range(1, 3_650).unit("days").step(1)
                .recommend("small", "14").recommend("large", "90")
                .build());
        s.add(spec("audit.securityRetentionDays", STORAGE, INTEGER)
                .label("Security retention")
                .description("Days a security event (moderation, permission, protection, authentication) is kept.")
                .range(1, 36_500).unit("days").step(1)
                .build());
        s.add(spec("audit.memoryBuffer", STORAGE, INTEGER)
                .label("Audit memory buffer").range(64, 1_000_000).step(64).advanced().build());
        s.add(spec("audit.queueCapacity", STORAGE, INTEGER)
                .label("Audit queue capacity").range(64, 1_000_000).step(64).advanced().build());
        s.add(spec("audit.maxQueryResults", STORAGE, INTEGER)
                .label("Max query results").range(1, 100_000).step(50).advanced().build());
        s.add(spec("audit.batchSize", STORAGE, INTEGER).range(1, 65_536).step(32).advanced().build());
        s.add(spec("audit.flushIntervalSeconds", STORAGE, INTEGER)
                .range(1, 3_600).unit("s").step(1).advanced().build());
        s.add(spec("audit.writeThrough", STORAGE, BOOLEAN)
                .label("Write-through")
                .description("Write audit events straight to disk instead of buffering. Safer on a security-critical server, at the cost of more disk writes.")
                .advanced()
                .build());
        s.add(spec("storage.enabled", STORAGE, BOOLEAN)
                .label("Automatic compression")
                .description("Compress old data on a background worker. Retention still applies when off.")
                .build());
        s.add(spec("storage.algorithm", STORAGE, ENUM)
                .label("Compression algorithm")
                .description("Zstandard gives the best ratio; gzip is always available; none stores data uncompressed.")
                .options("zstd", "gzip", "none")
                .restartRequired()
                .build());
        s.add(spec("storage.level", STORAGE, INTEGER)
                .label("Compression level")
                .description("Higher levels compress more but cost more CPU. Clamped to the chosen codec's range.")
                .range(0, 22).step(1)
                .build());
        s.add(spec("storage.compressIntervalSeconds", STORAGE, INTEGER)
                .label("Compression interval").range(10, 86_400).unit("s").step(30).build());
        s.add(spec("storage.minArchiveAgeHours", STORAGE, INTEGER)
                .label("Min archive age")
                .description("Hours before a record is eligible for compression. Recent data stays quick to search.")
                .range(0, 8_760).unit("h").step(1)
                .build());
        s.add(spec("storage.maxArchiveBytes", STORAGE, LONG)
                .label("Max archive size")
                .description("Target size of one rotated archive file.")
                .range(65_536, 2_147_483_648L).unit("bytes").step(1_048_576)
                .advanced()
                .build());
        s.add(spec("storage.maxArchives", STORAGE, INTEGER)
                .label("Max archives")
                .description("Most archive files kept before the oldest is removed.")
                .range(1, 10_000).step(1)
                .build());
        s.add(spec("storage.workerThreads", STORAGE, INTEGER)
                .label("Worker threads").range(1, 8).step(1).build());
        s.add(spec("storage.maxQueue", STORAGE, INTEGER)
                .label("Compression queue").range(1, 1_000_000).step(64).advanced().build());
        s.add(spec("storage.batchSize", STORAGE, INTEGER)
                .label("Archive batch size").range(1, 65_536).step(32).advanced().build());
        s.add(spec("storage.cacheEntries", STORAGE, INTEGER)
                .label("Cache entries").range(0, 1_000_000).step(32).advanced().build());
        s.add(spec("storage.cacheMaxBytes", STORAGE, INTEGER)
                .label("Cache max bytes").range(0, 1_073_741_824).step(1_048_576).advanced().build());
        s.add(spec("storage.maxDecompressBytes", STORAGE, INTEGER)
                .label("Max decompressed bytes")
                .description("Ceiling on a single archive's decompressed size, so a bad header cannot exhaust memory.")
                .range(1_024, 1_073_741_824).step(1_048_576).advanced()
                .build());
        s.add(spec("storage.deduplicate", STORAGE, BOOLEAN)
                .label("Deduplicate records").build());
        s.add(spec("storage.dedupeWindowMillis", STORAGE, LONG)
                .range(0, 600_000).unit("ms").step(50).advanced().build());
        s.add(spec("storage.pauseDuringLag", STORAGE, BOOLEAN)
                .label("Pause compression during lag")
                .description("Skip a compression pass while the server is lagging; it resumes on the next interval.").build());
        s.add(spec("storage.pauseMsptThreshold", STORAGE, DECIMAL)
                .label("Compression pause threshold").range(1, 10_000).unit("ms").step(1).build());
    }

    private static void backups(List<SettingSpec> s) {
        s.add(spec("backups.enabled", BACKUPS, BOOLEAN)
                .label("Automatic backups")
                .description("Back up the configuration before a migration or a bulk change, and on a schedule.")
                .build());
        s.add(spec("backups.intervalHours", BACKUPS, INTEGER)
                .label("Backup interval")
                .description("Hours between scheduled configuration backups. Zero disables the schedule.")
                .range(0, 720).unit("h").step(1)
                .build());
        s.add(spec("backups.maxBackups", BACKUPS, INTEGER)
                .label("Backups kept")
                .description("Most backup files to keep before the oldest is removed.")
                .range(1, 1_000).step(1)
                .build());
        s.add(spec("backups.backupBeforeMigration", BACKUPS, BOOLEAN)
                .label("Back up before migration")
                .description("Copy the configuration aside before an upgrade rewrites it. Strongly recommended.")
                .build());
        s.add(spec("backups.backupBeforeImport", BACKUPS, BOOLEAN)
                .label("Back up before import")
                .description("Copy the configuration aside before a profile import is applied.")
                .build());
    }

    private static void automation(List<SettingSpec> s) {
        s.add(spec("automation.enabled", AUTOMATION, BOOLEAN)
                .label("Automation enabled")
                .description("Allow scheduled maintenance tasks to run.").build());
        s.add(spec("automation.autoCleanupEnabled", AUTOMATION, BOOLEAN)
                .label("Automatic cleanup")
                .description("Sweep expired data on the cleanup interval.").build());
        s.add(spec("automation.autoCleanupIntervalMinutes", AUTOMATION, INTEGER)
                .label("Cleanup interval").range(1, 10_080).unit("min").step(5).build());
        s.add(spec("automation.autoBackupOnStart", AUTOMATION, BOOLEAN)
                .label("Back up on start").build());
        s.add(spec("automation.autoCompressEnabled", AUTOMATION, BOOLEAN)
                .label("Automatic compression").build());
        s.add(spec("automation.restartRequiredNotice", AUTOMATION, BOOLEAN)
                .label("Show restart-required notice")
                .description("Warn in the dashboard when a change needs a restart to take effect.").build());
    }

    private static void notifications(List<SettingSpec> s) {
        s.add(spec("notifications.enabled", NOTIFICATIONS, BOOLEAN)
                .label("Notifications enabled")
                .description("Send ADMIN's own alerts to the destinations below.").build());
        s.add(spec("notifications.onProtectionTrigger", NOTIFICATIONS, BOOLEAN)
                .label("On protection trigger").build());
        s.add(spec("notifications.onModerationAction", NOTIFICATIONS, BOOLEAN)
                .label("On moderation action").build());
        s.add(spec("notifications.onAuthFailure", NOTIFICATIONS, BOOLEAN)
                .label("On authentication failure").build());
        s.add(spec("notifications.onPerformanceAlert", NOTIFICATIONS, BOOLEAN)
                .label("On performance alert").build());
        s.add(spec("notifications.minSeverity", NOTIFICATIONS, ENUM)
                .label("Minimum severity")
                .description("Only notify for events at or above this severity.")
                .options("info", "notice", "warning", "error", "critical")
                .build());
        s.add(spec("notifications.toChat", NOTIFICATIONS, BOOLEAN)
                .label("Notify in chat").build());
        s.add(spec("notifications.toBridge", NOTIFICATIONS, BOOLEAN)
                .label("Notify the chat bridge")
                .description("Send notifications to the external platform. Requires the chat bridge to be enabled.")
                .dependsOn("chatBridge.enabled")
                .build());
        s.add(spec("notifications.quietSeconds", NOTIFICATIONS, LONG)
                .label("Quiet period")
                .description("Minimum gap between two notifications of the same kind.")
                .range(0, 86_400).unit("s").step(10)
                .build());
    }

    private static SettingSpec.Builder spec(String path, SettingCategory category, SettingType type) {
        return SettingSpec.builder(path, category, type);
    }
}
