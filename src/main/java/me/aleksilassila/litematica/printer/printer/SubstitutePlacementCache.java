package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.IdentifierUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 代替放置列表缓存：解析"代替列表"配置为目标方块 -> 代替方块集合 的映射，
 * 供 SubstituteGuide（放置候选）与破坏豁免（代替放置的结果不被当作错误方块挖掉）共用。
 *
 * <p>条目格式：每行一条"代替1,代替2,...:目标"，行内亦可用 ";" 分隔多条规则；
 * 分隔符 "," ";" "：" 与全角 "，" "；" 均可。
 *
 * <p>名称严格匹配：token 精确等于注册路径（fire_coral）、完整 ID（minecraft:fire_coral）
 * 或精确本地化译名（如"失活的火珊瑚"）才有效，不做模糊/拼音搜索；一个 token 命中
 * 多个方块时视为歧义，整条规则作废。
 *
 * <p>注册表静态，仅在开关或列表内容变化时于主线程重新解析，热路径零开销。
 */
public final class SubstitutePlacementCache {
    private static boolean enabled;
    private static List<String> source = List.of();
    /** 目标方块 -> 代替方块（有序去重，保持列表书写顺序） */
    private static final Map<Block, List<Block>> substitutesByTarget = new HashMap<>();

    private SubstitutePlacementCache() {
    }

    /** 代替放置是否生效：开关开启且列表解析结果非空 */
    public static boolean active() {
        refresh();
        return enabled && !substitutesByTarget.isEmpty();
    }

    /** 目标方块是否登记了代替方块 */
    public static boolean hasSubstitutes(Block target) {
        refresh();
        return substitutesByTarget.containsKey(target);
    }

    /** 目标方块的代替方块列表（无则返回空表） */
    public static List<Block> getSubstitutes(Block target) {
        refresh();
        return substitutesByTarget.getOrDefault(target, List.of());
    }

    /** 当前方块是否是目标方块登记的代替方块之一（破坏豁免判定） */
    public static boolean isSubstituteOf(Block target, Block candidate) {
        refresh();
        return substitutesByTarget.getOrDefault(target, List.of()).contains(candidate);
    }

    /** 开关或列表内容变化时重新解析（主线程专用） */
    private static void refresh() {
        boolean en = Configs.Print.SUBSTITUTE_PLACEMENT.getBooleanValue();
        List<String> cur = Configs.Print.SUBSTITUTE_LIST.getStrings();
        if (en == enabled && cur.equals(source)) {
            return;
        }
        enabled = en;
        source = List.copyOf(cur);
        substitutesByTarget.clear();
        if (!en) {
            return;
        }
        for (String line : cur) {
            parseLine(line);
        }
    }

    private static void parseLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        // 全角分隔符归一化后按 ";" 拆分多条规则
        String normalized = line.replace('，', ',').replace('；', ';').replace('：', ':');
        for (String rule : normalized.split(";")) {
            rule = rule.trim();
            if (rule.isEmpty()) {
                continue;
            }
            int sep = rule.lastIndexOf(':');
            if (sep <= 0 || sep == rule.length() - 1) {
                continue;
            }
            String targetToken = rule.substring(sep + 1).trim();
            Block target = resolveStrict(targetToken);
            if (target == null || target == Blocks.AIR) {
                continue;
            }
            Set<Block> subs = new LinkedHashSet<>();
            boolean valid = true;
            for (String token : rule.substring(0, sep).split(",")) {
                Block sub = resolveStrict(token.trim());
                if (sub == null || sub == Blocks.AIR || sub == target) {
                    valid = false; // 任一代替名无法唯一解析则整条作废（严格匹配）
                    break;
                }
                subs.add(sub);
            }
            if (valid && !subs.isEmpty()) {
                List<Block> merged = new ArrayList<>(substitutesByTarget.getOrDefault(target, List.of()));
                for (Block sub : subs) {
                    if (!merged.contains(sub)) {
                        merged.add(sub);
                    }
                }
                substitutesByTarget.put(target, merged);
            }
        }
    }

    /**
     * 严格解析：token 精确等于注册路径、完整 ID 或精确本地化译名之一，
     * 且唯一命中一个方块才返回结果；否则返回 null。
     */
    private static Block resolveStrict(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        // 完整 ID（含命名空间）：直接查注册表
        if (token.contains(":")) {
            try {
                Identifier id = IdentifierUtils.of(token);
                return BlockUtils.getBlock(id);
            } catch (Exception e) {
                return null;
            }
        }
        // 精确注册路径（默认命名空间写法）
        Block byPath = null;
        int pathHits = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (BuiltInRegistries.BLOCK.getKey(block).getPath().equals(token)) {
                byPath = block;
                pathHits++;
            }
        }
        if (pathHits == 1) {
            return byPath;
        }
        if (pathHits > 1) {
            return null; // 跨命名空间同名，歧义
        }
        // 精确本地化译名
        Block byName = null;
        int nameHits = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.getName().getString().equals(token)) {
                byName = block;
                nameHits++;
            }
        }
        return nameHits == 1 ? byName : null;
    }
}
