package com.adminmod.client;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.net.AdminClientNetworking;
import com.adminmod.client.net.AdminPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Client entrypoint.
 *
 * <p>Registers the payload receiver, the connection listeners and the dashboard key bind. All the
 * work of a request happens on the server; the client only renders answers, so there is no
 * privileged logic in this jar.
 */
public final class AdminClientMod implements ClientModInitializer {

    public static final String MOD_ID = "adminmodclient";

    private static KeyBinding openDashboard;

    @Override
    public void onInitializeClient() {
        openDashboard = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.adminmodclient.dashboard", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET,
                "category.adminmodclient"));

        ClientPlayNetworking.registerGlobalReceiver(AdminPayload.ID,
                (payload, context) -> AdminClientNetworking.get().onFrame(payload.data()));

        // A session key is bound to the server nonce, so it is discarded whenever the player
        // joins or leaves a world; the operator re-authenticates against the new server.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> AdminClientNetworking.get().disconnect());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> AdminClientNetworking.get().disconnect());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AdminClientNetworking.get().tick();
            while (openDashboard.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new AdminDashboardScreen());
                }
            }
        });
    }

    public static Identifier id(String path) {
        return Identifier.of(MOD_ID, path);
    }
}
