package me.aleksilassila.litematica.printer.go;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
 * 原理图中未放置的白名单方块或高亮缺失方块的期望状态，收集全部候选、<b>取离玩家最近的</b>
 * 派发 /go 寻路把玩家载到该方块的
 * 紧邻位置（水平相邻、上下 ±1 层，不占用目标格）；到达后释放控制权（玩家自由移动），
 * 无限等待打印机放置完成；完成后继续扫描（玩家若已换子区块，则以玩家新区块为起点重建 BFS 队列）。
 * 派发时序：上一条自动寻路任务走完（自然到达/结束，不中途打断）后，
 * 先复核新目标是否已被放置，再派发新任务。
 * 子区块扩展：BFS 按配置轴序逐层向外入队；DFS 为"中心 + 指针漫游"——中心 = 离玩家最近的
 * 未放置方块所在子区块（FIND 阶段全局枚举按距离扫描），中心耗尽后指针只在"已加载且有原理图"
 * 的子区块间漫游找下一个中心（指针不复用），漫游穷尽回到 FIND。
 * 加载范围内无待放方块时待命。
 * 扫描每 tick 受「工作时长预算」限制；只依赖判定缓存点查，不干预打印机的任何逻辑。
 */
public final class AutoWalkScanner {
    public static final AutoWalkScanner INSTANCE = new AutoWalkScanner();

    /** 临时调试开关：排查 DFS 不派发问题（定位后关闭） */
    private static final boolean DEBUG_WALK = true;

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
    /** DFS 锚定的工作子区块距离上限（格）：玩家离工作子区块超过该值时放弃锚定、
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
    /** 当前子区块已收集的待放候选（long 编码坐标，跨预算 tick 累积；扫完整个子区块后取离玩家最近的派发） */
    private final LongArrayList sectionCandidates = new LongArrayList();
    /** DFS 指针漫游栈：帧 = (子区块, 该位置下一个待试方向序号)。指针移入新子区块时压入
     *  (新子区块, 0)——方向序从新中心重新开始；被围死时弹帧回溯上一分叉。找到新中心后
     *  旧路径的祖先分叉仍保留（回溯兜底覆盖），但指针总是从栈顶（最新中心）重新出发 */
    private final ArrayDeque<WalkFrame> walkStack = new ArrayDeque<>();

    /** FIND 阶段的中心候选子区块（离玩家最近排序，懒构建），及消费游标（两算法共用） */
    @Nullable
    private ArrayList<BlockPos> findSections;
    private int findIndex;

