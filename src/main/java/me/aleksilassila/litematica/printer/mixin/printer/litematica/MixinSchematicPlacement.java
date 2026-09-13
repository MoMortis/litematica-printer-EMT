package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import me.aleksilassila.litematica.printer.config.Configs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 「验证器优化」开关的注入点：开启后放置对象首次取验证器时改用
 * {@link me.aleksilassila.litematica.printer.printer.verifier.OptimizedSchematicVerifier}。
 * 实例照旧缓存在放置的 verifier 字段上（放置随进服重建），因此开关只在
 * 重进服务器后对新生成的放置生效；会话中途切换开关不会替换已存在的验证器。
 */
@Mixin(value = SchematicPlacement.class, remap = false)
public abstract class MixinSchematicPlacement {
    @Inject(method = "getSchematicVerifier", at = @At("HEAD"), cancellable = true)
    private void printer$useOptimizedVerifier(CallbackInfoReturnable<SchematicVerifier> cir) {
        if (!Configs.Core.VERIFIER_OPTIMIZED.getBooleanValue()) {
            return;
        }
        SchematicPlacement self = (SchematicPlacement) (Object) this;
        SchematicPlacementAccessor accessor = (SchematicPlacementAccessor) self;
        SchematicVerifier current = accessor.printer$getVerifier();
        if (current == null) {
            // 维度切换时 litematica 会重建放置对象（hashId 不变）：
            // 注册表命中则重挂旧验证器，错误数据/忽略设置/高亮选择跨维度保留
            me.aleksilassila.litematica.printer.printer.verifier.OptimizedSchematicVerifier reused =
                    me.aleksilassila.litematica.printer.printer.verifier.VerifierRegistry.get(self.getHashId());
            if (reused != null) {
                reused.rebindPlacement(self);
                accessor.printer$setVerifier(reused);
                cir.setReturnValue(reused);
                return;
            }
            SchematicVerifier optimized = new me.aleksilassila.litematica.printer.printer.verifier.OptimizedSchematicVerifier();
            accessor.printer$setVerifier(optimized);
            cir.setReturnValue(optimized);
        }
        // 已有实例（原版或优化版）时回落到原方法原样返回，不替换——保证"重进服务器后生效"
    }
}
