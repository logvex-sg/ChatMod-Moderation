package com.adminmod.server.mixin;

import java.util.List;
import java.util.Optional;

import com.adminmod.common.protection.PayloadGuard;
import com.adminmod.common.protection.ProtectionManager;
import com.adminmod.server.AdminServerMod;

import net.minecraft.network.packet.c2s.play.BookUpdateC2SPacket;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the anti-crash and anti-abuse checks at the point each packet is handled.
 *
 * <p>The checks run at {@code HEAD} and cancel the handler when a limit is exceeded. Cancelling
 * before the vanilla logic means an oversized book never allocates its pages, an out-of-range
 * click never touches the container and a flood never reaches the world. This is deliberately
 * where the guard sits: rejecting the packet after the fact would already have paid its cost.
 *
 * <p>Every handler consults the same {@link ProtectionManager}, which reads its thresholds from the
 * live configuration, so a profile change or a hot reload takes effect on the next packet without
 * a restart. The mixin holds no state of its own.
 *
 * <p>The packet-frequency check counts the packets that dominate real traffic (movement, actions,
 * interactions, clicks and chat) rather than every packet on the connection, because the
 * connection-level counter is not reachable without a second mixin and these are the packets a
 * flood is made of.
 */
@Mixin(value = ServerPlayNetworkHandler.class, priority = 1000)
public abstract class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    /** The guard, or {@code null} before the server has started. */
    private static ProtectionManager adminmod$guard() {
        return AdminServerMod.protection();
    }

    private static String adminmod$key(ServerPlayerEntity player) {
        return player == null ? null : player.getUuidAsString();
    }

    private void adminmod$countPacket() {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        guard.allowPacket(adminmod$key(player), System.currentTimeMillis());
    }

    @Inject(method = "onPlayerMove(Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;)V", at = @At("HEAD"))
    private void adminmod$onMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        adminmod$countPacket();
    }

    @Inject(method = "onPlayerAction(Lnet/minecraft/network/packet/c2s/play/PlayerActionC2SPacket;)V", at = @At("HEAD"))
    private void adminmod$onAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        adminmod$countPacket();
    }

    @Inject(method = "onPlayerInteractBlock(Lnet/minecraft/network/packet/c2s/play/PlayerInteractBlockC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onInteractBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!guard.allowPacket(adminmod$key(player), now)
                || !guard.allowEntityInteraction(adminmod$key(player), now)) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractItem(Lnet/minecraft/network/packet/c2s/play/PlayerInteractItemC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onInteractItem(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!guard.allowPacket(adminmod$key(player), now)
                || !guard.allowEntityInteraction(adminmod$key(player), now)) {
            ci.cancel();
        }
    }

    @Inject(method = "onPlayerInteractEntity(Lnet/minecraft/network/packet/c2s/play/PlayerInteractEntityC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onInteractEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!guard.allowPacket(adminmod$key(player), now)
                || !guard.allowEntityInteraction(adminmod$key(player), now)) {
            ci.cancel();
        }
    }

    @Inject(method = "onChatMessage(Lnet/minecraft/network/packet/c2s/play/ChatMessageC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onChat(ChatMessageC2SPacket packet, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        String message = packet.chatMessage();
        if (!guard.allowChat(adminmod$key(player), message, System.currentTimeMillis())) {
            ci.cancel();
        }
    }

    @Inject(method = "onCommandExecution(Lnet/minecraft/network/packet/c2s/play/CommandExecutionC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onCommand(CommandExecutionC2SPacket packet, CallbackInfo ci) {
        adminmod$checkCommand(packet.command(), ci);
    }

    private void adminmod$checkCommand(String command, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        if (!guard.allowCommand(adminmod$key(player), command, System.currentTimeMillis())) {
            ci.cancel();
        }
    }

    @Inject(method = "onClickSlot(Lnet/minecraft/network/packet/c2s/play/ClickSlotC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onClickSlot(ClickSlotC2SPacket packet, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        String key = adminmod$key(player);
        long now = System.currentTimeMillis();
        if (!guard.allowPacket(key, now) || !guard.allowInventoryClick(key, now)) {
            ci.cancel();
            return;
        }
        ScreenHandler handler = player.currentScreenHandler;
        int containerSize = handler == null ? 0 : handler.slots.size();
        int modified = packet.getModifiedStacks() == null ? 0 : packet.getModifiedStacks().size();
        if (!guard.validateClickShape(key, packet.getSlot(), containerSize, modified)) {
            ci.cancel();
            return;
        }
        long signature = ((long) packet.getSyncId() << 40) ^ ((long) packet.getSlot() << 20)
                ^ ((long) packet.getButton() << 8) ^ (packet.getActionType() == null ? 0
                        : packet.getActionType().ordinal());
        if (!guard.observeClickRepeat(key, signature, now)) {
            ci.cancel();
        }
    }

    @Inject(method = "onBookUpdate(Lnet/minecraft/network/packet/c2s/play/BookUpdateC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onBookUpdate(BookUpdateC2SPacket packet, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        List<String> pages = packet.pages();
        Optional<String> title = packet.title();
        PayloadGuard.Verdict verdict = guard.checkBook(adminmod$key(player), pages, title.orElse(null));
        if (verdict != PayloadGuard.Verdict.OK) {
            ci.cancel();
        }
    }

    @Inject(method = "onUpdateSign(Lnet/minecraft/network/packet/c2s/play/UpdateSignC2SPacket;)V",
            at = @At("HEAD"), cancellable = true)
    private void adminmod$onUpdateSign(UpdateSignC2SPacket packet, CallbackInfo ci) {
        ProtectionManager guard = adminmod$guard();
        if (guard == null || player == null) {
            return;
        }
        if (guard.checkSign(adminmod$key(player), packet.getText()) != PayloadGuard.Verdict.OK) {
            ci.cancel();
        }
    }
}
