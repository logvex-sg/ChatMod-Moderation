# Discord webhook setup

This guide sets up both Discord directions. They use different Discord features and can be
enabled independently.

| Direction | Discord feature | What it needs |
| --- | --- | --- |
| Minecraft → Discord (outbound) | Channel webhook | A webhook URL |
| Discord → Minecraft (inbound) | Bot + Gateway | A bot token and the Message Content intent |

## Outbound: post Minecraft chat to a channel

### Create the webhook

1. Open Discord and go to the channel that should receive chat.
2. Click the gear icon to open **Channel settings**.
3. Open **Integrations → Webhooks** and click **New Webhook**.
4. Give it a name, then click **Copy Webhook URL**.

The URL looks like:

```
https://discord.com/api/webhooks/<id>/<token>
```

Treat it as a secret. Anyone who has the URL can post to the channel. Do not paste it into an
issue, a log or a chat message.

### Configure the mod

In `config/admin-mod.json`:

```json
"chatBridge": {
  "enabled": true,
  "provider": "discord",
  "discord": {
    "webhookUrl": "https://discord.com/api/webhooks/<id>/<token>",
    "username": "Minecraft Server",
    "avatarUrl": "",
    "useEmbeds": true,
    "messageFormat": "**%player%**: %message%",
    "embedColor": 5793266
  }
}
```

- `username` is the display name the webhook posts under.
- `avatarUrl` is an optional image URL for the webhook avatar.
- `useEmbeds` posts a rich embed instead of plain text; `embedColor` sets its colour.
- `messageFormat` is the text body. `%player%` and `%message%` are substituted.

Restart the server, or run `/adminmod reload`, then type a message in game. It should appear in
the channel.

### Test the webhook from a shell

To confirm the URL works before touching the mod:

```bash
curl -sS -X POST -H 'Content-Type: application/json' \
  -d '{"content":"webhook test","allowed_mentions":{"parse":[]}}' \
  "$DISCORD_WEBHOOK_URL"
```

### Mentions are neutralised

The provider always sends `"allowed_mentions":{"parse":[]}` and neutralises `@` in player names
and messages, so a player cannot ping `@everyone`, a role or another user through the bridge.

## Inbound: show Discord messages in Minecraft chat

A webhook can only post. To read a channel you need a bot and the Gateway WebSocket.

### Create the bot

1. Open <https://discord.com/developers/applications> and click **New Application**.
2. Open the **Bot** tab and click **Add Bot**.
3. Click **Reset Token** and copy the token.
4. Under **Privileged Gateway Intents**, enable **Message Content Intent**. This is required:
   without it Discord sends empty content for messages authored by other users.
5. Under **OAuth2 → URL Generator**, select the `bot` scope and the `View Channels` and
   `Read Message History` permissions, then open the generated URL to invite the bot to your
   server.

### Find the channel id

Enable **Settings → Advanced → Developer Mode** in Discord, then right-click the channel and
choose **Copy Channel ID**.

### Configure the mod

```json
"chatBridge": {
  "inbound": {
    "enabled": true,
    "format": "[%platform%] %user%: %message%",
    "dedupeWindowMillis": 5000,
    "maxMessageLength": 512,
    "discord": {
      "enabled": true,
      "botToken": "<bot token>",
      "channelId": "123456789012345678",
      "guildId": "",
      "gatewayUrl": "wss://gateway.discord.gg/?v=10&encoding=json"
    }
  }
}
```

- `channelId` restricts the reader to one channel. Leave it empty to read every channel the bot
  can see.
- `guildId` optionally restricts the reader to one guild.

Restart the server. Messages posted in the channel now appear in Minecraft as
`[Discord] Username: message`.

### Why outbound posts do not loop back

The reader ignores messages authored by bots and messages that carry a `webhook_id`. This mod's
own outbound posts carry a `webhook_id`, so they are never re-injected. On top of that, a line
that was shown in chat from a platform is suppressed for ten seconds so it is not forwarded back
out.

## Verifying

1. Type in Minecraft chat: the message appears in the Discord channel.
2. Type in the Discord channel: the message appears in Minecraft as `[Discord] Name: message`.
3. Run a command in Minecraft (for example `/list`): it does **not** appear in Discord.
4. Send a private message (`/msg <player> hi`): it does **not** appear in Discord.
5. Open the **Chat Bridge** tab: delivery counters increase, and the inbound panel shows the
   reader as connected.

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Nothing appears in Discord | `chatBridge.enabled` is false, `provider` is not `"discord"`, or the webhook URL is wrong. Check the **Chat Bridge** tab for the last error. |
| HTTP 401 or 404 from Discord | The webhook was deleted or the URL is mistyped. Create a new webhook. |
| HTTP 429 | Rate limited. The dispatcher backs off and retries; no action is needed. |
| Discord messages arrive empty | The **Message Content Intent** is not enabled for the bot. |
| Nothing arrives in Minecraft | `chatBridge.inbound.enabled` or the Discord inbound flag is false, or the bot cannot read the channel. Check the inbound panel's reader state and last error. |
| Bot connects but sees no messages | The bot lacks `View Channels` / `Read Message History`, or it is not in the guild. |
| Messages loop | Inbound is disabled but a relay bot is echoing the channel. Check for a second relay. |
