package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.go.GoExecutor;
import me.aleksilassila.litematica.printer.go.GoManager;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 寻路的输入覆写注入（LocalPlayer）：
 *
 * <ul>
 *   <li>{@code aiStep} 内 {@code input.tick()} 之后写入：位于疾跑判定（canStartSprinting）
 *       之前，保证 sprint 生效。灵魂出窍（tweakeroo）把 input 换成 tick 为空操作的
 *       DummyMovementInput，{@code KeyboardInput.tick} 的注入不会触发，此点是
 *       灵魂出窍下唯一的疾跑判定前写入时机（两版本字节码一致：input.tick() 在
 *       aiStep offset 158 无条件调用，疾跑判定在 offset ~377）。</li>
 *   <li>{@code applyInput} HEAD 写入：后备时机，此时相机 yaw 最新。</li>
 *   <li>{@code isControlledCamera} 强制 true（仅寻路激活期间）：原版 applyInput 全部
 *       由该门控决定是否消费输入；灵魂出窍把相机切走后若不强制，寻路输入会被整体
 *       忽略（tweakeroo 自身也用同样的强制注入保证本体可动，语义相同）。</li>
 * </ul>
 */
@Mixin(LocalPlayer.class)
public abstract class MixinLocalPlayerGo {
    @Inject(method = "aiStep",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/player/ClientInput;tick()V",
                     shift = At.Shift.AFTER))
    private void printer$goOverwriteInputBeforeSprint(CallbackInfo ci) {
        GoExecutor.onApplyInput((LocalPlayer) (Object) this);
    }

    @Inject(method = "applyInput", at = @At("HEAD"))
    private void printer$goOverwriteInput(CallbackInfo ci) {
        GoExecutor.onApplyInput((LocalPlayer) (Object) this);
    }

    @Inject(method = "isControlledCamera", at = @At("HEAD"), cancellable = true)
    private void printer$goForceControlledCamera(CallbackInfoReturnable<Boolean> cir) {
        if (GoManager.INSTANCE.isActive()) {
            cir.setReturnValue(true);
        }
    }
}
