package com.adminmod.integrations;

import java.util.Locale;
import java.util.regex.Pattern;

import com.adminmod.common.config.AdminConfig;

/**
 * Decides whether an outgoing message may be forwarded to the chat bridge.
 *
 * <p>This is the single place where the forwarding policy lives, so the answer to "can a
 * private message reach Discord?" is one short method rather than a search across the codebase.
 */
public final class ChatFilter {

    /** Fabric's signed-chat prefix, used to detect client-side message decoration. */
    private static final Pattern LEADING_SLASH = Pattern.compile("^\\s*/.*", Pattern.DOTALL);

    private final AdminConfig.ChatBridge config;

    public ChatFilter(AdminConfig.ChatBridge config) {
        this.config = config;
    }

    /**
     * @param rawMessage the message exactly as the player typed it
     * @return {@code true} when the message should be forwarded
     */
    public boolean shouldForward(String rawMessage) {
        if (!config.enabled) {
            return false;
        }
        if (rawMessage == null || rawMessage.isBlank()) {
            return false;
        }
        if (isCommand(rawMessage)) {
            return config.forwardCommands;
        }
        if (isPrivateMessage(rawMessage)) {
            return config.forwardPrivateMessages;
        }
        return config.publicChatOnly || config.forwardPrivateMessages;
    }

    /**
     * Commands are recognised by a leading slash. The server only ever receives the plain text
     * of a chat message here, so this is a defence-in-depth check for callers that pass a
     * command string by mistake.
     */
    public static boolean isCommand(String message) {
        return message != null && LEADING_SLASH.matcher(message).matches();
    }

    /**
     * Detects the common private-message shapes: {@code /msg}, {@code /tell}, {@code /w} and
     * the {@code ->} whisper rendering used by many chat plugins.
     */
    public static boolean isPrivateMessage(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.trim().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("/msg ") || trimmed.startsWith("/tell ") || trimmed.startsWith("/w ")
                || trimmed.startsWith("/whisper ") || trimmed.startsWith("/dm ")
                || trimmed.startsWith("/r ") || trimmed.startsWith("/reply ")) {
            return true;
        }
        // "Steve whispers to you: ..." / "You whisper to Steve: ..." / "[Steve -> Alex] ..."
        return trimmed.contains(" whispers to ")
                || trimmed.contains("whispers:")
                || (trimmed.contains(" -> ") && trimmed.contains("["))
                || trimmed.contains("you whisper to ");
    }
}
