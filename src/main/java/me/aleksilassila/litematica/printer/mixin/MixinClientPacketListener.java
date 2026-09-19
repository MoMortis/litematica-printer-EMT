package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.PacketSoundConfirmationTracker;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

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

    @Inject(at = @At("TAIL"), method = "handleBlockUpdate")
    private void confirmPacketSound(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        PacketSoundConfirmationTracker.confirmServerBlockUpdate(packet.getPos(), packet.getBlockState());
    }

    // 批量子区块更新包：同一游戏刻内同一子区块有多个方块变化时，服务端会合并成此包发送，
    // 不确认的话数据包挖掘/打印的音效确认会大量超时丢失
    @Inject(at = @At("TAIL"), method = "handleChunkBlocksUpdate")
    private void confirmPacketSectionSound(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        packet.runUpdates(PacketSoundConfirmationTracker::confirmServerBlockUpdate);
    }

    @Inject(at = @At("TAIL"), method = "handleContainerContent")
    public void onInventory(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (isOpenHandler) {
            InventoryUtils.switchInv();
        }
        // 容器同步：只认「当前打开容器」的内容包。玩家背包(containerId=0)等无关内容包会在
        // 界面刚打开、真正的容器内容包尚未到达时提前触发 case 1/3，读到全空快照/全 0 禁用位
        if (ZxyUtils.num == 1 || ZxyUtils.num == 3) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null
                    && !minecraft.player.containerMenu.equals(minecraft.player.inventoryMenu)
                    && packet.containerId() == minecraft.player.containerMenu.containerId) {
                ZxyUtils.syncInv();
            }
        }
    }
}
