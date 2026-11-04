package com.adminmod.server.mixin;

import com.adminmod.server.AdminServerMod;
import com.adminmod.server.moderation.ModerationService;

import net.minecraft.network.message.MessageType;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Enforces mutes by suppressing the broadcast of a muted player's chat.
 *
 * <p>Cancelling at {@code broadcast} rather than at the packet handler means the message never
 * reaches any player and never reaches the chat bridge, which is what "muted" has to mean. The
 * sender is still told the message was blocked.
 */
@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Inject(method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$blockMutedChat(SignedMessage message, ServerPlayerEntity sender,
            MessageType.Parameters params, CallbackInfo ci) {
        if (sender == null) {
            return;
        }
        ModerationService moderation = AdminServerMod.moderation();
        if (moderation == null) {
            return;
        }
        if (moderation.isMuted(sender.getUuid())) {
            sender.sendMessage(Text.of("You are muted and cannot send messages."), false);
            ci.cancel();
        }
    }
}
