package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinContainerScreenGuard {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void suppressAutomatedQuickShulkerScreen(@Nullable Screen screen, CallbackInfo ci) {
        if (screen instanceof AbstractContainerScreen<?>
                && InventoryUtils.shouldSuppressContainerScreen()) {
            ci.cancel();
        }
    }
}