    /** DFS 漫游栈帧：子区块 + 下一待试方向序号 */
    private record WalkFrame(BlockPos section, int dirIndex) {
    }

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
        sectionCandidates.clear();
        walkStack.clear();
        findSections = null;
        findIndex = 0;
        suspendedByManual = false;
        cursorSection = playerSectionBase();
        cursorIndex = 0;
        debug("扫描启动 玩家=" + cursorSection.getX() + "," + cursorSection.getY() + "," + cursorSection.getZ()
                + " 算法=" + Configs.Print.PRINT_SCAN_EXPAND_ALGO.getOptionListValue()
                + " → FIND 选中心（离玩家最近的原理图子区块）");
        // 两算法共用：中心 = 离玩家最近的原理图子区块（无论是否已放置），
        // 不再从玩家脚下子区块起扫（玩家悬在原理图上方/旁边时起点直接落在原理图上）
        findCenterStep();
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
            // 候选 = 白名单列表 ∪ 验证器高亮的"缺失方块"（统一判定在 ScanWhitelistCache 内）
            if (!ScanWhitelistCache.isWhitelisted(schematic)) {
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
     * 当前子区块扫描完毕：从收集的候选中选离玩家最近的（三维距离，与验证器目标源同度量）
     * 派发。候选列表<b>保留</b>到本子区块耗尽为止——目标完成/派发失败回到扫描态时从中
     * 继续选次近的，不重扫；选中与复选时都会重新校验"已被放置"与"不可达冷却"
     * （扫描与派发之间状态可能变化）。无有效候选时清空并取下一子区块。
     */
    private void dispatchNearestInSection() {
        LocalPlayer player = mc.player;
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (long key : sectionCandidates) {
            if (unreachableCooldown.get(key) > now) {
                continue; // 派发失败进入的不可达冷却（扫描时还未冷却）
            }
            BlockPos pos = BlockPos.of(key);
            if (targetCompleted(pos)) {
                continue; // 扫描期间已被放置
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
        if (best == null) {
            debug("子区块无有效候选（收集" + sectionCandidates.size() + "个）→ sectionDone");
            sectionCandidates.clear();
            sectionDone(); // 候选全部失效
            return;
        }
        debug("派发目标=" + best.getX() + "," + best.getY() + "," + best.getZ());
        target = best;
        GoManager.INSTANCE.autoDispatch(best);
        state = State.DRIVING;
        if ((SectionExpandAlgorithmType) Configs.Print.PRINT_SCAN_EXPAND_ALGO.getOptionListValue()
                == SectionExpandAlgorithmType.DFS) {
            // 找到新中心：指针漫游从该中心重新出发（旧漫游路径不复用）
            walkStack.clear();
            walkStack.push(new WalkFrame(cursorSection, 0));
        } else {
            // 找到新中心：广度搜索队列整体丢弃，下次搜索从新中心重新环形扩散（指针不复用）
            sectionQueue.clear();
        }
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

    /**
     * 当前子区块扫完（中心耗尽）：DFS 交回 WALK 指针漫游（从当前中心按配置方向序继续，
     * 中心本身是否有未放置候选不影响——中心只负责锚定，找未放置方块交给漫游扫描）；
     * BFS 邻居入队后出队。
     */
    private void sectionDone() {
        if ((SectionExpandAlgorithmType) Configs.Print.PRINT_SCAN_EXPAND_ALGO.getOptionListValue()
                == SectionExpandAlgorithmType.DFS) {
            // 健康的漫游流程里栈顶就是当前中心（进入时压入）；FIND 刚选定中心或
            // 状态残留（切换算法等）导致栈空/栈顶不符时，以当前子区块重新起栈
            if (walkStack.isEmpty() || !walkStack.peek().section().equals(cursorSection)) {
                debug("DFS漫游栈空/与当前中心不一致（栈"
                        + (walkStack.isEmpty() ? "空" : "顶=" + walkStack.peek().section().getX() + "," + walkStack.peek().section().getY() + "," + walkStack.peek().section().getZ())
                        + "）→ 以当前子区块(" + (cursorSection.getX() >> 4) + "," + (cursorSection.getY() >> 4) + "," + (cursorSection.getZ() >> 4) + ")起栈漫游");
                walkStack.clear();
                walkStack.push(new WalkFrame(cursorSection, 0));
            }
            dfsWalkStep();
            return;
        }
        enqueueNeighbors(cursorSection);
        pollNextSection();
    }

    /** 按配置轴序（每轴先 + 后 -，反转配置则先 - 后 +）生成 6 个相邻子区块方向（子区块坐标增量） */
    private void appendConfiguredDirections(ArrayList<int[]> out) {
        SectionScanOrderType order = (SectionScanOrderType) Configs.Print.PRINT_SCAN_SECTION_ORDER.getOptionListValue();
        boolean xReverse = Configs.Print.PRINT_SCAN_X_REVERSE.getBooleanValue();
        boolean yReverse = Configs.Print.PRINT_SCAN_Y_REVERSE.getBooleanValue();
        boolean zReverse = Configs.Print.PRINT_SCAN_Z_REVERSE.getBooleanValue();
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
     * 新中心（派发时队列整体丢弃重建）。队列穷尽 → 回 FIND 重新全局选中心（与 DFS 漫游穷尽
     * 同语义）；FIND 也穷尽才真正待命 */
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
     * DFS WALK 阶段（指针漫游）：中心子区块耗尽后，指针从它出发按配置方向序（如 x+、x-、z+、z-、
     * y+、y-）寻找下一个有未放置方块的子区块——只进入"已加载、渲染距离内（看得见地形）且有原理图"
     * 的子区块（扫描即"检查"），发现候选即成为新中心（派发最近候选，指针从新中心重新出发，旧漫游
     * 路径不复用）。渲染距离外的方向不标记已搜索（玩家靠近后可再试）。
     * 指针在某位置被围死时<b>沿漫游路径回溯</b>到上一个还有剩余方向的分叉继续；整条漫游路径
     * 穷尽 → 回到 FIND 阶段重新全局找中心（无回溯的自回避漫游会把自己封进口袋导致提前待命）。
     */
    private void dfsWalkStep() {
        ClientLevel level = mc.level;
        if (level == null || cursorSection == null) {
            state = State.IDLE;
            return;
        }
        ArrayList<int[]> dirs = new ArrayList<>(6);
        appendConfiguredDirections(dirs);
        while (!walkStack.isEmpty()) {
            WalkFrame frame = walkStack.pop();
            BlockPos top = frame.section();
            int dirIndex = frame.dirIndex();
            int sx = top.getX() >> 4;
            int sy = top.getY() >> 4;
            int sz = top.getZ() >> 4;
            BlockPos next = null;
            while (dirIndex < dirs.size()) {
                int[] d = dirs.get(dirIndex);
                dirIndex++;
                int nx = sx + d[0];
                int ny = sy + d[1];
                int nz = sz + d[2];
                // 不做客户端子区块高度范围检查：服务器实际高度可能超出客户端上报范围
                // （实测方块存在于客户端认为越界的子区块层），且"与原理图不相交"过滤
                // 已保证只进入有原理图的子区块，不会漫游到世界外空层
                if (!level.hasChunk(nx, nz)) {
                    debug("漫游方向(" + d[0] + "," + d[1] + "," + d[2] + ") → 拒绝：区块未加载 (" + nx + "," + nz + ")");
                    continue; // 未加载区块：该方向不通
                }
                if (!isChunkVisible(nx, nz)) {
                    debug("漫游方向(" + d[0] + "," + d[1] + "," + d[2] + ") → 拒绝：渲染距离外（看不见地形）(" + nx + "," + nz + ")");
                    continue; // 不标记已搜索：玩家靠近进入渲染圈后可再试
                }
                if (!visitedSections.add(sectionKey(nx, ny, nz))) {
                    debug("漫游方向(" + d[0] + "," + d[1] + "," + d[2] + ") → 拒绝：已搜索");
                    continue; // 已搜索
                }
                BlockPos candidate = new BlockPos(nx << 4, ny << 4, nz << 4);
                if (!SchematicStateCache.INSTANCE.intersectsSchematic(candidate, candidate.offset(15, 15, 15))) {
                    debug("漫游方向(" + d[0] + "," + d[1] + "," + d[2] + ") → 拒绝：与原理图不相交 ("
                            + nx + "," + ny + "," + nz + ")");
                    continue; // 与原理图不相交：必无待放方块（已标记搜索，省一次空扫）
                }
                next = candidate;
                break;
            }
            if (next != null) {
                walkStack.push(new WalkFrame(top, dirIndex)); // 该位置还有剩余方向，回溯时继续
                walkStack.push(new WalkFrame(next, 0));       // 指针移入：逐格扫描即"检查"，有候选则成为新中心
                debug("DFS漫游 移入子区块(" + (next.getX() >> 4) + "," + (next.getY() >> 4) + "," + (next.getZ() >> 4) + ")");
                cursorSection = next;
                cursorIndex = 0;
                enterScanning();
                return;
            }
            // 该位置围死：不压回，弹出后继续回溯上一分叉
        }
        // 整条漫游路径穷尽：回到 FIND 阶段，重新全局选离玩家最近的未探索原理图子区块为中心
        debug("DFS漫游穷尽 → 回到全局找中心");
        findSections = null;
        findIndex = 0;
        findCenterStep();
    }

    /**
     * FIND 阶段（选中心，<b>BFS 与 DFS 共用</b>）：枚举与原理图相交的子区块（即含原理图方块的
     * 子区块，<b>无论是否已放置</b>，由 subregion 盒直接展开），过滤已加载、渲染距离内与未探索的，
     * 按离玩家最近排序后取第一个直接作为中心进入扫描——不要求扫描出未放置候选；本中心扫完
     * 无候选时由 {@link #sectionDone} 交回各自算法的扩散（DFS 指针漫游 / BFS 邻居入队），
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
                    if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                            || !isChunkVisible(pos.getX() >> 4, pos.getZ() >> 4)) {
                        continue; // 未加载或渲染距离外（看不见地形）
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
     * 等待逻辑兜住，等它走完再派发新任务；玩家若已换子区块（BFS 与 DFS 同规则）：
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
     * BFS 与 DFS 通用：无工作子区块，或玩家离该子区块包围盒最近点
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
