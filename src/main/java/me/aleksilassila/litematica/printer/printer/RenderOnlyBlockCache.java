package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.IdentifierUtils;
import fi.dy.masa.litematica.render.LitematicaRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 仅渲染方块缓存：解析"仅渲染方块列表"配置为方块集合，
 * 供注入投影模组渲染管线的 Mixin（方块模型/流体/方块实体三处）统一判定。
 *
 * <p>开关未开启时恒渲染全部；开关开启后只渲染集合内方块，
 * 列表为空（含全部条目解析失败）时不渲染任何方块。
 *
 * <p>名称严格匹配：token 精确等于注册路径（stone）、完整 ID（minecraft:stone）
 * 或精确本地化译名（如"石头"）才有效，不做模糊/拼音搜索；命中多个方块视为歧义作废。
 *
 * <p>注册表静态，仅在开关或列表内容变化时重新解析（渲染线程调用，解析只在变化时发生一次），
 * 热路径为一次不可变集合查询。
 *
 * <p>线程安全：查询发生在区块构建线程，重建可能由任意一条区块构建线程触发，
 * 而重建后又要让主线程的下一次全量重建生效。因此所有跨线程可见的状态都声明为 volatile，
 * 方块集合先构建完整快照再一次性发布（不在原位清空/填充），
 * 任何时刻读到的都是"完整旧快照"或"完整新快照"，不会读到半成品。
 */
public final class RenderOnlyBlockCache {
    private static volatile boolean enabled;
    private static volatile List<String> source = List.of();
    /** 不可变快照，重建时整体替换 */
    private static volatile Set<Block> blocks = Set.of();

    /** 配置变化计数：apply 时自增（首次初始化不计），驱动自动重建 */
    private static volatile int changeCounter;
    /** 自动重建已处理到的变化计数（仅主线程读写） */
    private static int lastSeenCounter;
    private static volatile boolean initialized;

    private RenderOnlyBlockCache() {
    }

    /**
     * 该方块是否允许被投影模组渲染。
     * 开关未开启时恒返回 true（全部照常渲染）；
     * 开启后返回"方块在列表内"（空列表 = 全部不渲染）。
     */
    public static boolean shouldRender(BlockState state) {
        refresh();
        if (!enabled) {
            return true;
        }
        return state != null && blocks.contains(state.getBlock());
    }

    /**
     * 每客户端 tick 调用（主线程）：配置变化后全量重建原理图渲染网格。
     * 过滤发生在网格重建阶段、结果被网格缓存，开关/列表变化不会自动作用于已渲染区块，
     * 因此检测到变化时调用 litematica 的 loadRenderers 全量重建（未加载世界时其内部安全跳过）。
     */
    public static void tickPendingReload() {
        if (changeCounter == lastSeenCounter) {
            return;
        }
        lastSeenCounter = changeCounter;
        if (Minecraft.getInstance().level != null) {
            LitematicaRenderer.getInstance().loadRenderers(null);
        }
    }

    /** 渲染线程兜底：本 tick 内 tick 检查之后的配置变化在下次重建前即时生效 */
    private static void refresh() {
        boolean en = Configs.Special.RENDER_ONLY_BLOCKS.getBooleanValue();
        List<String> cur = Configs.Special.RENDER_ONLY_BLOCK_LIST.getStrings();
        if (en == enabled && cur.equals(source)) {
            return;
        }
        apply(en, cur);
    }

    private static void apply(boolean en, List<String> cur) {
        boolean first = !initialized;
        Set<Block> snapshot = new HashSet<>();
        if (en) {
            for (String line : cur) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                // 全角分隔符归一化（与代替列表一致），条目内可用 ";" 写多个方块
                String normalized = line.replace('，', ',').replace('；', ';').replace('：', ':');
                for (String token : normalized.split("[,;]")) {
                    Block block = resolveStrict(token.trim());
                    if (block != null && block != Blocks.AIR) {
                        snapshot.add(block);
                    }
                }
            }
        }
        // 先发布完整快照，再发布开关：开关变为 true 时集合一定已就绪
        source = List.copyOf(cur);
        blocks = Set.copyOf(snapshot);
        enabled = en;
        initialized = true;
        if (!first) {
            changeCounter++;
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
