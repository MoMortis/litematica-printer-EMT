package me.aleksilassila.litematica.printer.printer.verifier;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.SchematicPlacementAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 验证器"主动回填"路由：把打印机扫描器的判定产物提交给验证器复查管线。
 *
 * <p>被动监听（服务端方块变化事件）存在盲区——最典型的是区块卸载期间被第三方改动、
 * 玩家返回后区块重载时客户端整块收到新数据而<b>不产生逐方块事件</b>的情形，被动路径
 * 对此全盲。扫描器（打印主循环 / 扫描自动寻路的分区扫描）路过时会逐格对账
 * （原理图要求状态 vs 现实状态），把与验证器记录不一致的差异提交进复查管线，
 * 验证器数据随玩家动线自愈，无需手动重验。
 *
 * <p>仅优化版验证器（「验证器优化」开启）接受回填；提交的是<b>坐标</b>而非状态对，
 * 复查时以现势状态为准，与被动路径汇聚同一复查队列（HashSet 天然去重），
 * 不引入第二写路径与时序问题。差分去重（{@link OptimizedSchematicVerifier#probeMismatch}）
 * 以验证器自身分类语义为准，提交量正比于漂移量而非遍历量。
 */
public final class VerifierActiveUpdate {
    private VerifierActiveUpdate() {
    }

    /**
     * 扫描器判定回调：pos 处原理图要求 {@code required}、现实为 {@code found}。
     * 仅当所属放置的优化版验证器已完成初扫描、且差分判定发现记录与现势不一致时，
     * 该坐标才会进入复查队列（复查阶段原样重读双世界现势状态，此处状态仅用于差分）。
     */
    public static void onScannerVerdict(SchematicPlacement placement, BlockPos pos,
                                        BlockState required, BlockState found) {
        SchematicVerifier verifier = ((SchematicPlacementAccessor) placement).printer$getVerifier();
        if (!(verifier instanceof OptimizedSchematicVerifier optimized)) {
            return; // 原版验证器（含未开启优化）不具备主动回填能力
        }
        if (!optimized.isFinished()) {
            return; // 初扫描期间的差异由快照管线与被动事件覆盖
        }
        if (optimized.probeMismatch(pos, required, found)) {
            optimized.markBlockChanged(pos);
        }
    }
}
