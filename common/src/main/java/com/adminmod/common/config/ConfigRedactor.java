package com.adminmod.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Produces a redacted view of the server configuration for the client dashboard.
 *
 * <p>Credential-bearing fields are replaced by a boolean that reports whether they are set.
 * The client can therefore show "webhook configured: yes" without ever receiving the URL or
 * token itself.
 */
public final class ConfigRedactor {

    private ConfigRedactor() {
    }

    public static ServerSettingsView view(AdminConfig config) {
        ServerSettingsView view = new ServerSettingsView();
        view.chatBridgeEnabled = config.chatBridge.enabled;
        view.provider = config.chatBridge.provider == null ? "" : config.chatBridge.provider.toLowerCase(Locale.ROOT);
        view.publicChatOnly = config.chatBridge.publicChatOnly;
        view.forwardCommands = config.chatBridge.forwardCommands;
        view.forwardPrivateMessages = config.chatBridge.forwardPrivateMessages;
        view.forwardJoinLeave = config.chatBridge.forwardJoinLeave;
        view.queueCapacity = config.chatBridge.queueCapacity;
        view.maxAttempts = config.chatBridge.maxAttempts;
        view.requestTimeoutMillis = config.chatBridge.requestTimeoutMillis;

        view.discordWebhookConfigured = isSet(config.chatBridge.discord.webhookUrl);
        view.discordUsername = config.chatBridge.discord.username;
        view.discordUseEmbeds = config.chatBridge.discord.useEmbeds;
        view.discordMessageFormat = config.chatBridge.discord.messageFormat;

        view.matrixHomeserver = config.chatBridge.matrix.homeserver;
        view.matrixRoomId = config.chatBridge.matrix.roomId;
        view.matrixTokenConfigured = isSet(config.chatBridge.matrix.accessToken);

        view.genericUrl = config.chatBridge.generic.url;
        view.genericTokenConfigured = isSet(config.chatBridge.generic.authToken);
        view.genericMethod = config.chatBridge.generic.method;

        view.moderationEnabled = config.moderation.enabled;
        view.requireReasons = config.moderation.requireReasons;
        view.broadcastPunishments = config.moderation.broadcastPunishments;

        view.inboundEnabled = config.chatBridge.inbound.enabled;
        view.inboundFormat = config.chatBridge.inbound.format;
        view.inboundDiscordEnabled = config.chatBridge.inbound.discord.enabled;
        view.inboundDiscordBotTokenConfigured = isSet(config.chatBridge.inbound.discord.botToken);
        view.inboundDiscordChannelId = config.chatBridge.inbound.discord.channelId;
        view.inboundMatrixEnabled = config.chatBridge.inbound.matrix.enabled;
        view.inboundMatrixHomeserver = config.chatBridge.inbound.matrix.homeserver;
        view.inboundMatrixRoomId = config.chatBridge.inbound.matrix.roomId;
        view.inboundMatrixTokenConfigured = isSet(config.chatBridge.inbound.matrix.accessToken);
        view.inboundMatrixUserId = config.chatBridge.inbound.matrix.userId;

        view.remoteAdminEnabled = config.remoteAdmin.enabled;
        view.ownerUuid = config.remoteAdmin.ownerUuid;
        view.requireAuthentication = config.remoteAdmin.requireAuthentication;
        view.requestsPerMinute = config.remoteAdmin.requestsPerMinute;
        view.allowRemoteConsole = config.remoteAdmin.allowRemoteConsole;
        view.allowOpManagement = config.remoteAdmin.allowOpManagement;

        view.viewLevel = config.permissions.viewLevel;
        view.moderateLevel = config.permissions.moderateLevel;
        view.adminLevel = config.permissions.adminLevel;
        view.moderators = new ArrayList<>(config.permissions.moderators);
        return view;
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /** Redacted settings payload; contains no credential material. */
    public static final class ServerSettingsView {
        public boolean chatBridgeEnabled;
        public String provider = "";
        public boolean publicChatOnly;
        public boolean forwardCommands;
        public boolean forwardPrivateMessages;
        public boolean forwardJoinLeave;
        public int queueCapacity;
        public int maxAttempts;
        public int requestTimeoutMillis;

        public boolean discordWebhookConfigured;
        public String discordUsername = "";
        public boolean discordUseEmbeds;
        public String discordMessageFormat = "";

        public String matrixHomeserver = "";
        public String matrixRoomId = "";
        public boolean matrixTokenConfigured;

        public String genericUrl = "";
        public boolean genericTokenConfigured;
        public String genericMethod = "";

        public boolean moderationEnabled;
        public boolean requireReasons;
        public boolean broadcastPunishments;

        public boolean inboundEnabled;
        public String inboundFormat = "";
        public boolean inboundDiscordEnabled;
        public boolean inboundDiscordBotTokenConfigured;
        public String inboundDiscordChannelId = "";
        public boolean inboundMatrixEnabled;
        public String inboundMatrixHomeserver = "";
        public String inboundMatrixRoomId = "";
        public boolean inboundMatrixTokenConfigured;
        public String inboundMatrixUserId = "";

        public boolean remoteAdminEnabled;
        public String ownerUuid = "";
        public boolean requireAuthentication;
        public int requestsPerMinute;
        public boolean allowRemoteConsole;
        public boolean allowOpManagement;

        public int viewLevel;
        public int moderateLevel;
        public int adminLevel;
        public List<String> moderators = new ArrayList<>();
    }
}
