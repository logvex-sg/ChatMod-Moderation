package com.adminmod.common.config;

import java.util.List;
import java.util.Map;

/**
 * Server-side configuration model, serialised to {@code config/admin-mod.json}.
 *
 * <p>Only the server reads this file. The remote administration protocol exposes a redacted
 * view of it and never transmits the credentials held here.
 *
 * <p>{@link #configVersion} records the schema the file was written with. {@link ConfigLoader}
 * upgrades an older file in place, so a server that is updated does not lose its settings and
 * does not have to be reconfigured by hand.
 */
public final class AdminConfig {

    /** Schema version written into the file. Bumped whenever the layout changes. */
    public int configVersion = ConfigLoader.CURRENT_VERSION;

    /**
     * The optimisation profile that was applied. One of {@code performance}, {@code balanced} or
     * {@code maximum}. Selecting a profile rewrites the protection and performance thresholds to
     * that profile's values; the individual values remain editable afterwards, so a profile is a
     * starting point rather than a lock.
     */
    public String profile = "balanced";

    public General general = new General();
    public ChatBridge chatBridge = new ChatBridge();
    public LocalChat localChat = new LocalChat();
    public Moderation moderation = new Moderation();
    public RemoteAdmin remoteAdmin = new RemoteAdmin();
    public Permissions permissions = new Permissions();
    public Announcements announcements = new Announcements();
    public Maintenance maintenance = new Maintenance();
    public Anticheat anticheat = new Anticheat();
    public Antidupe antidupe = new Antidupe();
    public Protection protection = new Protection();
    public Performance performance = new Performance();
    public Alerts alerts = new Alerts();
    public Logging logging = new Logging();
    public Audit audit = new Audit();
    public Storage storage = new Storage();
    public Backups backups = new Backups();
    public Automation automation = new Automation();
    public Notifications notifications = new Notifications();

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
        /** Hard cap on a forwarded message, after formatting. Longer messages are truncated. */
        public int maxMessageLength = 1500;
        /** Removes Minecraft colour/formatting codes before the message leaves the server. */
        public boolean stripFormatting = true;
        /**
         * Substrings that block forwarding. Matching is case-insensitive and a match drops the
         * message from the bridge entirely; it is a filter, not a punishment.
         */
        public List<String> blockedKeywords = List.of();
        public Discord discord = new Discord();
        public Matrix matrix = new Matrix();
        public GenericWebhook generic = new GenericWebhook();
        /**
         * Additional Discord webhooks. When non-empty these are used <em>in addition to</em>
         * {@link Discord#webhookUrl}, so a server can mirror chat into several channels.
         */
        public List<DiscordChannel> extraDiscordChannels = List.of();
        /** Additional Matrix room ids, delivered with the homeserver and token from {@link Matrix}. */
        public List<String> extraMatrixRooms = List.of();
        public AntiSpam antiSpam = new AntiSpam();
        /**
         * Inbound direction: platform messages shown in Minecraft chat. Off by default, so an
         * upgraded server keeps forwarding outbound chat exactly as before.
         */
        public Inbound inbound = new Inbound();
        /**
         * Batching and de-duplication for the outbound queue.
         *
         * <p>Batching combines several chat lines into one HTTP request, which is what keeps a busy
         * server under Discord's webhook rate limit. De-duplication drops an identical message seen
         * twice inside a short window, which catches a double-fired event.
         */
        public Batching batching = new Batching();
    }

    /** Outbound batching and duplicate suppression. */
    public static final class Batching {
        public boolean enabled = true;
        /** Most messages combined into one request; the provider may cap this lower. */
        public int batchSize = 8;
        /** How long the worker may wait for a batch to fill before sending what it has. */
        public long batchWindowMillis = 250L;
        /** Identical (player, message) pairs inside this window are delivered once. */
        public long dedupeWindowMillis = 1_500L;
        /** Most recent message signatures remembered for duplicate detection. */
        public int dedupeMemory = 512;
    }

    /**
     * Proximity ("local") chat settings.
     *
     * <p>When enabled, ordinary chat is delivered only to players within {@code radius} blocks of
     * the sender in the same dimension; a message beginning with {@code globalPrefix} is broadcast
     * to everyone instead. Distribution is server-authoritative: the client never decides who can
     * see a message.
     *
     * <p>These values are mirrored to {@code config/admin/localchat.json} so the feature can be
     * tuned in its own file; {@code admin-mod.json} keeps the section for compatibility.
     */
    public static final class LocalChat {
        /** Master switch. When off, chat behaves exactly as vanilla. */
        public boolean enabled = false;
        /** Delivery radius for a local message, in blocks. */
        public double radius = 50.0;
        /** A message beginning with this prefix is sent globally instead of locally. */
        public String globalPrefix = "!";
        /**
         * Rendered for a local message. {@code %player%}, {@code %message%}, {@code %radius%},
         * {@code %world%} and {@code %count%} are substituted; colour codes use {@code &}.
         * The local and global formats carry their own colours, so the two channels can be
         * distinguished at a glance.
         */
        public String localFormat = "[Local] &f%player%&7: &f%message%";
        /** Rendered for a global message. See {@link #localFormat} for the placeholders. */
        public String globalFormat = "[Global] &f%player%&7: &f%message%";
        /** When true, a global message reaches every online player regardless of dimension. */
        public boolean globalReachesAllDimensions = true;
        /** Staff always receive local chat, ignoring the radius, so moderation stays possible. */
        public boolean staffBypass = true;
        /** Minecraft permission level at or above which the staff bypass applies. */
        public int staffLevel = 2;
        /** Sends a subtle sound to a player when they receive a local message. */
        public boolean soundEnabled = false;
        /** Sound id played on receipt, e.g. {@code minecraft:entity.experience_orb.pickup}. */
        public String soundId = "minecraft:entity.experience_orb.pickup";
        public float soundVolume = 0.5f;
        public float soundPitch = 1.2f;
        /** Minimum gap between two chat messages from one player, in milliseconds. 0 disables. */
        public long cooldownMillis = 750L;
        /** Tells the sender how many players heard a local message. */
        public boolean showRecipientCount = false;
        /** Shown to the sender when nobody is within range. Empty means say nothing. */
        public String noRecipientsMessage = "&7Nobody is close enough to hear you.";
        /** Applies the moderation keyword filter and the bridge anti-spam guard to local chat. */
        public boolean filterMessages = true;
        /** Forwards local and global messages to the external bridge with their channel type. */
        public boolean forwardToBridge = true;
        /** Per-dimension overrides, matched by dimension id; the first match wins. */
        public List<DimensionRule> dimensions = List.of();
    }

    /** Per-dimension override of the proximity settings. */
    public static final class DimensionRule {
        /** Dimension id, e.g. {@code minecraft:overworld}. */
        public String dimension = "";
        public boolean enabled = true;
        /** Radius override; values at or below zero fall back to the global radius. */
        public double radius = 0.0;
        /** Local format override; empty falls back to the global setting. */
        public String localFormat = "";
        /** Global format override; empty falls back to the global setting. */
        public String globalFormat = "";
        /** Global prefix override; empty falls back to the global setting. */
        public String globalPrefix = "";
        /** Global-reach override; {@code null} falls back to the global setting. */
        public Boolean globalReachesAllDimensions = null;
    }

    /** One extra Discord destination, sharing the shape of the primary webhook settings. */
    public static final class DiscordChannel {
        public String webhookUrl = "";
        public String username = "Minecraft Server";
        public String avatarUrl = "";
        public boolean useEmbeds = true;
        public String messageFormat = "**%player%**: %message%";
        public int embedColor = 0x5865F2;
    }

    /**
     * Anti-spam guard for the outbound bridge.
     *
     * <p>It exists so a player holding down a macro cannot flood the external channel or burn the
     * webhook rate limit for everybody else. Blocked messages are dropped, never queued.
     */
    public static final class AntiSpam {
        public boolean enabled = true;
        /** An identical (player, message) pair inside this window counts as a repeat. */
        public long duplicateWindowSeconds = 8L;
        /** Repeats allowed inside the window before further copies are dropped. */
        public int maxDuplicates = 2;
        /** Minimum gap between two forwarded messages from the same player, in milliseconds. */
        public long minIntervalMillis = 250L;
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
        /** Additional channels to accept, so one bot can bridge several channels at once. */
        public List<String> extraChannelIds = List.of();
        /** Optional guild restriction. Empty means every guild the bot is a member of. */
        public String guildId = "";
        public String gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json";
    }

    /** Matrix inbound settings, using the client-server sync endpoint. */
    public static final class MatrixInbound {
        public boolean enabled = false;
        public String homeserver = "";
        public String roomId = "";
        /** Additional rooms to read, sharing the homeserver and access token. */
        public List<String> extraRoomIds = List.of();
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
        /** Sends a private notice to online staff when an action is taken. */
        public boolean notifyStaff = true;
        /** Announces joins and leaves in chat, which the bridge can then forward. */
        public boolean announceJoinLeave = false;
        public Escalation escalation = new Escalation();
        /**
         * Named punishment templates. A template is a duration and a reason a moderator can apply
         * by name, so common actions are consistent instead of hand-typed each time.
         */
        public List<PunishmentTemplate> templates = List.of(
                PunishmentTemplate.of("mute-1h", "MUTE", 3_600L, "Disruptive behaviour"),
                PunishmentTemplate.of("mute-1d", "MUTE", 86_400L, "Repeated disruptive behaviour"),
                PunishmentTemplate.of("ban-1d", "BAN", 86_400L, "Serious rule violation"),
                PunishmentTemplate.of("ban-perm", "BAN", 0L, "Permanent removal"));
    }

    /**
     * Automatic escalation of warnings into mutes.
     *
     * <p>When a player accumulates {@code warnThreshold} active warnings inside
     * {@code windowSeconds}, the configured mute is applied automatically. This is what turns a
     * warning system into something that actually deters repeat behaviour.
     */
    public static final class Escalation {
        public boolean enabled = false;
        /** Number of active warnings that triggers the automatic punishment. */
        public int warnThreshold = 3;
        /** Warnings older than this do not count, in seconds. Zero means no time limit. */
        public long windowSeconds = 604_800L;
        /** Duration of the automatic mute, in seconds. Zero means permanent. */
        public long muteSeconds = 86_400L;
        public String reason = "Automatic mute: too many warnings";
    }

    /** A reusable punishment preset. */
    public static final class PunishmentTemplate {
        public String name = "";
        public String type = "MUTE";
        public long seconds = 3_600L;
        public String reason = "";

        public static PunishmentTemplate of(String name, String type, long seconds, String reason) {
            PunishmentTemplate template = new PunishmentTemplate();
            template.name = name;
            template.type = type;
            template.seconds = seconds;
            template.reason = reason;
            return template;
        }
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
        /** An authenticated session is dropped after this much idle time, in seconds. */
        public long sessionTimeoutSeconds = 900L;
        /**
         * Command names the remote console may not run. Matching is on the first word, so
         * {@code stop} blocks {@code stop} and any argument it takes.
         */
        public List<String> blockedCommands = List.of("stop", "restart");
        /**
         * Relative to the server run directory. The default is the fixed folder the credentials
         * live in; the password file inside is readable only by the server process owner.
         */
        public String vaultDirectory = "H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme";

        /** Whether the shared operator password may be used to authenticate a session. */
        public boolean allowPasswordAuth = true;
        /** Whether one-time pairing keys may be redeemed to enrol a new client. */
        public boolean allowPairing = true;
        /**
         * When true, only a paired client may authenticate: the shared password is refused. This is
         * the "private client authorization" switch, and it is off by default so an existing server
         * keeps working with the password until its operator pairs a client.
         */
        public boolean requirePairedClient = false;
        /** How long a generated pairing key stays redeemable, in seconds. */
        public long pairingKeyTtlSeconds = 900L;
        /** Number of characters in a generated pairing key. */
        public int pairingKeyLength = 24;
        /** Number of characters in a paired client's long-lived secret. */
        public int clientSecretLength = 40;
        /**
         * Client build ids the protocol accepts. Empty means every build is accepted, which is the
         * default; the check is a compatibility gate, never an authorization decision.
         */
        public List<String> allowedClientBuilds = List.of();
        /** Failed authentications from one address before it is locked out. */
        public int authFailureLimit = 5;
        /** How long an address stays locked out after too many failures, in seconds. */
        public long authLockoutSeconds = 300L;
        /** Relative to the server run directory; holds paired clients and pending pairing keys. */
        public String clientRegistryFile = "config/adminmod-clients.json";
        /** Number of authentication events retained for the dashboard. */
        public int authHistoryLimit = 200;
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

    /** Scheduled server announcements, delivered to chat and therefore to the bridge. */
    public static final class Announcements {
        public boolean enabled = false;
        /** Interval between the rotating announcements, in seconds. */
        public long intervalSeconds = 300L;
        /** Announcement lines, shown one at a time in order. */
        public List<String> messages = List.of();
    }

    /** Maintenance mode: a visible notice and an optional allow-list for who may join. */
    public static final class Maintenance {
        public boolean enabled = false;
        public String message = "The server is under maintenance and will be back shortly.";
        /** When true, players who are not operators are kicked on join. */
        public boolean kickNonOperators = false;
        /** UUIDs allowed to join during maintenance even when kicking is on. */
        public List<String> allowList = List.of();
    }

    /** Diagnostics verbosity. */
    public static final class Logging {
        /** One of {@code off}, {@code error}, {@code warn}, {@code info}, {@code debug}. */
        public String level = "info";
    }

    /** Server identity and the master switches for the whole mod. */
    public static final class General {
        /** Name shown in the bridge payload and on the diagnostics dashboard. */
        public String serverName = "";
        /** Master switch for the chat bridge and the remote administration endpoint together. */
        public boolean enabled = true;
        /** Retention for the in-memory event history, in minutes. */
        public int historyMinutes = 240;
    }

    /**
     * Anti-crash and abuse protection.
     *
     * <p>Every check here is off by default except the ones that only reject data a legitimate
     * client never sends (oversized payloads, malformed text). The rate limits default to values a
     * real client does not reach, because the cost of a false positive — kicking an innocent
     * player during a lag spike — is worse than the cost of a missed trigger.
     */
    public static final class Protection {
        public boolean enabled = true;
        /** Longest chat message accepted; longer messages are dropped before processing. */
        public int maxChatLength = 256;
        /** Largest custom payload accepted on the mod's own channel, in bytes. */
        public int maxPayloadBytes = 32_768;
        /** Pages allowed in one book-and-quill update. */
        public int maxBookPages = 100;
        /** Characters allowed on one book page. */
        public int maxBookPageLength = 1_024;
        /** Characters allowed in a book title. */
        public int maxBookTitleLength = 64;
        /** Characters allowed on one sign line. */
        public int maxSignLineLength = 384;
        /** Stacks a single inventory click may claim to modify. */
        public int maxModifiedStacks = 128;
        /** Ticks of grace after a join before the checks apply, so login traffic is not counted. */
        public int joinGraceTicks = 100;
        /** Chat messages allowed per minute, per player. */
        public int chatPerMinute = 40;
        /** Commands allowed per minute, per player. */
        public int commandsPerMinute = 60;
        /** Packets allowed in one second, per player. */
        public int packetsPerSecond = 400;
        /** Packets allowed in ten seconds, per player; both windows must trip. */
        public int packetsPerTenSeconds = 3_000;
        /** Inventory clicks allowed per minute, per player. */
        public int inventoryClicksPerMinute = 300;
        /** Entity interactions allowed per minute, per player. */
        public int entityInteractionsPerMinute = 600;
        /** Consecutive violations before the player is kicked. Zero never kicks. */
        public int violationsBeforeKick = 0;
        /** Whether a detected violation is recorded in the bounded violation log. */
        public boolean logViolations = true;
    }

    /**
     * Connection-level protection. Kept separate from {@link Protection} because it is about
     * the login path rather than per-player packet behaviour.
     */
    public static final class Anticheat {
        public boolean enabled = true;
        /** Connections accepted from one address per minute. */
        public int connectionRatePerMinute = 30;
        /** Concurrent connections allowed from one address. */
        public int maxConnectionsPerAddress = 8;
        /** Largest number of tracked addresses in the connection limiter. */
        public int maxTrackedAddresses = 4_096;
        /** Whether an over-rate address is temporarily restricted rather than only refused once. */
        public boolean restrictOnFlood = true;
    }

    /**
     * Duplication and inventory-transaction abuse protection.
     *
     * <p>The checks are conservative on purpose: they reject only shapes no vanilla client
     * produces (a click against a slot that does not exist, a modified-stack map far larger than
     * the window, a click rate a human cannot reach). Anything ambiguous is allowed and counted.
     */
    public static final class Antidupe {
        public boolean enabled = true;
        /** Reject a click whose slot index is outside the open container. */
        public boolean validateSlots = true;
        /** Reject a click that claims to modify more stacks than the window can hold. */
        public boolean validateModifiedStacks = true;
        /** Tracks a rolling per-player click rate and flags a sustained burst. */
        public boolean monitorClickRate = true;
        /** Tracks repeated identical clicks, a common signature of a dupe attempt. */
        public boolean monitorRepeatClicks = true;
        /** Identical (window, slot, button) clicks inside this window before it is flagged. */
        public int repeatClickThreshold = 12;
        /** Length of the repeat window, in milliseconds. */
        public long repeatClickWindowMillis = 1_000L;
    }

    /**
     * Performance and memory limits.
     *
     * <p>These bound how much memory ADMIN itself may use. Every collection ADMIN keeps is sized
     * from here, so a server with many players can raise them deliberately rather than discovering
     * an unbounded map under load.
     */
    public static final class Performance {
        /** How long a cached player record lives before it is rebuilt, in milliseconds. */
        public long playerCacheMillis = 1_000L;
        /** Most player records the cache will hold. */
        public int playerCacheLimit = 1_024;
        /** Most chat-history entries retained per player. */
        public int chatHistoryPerPlayer = 50;
        /** Most players for whom chat history is retained. */
        public int chatHistoryPlayers = 256;
        /** Most inventory transactions retained for the transaction view. */
        public int transactionHistory = 512;
        /** Most server events retained for the event history. */
        public int eventHistory = 512;
        /** Most samples retained for each performance series. */
        public int metricHistory = 300;
        /** How often a performance sample is taken, in ticks. */
        public int sampleIntervalTicks = 20;
        /** Whether to sample the JVM's CPU load; off by default because it costs a syscall. */
        public boolean collectCpu = true;
        /** Whether to walk loaded chunks for the chunk-activity metric; off by default. */
        public boolean collectChunkActivity = false;
        /** Whether to count entities each sample; a full iteration, so sampled coarsely. */
        public boolean collectEntityCounts = true;
        /** Ticks between two entity counts; entity counting is the most expensive metric. */
        public int entityCountIntervalTicks = 100;
        /** Interval between sweeps that drop expired data, in ticks. */
        public int cleanupIntervalTicks = 1_200;
    }

    /** Alert thresholds; a crossing is recorded in the event history and shown on the dashboard. */
    public static final class Alerts {
        public boolean enabled = true;
        /** MSPT above which a warning event is raised. */
        public double msptWarn = 50.0;
        /** TPS below which a warning event is raised. */
        public double tpsWarn = 19.0;
        /** Heap used fraction above which a warning event is raised. */
        public double memoryWarnFraction = 0.9;
        /** Bridge queue depth above which a warning event is raised. */
        public int queueWarnDepth = 400;
        /** Minimum gap between two alerts of the same kind, in seconds. */
        public long alertCooldownSeconds = 60L;
    }

    /**
     * Audit and investigation settings.
     *
     * <p>The audit log is the long-lived record of who did what: joins, leaves, deaths, dimension
     * changes, block and container activity, commands, moderation actions and configuration
     * changes. It is stored separately from the in-memory {@link EventHistory} ring because it is
     * meant to survive restarts and to be searched, not just displayed.
     *
     * <p>Nothing here records passwords, authentication secrets or private chat. Player chat is
     * deliberately absent from the default category set; an operator who wants it must opt in and
     * accept the privacy cost, and even then only public chat is eligible.
     */
    public static final class Audit {
        /** Master switch. When off, no audit events are recorded. */
        public boolean enabled = true;
        /**
         * Categories that are recorded. Removing a category stops it being written, which is the
         * supported way to reduce volume without losing the rest of the log.
         */
        public List<String> categories = List.of(
                "PLAYER", "MOVEMENT", "BLOCK", "CONTAINER", "COMMAND", "MODERATION", "PERMISSION",
                "ANTICHEAT", "ANTIDUPE", "CONFIG", "SECURITY", "SERVER");
        /** Record a player's own chat as an audit event. Off by default: it is the most sensitive. */
        public boolean recordChat = false;
        /** Days a non-security event is kept before retention deletes it. */
        public int retentionDays = 30;
        /**
         * Days a security event (a moderation action, a permission change, a protection trigger,
         * an authentication event) is kept. Security events are exempt from the ordinary sweep.
         */
        public int securityRetentionDays = 365;
        /** Most events held in memory for the live view before the oldest is spilled to disk. */
        public int memoryBuffer = 2_048;
        /** Most events accepted into the write queue before new ones are dropped and counted. */
        public int queueCapacity = 16_384;
        /** Most events a single search returns. */
        public int maxQueryResults = 1_000;
        /** Smallest number of events in one on-disk batch. */
        public int batchSize = 256;
        /** Seconds between two automatic flushes of the in-memory buffer. */
        public int flushIntervalSeconds = 15;
        /**
         * When true, events are written straight to the store rather than buffered. Useful for a
         * security-critical server at the cost of more disk writes; off by default.
         */
        public boolean writeThrough = false;
    }

    /**
     * Compressed storage settings.
     *
     * <p>Everything ADMIN persists grows without bound unless it is pruned, so the store keeps
     * recent data uncompressed and quick to search and compresses it once it is old enough to be
     * read rarely. Compression runs on a background worker; it is never allowed on the server tick
     * thread.
     */
    public static final class Storage {
        /** Master switch for automatic compression. Retention still applies when off. */
        public boolean enabled = true;
        /** Preferred algorithm: {@code zstd}, {@code gzip} or {@code none}. */
        public String algorithm = "zstd";
        /** Requested compression level, clamped to the chosen codec's range. */
        public int level = 3;
        /** Seconds between two automatic compression passes. */
        public int compressIntervalSeconds = 300;
        /** Minimum age, in hours, before a record is eligible for compression. */
        public int minArchiveAgeHours = 6;
        /** Most decompressed bytes a single archive may claim, so a bad header cannot exhaust memory. */
        public int maxDecompressBytes = 64 * 1024 * 1024;
        /** Most worker threads doing compression. */
        public int workerThreads = 1;
        /** Most records queued for compression before the pass is paused. */
        public int maxQueue = 4_096;
        /** Smallest number of records combined into one archive. */
        public int batchSize = 128;
        /** Most decompressed records kept in the hot cache. */
        public int cacheEntries = 256;
        /** Most bytes the hot cache may hold. */
        public int cacheMaxBytes = 32 * 1024 * 1024;
        /** Target size of one rotated archive file, in bytes. */
        public long maxArchiveBytes = 64L * 1024 * 1024;
        /** Most archive files kept before the oldest is removed. */
        public int maxArchives = 64;
        /** Whether identical records are stored once, which is what makes deduplication safe. */
        public boolean deduplicate = true;
        /** Identical records seen inside this window are stored once, in milliseconds. */
        public long dedupeWindowMillis = 250L;
        /**
         * When true, a compression pass is skipped while the server is severely lagging. The pass
         * resumes on the next interval once the lag clears.
         */
        public boolean pauseDuringLag = true;
        /** MSPT above which a running compression pass pauses. */
        public double pauseMsptThreshold = 60.0;
    }

    /**
     * Configuration backup settings.
     *
     * <p>ADMIN rewrites the configuration files on migration and on a bulk change, and a rewrite is
     * the one moment a hand-edited file could be lost. A backup is taken first so that never
     * happens silently, and an optional schedule keeps a recent copy on hand regardless.
     */
    public static final class Backups {
        /** Master switch for automatic configuration backups. */
        public boolean enabled = true;
        /** Hours between scheduled backups. Zero disables the schedule. */
        public int intervalHours = 24;
        /** Most backup files to keep before the oldest is removed. */
        public int maxBackups = 20;
        /** Copy the configuration aside before an upgrade rewrites it. */
        public boolean backupBeforeMigration = true;
        /** Copy the configuration aside before a profile import is applied. */
        public boolean backupBeforeImport = true;
    }

    /**
     * Scheduled maintenance tasks.
     *
     * <p>These are the jobs that would otherwise have to be run by hand: sweeping expired data,
     * compressing old records and taking a backup on start. They run on the server tick scheduler,
     * so they are never on a request thread.
     */
    public static final class Automation {
        /** Master switch for the scheduled tasks. */
        public boolean enabled = true;
        /** Sweep expired data on the cleanup interval. */
        public boolean autoCleanupEnabled = true;
        /** Minutes between two cleanup sweeps. */
        public int autoCleanupIntervalMinutes = 60;
        /** Take a configuration backup once on server start. */
        public boolean autoBackupOnStart = false;
        /** Run the audit store's compression pass on its interval. */
        public boolean autoCompressEnabled = true;
        /** Warn in the dashboard when a change needs a restart to take effect. */
        public boolean restartRequiredNotice = true;
    }

    /**
     * Where ADMIN sends its own alerts.
     *
     * <p>These are separate from the chat bridge: the bridge forwards player chat, while a
     * notification reports something ADMIN itself noticed (a protection trigger, a moderation
     * action, an authentication failure, a performance alert).
     */
    public static final class Notifications {
        /** Master switch for notifications. */
        public boolean enabled = false;
        public boolean onProtectionTrigger = true;
        public boolean onModerationAction = true;
        public boolean onAuthFailure = true;
        public boolean onPerformanceAlert = true;
        /** Only notify for events at or above this severity: info, notice, warning, error, critical. */
        public String minSeverity = "warning";
        /** Show notifications in Minecraft chat for online staff. */
        public boolean toChat = true;
        /** Send notifications to the chat bridge's external platform. */
        public boolean toBridge = false;
        /** Minimum gap between two notifications of the same kind, in seconds. */
        public long quietSeconds = 60L;
    }
}