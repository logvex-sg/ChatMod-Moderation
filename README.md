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
13. [Remote administration](#13-remote-administration)
14. [Security model](#14-security-model)
15. [Performance](#15-performance)
16. [Testing](#16-testing)
17. [Troubleshooting](#17-troubleshooting)
18. [Project layout](#18-project-layout)

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

### Remote administration

- Six-tab dashboard in the client: **Console, Players, OP Management, Moderation, Chat Bridge,
  Settings**.
- Server-side command execution through the mod, with no command echo forwarded to the bridge.
- OP grant, revoke, status and list, applied immediately.
- Password-vault authentication, owner authorization, replay protection and rate limiting.

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

`config/admin-mod.json` is created with defaults on first run. A representative file:

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
  "logging": {
    "level": "info"
  }
}
```

Credentials are validated and clamped on load: blank secrets disable the affected integration
instead of failing the server, and out-of-range numbers fall back to safe defaults.

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

## 13. Remote administration

Open the dashboard in-game with the configured key (default **`]`**).

| Tab | Purpose |
| --- | --- |
| **Console** | Run server commands remotely. Output is shown in a scrollable terminal. Command output is not forwarded to the chat bridge and is not written to either side's log. |
| **Players** | Online player list with UUID and username, details, kick, mute, ban and unban. |
| **OP Management** | Grant, revoke, check and list operators, applied immediately. |
| **Moderation** | History, warnings, notes and punishment management. |
| **Chat Bridge** | Connection status, active integrations, inbound reader state and recent delivery errors. |
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

---

## 14. Security model

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

## 15. Performance

- All outbound HTTP happens on a dedicated dispatcher thread with a **bounded queue**; a full
  queue drops the oldest entry rather than stalling the tick.
- Every request has a timeout, and retries use **exponential backoff** with a retry policy.
- Inbound readers run on their own daemon threads. Broadcasts are scheduled onto the main thread
  with `MinecraftServer.execute`, so a reader never blocks the tick.
- Connections, threads and queues are closed on server stop for a clean shutdown.

---

## 16. Testing

```bash
./gradlew test
```

The suite covers, among other things:

- Wire-format round trips and framing, including malformed input.
- Provider payload construction, header handling and error redaction.
- Config loading, clamping, defaults and redaction.
- Moderation service behaviour and permission decisions.
- Inbound formatting, sanitisation, de-duplication and receiver shutdown.
- **End-to-end Discord Gateway test** against a real local WebSocket server, which exercises the
  handshake, IDENTIFY frame and dispatch parsing.

A dedicated-server smoke test (`./gradlew :server:runServer`) boots a real 1.21.1 server, writes
the config and vault, and reaches `Done!`.

---

## 17. Troubleshooting

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

---

## 18. Project layout

```
common/         config model, wire format, packets, crypto helpers
integrations/   Discord, Matrix and webhook providers, inbound readers, dispatcher
server/         Fabric dedicated-server mod: moderation, chat bridge, remote admin endpoint
client/         Fabric client mod: dashboard GUI and networking
buildSrc/       build helpers (jar scanning)
```

---

## License

MIT. See [LICENSE](LICENSE).
