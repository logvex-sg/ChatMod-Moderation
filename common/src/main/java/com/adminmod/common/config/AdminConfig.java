package com.adminmod.common.config;

import java.util.List;
import java.util.Map;

/**
 * Server-side configuration model, serialised to {@code config/admin-mod.json}.
 *
 * <p>Only the server reads this file. The remote administration protocol exposes a redacted
 * view of it and never transmits the credentials held here.
 */
public final class AdminConfig {

    public ChatBridge chatBridge = new ChatBridge();
    public Moderation moderation = new Moderation();
    public RemoteAdmin remoteAdmin = new RemoteAdmin();
    public Permissions permissions = new Permissions();

    public static final class ChatBridge {
        public boolean enabled = true;
        public String provider = "discord";
        public boolean publicChatOnly = true;
        public boolean forwardCommands = false;
        public boolean forwardPrivateMessages = false;
        public boolean forwardJoinLeave = true;
        public int queueCapacity = 512;
        public int maxAttempts = 5;
        public int requestTimeoutMillis = 8000;
        public Discord discord = new Discord();
        public Matrix matrix = new Matrix();
        public GenericWebhook generic = new GenericWebhook();
        /**
         * Inbound direction: platform messages shown in Minecraft chat. Off by default, so an
         * upgraded server keeps forwarding outbound chat exactly as before.
         */
        public Inbound inbound = new Inbound();
    }

    /**
     * Platform-to-Minecraft settings.
     *
     * <p>Every credential here stays on the server. The dashboard only ever learns whether a
     * value is set, never the value itself.
     */
    public static final class Inbound {
        public boolean enabled = false;
        /**
         * Rendered for each inbound message. {@code %platform%} becomes the label of the origin,
         * {@code %user%} the platform display name and {@code %message%} the body. The default
         * produces {@code [Discord] Username: Message}.
         */
        public String format = "[%platform%] %user%: %message%";
        public String discordLabel = "Discord";
        public String matrixLabel = "Matrix";
        /** Suppresses an identical message repeated inside this window, in milliseconds. */
        public long dedupeWindowMillis = 5_000L;
        /** Hard cap on a single inbound message, guarding against oversized payloads. */
        public int maxMessageLength = 512;
        public DiscordInbound discord = new DiscordInbound();
        public MatrixInbound matrix = new MatrixInbound();
    }

    /**
     * Discord inbound settings. A bot token is required because reading messages needs the
     * Gateway; a webhook can only post.
     */
    public static final class DiscordInbound {
        public boolean enabled = false;
        /** Bot token. Treat as a secret; it is never sent to a client. */
        public String botToken = "";
        /** Channel to read from. Empty means every text channel the bot can see. */
        public String channelId = "";
        /** Optional guild restriction. Empty means every guild the bot is a member of. */
        public String guildId = "";
        public String gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";
    }

    /** Matrix inbound settings, using the client-server sync endpoint. */
    public static final class MatrixInbound {
        public boolean enabled = false;
        public String homeserver = "";
        public String roomId = "";
        /** Access token. Treat as a secret; it is never sent to a client. */
        public String accessToken = "";
        /** The bot's own MXID, used to ignore its echo of outbound messages. */
        public String userId = "";
        /** How long a long-poll sync may wait for new events, in milliseconds. */
        public int syncTimeoutMillis = 30_000;
        /** How many events to request per sync. */
        public int syncLimit = 50;
    }

    public static final class Discord {
        public String webhookUrl = "";
        public String username = "Minecraft Server";
        public String avatarUrl = "";
        public boolean useEmbeds = true;
        public String messageFormat = "**%player%**: %message%";
        public int embedColor = 0x5865F2;
    }

    public static final class Matrix {
        public String homeserver = "";
        public String roomId = "";
        public String accessToken = "";
        public String messageFormat = "%player%: %message%";
    }

    public static final class GenericWebhook {
        public String url = "";
        public String method = "POST";
        /** Template rendered with %player%, %message%, %timestamp%, %server%. */
        public String payloadTemplate = "{\"player\":\"%player%\",\"message\":\"%message%\",\"timestamp\":\"%timestamp%\",\"server\":\"%server%\"}";
        public String authHeader = "Authorization";
        public String authToken = "";
        public Map<String, String> headers = Map.of();
        public String contentType = "application/json";
    }

    public static final class Moderation {
        public boolean enabled = true;
        public boolean requireReasons = true;
        public boolean broadcastPunishments = true;
        public String defaultKickReason = "Kicked by a staff member";
        public String defaultBanReason = "Banned by a staff member";
        public String defaultMuteReason = "Muted by a staff member";
        public long defaultTempBanSeconds = 86_400L;
        public long defaultTempMuteSeconds = 3_600L;
        public long maxPunishmentSeconds = 31_536_000L;
    }

    public static final class RemoteAdmin {
        public boolean enabled = true;
        /** UUID allowed to use the console and OP management. Empty means "first op to connect". */
        public String ownerUuid = "";
        public boolean requireAuthentication = true;
        public int requestsPerMinute = 120;
        public int consoleRequestsPerMinute = 30;
        public boolean allowRemoteConsole = true;
        public boolean allowOpManagement = true;
        /**
         * When true, an already-operator player receives the generated password in the handshake
         * result so the client panel can display it. Off by default: the password file is the
         * intended channel, and an operator can always read it there.
         */
        public boolean revealPasswordToOperators = false;
        /**
         * Relative to the server run directory. The default is the fixed folder the credentials
         * live in; the password file inside is readable only by the server process owner.
         */
        public String vaultDirectory = "H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme";
    }

    public static final class Permissions {
        /** Minecraft permission level required for read-only requests. */
        public int viewLevel = 0;
        /** Minecraft permission level required for moderation requests. */
        public int moderateLevel = 2;
        /** Minecraft permission level required for console and OP management. */
        public int adminLevel = 4;
        /** Extra UUIDs granted moderation rights regardless of permission level. */
        public List<String> moderators = List.of();
    }
}
