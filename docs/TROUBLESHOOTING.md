# Troubleshooting guide

Start here: run `/adminmod status` in game or on the server console, and open the **Chat Bridge**
and **Settings** tabs in the dashboard. They report the state of every subsystem and the last
error, without revealing any credential value.

## Build problems

### The build fails resolving Fabric Loom or a toolchain

The first build needs network access; Loom resolves toolchains through the Foojay resolver. A
build run with `--offline` fails for this reason. Run it online:

```bash
./gradlew clean build
```

If your network uses a proxy, configure it for Gradle in `~/.gradle/gradle.properties`.

### `check` fails with "Server classes reference client-only classes"

The server distribution must never reference `net.minecraft.client`. This check exists so a
mistake is caught at build time instead of crashing a dedicated server. The failure lists the
offending class files. Move the client-only code into the `client` module.

## Server startup

### The server crashes at startup with a class-not-found for a client class

A client class leaked into the server JAR. Run `./gradlew clean buildServer`; the `check` task
will identify it. Do not run the client JAR on a server.

### The credential folder is not created

The server could not write to its run directory. Check the process user's permissions, then start
the server again.

### The mod does not load

Confirm the JAR is in `mods/` alongside a matching Fabric API, and that both are for 1.21.1.
Check `latest.log` for a Fabric loader error naming the mod.

## Chat bridge

### Chat does not reach Discord, Matrix or the webhook

1. Run `/adminmod bridge`. It prints the provider, queue depth, delivered and failed counts, and
   the last error.
2. Confirm `chatBridge.enabled` is `true` and `provider` names the integration you configured
   (`discord`, `matrix` or `generic`).
3. Confirm the destination details: webhook URL, room ID and token, or webhook endpoint.
4. Confirm the server can reach the destination host (firewall, DNS, outbound HTTPS).

### HTTP 429 from the platform

Rate limited. The dispatcher backs off and retries automatically. If it happens constantly, lower
the chat volume or split it across channels.

### HTTP 401 or 403

The credential is wrong or revoked, or the bot lacks permission. Recreate the webhook or token,
and for Matrix confirm the bot is joined to the room.

### Chat appears in Discord but not in Minecraft

The inbound direction is a separate switch. Set `chatBridge.inbound.enabled` to `true` and the
per-platform flag too. For Discord, enable the **Message Content Intent** in the developer portal.

### Messages loop between Minecraft and the platform

The bridge suppresses loops in several ways: it never forwards a line that came from a platform,
it ignores Discord bot and webhook messages, it ignores Matrix events from the configured bot
MXID, and it de-duplicates identical inbound messages within `dedupeWindowMillis`. If you still
see a loop:

- Check that no second relay bot is echoing the channel.
- Check that `forwardCommands` is `false`.
- Check that only one server process is running the mod.
- For Matrix, check `userId` matches the bot's MXID exactly.

### Commands appear in the platform channel

Command execution is not forwarded by default. If commands are appearing, `forwardCommands` has
been set to `true`; set it back to `false`. Note that the server echoes command results to chat,
and the bridge recognises and drops that echo.

### A player pings everyone on Discord

The provider always sends `allowed_mentions: {"parse": []}` and neutralises `@` in names and
messages, so this cannot happen through the bridge. If you see it, the message did not come from
this mod.

## Remote administration

### The dashboard says the server is not running the mod

The server JAR is not installed, or the server is older than the client's protocol version.
Install or update the server JAR.

### The dashboard cannot connect

The client and server protocol versions differ. Update both JARs together; the handshake refuses a
mismatched pair on purpose.

### Authentication fails

- Re-read the password from the credential folder; it does not change between restarts.
- Confirm the client is connected to the right server.
- If the vault was moved or deleted, the server generates a new password on next start.

### The console refuses commands

- Only `remoteAdmin.ownerUuid` may use the console and OP management. Confirm it matches your
  account, or clear it to let the first operator claim ownership.
- Check `remoteAdmin.allowRemoteConsole` is `true`.
- Check you have not hit `consoleRequestsPerMinute`; wait a minute and retry.
- Confirm `remoteAdmin.enabled` is `true`.

### OP changes do not take effect

OP changes are applied immediately. If nothing changes, confirm `allowOpManagement` is `true` and
that you are authenticated as the owner. Check the server console for the outcome.

### Requests are rejected as replays

A request was repeated or arrived out of order. This is the replay protection working. Reconnect
the dashboard to start a fresh session with a new nonce.

## Configuration

### My configuration was ignored or reset

The config is parsed leniently: blank credentials disable the affected integration, and
out-of-range numbers fall back to defaults. A value that cannot be parsed at all is replaced by
the default. Check the server log for a config warning naming the field.

### Secrets appear in a file I did not expect

Credentials live only in `config/admin-mod.json` and, for the vault password, in the credential
folder. They are never written to the server log or to chat. If you find a secret somewhere else,
it did not come from this mod.

## Getting more information

- `/adminmod status` — subsystem state
- `/adminmod bridge` — bridge counters and last error
- `/adminmod vault` — the vault directory and whether a credential exists (never the password)
- `/adminmod reload` — re-read `config/admin-mod.json`
- `logs/latest.log` — server log, with credentials redacted
- **Chat Bridge** and **Settings** tabs — the same information in the dashboard
