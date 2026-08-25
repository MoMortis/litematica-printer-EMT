package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem.reSwitchItem;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    @Inject(method = "handleOpenScreen", at = @At("HEAD"), cancellable = true)
    private void openHiddenQuickShulker(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!InventoryUtils.isAwaitingHiddenShulkerOpen() || minecraft.player == null) {
            return;
        }
        AbstractContainerMenu menu = packet.getType().create(packet.getContainerId(), minecraft.player.getInventory());
        if (InventoryUtils.openHiddenShulkerMenu(packet.getContainerId(), menu)) {
            ci.cancel();
        }
    }

    @Inject(
            method = "handleOpenScreen",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/screens/MenuScreens;create(Lnet/minecraft/world/inventory/MenuType;Lnet/minecraft/client/Minecraft;ILnet/minecraft/network/chat/Component;)V"
            ),
            cancellable = true
    )
    private void suppressTaskAnvilScreen(ClientboundOpenScreenPacket packet, CallbackInfo ci) {
        if (packet.getType() != MenuType.ANVIL
                || ActionManager.INSTANCE.consumeManualAnvilScreenAllowance()) {
            return;
        }
        if (ActionManager.INSTANCE.consumeTaskAnvilScreenSuppression()) {
            PacketUtils.sendPacket(new ServerboundContainerClosePacket(packet.getContainerId()));
            ci.cancel();
        }
    }

    @Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
    private void markHiddenQuickShulkerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        InventoryUtils.markHiddenShulkerContent(packet.getContainerId());
    }

    @Inject(method = "handleContainerClose", at = @At("HEAD"), cancellable = true)
    private void closeHiddenQuickShulker(ClientboundContainerClosePacket packet, CallbackInfo ci) {
        if (InventoryUtils.handleHiddenShulkerServerClose(packet.getContainerId())) {
            ci.cancel();
        }
    }

    @Inject(at = @At("TAIL"), method = "handleContainerContent")
    public void onInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (InventoryUtils.isHiddenShulkerContainer(packet.containerId())) {
            InventoryUtils.markHiddenShulkerContent(packet.containerId());
        }
        if (isOpenHandler) {
            InventoryUtils.switchInv();
        }
        if (reSwitchItem != null) {
            SwitchItem.reSwitchItem();
        }
        if (Minecraft.getInstance().player != null && ZxyUtils.printerMemoryAdding) {
            Minecraft.getInstance().player.closeContainer();
        }
        if (ZxyUtils.num == 1 || ZxyUtils.num == 3) {
            ZxyUtils.syncInv();
        }
    }
}
