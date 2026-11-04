package com.adminmod.server.command;

import com.adminmod.server.AdminServerMod;
import com.adminmod.server.chat.ChatBridgeService;
import com.adminmod.server.moderation.ModerationService;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
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

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("adminmod")
                        .requires(source -> source.hasPermissionLevel(4))
                        .then(CommandManager.literal("status").executes(context -> status(context.getSource())))
                        .then(CommandManager.literal("bridge").executes(context -> bridge(context.getSource())))
                        .then(CommandManager.literal("vault").executes(context -> vault(context.getSource())))
                        .then(CommandManager.literal("pair").executes(context -> pair(context.getSource())))
                        .then(CommandManager.literal("clients").executes(context -> clients(context.getSource())))
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

    /**
     * Mints a one-time pairing key and shows it once.
     *
     * <p>This is the bootstrap path: an operator on the server console can create a key without a
     * client session, redeem it on the machine that will run the dashboard, and from then on that
     * machine uses its own secret. The key is never stored in the clear, so it must be copied now.
     */
    private static int pair(net.minecraft.server.command.ServerCommandSource source) {
        com.adminmod.server.auth.PairingService pairing = AdminServerMod.pairing();
        if (pairing == null) {
            source.sendError(Text.of("Pairing is not available on this server"));
            return 0;
        }
        try {
            com.adminmod.server.auth.PairingService.IssuedKey issued = pairing.issueKey("console");
            long minutes = Math.max(1L, AdminServerMod.config().remoteAdmin.pairingKeyTtlSeconds / 60L);
            source.sendFeedback(() -> Text.of("Pairing key (single use, expires in " + minutes
                    + " min): " + issued.key()), false);
            return 1;
        } catch (java.io.IOException e) {
            source.sendError(Text.of("Could not persist the pairing key"));
            return 0;
        }
    }

    private static int clients(net.minecraft.server.command.ServerCommandSource source) {
        com.adminmod.server.auth.PairingService pairing = AdminServerMod.pairing();
        if (pairing == null) {
            source.sendError(Text.of("Pairing is not available on this server"));
            return 0;
        }
        var clients = pairing.registry().clients();
        source.sendFeedback(() -> Text.of("Paired clients: " + clients.size()
                + " | pending keys: " + pairing.pendingKeyCount()), false);
        for (var client : clients) {
            source.sendFeedback(() -> Text.of("  " + client.id() + "  " + client.label()
                    + "  owner=" + client.ownerName()), false);
        }
        return 1;
    }

    private static int reload(net.minecraft.server.command.ServerCommandSource source) {
        AdminServerMod.ReloadOutcome outcome = AdminServerMod.reloadConfiguration();
        if (!outcome.success()) {
            source.sendError(Text.of("Reload failed: " + outcome.message()));
            return 0;
        }
        source.sendFeedback(() -> Text.of("Configuration reloaded: " + outcome.message()), true);
        return 1;
    }
}
