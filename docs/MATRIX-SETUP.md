# Matrix integration setup

Matrix uses the client-server API for both directions. The mod talks to a homeserver directly, so
you need a bot account, its access token, and the room ID.

## 1. Create a bot account

Register a dedicated account on your homeserver, for example:

```
@adminbot:example.org
```

Do not reuse a personal account. The inbound reader ignores events sent by this account so its
own outbound messages are not echoed back into Minecraft.

## 2. Obtain an access token

Log in over the API to get a token:

```bash
curl -sS -X POST https://matrix.example.org/_matrix/client/v3/login \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "m.login.password",
    "identifier": { "type": "m.id.user", "user": "adminbot" },
    "password": "<bot password>"
  }'
```

The response contains an `access_token`. Treat it as a secret; it grants full access to the bot
account. Some homeservers also let you create a token from an admin panel or a client's session
settings.

## 3. Create or pick a room

Create a room, or use an existing one, and invite the bot. The room must be joined by the bot for
it to read or post. Copy the **room ID**, which looks like:

```
!abcdefg:example.org
```

Room IDs are not room names or aliases. If you only have an alias (`#room:example.org`), resolve
it:

```bash
curl -sS -H "Authorization: Bearer $MATRIX_TOKEN" \
  'https://matrix.example.org/_matrix/client/v3/directory/room/%23room%3Aexample.org'
```

## 4. Outbound configuration (Minecraft → Matrix)

```json
"chatBridge": {
  "enabled": true,
  "provider": "matrix",
  "matrix": {
    "homeserver": "https://matrix.example.org",
    "roomId": "!abcdefg:example.org",
    "accessToken": "<access token>",
    "messageFormat": "%player%: %message%"
  }
}
```

- `homeserver` must start with `https://`. A plain `http://` URL is rejected, so the token is
  never sent in the clear.
- `messageFormat` supports `%player%`, `%message%`, `%timestamp%` and `%server%`.

Restart the server, or run `/adminmod reload`, then type a message in game.

## 5. Inbound configuration (Matrix → Minecraft)

```json
"chatBridge": {
  "inbound": {
    "enabled": true,
    "format": "[%platform%] %user%: %message%",
    "matrixLabel": "Matrix",
    "matrix": {
      "enabled": true,
      "homeserver": "https://matrix.example.org",
      "roomId": "!abcdefg:example.org",
      "accessToken": "<access token>",
      "userId": "@adminbot:example.org",
      "syncTimeoutMillis": 30000,
      "syncLimit": 50
    }
  }
}
```

- `userId` must be the bot's own MXID. Events from this sender are ignored, which is what stops
  the bot's outbound posts from reappearing in Minecraft.
- `syncTimeoutMillis` is how long each long-poll waits for new events. Thirty seconds keeps the
  loop quiet without busy-waiting.
- `syncLimit` caps how many events one sync requests.

## 6. How the reader works

The reader long-polls `GET /_matrix/client/v3/sync`:

- The first call has no `since` token, so the homeserver answers immediately with a starting
  position.
- Every later call passes the `next_batch` token from the previous response, and waits up to
  `syncTimeoutMillis` for new events.
- Only `m.room.message` events with `msgtype` of `m.text`, `m.notice` or `m.emote` in the
  configured room are shown.
- Sender MXIDs are shortened for display: `@alice:example.org` becomes `alice`.
- The access token is sent in the `Authorization: Bearer` header and never appears in the URL.
- On HTTP 429 the reader waits for the homeserver's `Retry-After` before retrying, and backs off
  on other failures.

## 7. Verifying

1. Type in Minecraft: the message appears in the Matrix room.
2. Type in the Matrix room: the message appears in Minecraft as `[Matrix] alice: message`.
3. Check the **Chat Bridge** tab: the inbound panel shows `matrix-sync` as connected.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Nothing appears in the room | `provider` is not `"matrix"`, the homeserver URL is wrong, or the token lacks permission to post. |
| HTTP 403 from the homeserver | The bot is not joined to the room, or it lacks permission to send. |
| HTTP 401 | The access token is expired or wrong. Log in again to get a new one. |
| HTTP 404 | The room ID is wrong, or it is an alias rather than a room ID. |
| Inbound messages do not appear | The global inbound switch or the Matrix inbound switch is off, or the bot is not in the room. |
| The bot's own messages echo back | `userId` does not match the bot's MXID. |
| `homeserver must use https` | The URL starts with `http://`. Use `https://`. |
