package com.adminmod.server.chat;

import com.adminmod.integrations.inbound.ChatSink;
import com.adminmod.integrations.inbound.InboundChatMessage;

import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

/**
 * Shows an inbound platform message in Minecraft chat.
 *
 * <p>Platform receivers call {@link #accept} from their own threads, but a Minecraft server may
 * only be touched from its main thread, so the broadcast is scheduled with {@link
 * MinecraftServer#execute}. That call only enqueues a task, so the receiver thread never blocks
 * on the server tick.
 *
 * <p>The message is sent as a plain chat line with no {@code run_command} click event, so an
 * external message can never be turned into a command by a click. The text is also registered
 * with the bridge as an inbound echo so the outbound forwarder does not send it back to the
 * platform, which would create a loop.
 */
public final class MinecraftChatSink implements ChatSink {

    private final MinecraftServer server;
    private final ChatBridgeService bridge;

    public MinecraftChatSink(MinecraftServer server, ChatBridgeService bridge) {
        this.server = server;
        this.bridge = bridge;
    }

    @Override
    public void accept(InboundChatMessage message) {
        String line = message.message();
        if (line == null || line.isBlank()) {
            return;
        }
        // Registered before the broadcast so the resulting game message is recognised as our own.
        bridge.markInboundDisplayed(line);
        server.execute(() -> {
            Text text = Text.literal(line);
            server.getPlayerManager().broadcast(text, false);
        });
    }
}
