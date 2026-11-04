package com.adminmod.integrations;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.adminmod.common.config.AdminConfig;
import com.adminmod.integrations.discord.DiscordProvider;
import com.adminmod.integrations.http.HttpTransport;
import com.adminmod.integrations.matrix.MatrixProvider;
import com.adminmod.integrations.webhook.GenericWebhookProvider;

/**
 * Builds the outbound provider list from configuration.
 *
 * <p>This is the one place that knows how a configured destination becomes a {@link ChatProvider}.
 * It is separated from the bridge so the bridge can be rebuilt on a configuration reload without
 * duplicating the mapping, and so tests can construct providers without a running server.
 *
 * <p>The primary provider is always the selected one. Additional Discord webhooks and extra
 * Matrix rooms are appended as further destinations, which is what lets one server mirror its
 * chat into several channels or rooms at once.
 */
public final class ChatProviderFactory {

    private ChatProviderFactory() {
    }

    /** The provider selected by {@code chatBridge.provider}, regardless of configuration state. */
    public static ChatProvider primary(AdminConfig config, HttpTransport transport) {
        AdminConfig.ChatBridge bridge = config.chatBridge;
        String id = bridge.provider == null ? "discord" : bridge.provider.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "matrix" -> new MatrixProvider(transport, bridge.matrix.homeserver, bridge.matrix.roomId,
                    bridge.matrix.accessToken, bridge.matrix.messageFormat, bridge.maxMessageLength);
            case "generic", "webhook" -> new GenericWebhookProvider(transport, bridge.generic.url,
                    bridge.generic.method, bridge.generic.payloadTemplate, bridge.generic.authHeader,
                    bridge.generic.authToken, bridge.generic.headers, bridge.generic.contentType,
                    bridge.maxMessageLength);
            default -> new DiscordProvider(transport, bridge.discord.webhookUrl, bridge.discord.username,
                    bridge.discord.avatarUrl, bridge.discord.useEmbeds, bridge.discord.messageFormat,
                    bridge.discord.embedColor, bridge.maxMessageLength);
        };
    }

    /**
     * The full destination list: the primary provider plus every configured extra.
     *
     * <p>Extras are only meaningful for the platform they belong to. A Discord webhook added to
     * {@code extraDiscordChannels} is delivered even when the primary provider is Discord, and an
     * extra Matrix room is delivered even when the primary is Matrix; an extra that belongs to a
     * platform other than the selected one is still honoured, because mirroring into both is the
     * point of the feature.
     */
    public static List<ChatProvider> all(AdminConfig config, HttpTransport transport) {
        AdminConfig.ChatBridge bridge = config.chatBridge;
        List<ChatProvider> providers = new ArrayList<>();
        providers.add(primary(config, transport));

        int index = 2;
        for (AdminConfig.DiscordChannel extra : bridge.extraDiscordChannels) {
            if (extra == null || extra.webhookUrl == null || extra.webhookUrl.isBlank()) {
                continue;
            }
            providers.add(new DiscordProvider(transport, extra.webhookUrl, extra.username, extra.avatarUrl,
                    extra.useEmbeds, extra.messageFormat, extra.embedColor, bridge.maxMessageLength));
            index++;
        }

        for (String room : bridge.extraMatrixRooms) {
            if (room == null || room.isBlank()) {
                continue;
            }
            providers.add(new MatrixProvider(transport, bridge.matrix.homeserver, room,
                    bridge.matrix.accessToken, bridge.matrix.messageFormat, bridge.maxMessageLength));
            index++;
        }
        return providers;
    }
}
