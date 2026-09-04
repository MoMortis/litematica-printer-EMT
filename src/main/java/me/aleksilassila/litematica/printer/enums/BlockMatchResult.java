package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.BlockState;

public enum BlockMatchResult {
    /**
     * 缺失方块：实际位置为空，或当前方块在可替换列表中且启用了替换功能
     */
    MISSING,

    /**
     * 方块错误：方块类型完全不同，且不满足缺失/状态错误的条件
     */
    WRONG_BLOCK,

    /**
     * 状态错误：方块类型相同，但方块状态（如朝向、亮度等）不一致
     */
    WRONG_STATE,

    /**
     * 正确匹配：原理图方块与实际方块的类型和状态完全一致
     */
    CORRECT;


    public static BlockMatchResult compare(SchematicBlockContext context, Property<?>... propertiesToIgnore) {
        if (propertiesToIgnore.length == 0) {
            return compare(context.requiredState, context.currentState);
        }
        return compareWithIgnoredProperties(context, propertiesToIgnore);
    }

    /** 无需上下文对象的状态比较（判定缓存使用） */
    public static BlockMatchResult compare(BlockState requiredState, BlockState currentState) {
        if (requiredState.equals(currentState)) {
            return CORRECT;
        }
        if (requiredState.getBlock().equals(currentState.getBlock())) {
            if (BlockStateUtils.statesEqualIgnoreProperties(requiredState, currentState)) {
                return CORRECT;
            }
            return WRONG_STATE;
        }
        if (!requiredState.isAir() && BlockStateUtils.isReplaceable(currentState)) {
            return MISSING;
        }
        return WRONG_BLOCK;
    }

    private static BlockMatchResult compareWithIgnoredProperties(SchematicBlockContext context, Property<?>[] propertiesToIgnore) {
        if (context.requiredState.equals(context.currentState)) {
            return CORRECT;
        }
        if (context.requiredState.getBlock().equals(context.currentState.getBlock())) {
            if (BlockStateUtils.statesEqualIgnoreProperties(context.requiredState, context.currentState, propertiesToIgnore)) {
                return CORRECT;
            }
            return WRONG_STATE;
        }
        if (!context.requiredState.isAir() && BlockStateUtils.isReplaceable(context.currentState)) {
            return MISSING;
        }
        return WRONG_BLOCK;
    }
}

