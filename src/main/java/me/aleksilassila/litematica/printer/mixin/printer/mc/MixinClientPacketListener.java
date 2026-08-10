package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.guide.guides.ObserverPlacementGuard;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinClientPacketListener {

    /*** 玩家死亡后自动关闭打印机(避免持续执行打印发送数据包) ***/
    @Inject(method = "handleSetHealth", at = @At("RETURN"))
    private void injectHealthUpdate(ClientboundSetHealthPacket packet, CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (packet.getHealth() == 0 && Configs.Core.AUTO_DISABLE_PRINTER.getBooleanValue() && Configs.Core.WORK_SWITCH.getBooleanValue()) {
            MessageUtils.setOverlayMessage(I18n.AUTO_DISABLE_NOTICE.getName());
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
        }
    }

    /*** 服务器单方块更新：解除侦测器安全放置守卫的等待 ***/
    @Inject(method = "handleBlockUpdate", at = @At("RETURN"))
    private void litematica_printer$trackBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
            ObserverPlacementGuard.INSTANCE.onServerBlockUpdate(packet.getPos(), packet.getBlockState());
        }
    }

    /*** 服务器批量区块更新：解除侦测器安全放置守卫的等待 ***/
    @Inject(method = "handleChunkBlocksUpdate", at = @At("RETURN"))
    private void litematica_printer$trackChunkBlocksUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
            packet.runUpdates(ObserverPlacementGuard.INSTANCE::onServerBlockUpdate);
        }
    }
}
