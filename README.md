# ADMIN – Advanced Minecraft Moderation and Chat Control

A production-quality Fabric mod for **Minecraft 1.21.1** (Java 21) that adds moderation tools,
remote administration and a two-way chat bridge to Discord, Matrix and generic webhooks.

The project ships as **two independent JARs** that share one wire protocol:

| Artifact | Environment | Contents |
| --- | --- | --- |
| `build/libs/ADMIN-Server.jar` | dedicated server / LAN host | chat bridge, moderation, remote admin endpoint, command execution, OP management, authentication. No GUI. |
| `build/libs/ADMIN-Client.jar` | client | administration dashboard: console, players, OP management, moderation, chat bridge status, settings. No privileged execution logic, no credentials. |

---

## Table of contents

1. [Features](#1-features)
2. [Architecture](#2-architecture)
3. [Requirements](#3-requirements)
4. [Building](#4-building)
5. [Installation](#5-installation)
6. [First-run setup and authentication](#6-first-run-setup-and-authentication)
7. [Configuration reference](#7-configuration-reference)
8. [Chat bridge](#8-chat-bridge)
9. [Discord setup](#9-discord-setup)
10. [Matrix setup](#10-matrix-setup)
11. [Generic webhook setup](#11-generic-webhook-setup)
12. [Moderation](#12-moderation)
13. [Proximity chat](#13-proximity-chat)
14. [Remote administration](#14-remote-administration)
15. [Security model](#15-security-model)
16. [Performance](#16-performance)
17. [Testing](#17-testing)
18. [Troubleshooting](#18-troubleshooting)
19. [Project layout](#19-project-layout)

---

## 1. Features

### Public chat bridge (outbound, Minecraft → platform)

- Forwards **public player chat** to Discord (webhook), Matrix (client-server API) or any HTTP
  webhook.
- Configurable message format, Discord embeds, avatar, custom username, per-request headers and
  payload templates.
- Only public chat is forwarded. Private messages and command execution are **off by default**
  and can be re-enabled only deliberately in the configuration.
- Asynchronous, bounded queue with retry and exponential backoff. The server tick thread never
  performs network I/O.

### Chat bridge (inbound, platform → Minecraft)

- Reads messages from Discord over the Gateway WebSocket and from Matrix over the client-server
  `sync` API, then shows them in Minecraft chat.
- Configurable line format, message length cap and duplicate-suppression window.
- Loop prevention on both sides: bot messages and webhook messages are ignored on Discord, the
  bot's own MXID is ignored on Matrix, and a line that was shown because it came from a platform
  is never forwarded back out.

### Moderation

- Warnings, notes, kicks, mutes (temporary and permanent), bans (temporary and permanent),
  pardons and a full per-player history.
- Every action is recorded in a protected server-side history file with the target UUID, staff
  identity, reason and timestamps.
- Configurable default and maximum durations, and an option to require a reason.

### Proximity ("local") chat

- When enabled, an ordinary chat message is delivered only to players near the sender **in the
  same dimension**; a message beginning with the configured prefix (`!` by default) is broadcast
  to everyone instead.
- The recipient set is computed server-side from positions the server already knows, so a modified
  client cannot widen its own reach.
- Staff with the configured permission level can hear every local message, so moderation stays
  possible.
- Per-dimension overrides (radius, formats, prefix, enable/disable) and an optional cooldown.
- Proximity messages are labelled `[Local]`/`[Global]` in the bridge payload, so a Discord or
  Matrix format can tell the two channels apart, and forwarding can be turned off independently.
- Settings live in `config/admin/localchat.json` and are editable from the client dashboard's
  **Local Chat** tab.

### Remote administration

- Eleven-tab dashboard in the client: **Console, Players, OP Management, Moderation, Chat Bridge,
  Local Chat, Diagnostics, Protection, Audit, Access, Settings**.
- Server-side command execution through the mod, with no command echo forwarded to the bridge.
- OP grant, revoke, status and list, applied immediately.
- Password-vault authentication, owner authorization, replay protection and rate limiting.

### Anti-crash and abuse protection

- Payload-shape validation that runs at the packet handler, before the vanilla logic: oversized
  chat, book pages, sign lines and custom payloads are cancelled before they allocate; clicks are
  checked against the open container's slot count and modified-stack cap; text carrying NUL or
  other control characters is rejected.
- Per-player rate limits for chat, commands, inventory clicks and entity interactions, plus a
  two-window packet-frequency monitor (a one-second burst alone is not flagged, only a sustained
  rate over both windows).
- Repeat-click detection for the identical-click signature a dupe attempt produces.
- Connection-level limiter: a per-address accept rate, a concurrent-connection cap and temporary
  restrictions, all swept so a flood of spoofed addresses cannot grow the table.
- A bounded violation log feeds the **Protection** dashboard tab.
- Every trigger is also mirrored to the durable **Audit** log, so a review survives a restart.

### Audit log

- One append-only log records the events that matter for an investigation: joins and leaves, deaths
  and respawns, block breaks, commands (by name only), moderation actions, permission changes,
  configuration changes, protection triggers, server lifecycle and — when explicitly enabled —
  public chat.
- Records are written by a background thread through a bounded queue, so a stalled disk never stalls
  the tick. The newest records stay uncompressed and quick to search; older ones are rolled into
  compressed, checksummed archives on a background worker.
- A **Audit** tab searches the log by free text, category, player and severity, shows the store's
  real health (archive count, disk usage, compression ratio, dropped and deduplicated records),
  exports a result to CSV or JSON, and can force a compression pass.
- Two privacy rules are enforced in code, not left to callers: a command is recorded by its name
  only (never its arguments, which routinely carry a password or a token), and chat is recorded only
  when `audit.recordChat` is on.

### Performance profiles and diagnostics

- Three selectable profiles — `performance`, `balanced`, `maximum` — rewrite the protection,
  anti-cheat and diagnostic thresholds. Every profile keeps the checks that only reject data no
  legitimate client can produce, so a profile never silently disables protection.
- A **Diagnostics** tab shows MSPT, TPS, ADMIN's own tick cost, heap use, queue depth, error
  counters and a rolling event history, and can export a credential-free JSON report.
- The **Audit** tab reports the audit store's own health, so a stalled or failing writer is visible
  rather than silent.
- Every collection ADMIN keeps is bounded from configuration, and a periodic sweep drops expired
  records rather than waiting for the bound to evict them.

---

## 2. Architecture

Four Gradle modules with a strict dependency direction:

```
common        no Minecraft dependency — config model, wire format, packets, crypto helpers
  └── integrations   no Minecraft dependency — Discord/Matrix/webhook providers, inbound readers
        └── server   Fabric dedicated-server mod (never references net.minecraft.client)
        └── client   Fabric client mod (GUI + networking only)
```

- `common` and `integrations` are plain JVM libraries. Their classes are folded into each
  distributable JAR, so no extra mod files are needed.
- `server` and `client` are separate Fabric mods with separate entrypoints and metadata.
- The server module is verified at build time to contain no reference to `net.minecraft.client`.

---

## 3. Requirements

- **Java 21** (JDK for building, JRE 21+ for running)
- **Minecraft 1.21.1**
- **Fabric Loader** 0.16.10 or newer
- **Fabric API** 0.116.17+1.21.1 (installed on both server and client)

---

## 4. Building

The build requires network access on the first run (the Fabric Loom plugin resolves toolchains
through the Foojay resolver).

```bash
./gradlew clean          # remove previous outputs
./gradlew build          # compile, test and package both distributions
./gradlew buildServer    # only ADMIN-Server.jar
./gradlew buildClient    # only ADMIN-Client.jar
./gradlew test           # unit and integration tests
```

Outputs:

```
build/libs/ADMIN-Server.jar
build/libs/ADMIN-Client.jar
```

`buildServer`/`buildClient` are aliases for the module `build` tasks; either form produces the
same artifacts. Sources JARs are produced alongside for convenience.

---

## 5. Installation

### 5.1 Dedicated server

1. Install Fabric Loader for Minecraft 1.21.1 on the server.
2. Put `fabric-api-<version>+1.21.1.jar` into the `mods/` folder.
3. Put `ADMIN-Server.jar` into the `mods/` folder.
4. Start the server once. It writes `config/admin-mod.json` and creates the credential folder
   (see [section 6](#6-first-run-setup-and-authentication)).
5. Edit `config/admin-mod.json` to configure the chat bridge and moderation, then restart or run
   `/adminmod reload`.

### 5.2 Client

1. Install Fabric Loader for Minecraft 1.21.1.
2. Put `fabric-api-<version>+1.21.1.jar` and `ADMIN-Client.jar` into the `mods/` folder.
3. Launch the game. The dashboard opens with the **`]`** key by default (rebindable under
   Controls → Key Binds → ADMIN Mod).

The client JAR contains no webhook credentials and no privileged execution logic; every action is
validated server-side.

---

## 6. First-run setup and authentication

On the first start the server generates a random password and stores it in a restricted folder:

```
<server run dir>/H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme/
├── credential.dat   # PBKDF2 verifier used to authenticate sessions
└── password.txt     # the generated password, readable only by the server process owner
```

- The folder and both files are created with owner-only permissions (`rwx------` / `rw-------`).
- The folder name is persisted into `remoteAdmin.vaultDirectory` so it stays stable across
  restarts. To relocate the vault, change that value to a different relative path.
- The password is displayed **only in the client mod**, in the authentication panel. The server
  never sends it to anyone unless `remoteAdmin.revealPasswordToOperators` is enabled (off by
  default), and it is never written to the server log.

To read the password as the server operator:

```bash
cat <server run dir>/H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme/password.txt
```

### Authorizing the owner

`remoteAdmin.ownerUuid` pins remote administration to one account. Leave it empty to let the
first operator who connects claim ownership, or set it explicitly:

```json
"remoteAdmin": { "ownerUuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5" }
```

Only the owner UUID may use the console and OP management. Administrators (players whose live
Minecraft permission level is at least `permissions.adminLevel`) may moderate and manage pairing;
moderators (at least `moderateLevel`, or named in `permissions.moderators`) may moderate.

### 6.1 Private client authorization (pairing)

The password is a bootstrap credential: anyone who reads it can authenticate. To bind access to
specific machines instead, pair each client. A paired client authenticates with its own secret,
and the shared password can then be switched off entirely.

**Pair a client**

1. On the server console (or as an authenticated owner in the **Access** tab), run:

   ```
   /adminmod pair
   ```

   The server prints a single-use key and its lifetime. Only a verifier of the key is stored, so
   copy it now — it cannot be shown again.

2. In the client dashboard open **Settings**, paste the key into the credential field and press
   **Pair this client**. The client redeems the key, receives a client secret, stores it under
   `config/adminmod/adminmod-client.json` (owner-only) and immediately re-authenticates with it.

3. From then on the client authenticates with the stored secret. **Settings → Use stored key**
   reconnects without retyping anything.

**Require pairing**

```json
"remoteAdmin": {
  "allowPasswordAuth": true,
  "requirePairedClient": false
}
```

Set `requirePairedClient` to `true` to refuse the shared password: only paired clients may then
authenticate. Set `allowPasswordAuth` to `false` to disable the password path explicitly.

**Manage paired clients** — the **Access** tab (owner or administrator) lists paired clients,
live sessions and the authentication history, and can:

- generate further pairing keys,
- **revoke** a client (its live sessions are closed immediately),
- **rotate** the operator password or a single client's secret, shown once.

**Key rotation and revocation**

| Credential | Rotate | Revoke |
| --- | --- | --- |
| Operator password | Access tab → *Rotate password*, or delete `credential.dat` and restart | set `allowPasswordAuth` / `requirePairedClient` |
| Client secret | Access tab → *Rotate* on the client row | Access tab → *Revoke*, or delete `config/adminmod-clients.json` |
| Pairing key | keys expire automatically (`pairingKeyTtlSeconds`) and are single use | — |

---

## 7. Configuration reference

The configuration lives under `config/admin/`, split one file per concern so a single edit does
not mean scrolling past the whole schema and a malformed file cannot take the others down:

| File | Sections |
| --- | --- |
| `general.json` | `profile`, `general`, `announcements`, `maintenance`, `logging` |
| `moderation.json` | `moderation`, `permissions` |
| `anticheat.json` | `anticheat` |
| `antidupe.json` | `antidupe` |
| `chatbridge.json` | `chatBridge` |
| `remoteadmin.json` | `remoteAdmin` |
| `performance.json` | `performance`, `alerts` |
| `protection.json` | `protection` |
| `audit.json` | `audit` |
| `storage.json` | `storage` |
| `localchat.json` | proximity chat (written by the proximity service, not the split loader) |

An existing single-file `admin-mod.json` is migrated once: it is read, split into the module files
and kept beside them as `admin-mod.json.migrated`. A file that cannot be parsed is moved aside as
`<name>.broken` and its defaults are used in memory, so one bad edit never stops the server. The
module files are written with owner-only permissions where the filesystem supports it.

A representative `chatbridge.json` (the shape of every file is the same: a JSON object keyed by
the section names it owns):

```json
{
  "chatBridge": {
    "enabled": true,
    "provider": "discord",
    "publicChatOnly": true,
    "forwardCommands": false,
    "forwardPrivateMessages": false,
    "forwardJoinLeave": true,
    "queueCapacity": 512,
    "maxAttempts": 5,
    "requestTimeoutMillis": 8000,
    "discord": {
      "webhookUrl": "",
      "username": "Minecraft Server",
      "avatarUrl": "",
      "useEmbeds": true,
      "messageFormat": "**%player%**: %message%",
      "embedColor": 5793266
    },
    "matrix": {
      "homeserver": "",
      "roomId": "",
      "accessToken": "",
      "messageFormat": "%player%: %message%"
    },
    "generic": {
      "url": "",
      "method": "POST",
      "payloadTemplate": "{\"player\":\"%player%\",\"message\":\"%message%\",\"timestamp\":\"%timestamp%\",\"server\":\"%server%\"}",
      "authHeader": "Authorization",
      "authToken": "",
      "headers": {},
      "contentType": "application/json"
    },
    "inbound": {
      "enabled": false,
      "format": "[%platform%] %user%: %message%",
      "discordLabel": "Discord",
      "matrixLabel": "Matrix",
      "dedupeWindowMillis": 5000,
      "maxMessageLength": 512,
      "discord": {
        "enabled": false,
        "botToken": "",
        "channelId": "",
        "guildId": "",
        "gatewayUrl": "wss://gateway.discord.gg/?v=10&encoding=json"
      },
      "matrix": {
        "enabled": false,
        "homeserver": "",
        "roomId": "",
        "accessToken": "",
        "userId": "",
        "syncTimeoutMillis": 30000,
        "syncLimit": 50
      }
    },
    "maxMessageLength": 1500,
    "stripFormatting": true,
    "blockedKeywords": [],
    "extraDiscordChannels": [],
    "extraMatrixRooms": [],
    "antiSpam": {
      "enabled": true,
      "duplicateWindowSeconds": 8,
      "maxDuplicates": 2,
      "minIntervalMillis": 250
    }
  },
  "moderation": {
    "enabled": true,
    "requireReasons": true,
    "broadcastPunishments": true,
    "notifyStaff": true,
    "announceJoinLeave": false,
    "defaultKickReason": "Kicked by a staff member",
    "defaultBanReason": "Banned by a staff member",
    "defaultMuteReason": "Muted by a staff member",
    "defaultTempBanSeconds": 86400,
    "defaultTempMuteSeconds": 3600,
    "maxPunishmentSeconds": 31536000,
    "escalation": {
      "enabled": false,
      "warnThreshold": 3,
      "windowSeconds": 604800,
      "muteSeconds": 86400,
      "reason": "Automatic mute: too many warnings"
    },
    "templates": [
      { "name": "mute-1h", "type": "MUTE", "seconds": 3600, "reason": "Disruptive behaviour" },
      { "name": "mute-1d", "type": "MUTE", "seconds": 86400, "reason": "Repeated disruptive behaviour" },
      { "name": "ban-1d", "type": "BAN", "seconds": 86400, "reason": "Serious rule violation" },
      { "name": "ban-perm", "type": "BAN", "seconds": 0, "reason": "Permanent removal" }
    ]
  },
  "remoteAdmin": {
    "enabled": true,
    "ownerUuid": "",
    "requireAuthentication": true,
    "requestsPerMinute": 120,
    "consoleRequestsPerMinute": 30,
    "allowRemoteConsole": true,
    "allowOpManagement": true,
    "revealPasswordToOperators": false,
    "sessionTimeoutSeconds": 900,
    "blockedCommands": ["stop", "restart"],
    "vaultDirectory": "H3h12n3jk12n3j123b12k312jhkh312v3h1gefrjkdnoip4235jk235k2nme",
    "allowPasswordAuth": true,
    "allowPairing": true,
    "requirePairedClient": false,
    "pairingKeyTtlSeconds": 900,
    "pairingKeyLength": 24,
    "clientSecretLength": 40,
    "allowedClientBuilds": [],
    "authFailureLimit": 5,
    "authLockoutSeconds": 300,
    "clientRegistryFile": "config/adminmod-clients.json",
    "authHistoryLimit": 200
  },
  "permissions": {
    "viewLevel": 0,
    "moderateLevel": 2,
    "adminLevel": 4,
    "moderators": []
  },
  "announcements": {
    "enabled": false,
    "intervalSeconds": 300,
    "messages": []
  },
  "maintenance": {
    "enabled": false,
    "message": "The server is under maintenance and will be back shortly.",
    "kickNonOperators": false,
    "allowList": []
  },
  "localChat": {
    "enabled": false,
    "radius": 50.0,
    "globalPrefix": "!",
    "localFormat": "[Local] &f%player%&7: &f%message%",
    "globalFormat": "[Global] &f%player%&7: &f%message%",
    "globalReachesAllDimensions": true,
    "staffBypass": true,
    "staffLevel": 2,
    "cooldownMillis": 750,
    "filterMessages": true,
    "forwardToBridge": true,
    "dimensions": []
  },
  "logging": {
    "level": "info"
  }
}
```

Credentials are validated and clamped on load: blank secrets disable the affected integration
instead of failing the server, and out-of-range numbers fall back to safe defaults.

### Optimisation profiles

`profile` (in `general.json`) is one of `performance`, `balanced` or `maximum`. Selecting a profile
rewrites the protection, anti-cheat, anti-dupe and diagnostics thresholds; the individual values
stay editable afterwards. Every profile keeps the checks that only reject data no legitimate client
produces, so a profile is a starting point rather than a switch that can turn protection off. The
**Diagnostics** tab can change it live.

The `protection`, `anticheat`, `antidupe`, `performance` and `alerts` sections carry the individual
limits — see the **Protection** tab for the live view of them.

Server commands: `/adminmod status`, `/adminmod bridge`, `/adminmod vault`, `/adminmod pair`,
`/adminmod clients`, `/adminmod reload`.

`/adminmod reload` re-reads the file and applies it live: the config object is updated in place,
the chat bridge is rebuilt (new provider, queue size, readers) and the announcement timer is
reset. A value the loader had to correct is reported back to the command sender.

Pairing and authentication settings are read live from the same config object, so changing
`requirePairedClient`, `authFailureLimit` or the lockout window and running `/adminmod reload`
takes effect without a restart.

### Anti-spam guard

The outbound guard exists so a player holding down a macro cannot flood the external channel or
burn the webhook rate limit for everyone else. It drops, rather than queues:

- an identical `(player, message)` pair repeated more than `maxDuplicates` times inside
  `duplicateWindowSeconds`;
- two messages from the same player closer together than `minIntervalMillis`.

### Warning escalation

When `moderation.escalation.enabled` is true, a player who accumulates `warnThreshold` active
warnings inside `windowSeconds` is muted automatically for `muteSeconds` with the configured
`reason`. The mute is recorded like any other punishment, so it appears in the history and can be
lifted with the normal unmute path. The rule never double-mutes: a player who is already muted is
left alone.

### Announcements and maintenance

- `announcements` rotates through `messages` in order, one every `intervalSeconds` (minimum 10).
  Each line is broadcast in chat, so the chat bridge forwards it too.
- `maintenance` shows `message` on join; with `kickNonOperators` true, anyone not on `allowList`
  and not holding `permissions.adminLevel` is disconnected.

### Audit and storage

`audit.json` controls what is recorded and how long it is kept:

```json
"audit": {
  "enabled": true,
  "categories": ["PLAYER", "MOVEMENT", "BLOCK", "CONTAINER", "COMMAND", "MODERATION",
                 "PERMISSION", "ANTICHEAT", "ANTIDUPE", "CONFIG", "SECURITY", "SERVER"],
  "recordChat": false,
  "retentionDays": 30,
  "securityRetentionDays": 365,
  "memoryBuffer": 2048,
  "queueCapacity": 16384,
  "maxQueryResults": 1000,
  "batchSize": 256,
  "flushIntervalSeconds": 15,
  "writeThrough": false
}
```

| Field | Meaning |
| --- | --- |
| `enabled` | Master switch. When off nothing is recorded. |
| `categories` | Categories that are recorded. Removing one stops it being written — the supported way to cut volume without losing the rest. `CHAT` and `STAFF` are not in the default set. |
| `recordChat` | Record public chat. Off by default: it is the most sensitive category. Private messages and commands are never chat. |
| `retentionDays` | Age at which an ordinary event is dropped. |
| `securityRetentionDays` | Age for a security event (moderation, permission, protection, authentication), which is exempt from the ordinary sweep. |
| `memoryBuffer` / `queueCapacity` | Bounds on the in-memory buffer and the write queue. A full queue drops and counts rather than growing. |
| `maxQueryResults` | Ceiling on how many events one search returns. |
| `batchSize` / `flushIntervalSeconds` | How the in-memory buffer is flushed to the store. |
| `writeThrough` | Write straight to the store instead of buffering. For a security-critical server at the cost of more disk writes. |

`storage.json` controls compression and the archive:

```json
"storage": {
  "enabled": true,
  "algorithm": "zstd",
  "level": 3,
  "compressIntervalSeconds": 300,
  "minArchiveAgeHours": 6,
  "maxDecompressBytes": 67108864,
  "workerThreads": 1,
  "maxQueue": 4096,
  "batchSize": 128,
  "cacheEntries": 256,
  "cacheMaxBytes": 33554432,
  "maxArchiveBytes": 67108864,
  "maxArchives": 64,
  "deduplicate": true,
  "dedupeWindowMillis": 250,
  "pauseDuringLag": true,
  "pauseMsptThreshold": 60.0
}
```

| Field | Meaning |
| --- | --- |
| `algorithm` | `zstd`, `gzip` or `none`. Zstandard is bundled in the server JAR; on a host where its native library cannot load the store transparently falls back to GZIP. |
| `level` | Requested compression level, clamped to the chosen codec's range. |
| `compressIntervalSeconds`, `minArchiveAgeHours` | How often a pass runs and how old a record must be before it is eligible. |
| `maxArchiveBytes`, `maxArchives` | Rotation bounds; the oldest archive is removed first. |
| `maxDecompressBytes` | Ceiling on a single archive's claimed decompressed size, so a bad header cannot exhaust memory. |
| `workerThreads`, `maxQueue`, `batchSize` | Compression worker pool and its bounded work queue. |
| `cacheEntries`, `cacheMaxBytes` | Bounds on the hot cache of decompressed records. |
| `deduplicate`, `dedupeWindowMillis` | Store identical records once inside the window. |
| `pauseDuringLag`, `pauseMsptThreshold` | Skip a pass while the server is lagging, and resume on the next interval. |

The audit directory is created owner-only, like the credential folder. Records are checksummed, so
a truncated or edited archive is detected and counted as corrupt rather than silently trusted.


---

## 8. Chat bridge

### What is forwarded

| Message type | Forwarded by default |
| --- | --- |
| Public player chat | yes |
| Join / leave notices | yes (`forwardJoinLeave`) |
| Private messages (`/msg`, `/tell`) | **no** |
| Command execution | **no** |
| Command output / echoes | **no** |
| Remote-console command output | **no** |
| Lines that arrived from a platform | **no** (loop prevention) |

A message is forwarded only when `chatBridge.enabled` is true and the text passes the filter.
Setting `forwardCommands` or `forwardPrivateMessages` to `true` opts in explicitly; neither is
recommended.

### How the bridge avoids loops

1. **Outbound suppression of inbound lines.** When a platform message is shown in chat, its exact
   line is remembered for a short window. The broadcast that follows is a game message, which the
   bridge recognises and does not forward back.
2. **Discord.** Messages authored by a bot, and messages carrying a `webhook_id`, are ignored —
   the latter is how this mod's own webhook posts return.
3. **Matrix.** Events whose sender is the configured bot MXID are ignored.
4. **Inbound de-duplication.** Identical `(platform, user, body)` triples seen inside
   `dedupeWindowMillis` are dropped, covering platform redelivery after a reconnect.

---

## 9. Discord setup

### 9.1 Outbound — post chat to a channel (webhook)

1. In Discord, open **Channel settings → Integrations → Webhooks → New Webhook**.
2. Choose the channel, then **Copy Webhook URL**.
3. Paste it into `chatBridge.discord.webhookUrl` and set `chatBridge.provider` to `"discord"`.
4. Optionally adjust `username`, `avatarUrl`, `useEmbeds`, `messageFormat` and `embedColor`.
5. Restart the server or run `/adminmod reload`.

A webhook URL is a secret: anyone who has it can post to the channel. It is stored only on the
server and never transmitted to the client.

### 9.2 Inbound — read chat from a channel (bot + Gateway)

A webhook can only post, so reading messages needs a bot and the Gateway WebSocket:

1. Create an application at <https://discord.com/developers/applications> and add a **Bot**.
2. Copy the **bot token** into `chatBridge.inbound.discord.botToken`.
3. Enable **Message Content Intent** under **Bot → Privileged Gateway Intents**. Without it
   Discord sends empty content for other users' messages.
4. Invite the bot to your server with the `View Channels` and `Read Message History` permissions.
5. Set `chatBridge.inbound.discord.channelId` to the channel to read. Leave it empty to read
   every channel the bot can see, or set `guildId` to restrict to one guild.
6. Set `chatBridge.inbound.enabled` and `chatBridge.inbound.discord.enabled` to `true`.

The reader ignores bot messages and webhook messages, so the outbound webhook posts do not come
back into Minecraft.

---

## 10. Matrix setup

Matrix uses the client-server API for both directions.

1. Create a bot account on your homeserver, for example `@adminbot:example.org`.
2. Obtain an access token for it, for example with:

   ```bash
   curl -sS -X POST https://matrix.example.org/_matrix/client/v3/login \
     -H 'Content-Type: application/json' \
     -d '{"type":"m.login.password","identifier":{"type":"m.id.user","user":"adminbot"},"password":"<password>"}'
   ```

   Use the `access_token` from the response.
3. Invite the bot to the room and note the room ID (`!something:example.org`).
4. For **outbound**, set:

   ```json
   "provider": "matrix",
   "matrix": {
     "homeserver": "https://matrix.example.org",
     "roomId": "!something:example.org",
     "accessToken": "<token>"
   }
   ```

5. For **inbound**, set `chatBridge.inbound.matrix.homeserver`, `roomId`, `accessToken`, the
   bot's own `userId` (`@adminbot:example.org`) and enable both the global and Matrix inbound
   switches.

The homeserver URL must use `https://`. The access token is sent in the `Authorization: Bearer`
header and never appears in a query string.

---

## 11. Generic webhook setup

Any HTTP endpoint can receive chat:

```json
"provider": "generic",
"generic": {
  "url": "https://example.com/hooks/minecraft",
  "method": "POST",
  "payloadTemplate": "{\"player\":\"%player%\",\"message\":\"%message%\",\"timestamp\":\"%timestamp%\",\"server\":\"%server%\"}",
  "authHeader": "Authorization",
  "authToken": "Bearer <token>",
  "headers": { "X-Source": "minecraft" },
  "contentType": "application/json"
}
```

- `%player%`, `%message%`, `%timestamp%` and `%server%` are substituted into the template; values
  are escaped for JSON.
- `headers` adds extra request headers.
- `authHeader` and `authToken` add one authentication header; leave `authToken` empty to omit it.

---

## 12. Moderation

Actions are available from the **Players** and **Moderation** tabs, and every one is written to
`config/adminmod-history.json`:

| Action | Effect |
| --- | --- |
| Warn | Records a warning against the player. |
| Note | Attaches a free-form staff note. |
| Kick | Disconnects the player. |
| Mute / Temp mute | Blocks chat for the configured or specified duration. |
| Ban / Temp ban | Bans by UUID for the configured or specified duration. |
| Unmute / Unban / Pardon | Reverses a punishment by id. |

- Players are identified by UUID, so a name change does not evade a punishment.
- Durations are clamped to `moderation.maxPunishmentSeconds`.
- When `moderation.requireReasons` is true, an action without a reason is rejected.
- `broadcastPunishments` controls whether a punishment is announced in chat.

---

## 13. Proximity chat

Proximity chat makes ordinary chat local: a message is delivered only to players near the sender
in the same dimension. A message beginning with the configured prefix is broadcast to everyone
instead. The feature is off until `localChat.enabled` is set.

```json
"localChat": {
  "enabled": false,
  "radius": 50.0,
  "globalPrefix": "!",
  "localFormat": "[Local] &f%player%&7: &f%message%",
  "globalFormat": "[Global] &f%player%&7: &f%message%",
  "globalReachesAllDimensions": true,
  "staffBypass": true,
  "staffLevel": 2,
  "soundEnabled": false,
  "soundId": "minecraft:entity.experience_orb.pickup",
  "cooldownMillis": 750,
  "showRecipientCount": false,
  "noRecipientsMessage": "&7Nobody is close enough to hear you.",
  "filterMessages": true,
  "forwardToBridge": true,
  "dimensions": [
    { "dimension": "minecraft:the_nether", "enabled": true, "radius": 16.0,
      "localFormat": "", "globalFormat": "", "globalPrefix": "",
      "globalReachesAllDimensions": null }
  ]
}
```

The same settings can live in the standalone `config/admin/localchat.json`, which is written with
owner-only permissions on first start. When that file exists it is the source of truth; otherwise
the `localChat` section of `admin-mod.json` is used.

| Field | Meaning |
| --- | --- |
| `enabled` | Master switch. When off, chat behaves exactly as vanilla. |
| `radius` | Delivery radius for a local message, in blocks. |
| `globalPrefix` | A message starting with this is sent globally instead of locally. |
| `localFormat` / `globalFormat` | Rendered lines. Placeholders: `%player%`, `%message%`, `%radius%`, `%count%`, `%world%`, `%channel%`. Colour codes use `&`. |
| `globalReachesAllDimensions` | When true a global message reaches every player; when false only the sender's dimension. |
| `staffBypass` / `staffLevel` | Staff at or above this permission level hear every local message. |
| `soundEnabled`, `soundId`, `soundVolume`, `soundPitch` | Optional receipt sound. |
| `cooldownMillis` | Minimum gap between one player's messages. `0` disables. |
| `showRecipientCount` / `noRecipientsMessage` | Feedback to the sender about who heard them. |
| `filterMessages` | Applies the moderation keyword filter and the bridge anti-spam guard to local chat. |
| `forwardToBridge` | Forwards local and global messages to Discord/Matrix/webhook with their channel tag. |
| `dimensions` | Per-dimension overrides; the first match wins, and a blank field inherits the global value. |

How it behaves:

- **Delivery is server-authoritative.** The recipient set is computed from the positions and
  dimensions the server already knows. Nothing a client sends can widen its own reach.
- **The sender always hears their own line**, so their client shows it once with the same
  decoration everyone else sees.
- **Global messages** reach everyone (or everyone in the sender's dimension, if
  `globalReachesAllDimensions` is false). A per-dimension rule can override that for one world.
- **Commands are never turned into chat.** A message that is a command is left to vanilla.
- **Mutes still apply.** A muted player's message is refused here as well as in the vanilla
  broadcast path.
- **The bridge sees the channel.** A forwarded proximity message carries `%channel%` (`LOCAL` or
  `GLOBAL`) and `%channel_label%` (`Local`/`Global`), so a Discord or Matrix format can label it.
  Ordinary public chat has an empty label.
- **Editing live:** the client dashboard's **Local Chat** tab reads the current state and writes
  changes through the same authenticated order channel as every other tab.

## 14. Remote administration

Open the dashboard in-game with the configured key (default **`]`**).

| Tab | Purpose |
| --- | --- |
| **Console** | Run server commands remotely. Output is shown in a scrollable terminal. Command output is not forwarded to the chat bridge and is not written to either side's log. |
| **Players** | Online player list with UUID and username, details, kick, mute, ban and unban. |
| **OP Management** | Grant, revoke, check and list operators, applied immediately. |
| **Moderation** | History, warnings, notes and punishment management. |
| **Chat Bridge** | Connection status, active integrations, inbound reader state and recent delivery errors. |
| **Local Chat** | Proximity settings: enable, radius, prefix, formats, staff bypass, sound, cooldown and per-dimension overrides. Reads live state from the server and writes changes back. |
| **Protection** | Live anti-crash thresholds, counters and recent triggers. |
| **Audit** | Search the durable audit log; store health, CSV/JSON export and a manual compression pass. |
| **Access** | Pairing keys, paired-client list, live sessions, authentication history, credential rotation. |
| **Settings** | Authentication state, connection details, redacted server settings and safe toggles. |

### Protocol

Custom Fabric packets carry one frame type in each direction:

- **Client → server:** console orders, moderation orders, player-information orders, OP management
  orders and configuration requests.
- **Server → client:** command confirmations, player information, status, connection status and
  GUI synchronisation.

Every request carries a request id and is validated server-side before it runs.

### Authentication modes

The handshake names one of three modes; the server advertises which it accepts and the mode is
bound into the session-key derivation, so a transcript from one mode can never be replayed in
another.

| Mode | Credential | Use |
| --- | --- | --- |
| `PASSWORD` | the shared operator password from the vault | bootstrap; disabled when `requirePairedClient` is set |
| `PAIRING_KEY` | a single-use, time-limited key from `/adminmod pair` | enrol a new machine |
| `CLIENT_SECRET` | the secret issued at pairing, stored on the client | normal reconnection |

### Security controls

- The authenticated Minecraft player UUID is the identity; there is no separate login.
- A credential (password, pairing key or client secret) must be presented during the handshake;
  the first frame that decrypts under the derived key is the proof.
- Only `remoteAdmin.ownerUuid` (or the first operator to claim ownership) may use the console and
  OP management; administrators and moderators are bounded by their role.
- **Replay protection:** each frame carries a fresh 64-bit nonce and a timestamp. A repeated nonce
  or one outside the clock-skew window is rejected.
- **Rate limiting:** `requestsPerMinute` applies to all requests, with a stricter
  `consoleRequestsPerMinute` for command execution, plus a client-side ceiling.
- **Lockout:** `authFailureLimit` failed authentications from one address lock it out for
  `authLockoutSeconds`.
- **Build gate:** when `allowedClientBuilds` is non-empty, a client whose build id is not listed is
  refused at the handshake. This is a compatibility gate, never an authorization decision.
- Every request is re-checked against the permission model server-side; the client's claims are
  never trusted.

There are no hidden backdoors and no administrative path that bypasses these checks.

### Audit log

The **Audit** tab is a search over the server's own durable log, not a second copy. It offers:

- A free-text box matched case-insensitively against actor, subject, category, severity and detail
  together, so a search for an item id, a block id or an address finds the event.
- A category box (`player`, `block`, `container`, `command`, `moderation`, `permission`,
  `anticheat`, `config`, `security`, `server`, `chat`) and a player box (name or UUID).
- A store card with the live archive count, disk usage, compression ratio and the dropped and
  deduplicated record counters, so a failing writer is visible.
- **Export CSV** / **Export JSON**, which write the current search to `config/admin/audit-exports/`
  on the server, and **Compress now**, which forces a pass.

A command is stored by its name only. `detailValue("command")` returns e.g. `login` for
`/login hunter2`; the arguments are dropped before the record is built, so no secret can reach disk
through the audit path. The audit log never carries a private message, a credential or a token.

---

## 15. Security model

- **Credentials stay server-side.** Webhook URLs, bot tokens, Matrix access tokens, the vault
  password and paired-client secrets are never sent to a client, and never written to the log. The
  dashboard reports only whether a value is configured.
- **Only verifiers are stored.** The password, each pairing key and each client secret is reduced
  to a PBKDF2 verifier before it is written; `credential.dat`, `password.txt` and
  `adminmod-clients.json` hold nothing that can be replayed.
- **Single-use pairing keys.** A pairing key is consumed the first time it is redeemed and expires
  after `pairingKeyTtlSeconds`; it can never enrol a second client.
- **Restricted file permissions.** The credential folder is owner-only, as are the files inside and
  the client's own `adminmod-client.json`.
- **No secrets in chat.** Only public chat is forwarded, and inbound lines are stripped of
  control characters and section signs so a platform user cannot inject a colour code.
- **Least privilege.** Roles gate every order: viewer for read-only, moderator for moderation,
  administrator for pairing management, owner for console and OP management.
- **No client-side authority.** The client JAR holds no execution logic and no server secret; it
  sends orders and displays responses.

---

## 16. Performance

- All outbound HTTP happens on a dedicated dispatcher thread with a **bounded queue**; a full
  queue drops the oldest entry rather than stalling the tick.
- Every request has a timeout, and retries use **exponential backoff** with a retry policy.
- Inbound readers run on their own daemon threads. Broadcasts are scheduled onto the main thread
  with `MinecraftServer.execute`, so a reader never blocks the tick.
- Connections, threads and queues are closed on server stop for a clean shutdown.

---

## 17. Testing

```bash
./gradlew test
```

The suite covers, among other things:

- Wire-format round trips and framing, including malformed input.
- Provider payload construction, header handling and error redaction.
- Config loading, clamping, defaults and redaction.
- Moderation service behaviour and permission decisions.
- Inbound formatting, sanitisation, de-duplication and receiver shutdown.
- Proximity routing: radius and dimension rules, the staff bypass, global reach, the cooldown
  tracker, format parsing and colour handling, and the settings file.
- Protection primitives: payload-shape validation, the per-key and per-address rate limiters, the
  connection limiter's restriction window, repeat-click detection and the two-window packet-rate
  monitor, plus the decision layer that routes each check and records a violation.
- Modular configuration: file creation, per-module round trips, migration of the legacy file and
  quarantine of a malformed one; and the optimisation profiles.
- Bounded cache behaviour: TTL expiry, LRU eviction, sweeping and hit accounting.
- Audit and storage: category and severity routing, the command-name-only rule, the chat opt-in,
  the violation-log mirror, query matching, retention windows, the compressed archive round trip
  (including the corruption path), deduplication and the store's health counters.
- **End-to-end Discord Gateway test** against a real local WebSocket server, which exercises the
  handshake, IDENTIFY frame and dispatch parsing.
- **End-to-end webhook delivery** against a real loopback HTTP server: a public chat message is
  rendered, sent over a real socket, and the dispatcher's retry, rate-limit (`Retry-After`) and
  non-retryable-error paths are observed through the delivery counters.

A dedicated-server smoke test (`./gradlew :server:runServer`) boots a real 1.21.1 server, writes
the config and vault, and reaches `Done!`. The distributed `ADMIN-Server.jar` has also been run on
a stock Fabric 1.21.1 dedicated server (fabric-loader 0.16.10) with only Fabric API alongside it:
the mod loads, both mixins apply, the per-module config is written, and the server reaches `Done`.

---

## 18. Troubleshooting

**Chat does not appear on Discord/Matrix.**
Check `chatBridge.enabled` is true and `provider` names the integration you configured. Run
`/adminmod bridge` or open the **Chat Bridge** tab to see delivery counters and the last error.
Confirm the webhook URL / room id / token is correct, and that the destination is reachable from
the server.

**Inbound messages do not appear in Minecraft.**
Both `chatBridge.inbound.enabled` and the per-platform `enabled` flag must be true. For Discord,
the **Message Content Intent** must be enabled in the developer portal, or content arrives empty.
Check the **Chat Bridge** tab's inbound panel for the reader state and its last error.

**`[Discord]` messages loop back to Discord.**
They should not: lines shown from a platform are suppressed for 10 seconds. If you see a loop,
check you have not enabled `forwardCommands` and that only one process is running the mod.

**"not configured" on the dashboard.**
The relevant credential is empty. The dashboard reports configuration state, never values.

**Remote console refuses commands.**
Only the owner UUID may use it. Confirm `remoteAdmin.ownerUuid` matches your account, that
`allowRemoteConsole` is true, and that you have authenticated with the vault password. Rate
limiting may also be in effect; check `consoleRequestsPerMinute`.

**The client cannot connect to the server.**
Both sides must run the same protocol version. Update both JARs together.

**The credential folder is missing.**
It is created on first server start. Ensure the server process can write to its run directory.

**Proximity chat is on but everyone hears everything.**
Check that `localChat.enabled` is true and that no per-dimension rule disables the world. A message
beginning with `globalPrefix` (`!` by default) is meant to reach everyone. Verify the radius and
that `globalReachesAllDimensions` is not being used as a workaround.

**A player is not hearing nearby chat.**
They must be in the same dimension as the sender and within `radius` blocks. Check the per-dimension
rule for that world: a rule can shrink the radius or disable the feature entirely. A staff member
with the bypass on always hears it, so test with a non-staff account.

**Local chat is not reaching Discord/Matrix.**
Set `localChat.forwardToBridge` to true. The channel is available to the format as `%channel%` and
`%channel_label%`; ordinary public chat has an empty label.

**The Audit tab shows no events.**
Check `audit.enabled` is true and that the category you are looking for is still listed in
`audit.categories`. A blank search box browses the most recent events; a category that was removed
from the list is not recorded at all. If the store card shows a rising `dropped` counter the write
queue is full — raise `audit.queueCapacity` or lower `storage.compressIntervalSeconds` so archives
are written sooner.

**The audit log falls back to GZIP.**
The `algorithmAvailable` field on the store card is false. The Zstandard native library could not
load on this host, so the store is using the always-present GZIP codec. This is safe; set
`storage.algorithm` to `"gzip"` to silence the notice.

**A command I ran does not appear in the audit log.**
Only the command name is recorded, and only when `COMMAND` is an enabled category. Arguments are
never written, by design.

---

## 19. Project layout

```
common/         config model, wire format, packets, crypto helpers, proximity rules,
                anti-crash protection, bounded caches, diagnostics primitives,
                audit log and compressed storage
integrations/   Discord, Matrix and webhook providers, inbound readers, dispatcher
server/         Fabric dedicated-server mod: moderation, chat bridge, proximity delivery,
                protection mixins, performance monitor, audit manager, remote admin endpoint
client/         Fabric client mod: dashboard GUI and networking
buildSrc/       build helpers (jar scanning)
```

---

## License

MIT. See [LICENSE](LICENSE).
