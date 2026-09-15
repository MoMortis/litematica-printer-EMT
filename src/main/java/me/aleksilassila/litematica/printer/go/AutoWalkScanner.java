package me.aleksilassila.litematica.printer.go;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.SectionScanOrderType;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.SchematicVerifierAccessor;
import me.aleksilassila.litematica.printer.printer.ScanWhitelistCache;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import me.aleksilassila.litematica.printer.printer.verifier.VerifierDataView;
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
 * 扫描自动寻路（"寻路 → 扫描自动寻路"开关；"寻路扫描白名单"未开启时全量扫描，
 * 开启且列表非空时只找列表内/验证器高亮的方块）。
 *
 * <p>目标来源两级：<b>优先使用原理图验证器（Schematic Verifier）的缺失方块列表</b>
 * ——验证器已验证完成时，从缺失方块（经寻路扫描白名单过滤，白名单未开启则不过滤）中
 * 选离玩家最近的派发寻路，列表由 litematica 随世界方块变化自动维护；
 * 验证器未验证完成或无候选时，<b>退回子区块逐格扫描</b>：在玩家当前子区块（16³）内逐格扫描
 * 原理图中未放置的方块（白名单开启时仅列表内/高亮缺失方块的期望状态），收集全部候选后派发：
 * 「按路径最短选目标」开启且候选 ≥2 时，把全部候选的紧邻站立格作为一个目标集合做<b>一次多目标寻路</b>
 * （第一个定稿的目标格即路径成本最短的候选，选目标与算路径一次完成）；关闭或仅 1 个候选时
 * 取直线距离最近的走单目标寻路。寻路把玩家载到目标方块的
 * 紧邻位置（水平相邻、上下 ±1 层，不占用目标格）；到达后释放控制权（玩家自由移动），
 * 无限等待打印机放置完成；完成后继续扫描（玩家若已换子区块，则以玩家新区块为起点重建 BFS 队列）。
 * 派发时序：上一条自动寻路任务走完（自然到达/结束，不中途打断）后，
 * 先复核新目标是否已被放置，再派发新任务。
 * 子区块扩展（BFS）：中心 = 离玩家最近的原理图子区块（FIND 阶段全局枚举按距离扫描），
 * 中心扫完按配置轴序把相邻子区块逐层向外入队，队列穷尽回到 FIND。
 * 加载范围内无待放方块时待命。
 * 扫描每 tick 受「工作时长预算」限制；只依赖判定缓存点查，不干预打印机的任何逻辑。
 */
public final class AutoWalkScanner {
    public static final AutoWalkScanner INSTANCE = new AutoWalkScanner();

    /** 临时调试开关：排查不派发问题（定位后关闭） */
    private static final boolean DEBUG_WALK = false;

    private static void debug(String message) {
        if (DEBUG_WALK) {
            me.aleksilassila.litematica.printer.Reference.LOGGER.info("[扫描寻路调试] {}", message);
        }
    }

    private static final int CELLS_PER_SECTION = 16 * 16 * 16;
    /** 目标不可达（寻路无路）时的尝试冷却（tick），所在区块重扫时回头再试 */
    private static final long UNREACHABLE_COOLDOWN_TICKS = 200;
    /** 打印机交互距离的平方（原版生存放置射程 4.5 格，眼睛到目标方块中心）：
     *  寻路腿结束时玩家仍在该范围内即视为"到位"，等待打印机放置而非按不可达换目标 */
    private static final double PRINTER_REACH_SQ = 4.5 * 4.5;
    /** 待命态的世界变化复查间隔（tick）：revision 有变化才全量重扫 */
    private static final long DONE_RECHECK_INTERVAL_TICKS = 100;
    /** 多目标搜索未定稿任何目标（预算掐断/全不可达/判停）后的单目标回退持续（tick）：
     *  期间派发改走"直线距离最近 + 单目标寻路"的旧路径，避免失败的集合搜索反复重试 */
    private static final long MULTI_FALLBACK_TICKS = 200;
    /** 锚定的工作子区块距离上限（格）：玩家离工作子区块超过该值时放弃锚定、
     *  改以玩家位置重建扫描（见 {@link #shouldReanchorToPlayer}） */
    private static final double ANCHOR_MAX_DISTANCE = 32.0;

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
    /** 当前子区块已收集的待放候选（long 编码坐标，跨预算 tick 累积；扫完整个子区块后统一派发） */
    private final LongArrayList sectionCandidates = new LongArrayList();
    /** 当前寻路腿的多目标映射（站立格→候选）；null = 单目标腿 */
    @Nullable
    private Long2ObjectOpenHashMap<BlockPos> legGoalCells;
    /** 当前寻路腿的多目标 Goal（与 legGoalCells 同源；手动 /go 暂停恢复时重新派发用） */
    @Nullable
    private GoPathfinder.Goal legGoalSet;
    /** 多目标搜索失败后的单目标回退截止 tick */
    private long multiFallbackTick;

