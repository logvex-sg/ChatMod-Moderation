package com.adminmod.server.command;

import java.util.UUID;

import com.adminmod.server.AdminServerMod;
import com.adminmod.server.chat.ChatBridgeService;
import com.adminmod.server.moderation.ModerationService;
import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

/**
 * Registers the {@code /adminmod} operator command.
 *
 * <p>The command exists so an operator can inspect the bridge and the credential directory from
 * the server console. It intentionally does not print the credential itself: revealing the
 * password is the client panel's job, and only to the owner.
 */
public final class AdminCommand {

    private AdminCommand() {
    }

    public static void register(MinecraftServer server) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("adminmod")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(CommandManager.literal("status").executes(context -> status(context.getSource())))
                        .then(CommandManager.literal("bridge").executes(context -> bridge(context.getSource())))
                        .then(CommandManager.literal("vault").executes(context -> vault(context.getSource())))
                        .then(CommandManager.literal("reload").executes(context -> reload(context.getSource())))));
    }

    private static int status(net.minecraft.server.command.ServerCommandSource source) {
        ModerationService moderation = AdminServerMod.moderation();
        ChatBridgeService bridge = AdminServerMod.chatBridge();
        int sessions = AdminServerMod.sessions() == null ? 0 : AdminServerMod.sessions().authenticatedSessions();
        source.sendFeedback(() -> Text.of("ADMIN Mod " + AdminServerMod.VERSION
                + " | protocol " + AdminServerMod.protocolVersion()
                + " | authenticated sessions " + sessions
                + " | recorded punishments " + (moderation == null ? 0 : moderation.store().allPunishments().size())), false);
        return 1;
    }

    private static int bridge(net.minecraft.server.command.ServerCommandSource source) {
        ChatBridgeService bridge = AdminServerMod.chatBridge();
        if (bridge == null) {
            source.sendError(Text.of("The chat bridge is not running"));
            return 0;
        }
        source.sendFeedback(() -> Text.of("provider=" + bridge.provider().id()
                + " configured=" + bridge.provider().isConfigured()
                + " destination=" + bridge.provider().describe()
                + " delivered=" + bridge.dispatcher().deliveredCount()
                + " failed=" + bridge.dispatcher().failedCount()), false);
        return 1;
    }

    private static int vault(net.minecraft.server.command.ServerCommandSource source) {
        if (AdminServerMod.credentials() == null) {
            source.sendError(Text.of("The credential store is not available"));
            return 0;
        }
        // Only the directory name is printed; the password stays in the client panel.
        source.sendFeedback(() -> Text.of("Credential directory: "
                + AdminServerMod.credentials().directory()), false);
        return 1;
    }

    private static int reload(net.minecraft.server.command.ServerCommandSource source) {
        source.sendFeedback(() -> Text.of("Restart the server to apply configuration changes"), false);
        return 1;
    }

    /** Convenience for other server classes that need a UUID from a name-like argument. */
    static UUID asUuid(String value) {
        return UUID.fromString(value);
    }
}
