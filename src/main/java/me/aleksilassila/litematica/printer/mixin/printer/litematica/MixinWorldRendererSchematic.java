package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.render.schematic.WorldRendererSchematic;
import me.aleksilassila.litematica.printer.printer.RenderOnlyBlockCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 仅渲染方块：在原理图方块模型/流体的统一渲染入口处按"仅渲染方块列表"过滤。
 * 覆盖层（缺失/多余的彩色线框）在 ChunkRendererSchematicVbo 的独立分支绘制，不受影响。
 * 两版本 litematica（0.26.12 / 0.27.10）的 renderBlock / renderFluid 签名不同：
 * 1.21.11 裸代码为 0.26.12 经典签名（BufferBuilder 直出，renderFluid 为 void）；
 * 26.1.2 为 0.27.10 新渲染管线签名（//$$ 行，IBlockOutputSchematic 输出抽象）。
 */
@Mixin(value = WorldRendererSchematic.class, remap = false)
public class MixinWorldRendererSchematic {

    //#if MC >= 260100
    //$$ @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    //$$ private void printer$filterRenderBlock(fi.dy.masa.litematica.render.schematic.BlockModelRendererSchematic modelRenderer,
    //$$                                          net.minecraft.client.renderer.block.BlockAndTintGetter world,
    //$$                                          BlockState state, BlockPos pos, net.minecraft.world.phys.Vec3 cameraPos,
    //$$                                          fi.dy.masa.litematica.render.schematic.IBlockOutputSchematic output,
    //$$                                          CallbackInfoReturnable<Boolean> cir) {
    //$$     if (!RenderOnlyBlockCache.shouldRender(state)) {
    //$$         cir.setReturnValue(false);
    //$$     }
    //$$ }
    //$$
    //$$ @Inject(method = "renderFluid", at = @At("HEAD"), cancellable = true)
    //$$ private void printer$filterRenderFluid(fi.dy.masa.litematica.render.schematic.FluidModelRendererSchematic fluidRenderer,
    //$$                                        net.minecraft.client.renderer.block.BlockAndTintGetter world,
    //$$                                        BlockState state, net.minecraft.world.level.material.FluidState fluidState,
    //$$                                        BlockPos pos, net.minecraft.client.renderer.block.FluidRenderer.Output output,
    //$$                                        float partialTick, CallbackInfoReturnable<Boolean> cir) {
    //$$     if (!RenderOnlyBlockCache.shouldRender(state)) {
    //$$         cir.setReturnValue(false);
    //$$     }
    //$$ }
    //#else
    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void printer$filterRenderBlock(net.minecraft.world.level.BlockAndTintGetter world,
                                           BlockState state, BlockPos pos,
                                           com.mojang.blaze3d.vertex.PoseStack matrixStack,
                                           com.mojang.blaze3d.vertex.BufferBuilder buffer,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (!RenderOnlyBlockCache.shouldRender(state)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "renderFluid", at = @At("HEAD"), cancellable = true)
    private void printer$filterRenderFluid(net.minecraft.world.level.BlockAndTintGetter world,
                                           BlockState state, net.minecraft.world.level.material.FluidState fluidState,
                                           BlockPos pos, com.mojang.blaze3d.vertex.BufferBuilder buffer,
                                           CallbackInfo ci) {
        if (!RenderOnlyBlockCache.shouldRender(state)) {
            ci.cancel();
        }
    }
    //#endif
}
