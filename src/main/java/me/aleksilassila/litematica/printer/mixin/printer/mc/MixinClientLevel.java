package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.mixin_extension.ClientLevelExtension;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements PacketUtils.SequenceExtension, ClientLevelExtension {

    //#if MC > 11802
    @Final
    @Shadow
    private net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler blockStatePredictionHandler;

    @Override
    public int litematica_printer3$getSequence() {
        try (net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler pendingUpdateManager = blockStatePredictionHandler) {
            return pendingUpdateManager.currentSequence();
        }
    }

    @Override
    public boolean litematica_printer3$hasPendingPrediction(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        // 正在预测且该位置有服务器待确认状态 → 视为刚放置、服务器尚未确认
        if (!blockStatePredictionHandler.isPredicting()) {
            return false;
        }
        return blockStatePredictionHandler instanceof me.aleksilassila.litematica.printer.mixin_extension.BlockStatePredictionHandlerExtension extension
                && extension.litematica_printer3$contains(pos);
    }
    //#endif
}
