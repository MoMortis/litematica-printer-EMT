package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.go.GoExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 KeyboardInput.tick() 尾部覆写寻路输入。
 *
 * <p>关键时序：LocalPlayer.aiStep 内先调 input.tick()（键盘重置 keyPresses），
 * 再做疾跑判定（canStartSprinting + keyPresses.sprint()），最后 super.aiStep 才经
 * applyInput 消费移动向量。若只在 applyInput 处覆写（MixinLocalPlayerGo），
 * 疾跑判定永远读到键盘重置后的 false——必须在此处（疾跑判定之前）写入。
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput {
    @Inject(method = "tick", at = @At("TAIL"))
    private void printer$goOverrideInput(CallbackInfo ci) {
        GoExecutor.onApplyInput(Minecraft.getInstance().player);
    }
}
