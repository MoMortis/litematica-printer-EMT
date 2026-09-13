package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露放置对象缓存的验证器实例（{@code getSchematicVerifier()} 的懒初始化字段）。
 * 两版本 litematica（0.26.12 / 0.27.10）字段名与类型一致。
 */
@Mixin(value = SchematicPlacement.class, remap = false)
public interface SchematicPlacementAccessor {
    @Accessor("verifier")
    SchematicVerifier printer$getVerifier();

    @Accessor("verifier")
    void printer$setVerifier(SchematicVerifier verifier);
}
