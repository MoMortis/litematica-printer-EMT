package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
                && !Configs.Placement.PRINT_SOUND.getBooleanValue()) {
            ci.cancel();
        }
    }
    //#endif
}
