package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.go.GoRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 vanilla 调试渲染收集阶段（gizmo collector 激活作用域内）绘制寻路可视化。
 * emitGizmos(Frustum, DDD, F) 签名在 1.21.11 与 26.1.2 一致。
 */
@Mixin(DebugRenderer.class)
public abstract class MixinDebugRenderer {
    @Inject(method = "emitGizmos", at = @At("TAIL"))
    private void printer$goDrawPath(Frustum frustum, double camX, double camY, double camZ, float partialTick, CallbackInfo ci) {
        GoRenderer.emitGizmos();
    }
}
