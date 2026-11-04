package com.adminmod.integrations.inbound;

/**
 * A message that arrived from an external platform and is ready to be shown in Minecraft chat.
 *
 * @param platform the platform label, for example {@code Discord}
 * @param user     the sender's platform display name
 * @param message  the message body
 * @param timestampMillis epoch milliseconds when the platform received the message
 * @param sourceId the platform's own message id, used for de-duplication
 */
public record InboundChatMessage(
        String platform,
        String user,
        String message,
        long timestampMillis,
        String sourceId) {
}
