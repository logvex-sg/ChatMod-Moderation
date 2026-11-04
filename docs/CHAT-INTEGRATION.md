# Chat integration explained

The chat bridge connects Minecraft chat with an external platform. It has two directions, and
they are configured separately:

- **Outbound (Minecraft → platform):** a public chat message is posted to Discord, Matrix or a
  generic webhook.
- **Inbound (platform → Minecraft):** a message on the platform is shown in Minecraft chat.

The outbound direction is a single funnel. The inbound direction is a set of readers, one per
platform, feeding a single funnel. Both funnels are in the `integrations` module and contain no
Minecraft code, so they can be tested on a plain JVM.

## Outbound path

```
ServerMessageEvents.ALLOW_CHAT_MESSAGE
        │  (server tick thread, inside PlayerManager.broadcast)
        ▼
LocalChatService.dispatch       proximity routing, when the feature is on for the sender's dimension
        │  returns false to take the message over, so vanilla does not also deliver it
        │  forwards it to the bridge itself with its channel tag
        ▼
ServerMessageEvents.CHAT_MESSAGE
        │  (server tick thread) — ordinary public chat that was not taken over
        ▼
ChatFilter.shouldForward        policy: public chat only, no commands, no private messages
        │
        ▼
TextSanitizer.clean             strip colour codes and control characters, then truncate
        │
        ▼
AntiSpamGuard.accept            per-player minimum gap and duplicate cap
        │
        ▼
ChatDispatcher.enqueue          bounded queue, oldest dropped when full
        │  (returns immediately — the tick thread never does network I/O)
        ▼
   dispatcher worker thread
        │
        ▼
ChatProvider.send               Discord / Matrix / generic webhook
        │
        ▼
   HTTP request with a timeout
```

Design points:

- The tick thread only runs a policy check, a sanitisation pass and an enqueue. It never opens a
  socket.
- The queue is bounded. When it is full the oldest entry is dropped, so a slow or unreachable
  platform cannot grow memory without limit or stall the server.
- Retries use exponential backoff up to `chatBridge.maxAttempts`, after which the message is
  recorded as failed and surfaced on the dashboard.
- The anti-spam guard is keyed per player, so one player holding down a macro cannot burn the
  webhook rate limit for everybody else. A message that is dropped as spam is never queued.

### What is forwarded, and what is not

Only public player chat is forwarded by default. Private messages (`/msg`, `/tell`) and command
execution are never forwarded unless explicitly enabled, and enabling them is not recommended.
Command output and remote-console output are suppressed too: the server echoes a command's result
to chat, and that echo is recognised and dropped.

When proximity chat is enabled, a local or global message is forwarded with its channel tag:
`%channel%` is `LOCAL` or `GLOBAL` and `%channel_label%` is `Local` or `Global`, so a format can
render `[Local] Alex: hi` or branch on the channel. Ordinary public chat carries an empty label.
`localChat.forwardToBridge` turns proximity forwarding off without affecting ordinary chat.

## Inbound path

```
Discord Gateway WebSocket  ─┐
Matrix /sync long poll     ─┤
                            ▼
                    InboundChatBridge
                            │  1. global enable switch
                            │  2. blank-body check
                            │  3. loop guard (de-duplication)
                            │  4. formatting and sanitisation
                            ▼
                       ChatSink
                            │  (server implementation)
                            ▼
             MinecraftServer.execute(...)   hops onto the main thread
                            │
                            ▼
             PlayerManager.broadcast(...)
```

Design points:

- Each reader runs on its own daemon thread and never touches the server object directly. The
  sink schedules the broadcast with `MinecraftServer.execute`, which only enqueues a task.
- The line is sanitised before it reaches chat: control characters are removed, newlines are
  flattened, section signs are stripped (so a platform user cannot inject a colour code and
  impersonate a server prefix), and the line is truncated to `maxMessageLength`.
- The broadcast carries no `run_command` click event, so an external message can never be turned
  into a command by a click.

## Loop prevention

A bridge can easily feed itself. Four mechanisms prevent that, and they work together:

1. **Inbound lines are not forwarded back out.** When a platform message is shown, its exact line
   is remembered for ten seconds. The broadcast that follows is a game message, which the bridge
   sees and recognises, and does not forward.
2. **Discord ignores bots and webhooks.** Messages authored by a bot are skipped, and so are
   messages carrying a `webhook_id` — that is how this mod's own outbound webhook posts come back
   into the gateway.
3. **Matrix ignores the bot's own MXID.** Any event sent by the configured `userId` is skipped.
4. **Identical inbound messages are de-duplicated.** The same `(platform, user, body)` seen again
   inside `dedupeWindowMillis` is dropped. This covers a platform redelivering events after a
   reconnect, and a relay bot echoing a message twice.

## Discord specifics

A Discord **webhook** can only post, so outbound uses a webhook and inbound uses a **bot** with
the Gateway WebSocket. The bot needs the `MESSAGE_CONTENT` privileged intent enabled in the
developer portal; without it Discord sends empty content for other users' messages.

The Gateway reader implements HELLO, IDENTIFY, heartbeats, RESUME after a dropped connection and
MESSAGE_CREATE dispatch. When a heartbeat goes unanswered the connection is closed deliberately so
the reconnect path runs, instead of leaving a half-dead socket.

## Matrix specifics

Matrix uses the client-server API for both directions. Outbound is `PUT /rooms/{roomId}/send`.
Inbound is `GET /sync`, long-polled with a `since` token carried from one call to the next. The
access token travels in the `Authorization: Bearer` header and never in the query string.

## Generic webhook specifics

Any HTTP endpoint can receive chat. The request body is built from `payloadTemplate`, in which
`%player%`, `%message%`, `%timestamp%`, `%server%`, `%channel%` and `%channel_label%` are
substituted and JSON-escaped. Extra headers come from `headers`, and one authentication header
comes from `authHeader`/`authToken`.

## Secrets

Every credential — webhook URL, bot token, Matrix access token, generic auth token — is stored
only on the server and is never transmitted to a client. The dashboard reports whether a value is
configured, never the value. Error messages are redacted before they are shown or logged.

## Turning the directions on and off

| Switch | Effect |
| --- | --- |
| `chatBridge.enabled` | Master switch for the outbound direction. |
| `chatBridge.inbound.enabled` | Master switch for the inbound direction. |
| `chatBridge.inbound.discord.enabled` | Enables the Discord reader. |
| `chatBridge.inbound.matrix.enabled` | Enables the Matrix reader. |

Inbound is off by default, so upgrading a server keeps the previous outbound-only behaviour until
you opt in.
