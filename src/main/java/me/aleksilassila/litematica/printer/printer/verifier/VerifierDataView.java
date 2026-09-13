package me.aleksilassila.litematica.printer.printer.verifier;

import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Set;

/**
 * 打印机侧对验证器错误数据的统一读取视图。
 * <p>
 * 原版 {@link SchematicVerifier} 的错误表是私有字段（经 {@code SchematicVerifierAccessor}
 * 读取）；优化版 {@link OptimizedSchematicVerifier} 按子区块分桶存储、不物化 BlockPos，
 * 无法经同一 accessor 暴露。消费方（扫描自动寻路、扫描白名单）用
 * {@code verifier instanceof VerifierDataView} 分流：优化版走本接口，原版走 accessor。
 */
public interface VerifierDataView {

    /**
     * 遍历指定类型下已知的全部不匹配位置（不校验高亮选择，与原版 multimap 同语义；
     * 被忽略的状态对不回调，与原版"忽略后从表中剔除"的行为一致）。
     * 回调收到的坐标为世界坐标的 64 位打包值（{@link net.minecraft.core.BlockPos#asLong(int, int, int)}，
     * 可用 {@link net.minecraft.core.BlockPos#getX(long)} 等解码），避免遍历百万级错误时物化 BlockPos。
     * 回调返回 false 可提前终止遍历。
     */
    boolean forEachMismatch(SchematicVerifier.MismatchType type, MismatchVisitor visitor);

    /** 高亮选择：整类选中的不匹配类型集合（与原版同构，选中即该类全部有效） */
    Set<SchematicVerifier.MismatchType> getSelectedMismatchTypes();

    /** 高亮选择：逐条选中的不匹配条目（按类型分组，与原版同构） */
    com.google.common.collect.HashMultimap<SchematicVerifier.MismatchType, SchematicVerifier.BlockMismatch> getSelectedMismatchEntries();

    /** 不匹配遍历回调：pair 为 <期望状态, 实际状态>，packedPos 为世界坐标 64 位打包值 */
    interface MismatchVisitor {
        boolean accept(Pair<BlockState, BlockState> pair, long packedPos);
    }
}
