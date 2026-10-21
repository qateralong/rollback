package com.qateralong.rollback.mixin;

import com.qateralong.rollback.Freeze;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPickItemPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    private static final String ENSURE = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

    @Shadow
    public ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void rollback$move(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (Freeze.blocks(this.player)) {
            Freeze.holdPosition(this.player);
            ci.cancel();
        }
    }

    @Inject(method = "handleMoveVehicle", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void rollback$vehicle(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (Freeze.blocks(this.player)) {
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerClick", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void rollback$click(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (Freeze.blocks(this.player)) {
            this.player.containerMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void rollback$action(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (Freeze.blocks(this.player)) {
            this.player.connection.send(new ClientboundBlockUpdatePacket(this.player.level(), packet.getPos()));
            this.player.inventoryMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void rollback$creative(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (Freeze.blocks(this.player)) {
            this.player.inventoryMenu.sendAllDataToRemote();
            ci.cancel();
        }
    }

    @Inject(method = "handlePickItem", at = @At(value = "INVOKE", target = ENSURE, shift = At.Shift.AFTER), cancellable = true)
    private void rollback$pick(ServerboundPickItemPacket packet, CallbackInfo ci) {
        if (Freeze.blocks(this.player)) {
            ci.cancel();
        }
    }
}
