package com.adminmod.integrations;

import com.adminmod.common.localchat.Channel;

/**
 * A public chat message handed to the bridge for delivery.
 *
 * @param playerName  the sender's display name
 * @param playerUuid  the sender's UUID, as a string
 * @param message     the message body
 * @param timestampMillis epoch milliseconds when the server accepted the message
 * @param serverName  human readable server identification
 * @param channel     the proximity channel the message belongs to, so the destination can label it
 *                    {@code [Local]} or {@code [Global]}
 */
public record ChatEvent(
        String playerName,
        String playerUuid,
        String message,
        long timestampMillis,
        String serverName,
        Channel channel,
        Priority priority) {

    /** Backwards-compatible constructor for ordinary public chat at normal priority. */
    public ChatEvent(String playerName, String playerUuid, String message, long timestampMillis, String serverName) {
        this(playerName, playerUuid, message, timestampMillis, serverName, Channel.PUBLIC, Priority.NORMAL);
    }

    /** Backwards-compatible constructor carrying a channel at normal priority. */
    public ChatEvent(String playerName, String playerUuid, String message, long timestampMillis, String serverName,
            Channel channel) {
        this(playerName, playerUuid, message, timestampMillis, serverName, channel, Priority.NORMAL);
    }

    public ChatEvent {
        if (channel == null) {
            channel = Channel.PUBLIC;
        }
        if (priority == null) {
            priority = Priority.NORMAL;
        }
    }

    /** The {@code Local}/{@code Global} tag for this message, or an empty string when there is none. */
    public String channelLabel() {
        return channel.label();
    }

    /** Whether this message belongs to a proximity channel rather than ordinary public chat. */
    public boolean isProximity() {
        return channel == Channel.LOCAL || channel == Channel.GLOBAL;
    }
}
