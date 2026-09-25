package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.render.schematic.ChunkMeshDataSchematic;
import me.aleksilassila.litematica.printer.printer.RenderOnlyBlockCache;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 仅渲染方块：方块实体（箱子/告示牌等模型为空、靠方块实体渲染器绘制外观的方块）的过滤。
 *
 * <p>过滤点必须放在网格数据的收集处，而不是 ChunkRendererSchematicVbo.addBlockEntity()。
 * 后者除了把方块实体加入网格数据列表外，还负责"原理图世界里没有方块实体实例时创建并注册一个"
 * （newBlockEntity + addAndRegisterBlockEntity）；在那里拦截会连带跳过创建，
 * 导致仅渲染方块开启时列表外方块的原理图方块实体永远不存在，读箱子/告示牌数据只能拿到 null。
 *
 * <p>三个版本（1.21.11 / 26.1.2 / 26.2）这两个方法签名一致，无需预处理分支。
 */
@Mixin(value = ChunkMeshDataSchematic.class, remap = false)
public class MixinChunkMeshDataSchematic {

    @Inject(method = "addBlockEntity", at = @At("HEAD"), cancellable = true)
    private void printer$filterAddBlockEntity(BlockEntity be, CallbackInfo ci) {
        if (!RenderOnlyBlockCache.shouldRender(be.getBlockState())) {
            ci.cancel();
        }
    }

    @Inject(method = "addNoCullBlockEntity", at = @At("HEAD"), cancellable = true)
    private void printer$filterAddNoCullBlockEntity(BlockEntity be, CallbackInfo ci) {
        if (!RenderOnlyBlockCache.shouldRender(be.getBlockState())) {
            ci.cancel();
        }
    }
}