package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 破冰放水的跨 tick 任务控制器（坐实 Guide 注释里的承诺）。
 *
 * 流程：目标为水源方块/含水方块且位置缺水时，
 * ① 先放置冰（new Action().setItem(Items.ICE)）→ ② 通过破坏队列直接破冰（工具切换交给 tweakeroo）→
 * ③ 本地预测到位置出现水（fluidState 非空）→ ④ 状态完成，交还普通 Guide 立即放置含水方块/水源判定。
 *
 * 放置顺序后置：新发起破冰放水前，必须等玩家交换范围（canInteracted）∩ 投影渲染层内的所有
 * "非水/非含水"普通方块都放置完毕，否则一直等待（不接管，让范围内的普通方块先被打印）。
 * 流动水等液体方块不计入，避免误判。
 *
 * 破坏队列非空时打印循环会整体暂停（MixinLocalPlayer.tick），天然充当破冰期间的等待，
 * 无需自建定时器。状态按 BlockPos.asLong() 存于 Map，跨 tick 保持。
 */
public class PrintTaskController {
    public static final PrintTaskController INSTANCE = new PrintTaskController();

    private enum Stage {
        NONE,          // 无任务
        NEED_ICE,      // 需要放冰
        ICE_PLACED,    // 冰已放置（等待进入破冰）
        BREAKING,      // 正在破冰（等待冰消失）
        WAITING_WATER  // 冰已破，等待水出现
    }

    /** 等待水源出现的超时（tick），超时后视为破冰失败，重新放冰 */
    private static final int WAIT_WATER_TIMEOUT_TICKS = 60;

    private final Map<Long, Stage> stages = new HashMap<>();
    private final Map<Long, Long> stageStartTicks = new HashMap<>();

    /** 未放完普通方块扫描：跨 tick 续扫状态（与打印主循环的分层续扫同机制，共用迭代时长限制） */
    private Iterator<BlockPos> scanIterator;
    private int lastSweptY = Integer.MIN_VALUE;
    private long scanTick = -1L;
    /** 本轮扫描已确认存在未放完的普通方块（含潜影盒口径，放水规则用） */
    private boolean scanPendingIncludingShulkers;
    /** 本轮扫描已确认存在未放完的普通方块（排除潜影盒口径，潜影盒规则用） */
    private boolean scanPendingExcludingShulkers;
    /** 本轮扫描是否已完整结束 */
    private boolean scanComplete = true;

    private PrintTaskController() {
    }

