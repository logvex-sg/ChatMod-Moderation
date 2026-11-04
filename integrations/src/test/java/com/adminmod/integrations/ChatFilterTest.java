package com.adminmod.integrations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.adminmod.common.config.AdminConfig;

import org.junit.jupiter.api.Test;

class ChatFilterTest {

    private static ChatFilter filter(boolean publicOnly, boolean forwardCommands, boolean forwardPrivate) {
        AdminConfig.ChatBridge bridge = new AdminConfig.ChatBridge();
        bridge.enabled = true;
        bridge.publicChatOnly = publicOnly;
        bridge.forwardCommands = forwardCommands;
        bridge.forwardPrivateMessages = forwardPrivate;
        return new ChatFilter(bridge);
    }

    @Test
    void publicChatIsForwardedByDefault() {
        assertTrue(filter(true, false, false).shouldForward("hello everyone"));
    }

    @Test
    void commandsAreNotForwardedByDefault() {
        ChatFilter chatFilter = filter(true, false, false);
        assertFalse(chatFilter.shouldForward("/op Steve"));
        assertFalse(chatFilter.shouldForward("/msg Alex hi"));
        assertFalse(chatFilter.shouldForward("/gamemode creative"));
    }

    @Test
    void privateMessagesAreNotForwardedByDefault() {
        ChatFilter chatFilter = filter(true, false, false);
        assertFalse(chatFilter.shouldForward("Alex whispers to you: hi"));
        assertFalse(chatFilter.shouldForward("You whisper to Alex: hi"));
        assertFalse(chatFilter.shouldForward("[Alex -> Steve] secret"));
    }

    @Test
    void commandsCanBeOptedIn() {
        assertTrue(filter(true, true, false).shouldForward("/say hello"));
    }

    @Test
    void privateMessagesCanBeOptedIn() {
        assertTrue(filter(true, false, true).shouldForward("Alex whispers to you: hi"));
    }

    @Test
    void disabledBridgeForwardsNothing() {
        AdminConfig.ChatBridge bridge = new AdminConfig.ChatBridge();
        bridge.enabled = false;
        assertFalse(new ChatFilter(bridge).shouldForward("hello"));
    }

    @Test
    void blankMessagesAreIgnored() {
        ChatFilter chatFilter = filter(true, false, false);
        assertFalse(chatFilter.shouldForward(""));
        assertFalse(chatFilter.shouldForward("   "));
        assertFalse(chatFilter.shouldForward(null));
    }

    @Test
    void commandDetectionIsIndependentOfFilterState() {
        assertTrue(ChatFilter.isCommand("/help"));
        assertTrue(ChatFilter.isCommand("  /help"));
        assertFalse(ChatFilter.isCommand("hello /help"));
        assertFalse(ChatFilter.isCommand("hello"));
    }
}
