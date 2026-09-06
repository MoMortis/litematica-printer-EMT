package me.aleksilassila.litematica.printer.go;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.SectionExpandAlgorithmType;
import me.aleksilassila.litematica.printer.enums.SectionScanOrderType;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.SchematicVerifierAccessor;
import me.aleksilassila.litematica.printer.printer.ScanWhitelistCache;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;

/**
 * 扫描自动寻路（"打印 → 扫描自动寻路"开关，须同时开启"扫描白名单"且列表非空）。
 *
 * <p>目标来源两级：<b>优先使用原理图验证器（Schematic Verifier）的缺失方块列表</b>
 * ——验证器已验证完成时，从缺失方块（经扫描白名单过滤，或验证器高亮的"缺失方块"）中
 * 选离玩家最近的派发寻路，列表由 litematica 随世界方块变化自动维护；
 * 验证器未验证完成或无候选时，<b>退回子区块逐格扫描</b>：在玩家当前子区块（16³）内逐格扫描
 * 原理图中未放置的白名单方块或高亮缺失方块的期望状态，扫到即派发 /go 寻路把玩家载到该方块的
 * 紧邻位置（水平相邻、上下 ±1 层，不占用目标格）；到达后释放控制权（玩家自由移动），
 * 无限等待打印机放置完成；完成后继续扫描（玩家若已换子区块，则以玩家新区块为起点重建 BFS 队列）。
 * 派发时序：上一条自动寻路任务走完（自然到达/结束，不中途打断）后，
 * 先复核新目标是否已被放置，再派发新任务。
 * 子区块按配置轴序向外扩展，加载范围内无待放方块时待命。
 * 扫描每 tick 受「工作时长预算」限制；只依赖判定缓存点查，不干预打印机的任何逻辑。
 */
public final class AutoWalkScanner {
    public static final AutoWalkScanner INSTANCE = new AutoWalkScanner();

    private static final int CELLS_PER_SECTION = 16 * 16 * 16;
    /** 目标不可达（寻路无路）时的尝试冷却（tick），所在区块重扫时回头再试 */
    private static final long UNREACHABLE_COOLDOWN_TICKS = 200;
    /** 打印机交互距离的平方（原版生存放置射程 4.5 格，眼睛到目标方块中心）：
     *  寻路腿结束时玩家仍在该范围内即视为"到位"，等待打印机放置而非按不可达换目标 */
    private static final double PRINTER_REACH_SQ = 4.5 * 4.5;
    /** 待命态的世界变化复查间隔（tick）：revision 有变化才全量重扫 */
    private static final long DONE_RECHECK_INTERVAL_TICKS = 100;

    private enum State { IDLE, SCANNING, DRIVING, ARRIVED_WAITING, DONE }

    private final Minecraft mc = Minecraft.getInstance();

    private State state = State.IDLE;

    /** 当前扫描子区块的最小角坐标 */
    @Nullable
    private BlockPos cursorSection;
    /** 子区块内扁平游标（x + y*16 + z*256），跨 tick 续扫 */
    private int cursorIndex;
    private final ArrayDeque<BlockPos> sectionQueue = new ArrayDeque<>();
    private final LongOpenHashSet visitedSections = new LongOpenHashSet();
    private final Long2LongOpenHashMap unreachableCooldown = new Long2LongOpenHashMap();

    @Nullable
    private BlockPos target;
    private long revisionAtDone = -1L;
    private long nextDoneRecheckTick = -1L;
    private boolean suspendedByManual;
    /** 进入扫描态时置位：先尝试从验证器缺失列表选目标（一次一用，避免每 tick 轮询整表） */
    private boolean verifierPollNeeded;

    private AutoWalkScanner() {
    }

    /** 到达后等待放置的目标（供渲染描边提示当前在等哪个方块） */
    @Nullable
    public BlockPos getWaitingTarget() {
        return state == State.ARRIVED_WAITING ? target : null;
    }