    /**
     * 由 PrintHandler.canProcessPos 在 Guides.buildAction 之前咨询。
     *
     * @return 非 null 表示本位置由破冰放水接管（返回的 Action 为放冰动作）；
     *         null 表示不接管（配置关闭/非水目标），或位置已含水/正在破冰/等待水（此时由
     *         {@link #isBreaking} / {@link #isWaitingWater} 区分后续处理）
     */
    @Nullable
    public Action handle(SchematicBlockContext ctx) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return null;
        }
        BlockState required = ctx.requiredState;
        if (!BlockStateUtils.isWaterBlock(required)) {
            return null;
        }

        BlockPos pos = ctx.blockPos;
        long key = pos.asLong();
        BlockState current = ctx.currentState;
        Stage stage = stages.getOrDefault(key, Stage.NONE);

        // 位置已经含水（本地预测）→ 完成，交还正常 Guide 放置含水方块/判定水源
        if (!current.getFluidState().isEmpty()) {
            stages.remove(key);
            stageStartTicks.remove(key);
            return null;
        }

        // 位置已是冰 → 进入破冰阶段
        if (current.is(Blocks.ICE)) {
            stages.put(key, Stage.BREAKING);
            return null;
        }

        // 位置是错误方块（不可替换、非水非冰非空）→ 清状态交还原流程破坏（DefaultGuide），破坏完后再放冰
        if (!current.isAir() && current.getFluidState().isEmpty()
                && !BlockStateUtils.isReplaceable(current)) {
            stages.remove(key);
            stageStartTicks.remove(key);
            return null;
        }

        // 冰已消失但水尚未同步到本地 → 等待水源
        if (stage == Stage.BREAKING) {
            stages.put(key, Stage.WAITING_WATER);
            stageStartTicks.put(key, getClientTick());
            return null;
        }
        if (stage == Stage.WAITING_WATER) {
            long start = stageStartTicks.getOrDefault(key, getClientTick());
            if (getClientTick() - start >= WAIT_WATER_TIMEOUT_TICKS) {
                // 超时（例如破冰被精准采集工具打断，冰掉落而非变水）→ 重新放冰
                stages.put(key, Stage.NEED_ICE);
                return new Action().setItem(Items.ICE);
            }
            return null;
        }

        // 放冰成功后等待客户端状态同步，避免在数据包放置或延迟同步时重复排队放冰。
        if (stage == Stage.ICE_PLACED) {
            return null;
        }

        // 放置顺序后置：玩家交换范围内还有待放置的普通方块（不含水/含水）时，
        // 不发起破冰放水，返回 null 让打印循环先处理普通方块。
        if (hasPendingOrdinaryInRange(false)) {
            return null;
        }

        // 需要放冰：显式 setItem(Items.ICE)，否则 getRequiredItems 会回退成水桶
        stages.put(key, Stage.NEED_ICE);
        return new Action().setItem(Items.ICE);
    }

    /**
     * 玩家交换范围 ∩ 投影渲染层内是否仍有"待放置的普通方块"。
     * 范围 = PlayerUtils.canInteracted(pos)（交互距离 + WORK_RANGE + ITERATOR_SHAPE）
     * 且 LitematicaUtils.isPositionWithinRange(pos)（当前投影渲染层）。
     * 排除所有液体方块与含水方块（由破冰放水/流体流程处理）；
     * excludeShulkers=true 时再排除其他潜影盒（它们同为后置放置，避免互相等待死锁）。
     *
     * 扫描与打印主循环一样受工作时长预算约束（{@link Configs.Core#ITERATION_TIME_LIMIT}）：
     * 超时后仅在 Y 层边界截断并缓存迭代器，下 tick 从截断点续扫，盒内每个位置每轮都会被
     * 检查到；截断期间返回的是已扫过部分的结论（结论最多滞后一轮完整扫描）。
     */
    public boolean hasPendingOrdinaryInRange(boolean excludeShulkers) {
        advanceScan();
        return excludeShulkers ? scanPendingExcludingShulkers : scanPendingIncludingShulkers;
    }

    /** 推进本轮扫描（每 tick 至多一次；两个口径共用一轮扫描，均已命中时提前结束） */
    private void advanceScan() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || SchematicWorldHandler.getSchematicWorld() == null) {
            scanIterator = null;
            scanComplete = true;
            scanPendingIncludingShulkers = false;
            scanPendingExcludingShulkers = false;
            return;
        }
        long tick = minecraft.level.getGameTime();
        if (tick == scanTick) {
            return; // 本 tick 已推进过
        }
        scanTick = tick;

        if (scanComplete) {
            // 上一轮已完整结束：开启新一轮扫描
            scanIterator = null;
            scanPendingIncludingShulkers = false;
            scanPendingExcludingShulkers = false;
            scanComplete = false;
        }

        if (scanIterator == null) {
            AtomicReference<PrinterBox> boxRef = ClientPlayerTickManager.PRINT.getBoxRef();
            PrinterBox box = boxRef == null ? null : boxRef.get();
            if (box == null) {
                scanComplete = true;
                return;
            }
            scanIterator = box.iterator();
            lastSweptY = Integer.MIN_VALUE;
        }

        ClientLevel level = minecraft.level;
        int timeLimit = Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue();
        long startTime = timeLimit > 0 ? System.nanoTime() : 0;
        long timeLimitNanos = timeLimit * 1_000_000L;
        int checkInterval = 10;
        int iterCount = 0;

        while (scanIterator.hasNext()) {
            BlockPos pos = scanIterator.next();
            if (pos == null) {
                continue;
            }
            // 分层截断：仅在进入新的 Y 层时检查预算，保证一整层 Y 被扫完才可能截断
            if (timeLimit > 0 && pos.getY() != lastSweptY && lastSweptY != Integer.MIN_VALUE
                    && System.nanoTime() - startTime >= timeLimitNanos) {
                return; // 下 tick 从此处续扫
            }
            lastSweptY = pos.getY();
            // 兜底：防止单层过大导致预算无限拖长（仅截断同一层，不影响 Y 层完整性）
            if (timeLimit > 0 && ++iterCount % checkInterval == 0
                    && System.nanoTime() - startTime >= timeLimitNanos) {
                return;
            }

            // 玩家交换范围之外的位置不参与"是否放完"判定
            if (!PlayerUtils.canInteracted(pos)) {
                continue;
            }
            // 投影渲染层之外的位置不参与"是否放完"判定
            if (!LitematicaUtils.isPositionWithinRange(pos)) {
                continue;
            }
            BlockState required = LitematicaUtils.getSchematicBlockState(pos);
            if (required == null || required.isAir()) {
                continue;
            }
            // 所有液体方块（水源/流动水/岩浆等）与含水方块由破冰放水/流体相关流程处理，不算普通方块
            if (required.getBlock() instanceof LiquidBlock || BlockStateUtils.isWaterBlock(required)) {
                continue;
            }
            // 已放完（含水性差异被 statesEqualIgnoreProperties 自动忽略）不算
            if (BlockStateUtils.statesEqualIgnoreProperties(level.getBlockState(pos), required)) {
                continue;
            }
            scanPendingIncludingShulkers = true;
            if (!(required.getBlock() instanceof ShulkerBoxBlock)) {
                scanPendingExcludingShulkers = true;
            }
            // 两个口径均已命中 → 结论已定，提前结束本轮（下 tick 开新一轮）
            if (scanPendingExcludingShulkers && scanPendingIncludingShulkers) {
                scanIterator = null;
                scanComplete = true;
                return;
            }
        }

        // 完整扫完一轮：两口径结论即为最终结论
        scanIterator = null;
        scanComplete = true;
    }

    /** 是否正处于破冰阶段（canProcessPos 应返回 true，executeIteration 里把冰入破坏队列） */
    public boolean isBreaking(BlockPos pos) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return false;
        }
        return stages.getOrDefault(pos.asLong(), Stage.NONE) == Stage.BREAKING;
    }

    /** 是否处于等待水源阶段（canProcessPos 应跳过该位置，保留状态等待水出现） */
    public boolean isWaitingWater(BlockPos pos) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return false;
        }
        return stages.getOrDefault(pos.asLong(), Stage.NONE) == Stage.WAITING_WATER;
    }

    /** 是否处于等待冰放置结果同步的阶段。 */
    public boolean isIcePlaced(BlockPos pos) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return false;
        }
        return stages.getOrDefault(pos.asLong(), Stage.NONE) == Stage.ICE_PLACED;
    }

    /** 放冰动作已发出后调用，标记冰已放置（下一 tick 会因位置变为冰而进入 BREAKING） */
    public void onIcePlaceSent(BlockPos pos) {
        long key = pos.asLong();
        if (stages.getOrDefault(key, Stage.NONE) == Stage.NEED_ICE) {
            stages.put(key, Stage.ICE_PLACED);
        }
    }

    public void reset() {
        stages.clear();
        stageStartTicks.clear();
        scanIterator = null;
        lastSweptY = Integer.MIN_VALUE;
        scanTick = -1L;
        scanPendingIncludingShulkers = false;
        scanPendingExcludingShulkers = false;
        scanComplete = true;
    }

    private static long getClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }
}
