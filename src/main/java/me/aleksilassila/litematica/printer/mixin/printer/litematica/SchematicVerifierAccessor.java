package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * 暴露验证器的缺失方块位置表与高亮选择（扫描自动寻路取目标用）。
 * 键为 <期望状态, 实际状态> 对，值为该组合下所有缺失位置。
 * 高亮选择：selectedCategories 为整类选中的不匹配类型集合，
 * selectedEntries 为逐条选中的不匹配条目（按类型分组）。
 * 两版本 litematica（0.26.12 / 0.27.10）字段名与泛型一致。
 */
@Mixin(value = fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.class, remap = false)
public interface SchematicVerifierAccessor {
    @Accessor("missingBlocksPositions")
    ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getMissingBlocksPositions();

    @Accessor("selectedCategories")
    Set<SchematicVerifier.MismatchType> printer$getSelectedCategories();

    @Accessor("selectedEntries")
    HashMultimap<SchematicVerifier.MismatchType, SchematicVerifier.BlockMismatch> printer$getSelectedEntries();
}