    /** 每客户端 tick（ClientPlayerTickManager.tick 中 GoManager 之后调用） */
    public void tick() {
        if (!conditionsMet()) {
            deactivate();
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        // 手动 /go 优先：暂停自动（停掉自动腿），手动任务结束后恢复并重新派发
        if (GoManager.INSTANCE.isManualActive()) {
            if (!suspendedByManual) {
                suspendedByManual = true;
                if (GoManager.INSTANCE.isAutoActive()) {
                    GoManager.INSTANCE.stop(null);
                }
            }
            return;
        }
        if (suspendedByManual) {
            suspendedByManual = false;
            if (state == State.DRIVING && target != null) {
                GoManager.INSTANCE.autoDispatch(target); // 恢复：重新派发同一目标
            }
        }

        switch (state) {
            case IDLE -> activate();
            case SCANNING -> tickScan();
            case DRIVING -> tickDriving();
            case ARRIVED_WAITING -> tickWaiting();
            case DONE -> tickDone();
        }
    }

    // ==================== 状态处理 ====================

    private void activate() {
        visitedSections.clear();
        sectionQueue.clear();
        unreachableCooldown.clear();
        suspendedByManual = false;
        BlockPos base = playerSectionBase();
        visitedSections.add(sectionKey(base));
        cursorSection = base;
        cursorIndex = 0;
        enterScanning();
    }

    private void tickScan() {
        ClientLevel level = mc.level;
        BlockPos base = cursorSection;
        if (level == null || base == null) {
            state = State.IDLE;
            return;
        }
        // 上一条自动寻路还在走：等它走完再派发新任务（不中途打断行走中的旧任务）
        if (GoManager.INSTANCE.isAutoActive()) {
            return;
        }
        // 进入扫描态后先给验证器一次机会（优先验证器缺失列表，最近优先）
        if (verifierPollNeeded) {
            verifierPollNeeded = false;
            if (tryDispatchVerifierTarget(level)) {
                return;
            }
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        long budgetNanos = Math.max(1, Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue()) * 1_000_000L;
        long start = System.nanoTime();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (; cursorIndex < CELLS_PER_SECTION; cursorIndex++) {
            if ((cursorIndex & 15) == 0 && System.nanoTime() - start >= budgetNanos) {
                return; // 预算用尽，下 tick 续扫
            }
            int x = cursorIndex & 15;
            int y = (cursorIndex >> 4) & 15;
            int z = cursorIndex >> 8;
            cursor.set(base.getX() + x, base.getY() + y, base.getZ() + z);
            // 未加载列跳过（判定缓存对未加载区块不落缓存，必须显式排除防假目标）
            if (!level.hasChunk(cursor.getX() >> 4, cursor.getZ() >> 4)) {
                continue;
            }
            BlockState schematic = SchematicStateCache.INSTANCE.getSchematicState(cursor);
            if (schematic == null) {
                continue; // 非原理图方块
            }
            // 候选 = 白名单列表 ∪ 验证器高亮的"缺失方块"（统一判定在 ScanWhitelistCache 内）
            if (!ScanWhitelistCache.isWhitelisted(schematic)) {
                continue;
            }
            if (SchematicStateCache.INSTANCE.isVerifiedNoWork(cursor, level)) {
                continue; // 已放置到位
            }
            long key = cursor.asLong();
            if (unreachableCooldown.get(key) > now) {
                continue; // 不可达冷却中
            }
            // 派发前复核该候选方块此刻仍未放置（扫描游标可能滞后数 tick）
            if (targetCompleted(cursor)) {
                continue; // 扫描期间已被放置，跳过继续扫
            }
            target = cursor.immutable();
            GoManager.INSTANCE.autoDispatch(target);
            state = State.DRIVING;
            return;
        }
        // 本子区块扫完且无待放目标 → 取下一区块
        sectionDone();
    }

    private void tickDriving() {
        BlockPos t = target;
        if (t == null) {
            enterScanning();
            return;
        }
        // 目标已被放置（玩家顺路经过时打印可能提前完成）→ 不打断行走中的旧任务腿，
        // 让它走完（由 tickScan 顶部的等待逻辑兜住），之后继续扫描派发新目标
        if (targetCompleted(t)) {
            onTargetDone();
            return;
        }
        if (GoManager.INSTANCE.isActive()) {
            return; // 还在路上
        }
        // 寻路已结束（含无可行路径的立即失败）：只要玩家仍在打印机的交互距离内
        // （原版 4.5 格，眼睛到目标方块中心），就视为"到位"——等待打印机放置而非
        // 按不可达换目标。否则紧邻格被挡住（寻路无路径/停在 2 格外或上下 2 层）时，
        // 玩家明明就在方块边上、打印机可放置，扫描器却会冷却该方块并派发新任务
        LocalPlayer player = mc.player;
        if (player != null) {
            double dx = t.getX() + 0.5 - player.getX();
            double dy = t.getY() + 0.5 - player.getEyeY();
            double dz = t.getZ() + 0.5 - player.getZ();
            if (dx * dx + dy * dy + dz * dz <= PRINTER_REACH_SQ) {
                state = State.ARRIVED_WAITING; // 释放控制，无限等待该方块放置
                return;
            }
        }
        // 不可达 → 冷却该方块（所在区块重扫时回头再试）
        unreachableCooldown.put(t.asLong(), ClientPlayerTickManager.getCurrentHandlerTime() + UNREACHABLE_COOLDOWN_TICKS);
        target = null;
        enterScanning();
    }

    private void tickWaiting() {
        BlockPos t = target;
        if (t == null) {
            enterScanning();
            return;
        }
        // 无限等待，直到目标被正确放置（CORRECT）；放错状态/放错方块期间打印机
        // 会破坏重放，正确后才会继续派发新任务
        if (targetCompleted(t)) {
            onTargetDone();
        }
    }

    private void tickDone() {
        LocalPlayer player = mc.player;
        if (player == null || cursorSection == null) {
            state = State.IDLE;
            return;
        }
        // 玩家换子区块 → 从玩家新区块重建
        if (!inSameSectionAsPlayer(cursorSection)) {
            activate();
            return;
        }
        // 世界/原理图有变化（限频）→ 全量重扫；或验证器新产生了可派发的缺失目标（如刚验证完成）→ 重新激活
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now >= nextDoneRecheckTick) {
            if (SchematicStateCache.INSTANCE.getRevision() != revisionAtDone) {
                activate();
                return;
            }
            ClientLevel level = mc.level;
            if (level != null && pollNearestVerifierTarget(level) != null) {
                activate();
            }
        }
    }

    // ==================== 子区块推进 ====================

    /** 当前子区块扫完：按配置的轴序（每轴先 + 后 -）将相邻子区块入队（BFS），然后出队下一个 */
    private void sectionDone() {
        enqueueNeighbors(cursorSection);
        pollNextSection();
    }

    private void enqueueNeighbors(@Nullable BlockPos base) {
        if (base == null) {
            return;
        }
        int sx = base.getX() >> 4;
        int sy = base.getY() >> 4;
        int sz = base.getZ() >> 4;
        SectionScanOrderType order = (SectionScanOrderType) Configs.Print.PRINT_SCAN_SECTION_ORDER.getOptionListValue();
        SectionExpandAlgorithmType algo =
                (SectionExpandAlgorithmType) Configs.Print.PRINT_SCAN_EXPAND_ALGO.getOptionListValue();
        boolean xReverse = Configs.Print.PRINT_SCAN_X_REVERSE.getBooleanValue();
        boolean yReverse = Configs.Print.PRINT_SCAN_Y_REVERSE.getBooleanValue();
        boolean zReverse = Configs.Print.PRINT_SCAN_Z_REVERSE.getBooleanValue();
        ArrayList<BlockPos> pendingNeighbors = new ArrayList<>(6);
        for (SectionScanOrderType.Axis axis : order.axis) {
            // 轴向默认先 + 后 -，反转配置则先 - 后 +（与核心目录遍历反向同语义）
            boolean reverse = axis == SectionScanOrderType.Axis.X ? xReverse
                    : axis == SectionScanOrderType.Axis.Y ? yReverse : zReverse;
            int[] signs = reverse ? new int[]{-1, 1} : new int[]{1, -1};
            for (int sign : signs) {
                int nx = sx + axis.dx * sign;
                int ny = sy + axis.dy * sign;
                int nz = sz + axis.dz * sign;
                if (visitedSections.add(sectionKey(nx, ny, nz))) {
                    pendingNeighbors.add(new BlockPos(nx << 4, ny << 4, nz << 4));
                }
            }
        }
        // BFS：按配置顺序排到队尾（逐层向外扩散）；
        // DFS：逆序压栈头，使配置顺序的第一个方向最后压入、位于栈顶（一路推进到底，LIFO）
        if (algo == SectionExpandAlgorithmType.DFS) {
            for (int i = pendingNeighbors.size() - 1; i >= 0; i--) {
                sectionQueue.addFirst(pendingNeighbors.get(i));
            }
        } else {
            for (BlockPos neighbor : pendingNeighbors) {
                sectionQueue.addLast(neighbor);
            }
        }
    }

    private void pollNextSection() {
        ClientLevel level = mc.level;
        while (!sectionQueue.isEmpty()) {
            BlockPos next = sectionQueue.poll();
            // 远离原理图的子区块直接跳过（不占扫描预算）
            if (level != null && SchematicStateCache.INSTANCE.intersectsSchematic(next, next.offset(15, 15, 15))) {
                cursorSection = next;
                cursorIndex = 0;
                enterScanning();
                return;
            }
        }
        // 队列耗尽：待命
        revisionAtDone = SchematicStateCache.INSTANCE.getRevision();
        nextDoneRecheckTick = ClientPlayerTickManager.getCurrentHandlerTime() + DONE_RECHECK_INTERVAL_TICKS;
        state = State.DONE;
    }

    // ==================== 验证器目标源 ====================

    /** 进入扫描态：先尝试验证器缺失列表选目标（离玩家最近），无候选时退回子区块逐格扫描 */
    private void enterScanning() {
        state = State.SCANNING;
        verifierPollNeeded = true;
    }

    /**
     * 从验证器缺失列表派发目标（离玩家最近优先）。调用前保证旧自动任务已结束
     * （tickScan 顶部的等待逻辑）；派发前复核目标仍未放置。
     *
     * @return true 表示已派发（进入 DRIVING）；false 表示验证器无候选，交由扫描器兜底
     */
    private boolean tryDispatchVerifierTarget(ClientLevel level) {
        BlockPos best = pollNearestVerifierTarget(level);
        if (best == null) {
            return false;
        }
        if (targetCompleted(best)) {
            return false; // 选目标到派发之间恰好被放置，下 tick 重新选
        }
        target = best;
        GoManager.INSTANCE.autoDispatch(best);
        state = State.DRIVING;
        return true;
    }

    /**
     * 从所有"已验证完成"的原理图验证器的缺失方块列表中选离玩家最近的合法目标。
     * 过滤：{@link me.aleksilassila.litematica.printer.printer.ScanWhitelistCache} 统一判定
     * （白名单列表 ∪ 验证器高亮的"缺失方块"，仅 MISSING 类，按期望状态）、
     * 区块已加载、不可达冷却、已完成。
     * 列表由 litematica 自动维护（世界方块变化进入复查队列实时修正），
     * 个别条目滞后由 targetCompleted 复核兜底。
     * 验证器未验证完成或无候选时返回 null（退回扫描器）。
     */
    @Nullable
    private BlockPos pollNearestVerifierTarget(ClientLevel level) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return null;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            SchematicVerifier verifier = placement.getSchematicVerifier();
            if (verifier == null || !verifier.isFinished()) {
                continue; // 该放置未验证过 → 无验证器目标，交由扫描器兜底
            }
            ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> missing =
                    ((SchematicVerifierAccessor) verifier).printer$getMissingBlocksPositions();
            if (missing == null || missing.isEmpty()) {
                continue;
            }
            for (Pair<BlockState, BlockState> key : missing.keySet()) {
                if (!ScanWhitelistCache.isWhitelisted(key.getLeft())) {
                    continue; // 白名单外且未被高亮的缺失方块（统一判定）
                }
                for (BlockPos pos : missing.get(key)) {
                    if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue;
                    }
                    if (unreachableCooldown.get(pos.asLong()) > now) {
                        continue;
                    }
                    if (targetCompleted(pos)) {
                        continue;
                    }
                    double dx = pos.getX() + 0.5 - player.getX();
                    double dy = pos.getY() + 0.5 - player.getY();
                    double dz = pos.getZ() + 0.5 - player.getZ();
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    // ==================== 目标完成与切换 ====================

    /**
     * 目标完成：目标已不在原理图中（原理图变更）或世界状态已与原理图完全一致（CORRECT）。
     * 注意必须严格 CORRECT：放错状态/放错方块（WRONG_STATE/WRONG_BLOCK）不算完成——
     * 否则打印机刚放下但状态不对（还需破坏重放）时扫描器会误判已完成而直接派发新任务；
     * 未到 CORRECT 期间继续等待/继续以该方块为目标，打印机破坏重放正确后才继续。
     */
    private boolean targetCompleted(BlockPos t) {
        ClientLevel level = mc.level;
        if (level == null) {
            return true;
        }
        BlockState required = SchematicStateCache.INSTANCE.getSchematicState(t);
        if (required == null) {
            return true;
        }
        return me.aleksilassila.litematica.printer.enums.BlockMatchResult
                .compare(required, level.getBlockState(t)) == me.aleksilassila.litematica.printer.enums.BlockMatchResult.CORRECT;
    }

    /**
     * 目标完成后继续。旧任务腿不打断：若寻路仍在走，由 tickScan 顶部的
     * 等待逻辑兜住，等它走完再派发新任务；玩家若已换子区块，
     * 则以玩家新区块为起点重建 BFS（放弃旧游标）。
     */
    private void onTargetDone() {
        target = null;
        BlockPos base = cursorSection;
        if (base == null || !inSameSectionAsPlayer(base)) {
            activate();
            return;
        }
        enterScanning();
    }

    // ==================== 条件与工具 ====================

    private boolean conditionsMet() {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isDeadOrDying()) {
            return false;
        }
        if (!Configs.Print.PRINT_SCAN_WHITELIST.getBooleanValue()
                || !Configs.Print.PRINT_SCAN_AUTOWALK.getBooleanValue()) {
            return false;
        }
        if (Configs.Print.PRINT_SCAN_WHITELIST_LIST.getStrings().isEmpty()) {
            return false;
        }
        if (!printModeActive()) {
            return false;
        }
        return true;
    }

    /** 与 PrintHandler.isConfigAllowed 相同的模式判定：打印机是否处于打印模式工作状态 */
    private boolean printModeActive() {
        if (!ConfigUtils.isPrinterEnable()) {
            return false;
        }
        WorkingModeType mode = (WorkingModeType) Configs.Core.WORK_MODE.getOptionListValue();
        return switch (mode) {
            case SINGLE -> Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.PRINTER;
            case MULTI -> Configs.Core.PRINT.getBooleanValue();
        };
    }

    private void deactivate() {
        if (GoManager.INSTANCE.isAutoActive()) {
            GoManager.INSTANCE.stop(null);
        }
        state = State.IDLE;
        target = null;
        cursorSection = null;
        cursorIndex = 0;
        sectionQueue.clear();
        visitedSections.clear();
        unreachableCooldown.clear();
        suspendedByManual = false;
    }

    /** 玩家脚部所在子区块的最小角坐标 */
    private BlockPos playerSectionBase() {
        BlockPos feet = mc.player.blockPosition();
        return new BlockPos(feet.getX() & ~15, feet.getY() & ~15, feet.getZ() & ~15);
    }

    private boolean inSameSectionAsPlayer(BlockPos base) {
        BlockPos feet = mc.player.blockPosition();
        return (feet.getX() >> 4) == (base.getX() >> 4)
                && (feet.getY() >> 4) == (base.getY() >> 4)
                && (feet.getZ() >> 4) == (base.getZ() >> 4);
    }

    private static long sectionKey(BlockPos base) {
        return sectionKey(base.getX() >> 4, base.getY() >> 4, base.getZ() >> 4);
    }

    private static long sectionKey(int sx, int sy, int sz) {
        return BlockPos.asLong(sx, sy, sz);
    }
}
