package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.go.GoExecutor;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 LocalPlayer.applyInput() 头部让寻路执行器覆写玩家输入。
 * 该时机晚于 KeyboardInput.tick()（原版键位已写入），早于 xxa/zxa/jumping 消费，
 * 且此时相机 yaw 最新，可把"期望世界方向"精确换算为与视角无关的移动向量。
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerGo {
    @Inject(method = "applyInput", at = @At("HEAD"))
    private void printer$goOverwriteInput(CallbackInfo ci) {
        GoExecutor.onApplyInput((LocalPlayer) (Object) this);
    }
}
