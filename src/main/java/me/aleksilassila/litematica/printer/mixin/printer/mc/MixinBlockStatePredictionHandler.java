package me.aleksilassila.litematica.printer.mixin.printer.mc;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import me.aleksilassila.litematica.printer.mixin_extension.BlockStatePredictionHandlerExtension;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 暴露 BlockStatePredictionHandler 内部"服务器已确认/待确认状态"查询，
 * 供侦测器安全放置守卫判断输入面方块是否刚放置、服务器尚未确认。
 */
@Mixin(BlockStatePredictionHandler.class)
public abstract class MixinBlockStatePredictionHandler implements BlockStatePredictionHandlerExtension {
    @Shadow
    @Final
    private Long2ObjectOpenHashMap<?> serverVerifiedStates;

    @Override
    public boolean litematica_printer3$contains(BlockPos pos) {
        return pos != null && serverVerifiedStates.containsKey(pos.asLong());
    }
}
