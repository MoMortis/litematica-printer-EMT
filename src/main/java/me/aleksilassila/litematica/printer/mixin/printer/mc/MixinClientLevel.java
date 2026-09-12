package me.aleksilassila.litematica.printer.mixin.printer.mc;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLevel.class)
public abstract class MixinClientLevel implements PacketUtils.SequenceExtension {

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
    //#endif

    //#if MC > 11802
    @Inject(method = "playLocalSound(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V", at = @At("HEAD"), cancellable = true)
    private void suppressPrinterPlacementSound(Entity entity, SoundEvent sound, SoundSource source,
                                                float volume, float pitch, CallbackInfo ci) {
        if (source == SoundSource.BLOCKS && ActionManager.INSTANCE.isPrintInteractionActive()
                && !Configs.Print.PRINT_SOUND.getBooleanValue()) {
            ci.cancel();
        }
    }
    //#endif

    /**
     * 世界方块变化通知（判定缓存失效信号一）。
     * 服务端方块回包、本地放置预测、流体/活塞等一切现实世界写入都经过此方法；
     * 原理图世界（WorldSchematic）的写入不影响现实世界判定，跳过。
     */
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void litematica_printer$onWorldSetBlock(BlockPos pos, BlockState state, int flags, int recursion,
                                                    CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        if ((Object) this instanceof WorldSchematic) {
            return;
        }
        SchematicStateCache.INSTANCE.onWorldBlockChanged(pos);
    }
}
