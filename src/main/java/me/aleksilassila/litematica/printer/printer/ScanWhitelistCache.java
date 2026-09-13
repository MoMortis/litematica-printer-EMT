package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.SchematicVerifierAccessor;
import me.aleksilassila.litematica.printer.printer.verifier.VerifierDataView;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import com.google.common.collect.HashMultimap;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 扫描白名单缓存（原 PrintHandler 内部类提取为共享类，供打印过滤与扫描自动寻路共用）。
 * 开启且列表非空时，打印扫描只处理列表内方块（匹配格式同 PRINT_SKIP 名单，
 * 经 PinYinSearchUtils 支持注册名/译名/#标签/拼音/包含匹配）。
 * 名单内容/开关变化时重建，并按方块状态缓存匹配结论
 * （同一状态在图中大量重复，实际拼音匹配次数趋近于零）。主线程专用。
 *
 * <p>扫描目标为并集：<b>白名单列表方块 ∪ 验证器高亮的"缺失方块"</b>。
 * 已验证完成的验证器中，选中整个"缺失方块"（MISSING）类别 → 所有未放置方块都算目标；
 * 逐条选中 → 该 (期望, 实际) 状态对的期望状态也算目标（不受白名单限制）。
 * 其余类别（多余方块等）的高亮不计入。高亮信息每 tick 限频刷新一次，
 * 变化时清空状态匹配缓存；白名单未生效时不做刷新（全量扫描，无并集语义）。
 */
public final class ScanWhitelistCache {
    private static List<String> source = List.of();
    private static boolean enabled;
    private static List<String> patterns = List.of();
    private static final Map<BlockState, Boolean> matchCache = new HashMap<>();

    /** 高亮刷新限频：记录上次刷新的 handler tick，避免热路径逐格重复遍历验证器 */
    private static long highlightRefreshTick = Long.MIN_VALUE;
    /** 验证器高亮：任一已验证完成的验证器选中了整个"缺失方块"类别 */
    private static boolean highlightAllMissing;
    /** 验证器高亮：逐条选中的"缺失方块"条目的期望状态并集 */
    private static final Set<BlockState> highlightStates = new HashSet<>();

    private ScanWhitelistCache() {
    }

    /** 白名单是否生效：开关开启且列表非空 */
    public static boolean active() {
        return enabled && !patterns.isEmpty();
    }

    /**
     * 该方块是否允许被扫描/放置（整个白名单扫描功能的统一判定）。
     * 白名单未生效时恒返回 true（全量扫描）；
     * 生效时返回"命中白名单 ∪ 命中验证器高亮的缺失方块"。
     */
    public static boolean isWhitelisted(BlockState requiredState) {
        boolean en = Configs.Print.PRINT_SCAN_WHITELIST.getBooleanValue();
        List<String> cur = Configs.Print.PRINT_SCAN_WHITELIST_LIST.getStrings();
        if (en != enabled || cur.size() != source.size() || !cur.equals(source)) {
            enabled = en;
            source = List.copyOf(cur);
            patterns = List.copyOf(cur);
            matchCache.clear();
        }
        if (!active()) {
            return true;
        }
        refreshHighlight(ClientPlayerTickManager.getCurrentHandlerTime());
        return matchCache.computeIfAbsent(requiredState, st -> {
            for (String s : patterns) {
                if (PinYinSearchUtils.matchName(s, st)) {
                    return true;
                }
            }
            // 并集：白名单未命中，但该状态是验证器高亮的"缺失方块"期望状态
            return highlightAllMissing || highlightStates.contains(st);
        });
    }

    /**
     * 刷新验证器高亮信息（每 tick 至多一次；开销为放置列表 × 验证器选择表的浅遍历）。
     * 高亮集合变化时清空 {@link #matchCache}，使新的并集结论立即生效。
     */
    private static void refreshHighlight(long now) {
        if (now == highlightRefreshTick) {
            return;
        }
        highlightRefreshTick = now;
        boolean allMissing = false;
        HashSet<BlockState> states = new HashSet<>();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            SchematicVerifier verifier = placement.getSchematicVerifier();
            if (verifier == null || !verifier.isFinished()) {
                continue; // 未验证完成的验证器没有可靠的缺失/高亮数据
            }
            Set<SchematicVerifier.MismatchType> selectedCats;
            HashMultimap<SchematicVerifier.MismatchType, SchematicVerifier.BlockMismatch> selectedMap;
            if (verifier instanceof VerifierDataView view) {
                // 优化版验证器：选择容器经视图接口暴露（读锁下拷贝）
                selectedCats = view.getSelectedMismatchTypes();
                selectedMap = view.getSelectedMismatchEntries();
            } else {
                SchematicVerifierAccessor accessor = (SchematicVerifierAccessor) verifier;
                selectedCats = accessor.printer$getSelectedCategories();
                selectedMap = accessor.printer$getSelectedEntries();
            }
            if (selectedCats != null && selectedCats.contains(SchematicVerifier.MismatchType.MISSING)) {
                allMissing = true;
                break; // 全类选中时所有未放置方块都是目标，期望状态集无需继续收集
            }
            Set<SchematicVerifier.BlockMismatch> selectedEntries =
                    selectedMap.get(SchematicVerifier.MismatchType.MISSING);
            for (SchematicVerifier.BlockMismatch mismatch : selectedEntries) {
                states.add(mismatch.stateExpected);
            }
        }
        if (allMissing != highlightAllMissing || !states.equals(highlightStates)) {
            highlightAllMissing = allMissing;
            highlightStates.clear();
            highlightStates.addAll(states);
            matchCache.clear();
        }
    }

    /** 验证器高亮信息是否非空（存在"缺失方块"类的高亮选择） */
    public static boolean hasHighlight() {
        return highlightAllMissing || !highlightStates.isEmpty();
    }
}
