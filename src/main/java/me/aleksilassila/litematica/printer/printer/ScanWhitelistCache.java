package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描白名单缓存（原 PrintHandler 内部类提取为共享类，供打印过滤与扫描自动寻路共用）。
 * 开启且列表非空时，打印扫描只处理列表内方块（匹配格式同 PRINT_SKIP 名单，
 * 经 PinYinSearchUtils 支持注册名/译名/#标签/拼音/包含匹配）。
 * 名单内容/开关变化时重建，并按方块状态缓存匹配结论
 * （同一状态在图中大量重复，实际拼音匹配次数趋近于零）。主线程专用。
 */
public final class ScanWhitelistCache {
    private static List<String> source = List.of();
    private static boolean enabled;
    private static List<String> patterns = List.of();
    private static final Map<BlockState, Boolean> matchCache = new HashMap<>();

    private ScanWhitelistCache() {
    }

    /** 白名单是否生效：开关开启且列表非空 */
    public static boolean active() {
        return enabled && !patterns.isEmpty();
    }

    /**
     * 该方块是否允许被扫描/放置。白名单未生效时恒返回 true（全量扫描）。
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
        return matchCache.computeIfAbsent(requiredState, st -> {
            for (String s : patterns) {
                if (PinYinSearchUtils.matchName(s, st)) {
                    return true;
                }
            }
            return false;
        });
    }
}
