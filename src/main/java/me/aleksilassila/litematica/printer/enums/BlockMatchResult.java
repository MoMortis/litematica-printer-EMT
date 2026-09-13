package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
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
        // 空气必然直接放置：空气也是"可替换方块"，但若落入下方覆盖打印分支，
        // 默认配置（列表不含空气）会把它误判为错误方块，导致打印机无法放置任何方块
        if (currentState.isAir()) {
            return MISSING;
        }
        if (!requiredState.isAir() && BlockStateUtils.isReplaceable(currentState)) {
            // 覆盖打印：开关开启且现实方块匹配"覆盖打印 - 列表"（且列表项非预期方块本身）
            // 时按缺失处理（直接覆盖放置，不破坏）；其余可替换方块按错误方块走破坏
            if (Configs.Print.PRINT_REPLACE.getBooleanValue()
                    && matchesCoverList(currentState) && !matchesCoverList(requiredState)) {
                return MISSING;
            }
            return WRONG_BLOCK;
        }
        return WRONG_BLOCK;
    }

    // ==================== 覆盖打印列表匹配（按状态缓存） ====================
    // 拼音匹配昂贵（SkipListCache/ScanWhitelistCache 均为此建有缓存）；
    // IdentityHashMap 按 BlockState 身份缓存，调用方（扫描/引导）均在主线程

    private static final java.util.IdentityHashMap<BlockState, Boolean> COVER_LIST_CACHE =
            new java.util.IdentityHashMap<>();
    private static java.util.List<String> coverListSnapshot = java.util.List.of();

    private static boolean matchesCoverList(BlockState state) {
        java.util.List<String> list = Configs.Print.REPLACEABLE_LIST.getStrings();
        if (list != coverListSnapshot) {
            coverListSnapshot = java.util.List.copyOf(list);
            COVER_LIST_CACHE.clear();
        }
        Boolean cached = COVER_LIST_CACHE.get(state);
        if (cached == null) {
            cached = list.stream().anyMatch(string -> PinYinSearchUtils.matchName(string, state));
            COVER_LIST_CACHE.put(state, cached);
        }
        return cached;
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
        if (context.currentState.isAir()) {
            return MISSING;
        }
        if (!context.requiredState.isAir() && BlockStateUtils.isReplaceable(context.currentState)) {
            if (Configs.Print.PRINT_REPLACE.getBooleanValue()
                    && matchesCoverList(context.currentState) && !matchesCoverList(context.requiredState)) {
                return MISSING;
            }
            return WRONG_BLOCK;
        }
        return WRONG_BLOCK;
    }
}

