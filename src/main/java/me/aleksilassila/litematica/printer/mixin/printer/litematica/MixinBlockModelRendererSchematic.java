package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic;
import me.aleksilassila.litematica.printer.printer.RenderOnlyBlockCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 仅渲染方块：面剔除修正。
 *
 * <p>litematica 的 shouldRenderModelSide() 用原理图世界里的邻居方块做遮挡判定
 * （Block.shouldRenderFace(stateIn, worldIn.getBlockState(邻居位置), side)）。
 * 邻居被"仅渲染方块列表"过滤掉后不再绘制，但它的遮挡判定仍然生效，
 * 于是列表内方块紧贴被隐藏方块的那一面会被错误剔除（极端情况下整块不可见）。
 *
 * <p>这里只在原始返回值为"被剔除"时修补：邻居方块会被隐藏时视为不遮挡，强制渲染该面。
 * 因此 litematica 原有的渲染边缘 / 半透明内侧例外逻辑完全保持不变。
 *
 * <p>两个版本的签名不同：1.21.11 为静态方法且多一个 cull 参数，
 * 26.x 为实例方法（无 cull 参数）；且 BlockAndTintGetter 的包名也不同
 * （1.21.11 在 world.level，26.x 在 client.renderer.block），故全部用全限定名分版本注入。
 */
@Mixin(value = BlockModelRendererSchematic.class, remap = false)
public class MixinBlockModelRendererSchematic {

    //#if MC >= 260100
    //$$ @Inject(method = "shouldRenderModelSide", at = @At("RETURN"), cancellable = true)
    //$$ private void printer$renderFaceIfNeighborHidden(net.minecraft.client.renderer.block.BlockAndTintGetter world,
    //$$                                                  BlockState state, BlockPos pos, Direction side, BlockPos neighbor,
    //$$                                                  CallbackInfoReturnable<Boolean> cir) {
    //$$     if (!cir.getReturnValueZ() && !RenderOnlyBlockCache.shouldRender(world.getBlockState(neighbor))) {
    //$$         cir.setReturnValue(true);
    //$$     }
    //$$ }
    //#else
    @Inject(method = "shouldRenderModelSide", at = @At("RETURN"), cancellable = true)
    private static void printer$renderFaceIfNeighborHidden(net.minecraft.world.level.BlockAndTintGetter world,
                                                           BlockState state, BlockPos pos, Direction side, boolean cull,
                                                           BlockPos neighbor, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && !RenderOnlyBlockCache.shouldRender(world.getBlockState(neighbor))) {
            cir.setReturnValue(true);
        }
    }
    //#endif
}