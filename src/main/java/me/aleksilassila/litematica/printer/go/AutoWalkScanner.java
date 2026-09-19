package me.aleksilassila.litematica.printer.go;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.malilib.util.LayerRange;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.enums.SectionScanOrderType;
import me.aleksilassila.litematica.printer.enums.SelectionType;
import me.aleksilassila.litematica.printer.enums.WorkingModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.SchematicVerifierAccessor;
import me.aleksilassila.litematica.printer.printer.ScanWhitelistCache;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import me.aleksilassila.litematica.printer.printer.verifier.VerifierDataView;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 扫描自动寻路（"寻路 → 扫描自动寻路"开关；"寻路扫描白名单"未开启时全量扫描，
 * 开启且列表非空时只找列表内/验证器高亮的方块）。
 * 目标受「打印-选取类型」约束（{@link PlayerUtils#isPositionInSelectionRange}，与打印动作侧
 * 同一判定：渲染层/玩家下方/玩家上方）——打印机不会放置的位置，寻路不选不等；
 * 约束范围变化（切渲染层等）时清掉已扫子区块标记触发重扫。
 *
 * <p>目标来源两级：<b>优先使用原理图验证器（Schematic Verifier）的缺失方块列表</b>
 * ——验证器已验证完成时，从缺失方块（经寻路扫描白名单过滤，白名单未开启则不过滤）中
 * 选离玩家最近的派发寻路，列表由 litematica 随世界方块变化自动维护；
 * 验证器未验证完成或无候选时，<b>退回子区块扫描 + 工作区候选池</b>：由
 * {@link SchematicStateCache#planSection} 给出"本子区块与各 subregion 盒的相交范围＋容器＋变换"的
 * <b>只读计划</b>，逐格重活（读容器、跳空气、比世界）全部交给<b>工作线程池</b>
 * （{@link #SCAN_WORKER_COUNT} 个线程，层内并行）执行；主线程每 tick 只做"回收结果 + 合并候选池"。
 * 主线程不再逐格扫描，因此不再受「工作时长预算」限制——这正是原来 ~20 子区块/秒 的瓶颈所在。
 * 扫完的候选<b>跨子区块并入候选池</b>（{@link #areaCandidates}），不按子区块各查各的。
 *
 * <p><b>「最短路径优先」关闭时＝旧行为</b>：没有候选池、没有多子区块并行、没有数量门禁——
 * 子区块<b>串行</b>扫描（一次只在飞一个），本子区块整块扫完就在<b>这一个子区块内</b>选离玩家
 * 最近的 1 个直接派发，不论该目标能否到达（<b>不做落脚点预检</b>，找到目标直接交给寻路推算路径）；
 * 扫完没有未放置方块才去下一个子区块。因为没有候选竞争阶段，也不写候选描边（<b>不画黄框</b>）。
 *
 * <p><b>候选池的排序比较门禁 = 曼哈顿层扫完 + 数量够</b>（仅「最短路径优先」开启时）：以起始中心子区块为原点，
 * 子区块按<b>曼哈顿距离 N 逐层向外</b>扫（N = 1、2、3…，BFS 层序保证同层全部先于下一层出队）。
 * <b>只有第 N 层的所有子区块都扫完</b>、且池内<b>可到达</b>候选（已完成剔除、不可达冷却跳过、
 * 预检通过＝周围有可到达位置：走路＝可站立位，乐魂飞行＝放得下「乐魂+骑乘者」并集箱的
 * 悬停位（水平切比雪夫 3、竖直 ±1，见 {@link GhastPathfinder#hasHoverSpot}））的数量<b>凑够「多目标候选上限」
 * （{@code PATH_TARGET_CANDIDATE_LIMIT}）</b>时才允许排序比较；不够就把 N 加一、把下一层扫完再判，
 * <b>绝不用"扫了一半的层"去比较</b>。两条止步线：① 向外推进满
 * {@link #SCAN_GATE_MAX_LAYERS} 个曼哈顿层仍凑不够 → 直接用手里已有的候选比较
 * （否则"差 1 个候选"会把渲染距离内几百个子区块全扫一遍，期间一个目标都不派）；
 * ② 玩家渲染距离内的子区块全扫完（FIND 枚举穷尽）仍凑不够 → 同样用现有候选比较。
 * 「最短路径优先」：候选 ≥2 时把它们的紧邻站立格作为一个目标集合做<b>一次多目标寻路</b>
 * （第一个定稿的目标格即路径成本最短的候选，选目标与算路径一次完成）；关闭或仅 1 个候选时
 * 取直线距离最近的走单目标寻路。寻路把玩家载到目标方块的
 * 紧邻位置（水平相邻、上下 ±1 层，不占用目标格）；到达后释放控制权（玩家自由移动），
 * 无限等待打印机放置完成；<b>完成后不复用上一轮的候选</b>——重置扫描会话
 * （清空候选池、已扫标记与 FIND 枚举），从玩家当前位置重新扫、重新比较选目标。
 * 派发时序：上一条自动寻路任务走完（自然到达/结束，不中途打断）后，
 * 先复核新目标是否已被放置，再派发新任务。
 * 子区块扩展（BFS）：中心 = 离玩家最近的原理图子区块（FIND 阶段全局枚举按距离扫描），
 * 中心扫完按配置轴序把相邻子区块逐层向外入队，队列穷尽回到 FIND。
 * 加载范围内无待放方块时待命。
 * 扫描每 tick 受「工作时长预算」限制；只依赖判定缓存点查，不干预打印机的任何逻辑。
 */
public final class AutoWalkScanner {
    public static final AutoWalkScanner INSTANCE = new AutoWalkScanner();

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
    /** 「凑数扫描」的止步层数：从本轮中心子区块起算，向外推进这么多曼哈顿层仍凑不够
     *  「多目标候选上限」，就直接用手里已有的候选比较派发——否则"差 1 个候选"会把
     *  渲染距离内几百个子区块全扫一遍（日志里 214→546 就是这么来的），期间一个目标都不派 */
    private static final int SCAN_GATE_MAX_LAYERS = 4;
    /** FIND 枚举（子区块列表 + 离玩家最近排序）的缓存时长（tick）：玩家没换子区块时复用，
     *  避免「不复用候选」后每轮重扫都要重排 ~30 万条 */
    private static final long FIND_CACHE_TICKS = 100;

    private enum State { IDLE, SCANNING, DRIVING, ARRIVED_WAITING, DONE }

    private final Minecraft mc = Minecraft.getInstance();

    /** 状态机状态。volatile：渲染线程（描边提示）会读 {@link #getWaitingTarget()} */
    private volatile State state = State.IDLE;

    /** 当前扫描子区块的最小角坐标（"工作子区块"：锚定判定与软重定位的参照） */
    @Nullable
    private BlockPos cursorSection;
    private final ArrayDeque<BlockPos> sectionQueue = new ArrayDeque<>();
    private final LongOpenHashSet visitedSections = new LongOpenHashSet();
    private final Long2LongOpenHashMap unreachableCooldown = new Long2LongOpenHashMap();
    /** 「打印-选取类型」约束指纹（见 {@link #selectionSig()}）：变化＝已扫子区块的结论过期，需重扫 */
    private long selectionSig;

    // ===== 子区块扫描：主线程只做"出计划 + 合并结果"，逐格重活在工作线程池里 =====

    /** 扫描工作线程数：留 2 个核给客户端主线程/渲染，上限 4（再多受内存带宽与区块读取限制） */
    private static final int SCAN_WORKER_COUNT = Math.max(1, Math.min(4,
            Runtime.getRuntime().availableProcessors() - 2));
    /** 在飞扫描任务上限：层内并行度（同时读多少个区块列，控制内存与 IO 压力） */
    private static final int SCAN_MAX_INFLIGHT = SCAN_WORKER_COUNT * 2;

    /** 扫描工作线程池（懒建；守护线程，随游戏退出） */
    @Nullable
    private ExecutorService scanPool;
    /** 后台扫描结果回收集（工作线程产出，主线程每 tick 回收合并） */
    private final java.util.concurrent.ConcurrentLinkedQueue<SectionScanResult> scanResults =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 在飞任务数（主线程维护）：曼哈顿层"整体扫完"＝它归零且结果已合并 */
    private int pendingScans;
    /** 扫描会话序号：整会话重建/软重定位时递增，使在飞结果作废 */
    private int scanSerial;

    /** 一个子区块的后台扫描结果 */
    private static final class SectionScanResult {
        final int serial;
        final BlockPos section;
        final int layer;
        /** 命中"原理图非空气且世界未放置"的格子 */
        final LongArrayList positions = new LongArrayList();
        /** 与 positions 一一对应的原理图期望方块 */
        final ArrayList<BlockState> expected = new ArrayList<>();
        /** 命中"原理图此处为空气（subregion 盒内）、现实却有非液体方块"的多余方块格子 */
        final LongArrayList extraPositions = new LongArrayList();

        SectionScanResult(int serial, BlockPos section, int layer) {
            this.serial = serial;
            this.section = section;
            this.layer = layer;
        }
    }
    /**
     * 工作区候选池：把已扫描子区块收集到的候选<b>跨子区块累积</b>起来（long 编码坐标去重）。
     * 派发时从池内全部候选里复核 + 落脚点预检，按距离升序比较——不再"一个子区块只查有限个"。
     */
    private final LongOpenHashSet areaCandidates = new LongOpenHashSet();
    /**
     * 池内"可到达"候选计数（并入时按落脚点预检判定）。只作为比较门禁的廉价判据——
     * 每 tick 都要跑门禁，池子可能上千，不能每次都全量复核；派发时才做权威复核。
     */
    private int reachablePoolCount;
    /**
     * 已被计入 {@link #reachablePoolCount} 的候选（与计数严格同步）：
     * 池内候选被剔除（已放置）时要能同步递减，否则计数只增不减、门禁永久打开，
     * "凑够数量再比较"就失效了。
     */
    private final LongOpenHashSet reachableKeys = new LongOpenHashSet();
    /**
     * 多余方块候选池（「寻路多余方块」开启时收集）：long 编码坐标去重。
     * 判定口径与打印机的「破坏多余方块」一致——subregion 盒内原理图为空气、
     * 现实有非空气非液体方块。派发时<b>优先于</b>普通待放方块。
     */
    private final LongOpenHashSet areaExtraCandidates = new LongOpenHashSet();
    /** 多余方块池内"可到达"候选计数（口径同 {@link #reachablePoolCount}） */
    private int extraReachableCount;
    /** 已被计入 {@link #extraReachableCount} 的多余方块候选（与计数严格同步） */
    private final LongOpenHashSet extraReachableKeys = new LongOpenHashSet();
    /** 扫描中心子区块（曼哈顿层的原点）：{@link #sectionLayer} 以它计算层号 */
    @Nullable
    private BlockPos areaCenterSection;
    /** 当前正在扫的曼哈顿层（-1＝尚未开始）；层号递增即代表上一层已整体扫完 */
    private int layerScanning = -1;
    /** 当前寻路腿的多目标映射（站立格→候选）；null = 单目标腿 */
    @Nullable
    private Long2ObjectOpenHashMap<BlockPos> legGoalCells;
    /** 当前寻路腿的多目标 Goal（与 legGoalCells 同源；手动 /go 暂停恢复时重新派发用） */
    @Nullable
    private GoPathfinder.Goal legGoalSet;
    /** 多目标搜索失败后的单目标回退截止 tick */
    private long multiFallbackTick;

    /**
     * 最近一批派发的候选（渲染描边用：黄色＝本批参与"最短路径优先"竞争的全部候选）。
     * 派发时写入不可变快照——渲染线程（DebugRenderer.emitGizmos）会读，不能给可变集合。
     */
    private volatile List<BlockPos> dispatchedCandidates = List.of();
    /**
     * 当前腿"选中的"目标（渲染描边用：绿色）。单目标腿＝派发时即确定；
     * 多目标腿＝A* 定稿后由 {@link GoManager#getMultiGoalCurrentTarget()} 填入（不必等到到达）。
     */
    @Nullable
    private volatile BlockPos selectedTarget;

    /** 最近一批候选（黄色描边） */
    public List<BlockPos> getDispatchedCandidates() {
        return dispatchedCandidates;
    }

    /** 当前选中的目标（绿色描边）；无腿在跑时为 null */
    @Nullable
    public BlockPos getSelectedTarget() {
        return selectedTarget;
    }

    /** FIND 阶段的中心候选子区块（离玩家最近排序，懒构建），及消费游标 */
    @Nullable
    private ArrayList<BlockPos> findSections;
    private int findIndex;
    /** FIND 枚举缓存（按玩家子区块 + 时长复用；见 {@link #FIND_CACHE_TICKS}） */
    @Nullable
    private ArrayList<BlockPos> findSectionsCache;
    private long findSectionsCachePlayer = Long.MIN_VALUE;
    private long findSectionsCacheTick = Long.MIN_VALUE;

    /** 当前目标（单目标腿＝派发时即定；多目标腿＝到达后反查）。volatile：渲染线程会读 */
    @Nullable
    private volatile BlockPos target;
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
        // 二档脱困进行中：暂停扫描与派发（乐魂正被有意开进"原理图预留空间"），
        // 状态机保留，脱困结束后从这里自动恢复
        if (GhastFlyer.isEscapingTier2()) {
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
        areaCandidates.clear();
        reachablePoolCount = 0;
        reachableKeys.clear();
        areaCenterSection = null;
        layerScanning = -1;
        findSections = null;
        findIndex = 0;
        suspendedByManual = false;
        scanSerial++;               // 在飞的扫描结果作废（池子已清空，旧结果不能并入）
        // 同时复位在飞计数与回收集：deactivate 期间不会回收（tickScan 不在 SCANNING 态不跑），
        // 若把 pendingScans 留着，findCenterStep 的"等任务回来"早退会让状态卡在 IDLE → 每 tick 重激活
        pendingScans = 0;
        scanResults.clear();
        cursorSection = playerSectionBase();
        // 中心 = 离玩家最近的原理图子区块（无论是否已放置），
        // 不从玩家脚下子区块起扫（玩家悬在原理图上方/旁边时起点直接落在原理图上）
        findCenterStep();
    }

    private void tickScan() {
        ClientLevel level = mc.level;
        if (level == null) {
            state = State.IDLE;
            return;
        }
        // 「打印-选取类型」约束变化（切渲染层/改选取类型）：已扫子区块的结论过期——
        // 队列穷尽回 FIND 也会被 visitedSections 挡住，不清标记的话玩家会停在待命
        // 直到走远 32 格才重建。候选池保留：派发复核（poolDispatch）按最新范围过滤
        long sig = selectionSig();
        if (sig != selectionSig) {
            selectionSig = sig;
            visitedSections.clear();
        }
        // 乐魂寻路开启但当前不可飞行（未骑乘/非第一上鞍者/缺挽具/静默态）：不派发自动腿，
        // 否则会出现"任务已激活却永远不动"的僵局（HUD 已有对应提示，骑上后自动恢复）
        if (Configs.Go.GHAST_PATHFIND.getBooleanValue() && !GhastRideState.canFly(mc.player)) {
            return;
        }
        // 上一条自动寻路还在走：等它走完再派发新任务（不中途打断行走中的旧任务）
        if (GoManager.INSTANCE.isAutoActive()) {
            return;  // 在飞扫描任务继续跑，结果下 tick 回收
        }
        // 进入扫描态后先给验证器一次机会（优先验证器缺失列表，最近优先）
        if (verifierPollNeeded) {
            verifierPollNeeded = false;
            if (tryDispatchVerifierTarget(level)) {
                return;
            }
        }
        drainScanResults(level);   // ① 回收后台扫描结果并合并（含白名单/落脚点预检/池计数）
        pumpScan(level);           // ② 层边界判定 + 投递本层剩余子区块（层内并行）
    }

    /**
     * 扫描推进泵：先判"当前曼哈顿层是否整体扫完"（＝本层投递的任务全部返回并已合并），
     * 是则做一次排序比较门禁；否则继续把本层剩余的相邻子区块投递给工作线程池。
     * <b>不跨层投递</b>：队首已进入下一层且本层还有在飞任务时先等——比较只发生在层边界，
     * 拿"扫了一半的层"去比较是被明确禁止的。
     */
    private void pumpScan(ClientLevel level) {
        if (state != State.SCANNING) {
            return; // 本 tick 已在结果回收阶段派发（串行模式）：结果已定，不再投递
        }
        // 关闭「最短路径优先」＝旧行为：子区块<b>串行</b>扫描，一次只在飞一个。
        // 本子区块整块扫完由 drainScanResults 在该子区块内选离玩家最近的目标直接派发
        // （不论能否到达，不做落脚点预检）；扫完没有未放置方块才继续下一个子区块。
        // 没有候选池、没有多子区块并行、没有数量门禁，也就没有候选竞争阶段（不画黄框）。
        if (!multiTargetMode()) {
            if (pendingScans > 0) {
                return; // 在飞：等它回来
            }
            if (submitNextSerialSection(level)) {
                return;
            }
            findCenterStep();
            return;
        }
        // ① 层边界：本层投递的任务全部返回（pendingScans==0）且队首已进入下一层（或队列空）
        boolean layerDone = pendingScans == 0 && layerScanning >= 0
                && (sectionQueue.isEmpty() || sectionLayer(sectionQueue.peek()) > layerScanning);
        if (layerDone) {
            if (poolDispatch(level, false)) {
                return;
            }
            if (layerScanning >= SCAN_GATE_MAX_LAYERS && poolDispatch(level, true)) {
                // 止步条件：向外推进到上限层数仍凑不够候选上限 → 不再"差几个就无限扫"，
                // 直接用手里已有的候选比较派发（否则渲染距离内几百个子区块全扫一遍，期间不派发）
                return;
            }
        }
        // ② 投递本层剩余子区块（层内并行，受在飞上限约束）
        while (!sectionQueue.isEmpty() && pendingScans < SCAN_MAX_INFLIGHT) {
            BlockPos next = sectionQueue.peek();
            int layer = sectionLayer(next);
            if (layer > layerScanning && layerScanning >= 0 && pendingScans > 0) {
                return; // 本层还有在飞任务：等它们回来（层边界语义要求本层整体扫完）
            }
            sectionQueue.poll();
            if (!isChunkVisible(next.getX() >> 4, next.getZ() >> 4)) {
                continue; // 玩家走远：尚未检查过，不标记，之后可再试
            }
            if (!visitedSections.add(sectionKey(next))) {
                continue; // 已检查过（同一区块被多个邻居重复入队，此处去重）
            }
            if (!SchematicStateCache.INSTANCE.intersectsSchematic(next, next.offset(15, 15, 15))) {
                continue; // 与原理图不相交（双保险，入队时已过滤）
            }
            cursorSection = next;   // 工作子区块：锚定判定与软重定位的参照
            layerScanning = layer;
            submitSection(level, next, layer);
        }
        // ③ 队列空且无在飞任务 → 回 FIND 选新中心（或宣告渲染距离内已扫尽）
        if (sectionQueue.isEmpty() && pendingScans == 0) {
            findCenterStep();
        }
    }

    /**
     * 「最短路径优先」是否开启。开启＝候选池 + 多子区块并行 + 数量门禁 + 多目标寻路；
     * 关闭＝子区块串行扫描 + 子区块内直线最近目标 + 单目标寻路（不做落脚点预检）。
     */
    private static boolean multiTargetMode() {
        return Configs.Go.PATH_NEAREST_TARGET.getBooleanValue();
    }

    /**
     * 串行模式投递下一个子区块（一次只在飞一个）：出队过滤与并行版一致
     * （可见/已扫去重/与原理图相交），但不做层序等待与门禁——本子区块扫完、
     * 没派发候选时才走到下一个。
     *
     * @return 是否已投递
     */
    private boolean submitNextSerialSection(ClientLevel level) {
        while (!sectionQueue.isEmpty()) {
            BlockPos next = sectionQueue.poll();
            if (!isChunkVisible(next.getX() >> 4, next.getZ() >> 4)) {
                continue; // 玩家走远：尚未检查过，不标记，之后可再试
            }
            if (!visitedSections.add(sectionKey(next))) {
                continue; // 已检查过（同一区块被多个邻居重复入队，此处去重）
            }
            if (!SchematicStateCache.INSTANCE.intersectsSchematic(next, next.offset(15, 15, 15))) {
                continue; // 与原理图不相交（双保险，入队时已过滤）
            }
            cursorSection = next;   // 工作子区块：锚定判定与软重定位的参照
            layerScanning = sectionLayer(next);
            submitSection(level, next, layerScanning);
            return true;
        }
        return false;
    }

    /**
     * 投递一个子区块的后台扫描任务。主线程只做两件轻活：
     * <ol>
     * <li>向 {@link SchematicStateCache#planSection} 要一份只读"扫描计划"（盒∩子区块 + 容器 + 变换）；</li>
     * <li>把相邻子区块入队（BFS 推进与扫描解耦：任务在跑的同时可以继续扩散）。</li>
     * </ol>
     * 逐格重活（读容器、比世界、跳空气）全在工作线程完成，不再吃主线程 tick 预算。
     */
    private void submitSection(ClientLevel level, BlockPos section, int layer) {
        List<SchematicStateCache.RegionPlanEntry> plan = SchematicStateCache.INSTANCE.planSection(section);
        enqueueNeighbors(section);  // BFS：入队相邻子区块（出队时去重、层序由 FIFO 保证）
        if (plan.isEmpty()) {
            return; // 与原理图不相交：无格子可扫
        }
        if (scanPool == null) {
            scanPool = Executors.newFixedThreadPool(SCAN_WORKER_COUNT, r -> {
                Thread t = new Thread(r, "litematica-printer-scan");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);   // 让位于主线程/渲染
                return t;
            });
        }
        SectionScanResult result = new SectionScanResult(scanSerial, section, layer);
        // 「寻路多余方块」投递时快照（工作线程不读配置）：两个开关都开才扫多余方块
        final boolean scanExtras = extraScanEnabled();
        pendingScans++;
        try {
            scanPool.execute(() -> {
                try {
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    for (SchematicStateCache.RegionPlanEntry entry : plan) {
                        for (int y = entry.minY; y <= entry.maxY; y++) {
                            for (int z = entry.minZ; z <= entry.maxZ; z++) {
                                for (int x = entry.minX; x <= entry.maxX; x++) {
                                    // ① 原理图期望方块：空气永远不可能是"待放置候选"，先跳过（省掉后续全部昂贵判定）
                                    BlockState expected = entry.stateAt(x, y, z);
                                    if (expected == null || expected.isAir()) {
                                        // ②「寻路多余方块」：subregion 盒内原理图为空气的格子，现实却有
                                        //    非空气非液体方块 → 多余方块候选（口径同打印机「破坏多余方块」）。
                                        //    只认 stateAt 非空的空气格（null = 不在该 subregion 容器内，不能算多余）
                                        if (scanExtras && expected != null
                                                && BlockStateUtils.isColumnLoaded(level, x >> 4, z >> 4)) {
                                            BlockState current = level.getBlockState(pos.set(x, y, z));
                                            if (!current.isAir()
                                                    && !(current.getBlock() instanceof LiquidBlock)) {
                                                result.extraPositions.add(BlockPos.asLong(x, y, z));
                                            }
                                        }
                                        continue;
                                    }
                                    if (!BlockStateUtils.isColumnLoaded(level, x >> 4, z >> 4)) {
                                        // 列未加载：读到的是空气，不能当候选。
                                        // 注意 ClientLevel.hasChunk 在客户端恒返回 true（字节码即 return true），
                                        // 不能拿它当"已加载"判据；isColumnLoaded 走区块源，未加载返回 false
                                        continue;
                                    }
                                    // ③ 只问"是否已正确放置"：用 isCorrect 而不是 compare——compare 的覆盖打印
                                    // 分支会写静态 IdentityHashMap（主线程专用缓存），工作线程并发写会损坏该表
                                    BlockState current = level.getBlockState(pos.set(x, y, z));
                                    if (me.aleksilassila.litematica.printer.enums.BlockMatchResult
                                            .isCorrect(expected, current)) {
                                        continue;
                                    }
                                    result.positions.add(BlockPos.asLong(x, y, z));
                                    result.expected.add(expected);
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    // 工作线程异常（区块卸载竞态等）：不能让它吞掉结果——主线程在等 pendingScans 归零，
                    // 少一个结果就会让层边界永远等下去。异常按"本子区块无候选"处理并记日志。
                    Reference.LOGGER.warn("[扫描寻路] 子区块后台扫描异常（按无候选处理）", t);
                } finally {
                    scanResults.add(result);
                }
            });
        } catch (Throwable t) {
            // 投递失败（线程池已关/资源不足）：必须把计数退回来，否则层边界永远等不到 pendingScans 归零
            pendingScans = Math.max(0, pendingScans - 1);
            Reference.LOGGER.warn("[扫描寻路] 子区块扫描任务投递失败（已回滚在飞计数）", t);
        }
    }

    /** 主线程回收后台扫描结果：合并候选（白名单/冷却/落脚点预检/池计数） */
    private void drainScanResults(ClientLevel level) {
        SectionScanResult result;
        while ((result = scanResults.poll()) != null) {
            pendingScans = Math.max(0, pendingScans - 1);
            if (result.serial != scanSerial) {
                continue; // 会话已重建：结果作废
            }
            mergeScanResult(level, result);
        }
    }

    /**
     * 合并一个子区块的扫描结果。
     * <p>多目标模式（「最短路径优先」开启）：白名单判定（批次级前置已做）+ 冷却过滤 +
     * 落脚点预检，新增候选进<b>池</b>并累计"可到达"计数。
     * <p>串行模式（关闭）：<b>不进池、不做落脚点预检</b>（只剔除已完成与冷却中的），
     * 本子区块扫完即在此处选离玩家最近的 1 个直接派发——扫完没有候选才继续下一个子区块。
     */
    private void mergeScanResult(ClientLevel level, SectionScanResult result) {
        ScanWhitelistCache.WALK.beginScanBatch(); // 配置变更检测与验证器高亮刷新：每批一次，不是每格
        boolean flying = ghastFlying(mc.player);
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        boolean multi = multiTargetMode();
        ArrayList<BlockPos> sectionCandidates = multi ? null : new ArrayList<>();
        // 串行模式的多余方块候选（优先于本子区块的待放方块直接派发）
        ArrayList<BlockPos> sectionExtras = !multi && !result.extraPositions.isEmpty() && extraScanEnabled()
                ? new ArrayList<>() : null;
        if (!extraScanEnabled() && !areaExtraCandidates.isEmpty()) {
            // 开关已关：清掉多余方块池（含可到达计数）
            areaExtraCandidates.clear();
            extraReachableKeys.clear();
            extraReachableCount = 0;
        }
        for (int i = 0; i < result.positions.size(); i++) {
            BlockState expected = result.expected.get(i);
            if (!ScanWhitelistCache.WALK.isWhitelistedFast(expected)) {
                continue;
            }
            long key = result.positions.getLong(i);
            BlockPos pos = BlockPos.of(key);
            // 计划陈旧复核：扫描期间原理图被移动/旋转/停用/卸载时，工作线程按旧计划推出的位置或
            // 期望方块会错位（位置反变换读的是实时值，而盒/变换是计划时的快照）。用主线程缓存
            // 复核"该位置此刻的期望方块"，不一致就丢弃，绝不把错位候选塞进池子。
            BlockState live = SchematicStateCache.INSTANCE.getSchematicState(pos);
            if (live == null || live.isAir() || !live.equals(expected)) {
                continue;
            }
            // 原"扫描器路过即对账"：把与验证器记录不一致的差异提交复查管线（主线程）
            SchematicStateCache.INSTANCE.reconcileVerdict(pos, expected, level.getBlockState(pos));
            if (!selectionAllows(pos)) {
                continue; // 「打印-选取类型」之外（渲染层/玩家上下方）：打印机不会放，寻路不认领
            }
            if (!multi) {
                // 串行模式：本子区块内的未放置方块就是候选，只剔已完成/冷却中的；
                // 不检查周围有没有落脚点（能否到达由寻路裁决），也不跨子区块累积
                if (unreachableCooldown.get(key) > now || targetCompleted(pos)) {
                    continue;
                }
                sectionCandidates.add(pos);
                continue;
            }
            if (!areaCandidates.add(key)) {
                continue; // 已在池中（跨子区块/重复并入）：不重复计数
            }
            if (unreachableCooldown.get(key) > now) {
                continue; // 不可达冷却中：留在池里，冷却过了再参与比较
            }
            if (reachableAt(level, pos, flying)) {
                if (reachableKeys.add(key)) {
                    reachablePoolCount++; // 飞行＝周围有可悬停位；走路＝周围有可站立位
                }
            }
        }
        // 多余方块并入池（主线程复核：已被破坏的丢弃，冷却中留池，可到达计数同步）
        if (!result.extraPositions.isEmpty() && extraScanEnabled()) {
            for (int i = 0; i < result.extraPositions.size(); i++) {
                long key = result.extraPositions.getLong(i);
                if (!areaExtraCandidates.add(key)) {
                    continue; // 已在池中
                }
                if (level.getBlockState(BlockPos.of(key)).isAir()) {
                    areaExtraCandidates.remove(key);
                    continue; // 已被破坏
                }
                if (unreachableCooldown.get(key) > now) {
                    continue; // 不可达冷却中：留在池里
                }
                if (reachableAt(level, BlockPos.of(key), flying) && extraReachableKeys.add(key)) {
                    extraReachableCount++;
                }
            }
        }
        if (sectionExtras != null) {
            for (int i = 0; i < result.extraPositions.size(); i++) {
                long key = result.extraPositions.getLong(i);
                BlockPos pos = BlockPos.of(key);
                if (level.getBlockState(pos).isAir() || unreachableCooldown.get(key) > now) {
                    continue; // 已被破坏 / 冷却中
                }
                sectionExtras.add(pos);
            }
        }
        if (sectionCandidates != null) {
            // 本子区块整块扫完 → 在这一个子区块内选离玩家最近的 1 个直接派发；
            // 空列表＝本子区块没有未放置方块，留在扫描态由 pumpScan 继续下一个子区块。
            // 有多余方块候选时优先派多余方块（先清场再放置）
            dispatchSerialNearest(sectionExtras != null && !sectionExtras.isEmpty()
                    ? sectionExtras : sectionCandidates);
        }
    }

    /**
     * 工作区候选池派发：池内全部候选复核（已完成剔除/不可达冷却跳过）+
     * 落脚点预检（"周围有可站的地方"＝当前地形下可到达）后，按离玩家的距离升序，
     * 交给 {@link #dispatchCandidates}（多目标竞争 / 单目标回退在那里裁决）。
     *
     * <p><b>门禁</b>：可到达候选的数量要<b>凑够「多目标候选上限」</b>才开始比较——
     * 池子不完整时比较出的"最近"没有意义；只有把玩家渲染距离内的方块都扫完
     * （FIND 枚举穷尽）仍凑不够时，才用手里已有的候选开始比较（{@code force}）。
     *
     * @param force true＝不再等凑数（渲染距离内已扫尽时的兜底：有多少比多少）
     * @return 是否已派发（false = 池内无可用候选或还在凑数，交由调用方继续扫新子区块）
     */
    private boolean poolDispatch(ClientLevel level, boolean force) {
        if (level == null || areaCandidates.isEmpty()) {
            return false;
        }
        // 门禁先判（廉价：每 tick 都会跑，池子上千时不能每次全量复核）：
        // 可到达候选数凑够目标数量才做权威复核并开始比较；
        // "渲染距离内已扫尽仍凑不够"的兜底不在这里判（要遍历 FIND 全表，太贵），
        // 而是由 FIND 枚举穷尽这个天然信号触发——见 findCenterStep 尾部的强制派发
        int required = Configs.Go.PATH_TARGET_CANDIDATE_LIMIT.getIntegerValue();
        // 门禁：可到达候选凑够目标数量才比较；多余方块候选存在时不受数量门禁限制
        //（优先清场，且多余方块通常不多，等凑数会让优先级落空）
        if (!force && reachablePoolCount < required && extraReachableCount == 0) {
            return false;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        boolean flying = ghastFlying(mc.player);
        ArrayList<BlockPos> candidates = new ArrayList<>(areaCandidates.size());
        LongArrayList completedKeys = new LongArrayList();
        for (long key : areaCandidates) {
            if (unreachableCooldown.get(key) > now) {
                continue; // 不可达冷却中：留在池子里，冷却过了再参与比较
            }
            BlockPos pos = BlockPos.of(key);
            if (targetCompleted(pos)) {
                completedKeys.add(key);
                continue; // 已被放置：从池子里剔除
            }
            // 顺手把"可到达"计数纠正过来：并入时可能正处冷却而漏计，冷却过期后在这里补上
            // （计数与 reachableKeys 严格同步，剔除时才能正确递减）
            if (reachableAt(level, pos, flying) && reachableKeys.add(key)) {
                reachablePoolCount++;
            }
            if (!selectionAllows(pos)) {
                continue; // 入池后选取范围变了（切渲染层/玩家上下分界）：留池，不参与本轮比较
            }
            candidates.add(pos);
        }
        areaCandidates.removeAll(completedKeys);
        for (int i = 0; i < completedKeys.size(); i++) {
            if (reachableKeys.remove(completedKeys.getLong(i))) {
                reachablePoolCount = Math.max(0, reachablePoolCount - 1); // 剔除已放置的，同步递减
            }
        }
        filterStandSpot(level, candidates); // 落脚点预检：只保留周围有可站立足面的（当前地形下可到达）
        // 多余方块优先：存在可派发的多余方块时，本轮只派多余方块（先清场再放置）——
        // 走路与飞行共用同一套候选竞争/多目标寻路，「最短路径优先」在其中照常生效
        ArrayList<BlockPos> extraList = collectExtraCandidates(level, now, flying);
        if (extraList != null && !extraList.isEmpty()) {
            filterStandSpot(level, extraList);
            if (!extraList.isEmpty()) {
                LocalPlayer playerExtra = mc.player;
                if (playerExtra != null) {
                    extraList.sort(Comparator.comparingDouble(p -> straightDist(p, playerExtra)));
                }
                dispatchCandidates(extraList);
                return true;
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        LocalPlayer player = mc.player;
        if (player != null) {
            candidates.sort(Comparator.comparingDouble(p -> straightDist(p, player)));
        }
        dispatchCandidates(candidates);
        return true;
    }

    /**
     * 收集本轮可派发的多余方块候选（池复核：已被破坏的出池、冷却跳过、选取范围外留池，
     * 可到达计数同步）。「寻路多余方块」关闭时清池并返回 null。
     *
     * @return 本轮可派发的多余方块候选；空池/开关关闭返回 null（调用方回落普通候选）
     */
    @Nullable
    private ArrayList<BlockPos> collectExtraCandidates(ClientLevel level, long now, boolean flying) {
        if (areaExtraCandidates.isEmpty()) {
            return null;
        }
        if (!extraScanEnabled()) {
            areaExtraCandidates.clear();
            extraReachableKeys.clear();
            extraReachableCount = 0;
            return null;
        }
        ArrayList<BlockPos> out = new ArrayList<>();
        LongArrayList goneKeys = new LongArrayList();
        for (long key : areaExtraCandidates) {
            BlockPos pos = BlockPos.of(key);
            if (level.getBlockState(pos).isAir()) {
                goneKeys.add(key);
                continue; // 已被破坏：出池
            }
            if (unreachableCooldown.get(key) > now) {
                continue; // 冷却中：留池，冷却过了再参与比较
            }
            if (reachableAt(level, pos, flying) && extraReachableKeys.add(key)) {
                extraReachableCount++;
            }
            if (!selectionAllows(pos)) {
                continue; // 入池后选取范围变了（切渲染层/玩家上下分界）：留池，不参与本轮比较
            }
            out.add(pos);
        }
        if (!goneKeys.isEmpty()) {
            areaExtraCandidates.removeAll(goneKeys);
            for (int i = 0; i < goneKeys.size(); i++) {
                if (extraReachableKeys.remove(goneKeys.getLong(i))) {
                    extraReachableCount = Math.max(0, extraReachableCount - 1);
                }
            }
        }
        return out;
    }

    /** 「寻路多余方块」是否生效：本开关 + 前置「破坏多余方块」同时开启 */
    private static boolean extraScanEnabled() {
        return Configs.Go.GO_SCAN_EXTRA_BLOCKS.getBooleanValue()
                && Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue();
    }

    /**
     * 派发候选目标（验证器候选与子区块扫描候选共用）。
     * <p>「最短路径优先」<b>开启</b>且候选 ≥2 且不在多目标回退期 → 把全部候选的紧邻站立格
     * 作为一个目标集合做一次多目标寻路（{@link GoPathfinder.GoalSet}），第一个定稿的
     * 目标即路径成本最短的候选，到达后由 {@link GoManager#getReachedGoalCell()} 反查；
     * 关闭或仅 1 个候选时取直线距离最近的走单目标寻路。
     * <p>「最短路径优先」<b>关闭</b> → 没有候选竞争阶段：候选列表不写描边快照（不画黄框），
     * 直接取直线距离最近的 1 个单目标派发。
     */
    private void dispatchCandidates(ArrayList<BlockPos> candidates) {
        LocalPlayer player = mc.player;
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (!multiTargetMode()) {
            // 关闭「最短路径优先」：没有候选竞争阶段 → <b>不画候选黄框</b>，
            // 只取直线距离最近的 1 个交给单目标寻路（不检查落脚点，能否到达由寻路裁决）
            dispatchedCandidates = List.of();
            selectedTarget = null;
            dispatchSingleNearest(candidates, "「最短路径优先」关闭");
            return;
        }
        // 描边/诊断快照：黄＝本批全部候选，绿＝选中目标（多目标腿定稿后再填）
        dispatchedCandidates = List.copyOf(candidates);
        selectedTarget = null;
        if (candidates.size() >= 2 && now >= multiFallbackTick) {
            int limit = Configs.Go.PATH_TARGET_CANDIDATE_LIMIT.getIntegerValue();
            GoPathfinder.Goal goalSet;
            Long2ObjectOpenHashMap<BlockPos> cells;
            boolean flying = Configs.Go.GHAST_PATHFIND.getBooleanValue() && GhastRideState.canFly(player);
            if (flying) {
                // 乐魂飞行：悬停位集合（切比雪夫半径随并集箱尺寸动态推导）
                // + "够得着"过滤（玩家眼位偏移快照：停在目标上方的位置多半够不着打印机）
                GhastGoal.HoverGoalSet hover = GhastGoal.hoverGoalSet(candidates, limit,
                        player.blockPosition(), GoManager.INSTANCE.ghastHoverRadius(),
                        GoManager.INSTANCE.eyeOffset());
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
            GoManager.INSTANCE.autoDispatchMulti(goalSet, cells);
            state = State.DRIVING;
            sectionQueue.clear();
            return;
        }
        dispatchSingleNearest(candidates, candidates.size() < 2 ? "候选不足 2 个"
                : "多目标失败后的回退期（剩余 " + (multiFallbackTick - now) + " tick）");
    }

    /**
     * 单目标派发：候选里取直线距离最近的 1 个交给寻路（「最短路径优先」关闭时的唯一路径，
     * 也是多目标搜索失败时的回退），<b>不检查落脚点</b>——可否到达由寻路本身裁决。
     * 候选列表不写入描边快照（没有候选竞争阶段，不画黄框）。
     */
    private void dispatchSingleNearest(ArrayList<BlockPos> candidates, String reason) {
        LocalPlayer player = mc.player;
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
        selectedTarget = best;
        GoManager.INSTANCE.autoDispatch(best);
        state = State.DRIVING;
        sectionQueue.clear();
    }

    /**
     * 串行模式（「最短路径优先」关闭）的子区块派发：<b>只在这一个子区块的候选里</b>
     * 选离玩家最近的 1 个直接派发；空列表＝本子区块没有未放置方块，
     * 留在扫描态由 {@link #pumpScan} 继续下一个子区块。
     */
    private void dispatchSerialNearest(ArrayList<BlockPos> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        dispatchSingleNearest(candidates, "子区块内直线最近（关闭「最短路径优先」）");
    }

    private static double straightDist(BlockPos a, LocalPlayer player) {
        double dx = a.getX() + 0.5 - player.getX();
        double dy = a.getY() + 0.5 - player.getY();
        double dz = a.getZ() + 0.5 - player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void tickDriving() {
        BlockPos t = target;
        if (t == null && legGoalCells == null) {
            enterScanning();
            return;
        }
        // 多目标腿：A* 一定稿就把"选中的目标"标绿（不必等到到达；部分路径/超时掐断时仍为 null）
        if (legGoalCells != null) {
            BlockPos current = GoManager.INSTANCE.getMultiGoalCurrentTarget();
            if (current != null) {
                selectedTarget = current;
            }
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
                selectedTarget = resolved;
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
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= PRINTER_REACH_SQ) {
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
        // 走到半路选取范围变了（切渲染层/玩家上下分界被跨越）：打印机不会再放它，
        // 与"需要 shift 拉黑"同口径冷却换目标，不无限等待
        if (!selectionAllows(t)) {
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

    // 子区块的推进（层边界判定 + 投递）全部收敛在 pumpScan/submitSection 里：
    // 主线程不再逐格扫描，因此没有"游标续扫"状态；"扫完一个子区块"＝后台任务返回并被合并。

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

    /** 子区块相对扫描中心（{@link #areaCenterSection}）的曼哈顿距离（单位＝子区块），即曼哈顿层号 */
    private int sectionLayer(@Nullable BlockPos section) {
        BlockPos center = areaCenterSection;
        if (center == null || section == null) {
            return 0;
        }
        return Math.abs((section.getX() >> 4) - (center.getX() >> 4))
                + Math.abs((section.getY() >> 4) - (center.getY() >> 4))
                + Math.abs((section.getZ() >> 4) - (center.getZ() >> 4));
    }

    /**
     * FIND 阶段（选中心）：枚举与原理图相交的子区块（即含原理图方块的
     * 子区块，<b>无论是否已放置</b>，由 subregion 盒直接展开），过滤已加载、渲染距离内与未探索的，
     * 按离玩家最近排序后取第一个直接作为中心进入扫描——不要求扫描出未放置候选；本中心扫完
     * 无候选时由 {@link #pumpScan} 按层向外扩散（BFS 邻居入队），
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
            long playerSec = playerSectionBase().asLong();
            long nowTick = ClientPlayerTickManager.getCurrentHandlerTime();
            if (findSectionsCache != null && playerSec == findSectionsCachePlayer
                    && nowTick - findSectionsCacheTick < FIND_CACHE_TICKS) {
                // 复用上轮的枚举与排序：玩家没换子区块就不必重排 ~30 万条
                // （「不复用候选」后每轮都要重扫，这里是最大的固定开销）
                findSections = findSectionsCache;
                findIndex = 0;
            } else {
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
                findSectionsCache = findSections;
                findSectionsCachePlayer = playerSec;
                findSectionsCacheTick = nowTick;
                findIndex = 0;
            }
        }
        while (findIndex < findSections.size()) {
            BlockPos section = findSections.get(findIndex);
            findIndex++;
            int sx = section.getX() >> 4;
            int sz = section.getZ() >> 4;
            if (!BlockStateUtils.isColumnLoaded(level, sx, sz)) {
                continue; // 未加载：后续重新枚举时会再试
            }
            if (!isChunkVisible(sx, sz)) {
                continue; // 不标记已搜索：玩家靠近进入渲染圈后可再试
            }
            if (visitedSections.contains(sectionKey(sx, section.getY() >> 4, sz))) {
                continue; // 已搜索过
            }
            visitedSections.add(sectionKey(sx, section.getY() >> 4, sz));
            cursorSection = section;
            // 新中心＝曼哈顿层的原点，层号从这里重新向外数（此前扫过的区块已标记，BFS 自然跳过）
            areaCenterSection = section;
            layerScanning = 0;
            submitSection(level, section, 0);
            verifierPollNeeded = true;
            state = State.SCANNING;
            return;
        }
        // 枚举穷尽：渲染圈内可探索的原理图子区块全部探索过
        // → 多目标模式先用手里的候选开始比较（哪怕没凑够目标数量，扫尽了就只能这么比）；
        // 串行模式没有池子，直接待命
        if (pendingScans > 0) {
            return; // 还有在飞任务未合并：等它们回来再判（避免用不完整的池子做"扫尽"兜底）
        }
        if (multiTargetMode() && poolDispatch(level, true)) {
            return;
        }
        findSections = null;
        revisionAtDone = SchematicStateCache.INSTANCE.getRevision();
        nextDoneRecheckTick = ClientPlayerTickManager.getCurrentHandlerTime() + DONE_RECHECK_INTERVAL_TICKS;
        state = State.DONE;
    }

    // ==================== 验证器目标源 ====================

    /** 进入扫描态：先尝试验证器缺失列表选目标（离玩家最近），无候选时退回子区块逐格扫描 */
    private void enterScanning() {
        // 「不复用候选」：每次进入扫描态都重置扫描会话——清空候选池、已扫标记、扩散队列与
        // FIND 枚举，下一轮从玩家当前位置重新扫；在飞结果一并作废（池子已清，不能混进新一轮）。
        // 只保留 unreachableCooldown（"够不着/不可达"的抑制表），否则会反复去试同一个到不了的目标。
        areaCandidates.clear();
        reachableKeys.clear();
        reachablePoolCount = 0;
        areaExtraCandidates.clear();
        extraReachableKeys.clear();
        extraReachableCount = 0;
        visitedSections.clear();
        sectionQueue.clear();
        areaCenterSection = null;
        layerScanning = -1;
        findSections = null;
        findIndex = 0;
        scanSerial++;
        pendingScans = 0;
        scanResults.clear();
        // 候选不复用：上一轮的黄框描边一并清掉（绿框 selectedTarget 保留，提示在等哪个方块）
        dispatchedCandidates = List.of();
        verifierPollNeeded = true;
        state = State.SCANNING;
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
        if (multiTargetMode()) {
            // 落脚点预检只在多目标模式：关闭「最短路径优先」时"找到目标直接交给算法推算路径"，
            // 不检查周围有没有落脚点
            filterStandSpot(level, candidates);
        }
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
     * 派发前预检：剔除周围"没有可到达位置"的候选（只删必死，不误删活）——
     * <b>走路</b>＝周围有可站立位（{@link GoPathfinder#hasStandableNeighbor}）；
     * <b>乐魂飞行</b>＝周围有可悬停位（水平切比雪夫 3、竖直 ±1 内存在放得下
     * 「乐魂+骑乘者」并集箱、且非原理图实心的空气位，见
     * {@link GhastPathfinder#hasHoverSpot}）。判定无效 = 目标周围连一个能停的地方都没有，
     * 该候选必死（A* 只会白烧预算、表现为"框在、路线不出"）。
     * 本轮不派、不写冷却表：下轮派发（目标完成/回到扫描态）时会重新预检，地形变化后自然恢复。
     */
    private void filterStandSpot(ClientLevel level, ArrayList<BlockPos> candidates) {
        if (level == null || candidates.isEmpty()) {
            return;
        }
        boolean flying = ghastFlying(mc.player);
        GhastPathfinder.BoxSpec spec = flying ? GoManager.INSTANCE.currentBoxSpec() : null;
        candidates.removeIf(pos -> flying
                ? !GhastPathfinder.hasHoverSpot(level, pos, spec)
                : !GoPathfinder.hasStandableNeighbor(level, pos));
    }

    /** 候选"可到达"预检（池内可到达计数的口径，与 {@link #filterStandSpot} 一致） */
    private static boolean reachableAt(ClientLevel level, BlockPos pos, boolean flying) {
        if (flying) {
            return GhastPathfinder.hasHoverSpot(level, pos, GoManager.INSTANCE.currentBoxSpec());
        }
        return GoPathfinder.hasStandableNeighbor(level, pos);
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
        if (!BlockStateUtils.isColumnLoaded(level, pos.getX() >> 4, pos.getZ() >> 4)
                || !isChunkVisible(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false; // 未加载或渲染距离外（看不见地形）
        }
        if (unreachableCooldown.get(pos.asLong()) > now) {
            return false;
        }
        if (GhastShiftBlacklist.contains(mc.player, pos)) {
            return false; // 骑乘时"需要 shift 的放置"已拉黑：不再派发（离开乐魂自动清空）
        }
        if (!selectionAllows(pos)) {
            return false; // 「打印-选取类型」之外（渲染层/玩家上下方）：打印机不会放
        }
        return !targetCompleted(pos);
    }

    /**
     * 目标是否落在「打印-选取类型」约束内——与打印动作侧
     * （{@code ClientPlayerTickHandler} 的逐方块动作循环）同一判定、同一配置：
     * 渲染层＝当前渲染层范围内；玩家下方/上方＝按 Y 分半；投影选择框＝无额外约束。
     * 打印机不会放置的位置，寻路不选不等——否则会走到目标旁无限等待。
     */
    private boolean selectionAllows(BlockPos pos) {
        return PlayerUtils.isPositionInSelectionRange(mc.player, pos, Configs.Print.PRINT_SELECTION_TYPE);
    }

    /**
     * 「打印-选取类型」约束的指纹：选取类型选项 + 渲染层范围（轴/层下界/层上界）。
     * 渲染层模式在 litematica「全部层」时范围为全世界高度（指纹恒定），
     * 单层/层区间随层移动变化——变化即已扫子区块的结论过期，需清标记重扫。
     */
    private long selectionSig() {
        long type = Configs.Print.PRINT_SELECTION_TYPE.getOptionListValue() instanceof SelectionType st
                ? st.ordinal() : -1L;
        LayerRange range = DataManager.getRenderLayerRange();
        return (type & 0xF) << 56 | (long) (range.getAxis().ordinal() & 0xF) << 52
                | ((long) range.getLayerMin() & 0xF_FFFF) << 20 | ((long) range.getLayerMax() & 0xF_FFFF);
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
     * 目标完成后继续。<b>不复用上一轮的候选</b>：直接重置扫描会话（清空候选池、已扫标记、
     * 扩散队列与 FIND 枚举），下一轮从玩家当前位置重新扫、重新比较选目标。
     * 旧任务腿不打断：若寻路仍在走，由 tickScan 顶部的等待逻辑兜住，等它走完再派发新任务。
     */
    private void onTargetDone() {
        target = null;
        enterScanning();
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

    /** 打印机是否处于打印模式工作状态（统一口径见 {@link ConfigUtils#isPrintModeActive}） */
    private boolean printModeActive() {
        return ConfigUtils.isPrintModeActive();
    }

    private void deactivate() {
        if (GoManager.INSTANCE.isAutoActive()) {
            GoManager.INSTANCE.stop(null);
        }
        state = State.IDLE;
        target = null;
        legGoalCells = null;
        legGoalSet = null;
        dispatchedCandidates = List.of();
        selectedTarget = null;
        areaCandidates.clear();
        reachablePoolCount = 0;
        reachableKeys.clear();
        areaExtraCandidates.clear();
        extraReachableKeys.clear();
        extraReachableCount = 0;
        areaCenterSection = null;
        layerScanning = -1;
        scanSerial++;               // 在飞扫描结果全部作废（扫描已停）
        pendingScans = 0;           // 同步复位：否则再次 activate 时 findCenterStep 的"等任务"早退会把状态卡住
        scanResults.clear();
        sectionQueue.clear();
        cursorSection = null;
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