    /** FIND 阶段的中心候选子区块（离玩家最近排序，懒构建），及消费游标 */
    @Nullable
    private ArrayList<BlockPos> findSections;
    private int findIndex;

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
            } else if (state == State.DRIVING && legGoalSet != null) {
                GoManager.INSTANCE.autoDispatchMulti(legGoalSet, legGoalCells); // 恢复：同一目标集合重新派发
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
        sectionCandidates.clear();
        findSections = null;
        findIndex = 0;
        suspendedByManual = false;
        cursorSection = playerSectionBase();
        cursorIndex = 0;
        debug("扫描启动 玩家=" + cursorSection.getX() + "," + cursorSection.getY() + "," + cursorSection.getZ()
                + " → FIND 选中心（离玩家最近的原理图子区块）");
        // 中心 = 离玩家最近的原理图子区块（无论是否已放置），
        // 不从玩家脚下子区块起扫（玩家悬在原理图上方/旁边时起点直接落在原理图上）
        findCenterStep();
    }

    private void tickScan() {
        ClientLevel level = mc.level;
        BlockPos base = cursorSection;
        if (level == null || base == null) {
            state = State.IDLE;
            return;
        }
        // 乐魂寻路开启但当前不可飞行（未骑乘/非第一上鞍者/缺挽具/静默态）：不派发自动腿，
        // 否则会出现"任务已激活却永远不动"的僵局（HUD 已有对应提示，骑上后自动恢复）
        if (Configs.Go.GHAST_PATHFIND.getBooleanValue() && !GhastRideState.canFly(mc.player)) {
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
        int seenSchematic = 0, rejectedWhitelist = 0, rejectedNoWork = 0, rejectedCooldown = 0, rejectedCompleted = 0;
        for (; cursorIndex < CELLS_PER_SECTION; cursorIndex++) {
            if ((cursorIndex & 15) == 0 && System.nanoTime() - start >= budgetNanos) {
                return; // 预算用尽，候选保留，下 tick 续扫
            }
            int x = cursorIndex & 15;
            int y = (cursorIndex >> 4) & 15;
            int z = cursorIndex >> 8;
            cursor.set(base.getX() + x, base.getY() + y, base.getZ() + z);
            // 未加载列跳过（判定缓存对未加载区块不落缓存，必须显式排除防假目标）；
            // 渲染距离外（看不见地形）的列同样跳过
            int chunkX = cursor.getX() >> 4;
            int chunkZ = cursor.getZ() >> 4;
            if (!level.hasChunk(chunkX, chunkZ) || !isChunkVisible(chunkX, chunkZ)) {
                continue;
            }
            BlockState schematic = SchematicStateCache.INSTANCE.getSchematicState(cursor);
            if (schematic == null) {
                continue; // 非原理图方块
            }
            seenSchematic++;
            // 候选 = 全部未放置方块；寻路扫描白名单生效时收窄为 白名单列表 ∪ 验证器高亮的
            // "缺失方块"（统一判定在 ScanWhitelistCache 内，未生效时全量放行）
            if (!ScanWhitelistCache.WALK.isWhitelisted(schematic)) {
                rejectedWhitelist++;
                continue;
            }
            if (SchematicStateCache.INSTANCE.isVerifiedNoWork(cursor, level)) {
                rejectedNoWork++;
                continue; // 已放置到位
            }
            long key = cursor.asLong();
            if (unreachableCooldown.get(key) > now) {
                rejectedCooldown++;
                continue; // 不可达冷却中
            }
            // 派发前复核该候选方块此刻仍未放置（扫描游标可能滞后数 tick）
            if (targetCompleted(cursor)) {
                rejectedCompleted++;
                continue; // 扫描期间已被放置，跳过继续扫
            }
            // 收集候选，本子区块扫完后统一取离玩家最近的派发（不再先到先得）
            sectionCandidates.add(key);
        }
        // 本子区块扫完 → 取离玩家最近的候选派发；全部已放置则取下一区块
        debug("子区块(" + (base.getX() >> 4) + "," + (base.getY() >> 4) + "," + (base.getZ() >> 4)
                + ")扫描完成 原理图方块=" + seenSchematic + " 非白名单=" + rejectedWhitelist
                + " 已放置=" + rejectedNoWork + " 冷却=" + rejectedCooldown
                + " 已完成=" + rejectedCompleted + " 候选=" + sectionCandidates.size());
        dispatchNearestInSection();
    }

    /**
     * 当前子区块扫描完毕：派发收集的候选。候选列表<b>保留</b>到本子区块耗尽为止——
     * 目标完成/派发失败回到扫描态时重新派发，不重扫；派发时会重新校验
     * "已被放置"与"不可达冷却"（扫描与派发之间状态可能变化）。
     * 无有效候选时清空并取下一子区块。
     */
    private void dispatchNearestInSection() {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        ArrayList<BlockPos> candidates = new ArrayList<>(sectionCandidates.size());
        for (long key : sectionCandidates) {
            if (unreachableCooldown.get(key) > now) {
                continue; // 派发失败进入的不可达冷却（扫描时还未冷却）
            }
            BlockPos pos = BlockPos.of(key);
            if (targetCompleted(pos)) {
                continue; // 扫描期间已被放置
            }
            candidates.add(pos);
        }
        filterStandSpot(mc.level, candidates); // 落脚点预检：剔除周围无合法落脚点的候选
        if (candidates.isEmpty()) {
            debug("子区块无有效候选（收集" + sectionCandidates.size() + "个）→ sectionDone");
            sectionCandidates.clear();
            sectionDone(); // 候选全部失效
            return;
        }
        debug("子区块候选=" + candidates.size());
        dispatchCandidates(candidates);
    }

    /**
     * 派发候选目标（验证器候选与子区块扫描候选共用）：
     * 「按路径最短选目标」开启且候选 ≥2 且不在多目标回退期 → 把全部候选的紧邻站立格
     * 作为一个目标集合做一次多目标寻路（{@link GoPathfinder.GoalSet}），第一个定稿的
     * 目标即路径成本最短的候选，到达后由 {@link GoManager#getReachedGoalCell()} 反查；
     * 否则取直线距离最近候选走单目标寻路（旧行为，也是多目标搜索失败时的回退）。
     */
    private void dispatchCandidates(ArrayList<BlockPos> candidates) {
        LocalPlayer player = mc.player;
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (Configs.Go.PATH_NEAREST_TARGET.getBooleanValue()
                && candidates.size() >= 2 && now >= multiFallbackTick) {
            int limit = Configs.Go.PATH_TARGET_CANDIDATE_LIMIT.getIntegerValue();
            GoPathfinder.Goal goalSet;
            Long2ObjectOpenHashMap<BlockPos> cells;
            if (Configs.Go.GHAST_PATHFIND.getBooleanValue() && GhastRideState.canFly(player)) {
                // 乐魂飞行：悬停位集合（切比雪夫半径随并集箱尺寸动态推导）
                GhastGoal.HoverGoalSet hover = GhastGoal.hoverGoalSet(candidates, limit,
                        player.blockPosition(), GoManager.INSTANCE.ghastHoverRadius());
                goalSet = hover;
                cells = hover.cellToTarget();
            } else {
                GoPathfinder.GoalSet walkSet = GoPathfinder.goalSet(candidates, limit, player.blockPosition());
                goalSet = walkSet;
                cells = walkSet.cellToTarget();
            }
            legGoalSet = goalSet;
            legGoalCells = cells;
            target = null; // 多目标腿的目标到到达后才确定
            debug("多目标派发 候选=" + candidates.size() + " 目标位=" + legGoalCells.size());
            GoManager.INSTANCE.autoDispatchMulti(goalSet, cells);
        } else {
            BlockPos best = null;
            double bestDistSq = Double.MAX_VALUE;
            for (BlockPos pos : candidates) {
                double dx = pos.getX() + 0.5 - player.getX();
                double dy = pos.getY() + 0.5 - player.getY();
                double dz = pos.getZ() + 0.5 - player.getZ();
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    best = pos;
                }
            }
            legGoalSet = null;
            legGoalCells = null;
            target = best;
            debug("单目标派发=" + best.getX() + "," + best.getY() + "," + best.getZ());
            GoManager.INSTANCE.autoDispatch(best);
        }
        state = State.DRIVING;
        // 找到新中心：广度搜索队列整体丢弃，下次搜索从新中心重新环形扩散
        sectionQueue.clear();
    }

    private void tickDriving() {
        BlockPos t = target;
        if (t == null && legGoalCells == null) {
            enterScanning();
            return;
        }
        // 单目标腿：目标已被放置（玩家顺路经过时打印可能提前完成）→ 不打断行走中的
        // 旧任务腿，让它走完（由 tickScan 顶部的等待逻辑兜住），之后继续扫描派发新目标；
        // 多目标腿的目标到到达后才确定，放置检查由反查后的 target 接管
        if (t != null && targetCompleted(t)) {
            onTargetDone();
            return;
        }
        if (GoManager.INSTANCE.isActive()) {
            return; // 还在路上
        }
        // 寻路腿已结束：多目标腿先反查到达的目标格
        if (legGoalCells != null) {
            BlockPos cell = GoManager.INSTANCE.getReachedGoalCell();
            BlockPos resolved = cell != null ? legGoalCells.get(cell.asLong()) : null;
            legGoalCells = null;
            legGoalSet = null;
            if (resolved != null) {
                target = resolved;
                t = resolved;
            } else {
                // 搜索未定稿任何目标格（预算掐断/全不可达/判停）：近期回退单目标直线最近，
                // 避免失败的集合搜索反复重试
                multiFallbackTick = ClientPlayerTickManager.getCurrentHandlerTime() + MULTI_FALLBACK_TICKS;
                enterScanning();
                return;
            }
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
        // 骑乘期间被判"需要 shift"而拉黑：本机无法放置该方块，立即放弃换下一个，
        // 避免在此无限等待（打印机只会一直暂缓）；黑名单在离开乐魂时清空
        if (GhastShiftBlacklist.contains(mc.player, t)) {
            unreachableCooldown.put(t.asLong(),
                    ClientPlayerTickManager.getCurrentHandlerTime() + UNREACHABLE_COOLDOWN_TICKS);
            target = null;
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
            if (level != null) {
                ArrayList<BlockPos> candidates = new ArrayList<>();
                collectVerifierCandidates(level, candidates);
                if (!candidates.isEmpty()) {
                    activate();
                }
            }
        }
    }

    // ==================== 子区块推进 ====================

    /** 当前子区块扫完（中心耗尽）：按配置轴序把相邻子区块入队后出队继续 */
    private void sectionDone() {
        enqueueNeighbors(cursorSection);
        pollNextSection();
    }

    /** 按配置轴序（每轴先 + 后 -，反转配置则先 - 后 +）生成 6 个相邻子区块方向（子区块坐标增量） */
    private void appendConfiguredDirections(ArrayList<int[]> out) {
        SectionScanOrderType order = (SectionScanOrderType) Configs.Go.PRINT_SCAN_SECTION_ORDER.getOptionListValue();
        boolean xReverse = Configs.Go.PRINT_SCAN_X_REVERSE.getBooleanValue();
        boolean yReverse = Configs.Go.PRINT_SCAN_Y_REVERSE.getBooleanValue();
        boolean zReverse = Configs.Go.PRINT_SCAN_Z_REVERSE.getBooleanValue();
        for (SectionScanOrderType.Axis axis : order.axis) {
            // 轴向默认先 + 后 -，反转配置则先 - 后 +（与核心目录遍历反向同语义）
            boolean reverse = axis == SectionScanOrderType.Axis.X ? xReverse
                    : axis == SectionScanOrderType.Axis.Y ? yReverse : zReverse;
            int[] signs = reverse ? new int[]{-1, 1} : new int[]{1, -1};
            for (int sign : signs) {
                out.add(new int[]{axis.dx * sign, axis.dy * sign, axis.dz * sign});
            }
        }
    }

    /** BFS：搜索下一个中心的过程（广度优先/环形扩散）。当前中心扫完后，按配置轴序把相邻子区块
     * 加入队列逐层向外找。与原理图不相交的邻居直接标记已探索（必无待放方块，不排队）；渲染圈外
     * （看不见地形）的不入队也<b>不标记</b>；<b>相交的不标记</b>——扫描即"检查"，出队检查时才
     * 标记（中心找到后队列整体丢弃重建，未检查的区块不能算探索过） */
    private void enqueueNeighbors(@Nullable BlockPos base) {
        if (base == null) {
            return;
        }
        int sx = base.getX() >> 4;
        int sy = base.getY() >> 4;
        int sz = base.getZ() >> 4;
        ArrayList<int[]> dirs = new ArrayList<>(6);
        appendConfiguredDirections(dirs);
        for (int[] d : dirs) {
            int nx = sx + d[0];
            int ny = sy + d[1];
            int nz = sz + d[2];
            if (!isChunkVisible(nx, nz)) {
                continue; // 渲染圈外：不入队不标记，玩家靠近后可再试
            }
            BlockPos neighbor = new BlockPos(nx << 4, ny << 4, nz << 4);
            if (SchematicStateCache.INSTANCE.intersectsSchematic(neighbor, neighbor.offset(15, 15, 15))) {
                sectionQueue.addLast(neighbor); // 不标记：出队扫描即"检查"，重复入队由出队去重
            } else {
                visitedSections.add(sectionKey(nx, ny, nz)); // 不相交：必无待放方块，直接算已探索
            }
        }
    }

    /** BFS：出队检查下一个候选中心（队头 = 环形扩散，层序）。出队即进入扫描（扫描 = 检查），
     * 此时才标记已探索；玩家走远（渲染圈外）的不标记直接跳过。检查出未放置候选的区块即成为
     * 新中心（派发时队列整体丢弃重建）。队列穷尽 → 回 FIND 重新全局选中心；
     * FIND 也穷尽才真正待命 */
    private void pollNextSection() {
        ClientLevel level = mc.level;
        while (!sectionQueue.isEmpty()) {
            BlockPos next = sectionQueue.poll();
            if (!isChunkVisible(next.getX() >> 4, next.getZ() >> 4)) {
                continue; // 玩家走远：尚未检查过，不标记，之后可再试
            }
            if (!visitedSections.add(sectionKey(next))) {
                continue; // 已检查过（同一区块被多个邻居重复入队，此处去重）
            }
            // 远离原理图的子区块直接跳过（不占扫描预算；入队时已过滤，此处双保险）
            if (level != null && SchematicStateCache.INSTANCE.intersectsSchematic(next, next.offset(15, 15, 15))) {
                debug("BFS出队 检查子区块(" + (next.getX() >> 4) + "," + (next.getY() >> 4) + "," + (next.getZ() >> 4) + ")");
                cursorSection = next;
                cursorIndex = 0;
                enterScanning();
                return;
            }
        }
        debug("BFS搜索穷尽 → 回 FIND 选中心");
        findCenterStep();
    }

    /**
     * FIND 阶段（选中心）：枚举与原理图相交的子区块（即含原理图方块的
     * 子区块，<b>无论是否已放置</b>，由 subregion 盒直接展开），过滤已加载、渲染距离内与未探索的，
     * 按离玩家最近排序后取第一个直接作为中心进入扫描——不要求扫描出未放置候选；本中心扫完
     * 无候选时由 {@link #sectionDone} 交回 BFS 邻居入队扩散，
     * 不在此处按距离序继续找。枚举按需懒构建（每次进入 FIND 重建）；全部穷尽 → 待命
     * （revision 变化 / 玩家换子区块的重建兜底）。
     */
    private void findCenterStep() {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            state = State.IDLE;
            return;
        }
        if (findSections == null) {
            findSections = new ArrayList<>();
            SchematicStateCache.INSTANCE.collectIntersectingSections(findSections::add);
            double px = player.getX();
            double py = player.getY();
            double pz = player.getZ();
            // 按玩家到子区块包围盒最近点的距离升序（与派发/验证器同用三维距离度量）
            findSections.sort((a, b) -> {
                double ax = Math.max(a.getX(), Math.min(px, a.getX() + 15));
                double ay = Math.max(a.getY(), Math.min(py, a.getY() + 15));
                double az = Math.max(a.getZ(), Math.min(pz, a.getZ() + 15));
                double bx = Math.max(b.getX(), Math.min(px, b.getX() + 15));
                double by = Math.max(b.getY(), Math.min(py, b.getY() + 15));
                double bz = Math.max(b.getZ(), Math.min(pz, b.getZ() + 15));
                double da = (px - ax) * (px - ax) + (py - ay) * (py - ay) + (pz - az) * (pz - az);
                double db = (px - bx) * (px - bx) + (py - by) * (py - by) + (pz - bz) * (pz - bz);
                return Double.compare(da, db);
            });
            findIndex = 0;
            debug("FIND 枚举原理图子区块 " + findSections.size() + " 个（离玩家最近序）");
        }
        while (findIndex < findSections.size()) {
            BlockPos section = findSections.get(findIndex);
            findIndex++;
            int sx = section.getX() >> 4;
            int sz = section.getZ() >> 4;
            if (!level.hasChunk(sx, sz)) {
                debug("FIND 跳过未加载子区块(" + sx + "," + (section.getY() >> 4) + "," + sz + ")");
                continue; // 未加载：后续重新枚举时会再试
            }
            if (!isChunkVisible(sx, sz)) {
                debug("FIND 跳过渲染距离外子区块(" + sx + "," + (section.getY() >> 4) + "," + sz + ")");
                continue; // 不标记已搜索：玩家靠近进入渲染圈后可再试
            }
            if (visitedSections.contains(sectionKey(sx, section.getY() >> 4, sz))) {
                continue; // 已搜索过
            }
            debug("FIND 进入子区块(" + sx + "," + (section.getY() >> 4) + "," + sz + ") 逐格扫描");
            visitedSections.add(sectionKey(sx, section.getY() >> 4, sz));
            cursorSection = section;
            cursorIndex = 0;
            enterScanning();
            return;
        }
        // 枚举穷尽：渲染圈内可探索的原理图子区块全部探索过 → 待命
        debug("FIND 穷尽 → 待命");
        findSections = null;
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
     * 从验证器缺失列表派发目标（按路径最短/直线最近，见 {@link #dispatchCandidates}）。
     * 调用前保证旧自动任务已结束（tickScan 顶部的等待逻辑）；候选收集时已复核未放置。
     *
     * @return true 表示已派发（进入 DRIVING）；false 表示验证器无候选，交由扫描器兜底
     */
    private boolean tryDispatchVerifierTarget(ClientLevel level) {
        ArrayList<BlockPos> candidates = new ArrayList<>();
        collectVerifierCandidates(level, candidates);
        filterStandSpot(level, candidates);
        if (candidates.isEmpty()) {
            return false;
        }
        dispatchCandidates(candidates);
        return true;
    }

    /**
     * 是否处于"乐魂飞行"模式。飞行靠悬停到达、**不需要落脚点**，
     * 因此走路版的落脚点预检（{@link #filterStandSpot}）在飞行模式下必须跳过——
     * 否则空中的待放方块会被整批误删，功能直接不可用。
     */
    private boolean ghastFlying(@Nullable LocalPlayer player) {
        return Configs.Go.GHAST_PATHFIND.getBooleanValue() && GhastRideState.canFly(player);
    }

    /**
     * 派发前预检：剔除周围无合法落脚点的候选（只删必死，不误删活——判定语义见
     * {@link GoPathfinder#hasStandableNeighbor}）。本轮不派、不写冷却表：
     * 下轮派发（目标完成/回到扫描态）时会重新预检，地形变化后自然恢复。
     * 乐魂飞行模式不适用（见 {@link #ghastFlying}）。
     */
    private void filterStandSpot(ClientLevel level, ArrayList<BlockPos> candidates) {
        if (level == null || candidates.isEmpty() || ghastFlying(mc.player)) {
            return;
        }
        int before = candidates.size();
        candidates.removeIf(pos -> !GoPathfinder.hasStandableNeighbor(level, pos));
        if (candidates.size() < before) {
            debug("落脚点预检剔除 " + (before - candidates.size()) + "/" + before + " 个候选");
        }
    }

    /**
     * 从所有"已验证完成"的原理图验证器的缺失方块列表收集全部合法候选。
     * 过滤：{@link me.aleksilassila.litematica.printer.printer.ScanWhitelistCache} 统一判定
     * （寻路扫描白名单生效时 = 列表 ∪ 验证器高亮的"缺失方块"，仅 MISSING 类，按期望状态；
     * 未生效时不过滤）、
     * 区块已加载、不可达冷却、已完成。
     * 列表由 litematica 自动维护（世界方块变化进入复查队列实时修正），
     * 个别条目滞后由 targetCompleted 复核兜底。
     * 验证器未验证完成时不产出候选（交由扫描器兜底）。
     */
    private void collectVerifierCandidates(ClientLevel level, ArrayList<BlockPos> out) {
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
        for (SchematicPlacement placement : DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()) {
            SchematicVerifier verifier = placement.getSchematicVerifier();
            if (verifier == null || !verifier.isFinished()) {
                continue; // 该放置未验证过 → 无验证器目标，交由扫描器兜底
            }
            if (verifier instanceof VerifierDataView view) {
                // 优化版验证器：错误按子区块分桶存储，经视图回调遍历（坐标为 64 位打包值）
                view.forEachMismatch(SchematicVerifier.MismatchType.MISSING, (pair, packed) -> {
                    if (!ScanWhitelistCache.WALK.isWhitelisted(pair.getLeft())) {
                        return true; // 白名单外且未被高亮的缺失方块（统一判定）
                    }
                    mpos.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
                    if (candidateUsable(level, now, mpos)) {
                        out.add(mpos.immutable());
                    }
                    return true;
                });
            } else {
                ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> missing =
                        ((SchematicVerifierAccessor) verifier).printer$getMissingBlocksPositions();
                if (missing == null || missing.isEmpty()) {
                    continue;
                }
                for (Pair<BlockState, BlockState> key : missing.keySet()) {
                    if (!ScanWhitelistCache.WALK.isWhitelisted(key.getLeft())) {
                        continue; // 白名单外且未被高亮的缺失方块（统一判定）
                    }
                    for (BlockPos pos : missing.get(key)) {
                        if (candidateUsable(level, now, pos)) {
                            out.add(pos.immutable());
                        }
                    }
                }
            }
        }
    }

    /** 单个候选是否可用（加载/可见/冷却/完成判定） */
    private boolean candidateUsable(ClientLevel level, long now, BlockPos pos) {
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                || !isChunkVisible(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false; // 未加载或渲染距离外（看不见地形）
        }
        if (unreachableCooldown.get(pos.asLong()) > now) {
            return false;
        }
        if (GhastShiftBlacklist.contains(mc.player, pos)) {
            return false; // 骑乘时"需要 shift 的放置"已拉黑：不再派发（离开乐魂自动清空）
        }
        return !targetCompleted(pos);
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
     * 等待逻辑兜住，等它走完再派发新任务；玩家若已换子区块：
     * 玩家离工作子区块 ≤32 格 → <b>锚定该子区块继续</b>（不重建，
     * 保留扩散推进与候选列表，玩家会被带回工作子区块）；
     * 无工作子区块、或玩家离工作子区块 >32 格 → 以玩家位置重建扫描。
     */
    private void onTargetDone() {
        target = null;
        BlockPos base = cursorSection;
        if (base == null || !inSameSectionAsPlayer(base)) {
            if (!shouldReanchorToPlayer(base)) {
                debug("目标完成 → 锚定 继续（工作子区块="
                        + base.getX() + "," + base.getY() + "," + base.getZ() + "）");
                enterScanning(); // 锚定：继续在原工作子区块扩散
                return;
            }
            debug("目标完成 → 以玩家位置重建扫描");
            activate();
            return;
        }
        debug("目标完成 → 同子区块继续");
        enterScanning();
    }

    /**
     * 是否应放弃当前工作子区块、改以玩家位置重建扫描（true = 重建）。
     * 无工作子区块，或玩家离该子区块包围盒最近点
     * 超过 {@link #ANCHOR_MAX_DISTANCE} 格时重建。
     */
    private boolean shouldReanchorToPlayer(@Nullable BlockPos base) {
        if (base == null || mc.player == null) {
            return true;
        }
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        // 玩家到子区块包围盒（16³）最近点的距离
        double nx = Math.max(base.getX(), Math.min(px, base.getX() + 15));
        double ny = Math.max(base.getY(), Math.min(py, base.getY() + 15));
        double nz = Math.max(base.getZ(), Math.min(pz, base.getZ() + 15));
        double dx = px - nx;
        double dy = py - ny;
        double dz = pz - nz;
        return dx * dx + dy * dy + dz * dz > ANCHOR_MAX_DISTANCE * ANCHOR_MAX_DISTANCE;
    }

    // ==================== 条件与工具 ====================

    private boolean conditionsMet() {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || player.isDeadOrDying()) {
            return false;
        }
        if (!Configs.Go.PRINT_SCAN_AUTOWALK.getBooleanValue()) {
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
        legGoalCells = null;
        legGoalSet = null;
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

    /**
     * 该区块列是否在客户端渲染距离内（"看得见地形"的范围）。
     * 渲染圈以玩家所在区块为圆心、"渲染距离"选项为半径：数据已加载但渲染圈外的区块
     * （画面上是虚空、看不到地形）不进入、不扫描、不派发——扫描寻路只在看得见地形处工作
     */
    private boolean isChunkVisible(int chunkX, int chunkZ) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return false;
        }
        int rd = mc.options.renderDistance().get();
        int dx = chunkX - (player.getBlockX() >> 4);
        int dz = chunkZ - (player.getBlockZ() >> 4);
        return dx * dx + dz * dz <= rd * rd;
    }

    private static long sectionKey(BlockPos base) {
        return sectionKey(base.getX() >> 4, base.getY() >> 4, base.getZ() >> 4);
    }

    private static long sectionKey(int sx, int sy, int sz) {
        return BlockPos.asLong(sx, sy, sz);
    }
}
