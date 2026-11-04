package com.adminmod.integrations;

/**
 * A public chat message handed to the bridge for delivery.
 *
 * @param playerName  the sender's display name
 * @param playerUuid  the sender's UUID, as a string
 * @param message     the message body
 * @param timestampMillis epoch milliseconds when the server accepted the message
 * @param serverName  human readable server identification
 */
public record ChatEvent(
        String playerName,
        String playerUuid,
        String message,
        long timestampMillis,
        String serverName) {
}
