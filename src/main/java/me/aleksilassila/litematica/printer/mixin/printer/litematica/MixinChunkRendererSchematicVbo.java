package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.render.schematic.ChunkMeshDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherBuffers;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import fi.dy.masa.litematica.util.OverlayType;
import me.aleksilassila.litematica.printer.printer.RenderOnlyBlockCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仅渲染方块：两处过滤——
 * 1. addBlockEntity：箱子/告示牌等模型为空、靠方块实体渲染器绘制的外观（不过滤会漏掉）；
 * 2. renderOverlay：仅隐藏列表外方块的"缺失"类标记；
 *    多余方块和错误方块/错误状态的标记不阻止渲染（即使方块不在列表内）。
 * 两版本 litematica（0.26.12 / 0.27.10）这两个方法签名一致，无需预处理分支。
 */
@Mixin(value = ChunkRendererSchematicVbo.class, remap = false)
public class MixinChunkRendererSchematicVbo {

    @Inject(method = "addBlockEntity", at = @At("HEAD"), cancellable = true)
    private void printer$filterAddBlockEntity(BlockState state, BlockPos pos, ChunkMeshDataSchematic chunkMeshData,
                                              CallbackInfo ci) {
        if (!RenderOnlyBlockCache.shouldRender(state)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderOverlay", at = @At("HEAD"), cancellable = true)
    private void printer$filterRenderOverlay(OverlayType type, BlockPos pos, BlockState state, boolean missing,
                                             ChunkRenderDataSchematic data, ChunkMeshDataSchematic chunkMeshData,
                                             ChunkRenderDispatcherBuffers pack, CallbackInfo ci) {
        // 只隐藏列表外方块的"缺失"类标记；多余方块（原理图位置为空气）与
        // 错误方块/错误状态等标记即使是列表外的方块也保留（供破坏错误方块定位）
        if (!RenderOnlyBlockCache.shouldRender(state) && type == OverlayType.MISSING) {
            ci.cancel();
        }
    }
}
