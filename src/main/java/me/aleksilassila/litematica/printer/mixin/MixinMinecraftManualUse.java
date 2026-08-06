package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftManualUse {

    @Inject(method = "startUseItem", at = @At("HEAD"))
    private void preserveManualAnvilScreens(CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.level != null
                && client.hitResult instanceof BlockHitResult blockHit
                && client.level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof AnvilBlock) {
            ActionManager.INSTANCE.prioritizeManualAnvilScreen();
        }
    }
}
