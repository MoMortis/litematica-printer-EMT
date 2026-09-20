package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * /go 自动寻路总控：状态机 + 重算调度 + 卡住检测 + 玩家跟随目标。
 *
 * <p>只驱动移动（经 {@link GoExecutor} 覆写玩家输入），不做破坏/放置等任何寻路之外的动作。
 * 寻路计算在后台单线程执行（时间预算见 特殊功能-GO_TIME_LIMIT），
 * 结果经主线程回调落地；执行途中只保留一条当前路径，新结果整体替换。
 */
public final class GoManager {
    public static final GoManager INSTANCE = new GoManager();

    /** 驾驶来源：手动 /go 指令（优先）或自动派发（扫描自动寻路） */
    private enum DriveMode { NONE, MANUAL, AUTO }

    private static final ExecutorService CALC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "litematica-printer-go-calc");
        t.setDaemon(true);
        return t;
    });

    private static final long STUCK_CHECK_INTERVAL_TICKS = 20;
    private static final double STUCK_MIN_MOVE_SQ = 0.25 * 0.25;
    private static final int MAX_STUCK_REPATHS = 4;
    private static final int MAX_REPATHS = 60;
    private static final long TARGET_CHECK_INTERVAL_TICKS = 20;
    private static final double TARGET_REROUTE_DISTANCE_SQ = 4.0 * 4.0;
    // 偏离检测：每 tick 采样，玩家到剩余路径的最小距离超过阈值（GO_DEVIATION_DISTANCE，
    // 默认 1 格）立即停止；总开关 GO_DEVIATION_STOP 可整体关闭。着地/入水比三维距离
    // （正常行走距最近路点约 ≤0.9 格、跑酷/跳上接近段有上一路点兜底 ≤0.8 格，均不会
    // 误触）；腾空分两类：自主跳跃（跑酷/跳上/出水跳，GoExecutor 起跳暂挂
    // selfJumpAirborne）整个空中阶段放行——缺口跳跃中点距两端路点必然超过阈值，属
    // 合法离路瞬间；外力腾空（被击退/冲走/失足）不放行且只比水平距离——若放行，寻路
    // 自己的空中操控会把玩家拉回路径内，落地采样永远不超阈值，击退带离永远判不到；
    // 只比水平是因为纵向起伏是下落/下台阶的正常现象，而正常腾空动作的水平偏移
    // 到不了 1 格
    private static final long DEVIATION_CHECK_INTERVAL_TICKS = 1;
    /** 乐魂飞行寻路：障碍快照（原理图非空气坐标）的外扩格数 */
    private static final int GHAST_OBSTACLE_PAD = 4;
    /** 乐魂飞行寻路：多目标（目标位置未知）时的障碍快照半径（格） */
    private static final int GHAST_OBSTACLE_RANGE = 16;
    /** 乐魂飞行寻路：多目标时的障碍快照取样候选数——按直线距离取最近的这些候选求包围盒。
     *  A* 的启发是"到最近候选的距离"，远处候选只有近处全不可达时才会被考虑，且成本上限
     *  会先把它们排掉，故近处这批的包围盒就够覆盖实际会走的区域 */
    private static final int GHAST_OBSTACLE_MULTI_CANDIDATES = 32;
    /** 乐魂飞行寻路：中途路点推进阈值（格）——须大于控制律的减速触发距离(2.0)，
     *  否则路点推不动、原地悬停 */
    private static final double GHAST_WAYPOINT_ARRIVE_SQ = 2.5 * 2.5;
    /** 乐魂飞行寻路：越过走廊半径（格）——沿路径方向已越过路点、且横向垂距在此范围内才算越过 */
    private static final double GHAST_CORRIDOR_RADIUS = 3.0;
    /** 乐魂飞行寻路：障碍快照最短重建间隔（tick）——revision 在打印期几乎每刻递增，必须节流 */
    private static final long GHAST_OBSTACLE_MIN_REBUILD_TICKS = 100;

    /** 乐魂飞行寻路：「原理图非空气」障碍快照缓存（主线程构建，按修订号 + 起止子区块失效） */
    @Nullable
    private LongOpenHashSet ghastObstacleCache;
    private long ghastObstacleRevision = Long.MIN_VALUE;
    private long ghastObstacleFrom = Long.MIN_VALUE;
    private long ghastObstacleTo = Long.MIN_VALUE;
    private long ghastObstacleBuiltTick = Long.MIN_VALUE;

    // ===== 节点超时（乐魂飞行，见 tickWaypointTimeout）=====
    /** 当前路点的锚定键（路点坐标 ⊕ 所引）：变化＝推进到新节点/换了路径，重置计时基准 */
    private long waypointKey = Long.MIN_VALUE;
    /** 当前路点开始计时的 tick */
    private long waypointStartTick = -1L;
    /** 锚定时刻的导航主体位置（≈上一节点处；A→B 距离按它到当前路点算） */
    private double waypointAnchorX;
    private double waypointAnchorY;
    private double waypointAnchorZ;

    private final Minecraft mc = Minecraft.getInstance();

    private volatile boolean active;
    private volatile boolean calculating;
    /** 请求序号：新请求/停止都会递增，使在途计算结果作废 */
    private volatile long calcSerial;
    private volatile DriveMode driveMode = DriveMode.NONE;
    /** 当前寻路的到达判定目标（MANUAL=站在目标方块，AUTO=走到目标附近） */
    private volatile GoPathfinder.Goal activeGoal;
    private volatile BlockPos goal;
    private volatile UUID liveTargetId;
    private volatile List<BlockPos> path = List.of();
    /** 多目标模式（扫描寻路派发）的站立格→候选映射；null = 单目标腿 */
    @Nullable
    private volatile Long2ObjectOpenHashMap<BlockPos> multiGoalCells;
    /** 多目标腿的候选集合包围盒（minX,minY,minZ,maxX,maxY,maxZ；派发时算好，供障碍快照定范围） */
    @Nullable
    private int[] multiGoalBounds;
    /** 包围盒对应的候选集合指纹（障碍快照缓存的失效键：候选变了不能复用旧快照） */
    private long multiGoalBoundsKey = Long.MIN_VALUE;
    /** 最近一次多目标腿到达的目标格（玩家脚格）：到达后置位，新会话/玩家失效时清空；
     *  stop 不清（供扫描器在腿结束后反查） */
    @Nullable
    private volatile BlockPos reachedGoalCell;

    /**
     * 当前多目标腿 A* <b>已定稿</b>的目标格对应的候选（诊断与描边用）：
     * 供渲染把"本次选中的目标"标绿、供日志说明"到底选了谁"。A* 未定稿（部分路径/超时掐断）时为 null。
     */
    @Nullable
    public BlockPos getMultiGoalCurrentTarget() {
        Long2ObjectOpenHashMap<BlockPos> cells = multiGoalCells;
        List<BlockPos> p = path;
        if (cells == null || p.isEmpty()) {
            return null;
        }
        return cells.get(p.get(p.size() - 1).asLong());
    }

    // ===== 主线程专用状态 =====
    private int waypointIndex;
    /** 最近一次 onPathResult 的路径是否真正到达目标：部分路径的尾节点也可能贴近起点，
     *  "贴近终点也算到达"兜底只对到过目标的路径生效，否则被困腔内时部分路径会误判到达 */
    private boolean pathReachedGoal;
    private float bestDistToGoal = Float.MAX_VALUE;
    private int repaths;
    private int stuckRepaths;
    private long nextStuckCheckTick = -1L;
    private double stuckRefX;
    private double stuckRefY;
    private double stuckRefZ;
    private long nextTargetCheckTick = -1L;
    private long nextDeviationCheckTick = -1L;

    private GoManager() {
    }

    public boolean isActive() {
        return active;
    }

    @Nullable
    public BlockPos getGoal() {
        return goal;
    }

    public List<BlockPos> getPath() {
        return path;
    }

    public int getWaypointIndex() {
        return waypointIndex;
    }

    /** 当前应走向的路点；无路径时为 null */
    @Nullable
    public BlockPos getWaypoint() {
        List<BlockPos> p = this.path;
        return waypointIndex < p.size() ? p.get(waypointIndex) : null;
    }

    /** /go x y z：走到目标方块（或离其最近的可达位置） */
    public void go(BlockPos target) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        begin(target, null, DriveMode.MANUAL, goalFor(target, true),
                "§a[寻路] 目标: " + target.getX() + " " + target.getY() + " " + target.getZ());
    }

    /** /go <player>：跟随目标玩家（每秒刷新目标位置） */
    public void go(AbstractClientPlayer target) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        BlockPos tpos = target.blockPosition();
        begin(tpos, target.getUUID(), DriveMode.MANUAL, goalFor(tpos, true),
                "§a[寻路] 跟随玩家: " + target.getName().getString());
    }

    /**
     * 自动派发（扫描自动寻路）：走到待放置方块紧邻位置（水平相邻、上下 ±1 层，
     * 不占用目标格）即到达并释放控制。无聊天提示，目标经渲染描边展示；
     * 重复派发会替换上一条自动行程。
     */
    public void autoDispatch(BlockPos target) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        multiGoalCells = null;
        begin(target, null, DriveMode.AUTO, autoGoal(target), null);
    }

    /**
     * 目标判定分派：乐魂飞行一律用"悬停位"（切比雪夫半径随并集箱尺寸动态推导），
     * 否则用走路版语义（{@code walkGoal=true} 为"站在目标方块"，false 为"走到紧邻格"）。
     */
    private GoPathfinder.Goal goalFor(BlockPos target, boolean walkGoal) {
        if (isGhastFlying()) {
            return GhastGoal.hoverGoal(target, ghastHoverRadius(), eyeOffset());
        }
        return walkGoal ? GoPathfinder.blockGoal(target) : GoPathfinder.adjacentGoal(target);
    }

    /**
     * 玩家眼位相对"乐魂基准点"的偏移快照（主线程；未骑乘/无乐魂时 null）。
     * 供飞行悬停位"够得着"过滤：玩家坐在乐魂背上，眼高约 +5 格，故"停在目标上方"的
     * 悬停位多半够不着，只有下方/侧下方才在打印机交互距离内。
     */
    @Nullable
    public GhastGoal.EyeOffset eyeOffset() {
        LocalPlayer p = mc.player;
        if (p == null) {
            return null;
        }
        var ghast = GhastRideState.riddenGhast(p);
        if (ghast == null) {
            return null;
        }
        Vec3 eye = p.getEyePosition();
        return new GhastGoal.EyeOffset(eye.x - ghast.getX(), eye.y - ghast.getY(), eye.z - ghast.getZ());
    }

    /** 自动模式的目标判定 */
    private GoPathfinder.Goal autoGoal(BlockPos target) {
        return goalFor(target, false);
    }

    /**
     * 多目标自动派发（扫描自动寻路·最短路径优先）：把全部候选的紧邻站立格作为
     * 一个目标集合寻路，第一个定稿的目标即路径最短候选；到达时记下玩家脚格，
     * 供扫描器经 {@link #getReachedGoalCell()} 反查是哪个候选。
     */
    public void autoDispatchMulti(GoPathfinder.Goal goalSet,
                                  Long2ObjectOpenHashMap<BlockPos> cellToTarget) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        multiGoalCells = cellToTarget;
        computeMultiGoalBounds(cellToTarget);
        begin(null, null, DriveMode.AUTO, goalSet, null);
    }

    /**
     * 多目标腿的障碍快照范围依据：把候选<b>去重</b>后按离导航主体的直线距离排序，取最近
     * {@link #GHAST_OBSTACLE_MULTI_CANDIDATES} 个求包围盒，并算一个候选集合指纹供缓存失效。
     * 原来多目标退化用"起点 ±{@link #GHAST_OBSTACLE_RANGE} 格"——候选在 30+ 格外时那段
     * 路程完全没有原理图约束，A* 会直接把路径穿过原理图的非空气方块。
     */
    private void computeMultiGoalBounds(Long2ObjectOpenHashMap<BlockPos> cells) {
        if (cells == null || cells.isEmpty()) {
            multiGoalBounds = null;
            multiGoalBoundsKey = Long.MIN_VALUE;
            return;
        }
        LongOpenHashSet seen = new LongOpenHashSet(256);
        ArrayList<BlockPos> cands = new ArrayList<>(256);
        for (BlockPos c : cells.values()) {
            if (seen.add(c.asLong())) {
                cands.add(c); // 悬停位远多于候选：先去重出候选方块本身
            }
        }
        Entity nav = navEntity();
        BlockPos from = nav != null ? nav.blockPosition() : (mc.player != null ? mc.player.blockPosition() : null);
        if (from != null) {
            cands.sort(Comparator.comparingDouble(c -> c.distSqr(from)));
        }
        int n = Math.min(cands.size(), GHAST_OBSTACLE_MULTI_CANDIDATES);
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        long h = 1125899906842597L;
        for (int i = 0; i < n; i++) {
            BlockPos c = cands.get(i);
            minX = Math.min(minX, c.getX());
            minY = Math.min(minY, c.getY());
            minZ = Math.min(minZ, c.getZ());
            maxX = Math.max(maxX, c.getX());
            maxY = Math.max(maxY, c.getY());
            maxZ = Math.max(maxZ, c.getZ());
            h = h * 31 + c.asLong();
        }
        multiGoalBounds = new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        multiGoalBoundsKey = h;
    }

    /** 最近一次多目标腿到达的目标格（未到达/单目标腿为 null） */
    @Nullable
    public BlockPos getReachedGoalCell() {
        return reachedGoalCell;
    }

    public boolean isManualActive() {
        return active && driveMode == DriveMode.MANUAL;
    }

    public boolean isAutoActive() {
        return active && driveMode == DriveMode.AUTO;
    }

    public void stop(@Nullable String reason) {
        boolean wasActive = active;
        active = false;
        calcSerial++;
        calculating = false;
        driveMode = DriveMode.NONE;
        activeGoal = null;
        path = List.of();
        waypointIndex = 0;
        liveTargetId = null;
        multiGoalCells = null; // reachedGoalCell 保留：扫描器在腿结束后反查
        multiGoalBounds = null;
        multiGoalBoundsKey = Long.MIN_VALUE;
        if (wasActive && reason != null) {
            msg("§e[寻路] " + reason);
        }
    }

    /** 每客户端 tick（ClientPlayerTickManager.tick 顶部调用） */
    public void tick() {
        if (!active) {
            return;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || player.isDeadOrDying()) {
            GoExecutor.resetViewRestore(); // 玩家失效（切维度/死亡）不会再走恢复分支，清掉暂存
            active = false;
            calcSerial++;
            calculating = false;
            driveMode = DriveMode.NONE;
            activeGoal = null;
            path = List.of();
            multiGoalCells = null;
            reachedGoalCell = null;
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();

        // 跟随玩家目标：定期刷新目标位置（仅手动跟随模式）
        if (liveTargetId != null && now >= nextTargetCheckTick) {
            nextTargetCheckTick = now + TARGET_CHECK_INTERVAL_TICKS;
            AbstractClientPlayer target = findPlayer(liveTargetId);
            if (target == null) {
                stop("目标玩家已不在世界中");
                return;
            }
            BlockPos tpos = target.blockPosition();
            BlockPos g = goal;
            if (!tpos.equals(g)) {
                goal = tpos;
                activeGoal = goalFor(tpos, true);
                double dx = tpos.getX() + 0.5 - player.getX();
                double dz = tpos.getZ() + 0.5 - player.getZ();
                if (!calculating && dx * dx + dz * dz > TARGET_REROUTE_DISTANCE_SQ) {
                    requestPath(navEntity() != null ? navEntity().blockPosition() : player.blockPosition());
                }
            }
        }

        // 导航主体：乐魂飞行时为恶魂（Goal 的悬停位按恶魂基准格定义），走路时为玩家
        Entity nav = navEntity();
        if (nav == null) {
            return;
        }

        // 到达判定（MANUAL=站在目标方块，AUTO=走到目标附近）
        GoPathfinder.Goal ag = activeGoal;
        boolean arrived = ag != null && ag.isInGoal(nav.getBlockX(), nav.getBlockY(), nav.getBlockZ());
        // 乐魂飞行：A* 的终点就是悬停位，而实际停点受控制律松手阈值影响会有误差，
        // 且"距悬停格 ≤N 格"的格坐标可能落在悬停圈之外（多目标是精确格集合，单目标还有眼位
        // 复查）→ 控制律一旦进入"到位"死区（不再写任何输入、位置不会再变）就同样算到达，
        // 否则会形成"控制律已无事可做、寻路却还在等"的僵局：原地悬停到节点超时
        //（实测「下降到达目标时停住不动」）。只对"真到过目标"的路径生效（见 pathReachedGoal）
        if (!arrived && isGhastFlying() && pathReachedGoal && !path.isEmpty()) {
            arrived = GhastFlyer.isSettledAt(nav, path.get(path.size() - 1));
        }
        if (arrived) {
            if (driveMode == DriveMode.AUTO) {
                if (multiGoalCells != null) {
                    // 多目标腿：记下到达的悬停格供扫描器反查候选。必须用 A* 终点（它在悬停集合内），
                    // 不能用 nav 当前位置——"贴近终点也算到达"时后者可能不在集合里，反查会落空。
                    reachedGoalCell = path.isEmpty()
                            ? nav.blockPosition().immutable()
                            : path.get(path.size() - 1);
                }
                stopInternal(); // 自动模式：到达即释放控制，等待逻辑由扫描器负责
            } else {
                stop("已到达目标附近");
            }
            return;
        }

        // 路点推进
        advanceWaypoints(nav);

        // 节点超时（乐魂飞行）：卡在某个节点超过限时 → 弃线重规划
        if (isGhastFlying()) {
            tickWaypointTimeout(nav, now);
        }

        // 偏离检测：每 tick 采样，到剩余路径（含上一路点，见下）的最小距离超过阈值
        // 立即停止任务（即使玩家还在试图跳/被拉回路径）。采样分态：着地/入水比三维
        // 距离；外力腾空只比水平距离；自主跳跃空中放行（见常量注释）。重算路径期间
        // 跳过（旧路径已过期，新路径将从当前位置出发）。
        // 扫描从 waypointIndex-1 开始：同层缺口起跳前当前路点已在缺口对岸（3~4 格外），
        // 起跳脚下的格子（上一路点）才是玩家真正的参照
        if (nextDeviationCheckTick < 0L) {
            nextDeviationCheckTick = now + DEVIATION_CHECK_INTERVAL_TICKS;
        } else if (now >= nextDeviationCheckTick && !calculating) {
            nextDeviationCheckTick = now + DEVIATION_CHECK_INTERVAL_TICKS;
            // 乐魂飞行跳过偏离判停：走路版按"剩余路径的 3D 折线"比对，对飞行的惯性过冲
            // 与垂直段严重失真，会把正常飞行直接判成"已远离路径"而停止
            if (!isGhastFlying() && Configs.Go.GO_DEVIATION_STOP.getBooleanValue()) {
                int maxDist = Configs.Go.GO_DEVIATION_DISTANCE.getIntegerValue();
                double maxDistSq = sq(maxDist);
                boolean grounded = player.onGround() || player.isInWater();
                boolean externalAir = !grounded && !GoExecutor.isSelfJumpAirborne();
                if (grounded || externalAir) {
                    double minSq = Double.MAX_VALUE;
                    List<BlockPos> p = path;
                    for (int i = Math.max(waypointIndex - 1, 0); i < p.size(); i++) {
                        BlockPos wp = p.get(i);
                        double dx = wp.getX() + 0.5 - player.getX();
                        double dz = wp.getZ() + 0.5 - player.getZ();
                        double dsq = dx * dx + dz * dz;
                        if (!externalAir) {
                            double dy = wp.getY() + 0.5 - player.getY();
                            dsq += dy * dy;
                        }
                        if (dsq < minSq) {
                            minSq = dsq;
                            if (minSq <= maxDistSq) {
                                break; // 已找到近路点，无需继续扫
                            }
                        }
                    }
                    if (minSq > maxDistSq) {
                        stop("已远离路径超过 " + maxDist + " 格，已停止寻路");
                        return;
                    }
                }
            }
        }

        // 路径耗尽仍未到目标：从当前位置重算（onPathResult 的"距离不改进即终止"防死循环）
        if (waypointIndex >= path.size()) {
            if (!calculating) {
                if (repaths >= MAX_REPATHS) {
                    stop(driveMode == DriveMode.AUTO ? null : "多次重算仍未到达，已停止");
                    return;
                }
                requestPath(nav.blockPosition());
            }
            return;
        }

        // 卡住检测：每 20 tick 检查水平位移，几乎未动则重算。
        // 打印开容器换料/补货（容器屏幕或 isOpenHandler 流程）期间寻路主动停手属正常静止，
        // 冻结检测（只刷新基准点，不累计、不重算），避免把打印的正常流程误判为卡住；
        // 聊天等普通界面下寻路照常驱动，不冻结
        boolean detectionPaused = GoExecutor.isContainerUiOpen(player)
                || me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
        if (detectionPaused) {
            stuckRefX = nav.getX();
            stuckRefY = nav.getY();
            stuckRefZ = nav.getZ();
            nextStuckCheckTick = now + STUCK_CHECK_INTERVAL_TICKS;
        } else if (nextStuckCheckTick < 0L) {
            stuckRefX = nav.getX();
            stuckRefY = nav.getY();
            stuckRefZ = nav.getZ();
            nextStuckCheckTick = now + STUCK_CHECK_INTERVAL_TICKS;
        } else if (now >= nextStuckCheckTick) {
            double movedSq = sq(nav.getX() - stuckRefX) + sq(nav.getZ() - stuckRefZ);
            if (isGhastFlying()) {
                // 飞行是三维移动：贴着墙往上飞脱困时水平位移几乎为 0，只用水平位移会把
                // 上升脱困误判成"仍然卡住"，反复触发脱困直至放弃
                movedSq += sq(nav.getY() - stuckRefY);
            }
            if (movedSq > 2.0 * 2.0) {
                stuckRepaths = 0; // 正常前进，累积卡住计数清零
            }
            // 乐魂飞行悬空，onGround 恒为假；飞行时不以此门控（否则卡住检测永不触发）
            if (movedSq < STUCK_MIN_MOVE_SQ && (isGhastFlying() || player.onGround())
                    && !calculating && !GhastFlyer.isEscaping()) {
                stuckRepaths++;
                if (stuckRepaths >= MAX_STUCK_REPATHS) {
                    stop(driveMode == DriveMode.AUTO ? null : "反复卡住，已停止寻路");
                    return;
                }
                // 乐魂飞行：先朝"箱子放得下的方向"脱困，不急着重算——贴墙卡死时前进方向被
                // 挡住，起点没变的重算只会得出同一条贴墙路线，先脱离阻塞点才有意义；
                // 找不到可用方向（被完全围住）时才退回重算
                if (!isGhastFlying() || !GhastFlyer.tryEscape(player)) {
                    requestPath(nav.blockPosition());
                }
            }
            stuckRefX = nav.getX();
            stuckRefY = nav.getY();
            stuckRefZ = nav.getZ();
            nextStuckCheckTick = now + STUCK_CHECK_INTERVAL_TICKS;
        }
    }

    /** 水平距离小于阈值即推进到下一路点；上行路点须已到达该层、下方路点须降到位才推进。
     * 半径未命中但已沿路径方向越过的路点同样推进（见 {@link #passedWaypoint}）。
     * 乐魂飞行改用三维距离阈值：走路版要求水平 ≤0.45 且层高匹配，而飞行控制律在 1.6 格内
     * 就松手，沿用会导致路点永远推不动、在原地悬停。 */
    private void advanceWaypoints(Entity nav) {
        List<BlockPos> p = this.path;
        boolean flying = isGhastFlying();
        while (waypointIndex < p.size()) {
            BlockPos wp = p.get(waypointIndex);
            if (flying) {
                // 保留最后一个路点：A* 的终点本身就是悬停位。若把它也"推完"，getWaypoint() 会返回
                // null → 控制律写零输入 → 完全静止；而 isInGoal 又因实际停点落在悬停圈外仍为 false
                // → 路径耗尽↔重算死循环，且卡住检测被"路径耗尽"分支的 return 挡掉、永远不触发。
                if (waypointIndex >= p.size() - 1) {
                    break;
                }
                double dx = wp.getX() + 0.5 - nav.getX();
                double dy = wp.getY() + 0.5 - nav.getY();
                double dz = wp.getZ() + 0.5 - nav.getZ();
                if (dx * dx + dy * dy + dz * dz > GHAST_WAYPOINT_ARRIVE_SQ
                        && !ghastPassedWaypoint(nav, wp,
                        waypointIndex + 1 < p.size() ? p.get(waypointIndex + 1) : null)) {
                    break;
                }
                waypointIndex++;
                continue;
            }
            double distSq = sq(wp.getX() + 0.5 - nav.getX()) + sq(wp.getZ() + 0.5 - nav.getZ());
            if (distSq > 0.45 * 0.45 && !passedWaypoint(nav, wp,
                    waypointIndex + 1 < p.size() ? p.get(waypointIndex + 1) : null)) {
                break;
            }
            if (nav.getY() < wp.getY() - 0.2) {
                break; // 还没爬上该路点（跳上型/攀爬列上行）
            }
            if (nav.getY() > wp.getY() + 1.2) {
                break; // 路点在身体高度之下（下爬梯子/藤蔓段）：未降到位不得跳过整列
            }
            waypointIndex++;
        }
    }

    /**
     * 飞行版"越过即推进"（三维）：已沿路径方向越过该路点（投影 &gt; 0）、且到"路点沿路径方向的
     * 直线"的横向垂距仍在走廊内时，认定已越过。作为"追点振荡/绕圈导致够不到路点"的兜底，
     * 避免卡死在某个路点上无限绕。
     */
    private boolean ghastPassedWaypoint(Entity nav, BlockPos wp, @Nullable BlockPos next) {
        if (next == null) {
            return false; // 最后一个路点：只能靠"接近判定"收尾
        }
        double dirX = next.getX() - wp.getX();
        double dirY = next.getY() - wp.getY();
        double dirZ = next.getZ() - wp.getZ();
        double dirLen = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (dirLen < 1.0E-3) {
            return false; // 重合节点：无法定义"越过"
        }
        double relX = nav.getX() - (wp.getX() + 0.5);
        double relY = nav.getY() - wp.getY();
        double relZ = nav.getZ() - (wp.getZ() + 0.5);
        if (relX * dirX + relY * dirY + relZ * dirZ <= 0.0) {
            return false; // 还没到该路点
        }
        // 横向垂距 = |rel × dir| / |dir|
        double crossX = relY * dirZ - relZ * dirY;
        double crossY = relZ * dirX - relX * dirZ;
        double crossZ = relX * dirY - relY * dirX;
        double perp = Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ) / dirLen;
        return perp <= GHAST_CORRIDOR_RADIUS;
    }

    /**
     * 路点是否已被沿路径方向越过（半径未命中但已过去）：平地连跳/疾跑跳会飞越
     * 路点，横向偏移可能让最近点超出 0.45 半径导致漏推进，当前路点滞留身后会让
     * 起跳/行走方向指向身后。正交与对角（斜向）路段均支持：玩家已越过该路点
     * （沿路径方向投影 > 0）且横向偏移仍在路径走廊内（垂距 ≤0.9 格）时认定越过；
     * 其余情况维持原判定，交给偏离检测兜底。
     */
    private boolean passedWaypoint(Entity nav, BlockPos wp, @Nullable BlockPos next) {
        if (next == null || next.getY() != wp.getY()) {
            return false;
        }
        int dirX = Integer.compare(next.getX(), wp.getX());
        int dirZ = Integer.compare(next.getZ(), wp.getZ());
        if (dirX == 0 && dirZ == 0) {
            return false; // 重合节点：无法定义"越过"
        }
        double relX = nav.getX() - (wp.getX() + 0.5);
        double relZ = nav.getZ() - (wp.getZ() + 0.5);
        if (relX * dirX + relZ * dirZ <= 0.0) {
            return false; // 还没到该路点
        }
        // 横向偏移在走廊内（对角方向的叉积含 √2 长度因子，按方向长度归一成垂距）
        double dirLen = Math.sqrt((double) dirX * dirX + (double) dirZ * dirZ);
        return Math.abs(relX * dirZ - relZ * dirX) / dirLen <= 0.9;
    }

    /**
     * 节点超时（乐魂飞行）：从当前节点 A 前往下一节点 B，超过「A→B 距离 × 『节点超时』倍率」
     * 仍未推进到 B（含最后一个节点一直进不了"贴近即到达"圈）→ 放弃当前路线：
     * 自动腿静默结束换目标重派，手动腿从当前位置重算（与脱困成功后的弃线同路径）。
     *
     * <p>重算在飞（calculating，本就要换路线）与脱困进行中
     *（{@link GhastFlyer#isEscaping}，乐魂正被有意开离路径）不计时；路点推进或换路径即重锚。
     * A→B 距离按"锚定时刻导航主体位置 → 当前路点"算（锚定时刚离开上一节点，偏差 ≤ 推进半径）。
     */
    private void tickWaypointTimeout(Entity nav, long now) {
        List<BlockPos> p = path;
        if (p.isEmpty() || waypointIndex >= p.size() || calculating || GhastFlyer.isEscaping()) {
            waypointKey = Long.MIN_VALUE;
            return;
        }
        BlockPos wp = p.get(waypointIndex);
        long key = wp.asLong() * 31L + waypointIndex;
        if (key != waypointKey) {
            // 换了当前节点（推进/新路径）：重新锚定计时基准
            waypointKey = key;
            waypointStartTick = now;
            waypointAnchorX = nav.getX();
            waypointAnchorY = nav.getY();
            waypointAnchorZ = nav.getZ();
            return;
        }
        double mult = Configs.Go.GO_GHAST_WAYPOINT_TIMEOUT.getDoubleValue();
        if (mult <= 0.0D) {
            return; // 0 = 不限制
        }
        double dx = wp.getX() + 0.5 - waypointAnchorX;
        double dy = wp.getY() + 0.5 - waypointAnchorY;
        double dz = wp.getZ() + 0.5 - waypointAnchorZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        long limitTicks = (long) Math.ceil(Math.max(dist, 1.0D) * mult);
        if (now - waypointStartTick > limitTicks) {
            abandonRoute();
        }
    }

    private void begin(@Nullable BlockPos target, @Nullable UUID liveId, DriveMode mode, GoPathfinder.Goal goalEvaluator, @Nullable String startMessage) {
        active = true;
        calcSerial++;
        calculating = false;
        driveMode = mode;
        activeGoal = goalEvaluator;
        goal = target;
        liveTargetId = liveId;
        path = List.of();
        reachedGoalCell = null; // 新会话清掉上一条腿的到达记录
        waypointIndex = 0;
        pathReachedGoal = false;
        waypointKey = Long.MIN_VALUE; // 节点超时计时作废（新路径由 onPathResult 重锚）
        bestDistToGoal = Float.MAX_VALUE;
        repaths = 0;
        stuckRepaths = 0;
        nextStuckCheckTick = -1L;
        nextTargetCheckTick = -1L;
        nextDeviationCheckTick = -1L;
        GoExecutor.resetViewRestore(); // 新会话不清掉上次跳跃残留的视角暂存会莫名回摆
        if (startMessage != null) {
            msg(startMessage);
        }
        if (mc.player != null) {
            requestPath(navEntity() != null ? navEntity().blockPosition() : mc.player.blockPosition());
        }
    }

    /** 静默停止（无聊天提示）：自动模式到达时使用 */
    private void stopInternal() {
        stop(null);
    }

    /**
     * 脱困成功回调（{@link GhastFlyer} 在主线程调用）：抛弃原路线。
     * 脱困成功意味着箱体已挪位 ≥1 格，原路径的剩余路点全是按脱困前的起点算的，继续沿用
     * 会朝"身后"的旧路点飞；且脱困本身常由"路径被现实几何/原理图排布卡死"触发，
     * 原路线已不可信。
     */
    public void onEscapeSucceeded() {
        if (!active) {
            return; // 待命期脱困（无任务腿）：没有路线可抛，扫描器自行调度
        }
        abandonRoute();
    }

    /** 弃线重规划：自动腿静默结束（扫描器重扫选新目标、派新腿），手动腿从当前位置重算到原目标 */
    private void abandonRoute() {
        if (driveMode == DriveMode.AUTO) {
            stopInternal();
            return;
        }
        // 手动腿：作废在飞的旧计算（起点是弃线前的位置，结果已过期），从当前位置重算；
        // 旧路点立即清空——重算期间不再按旧路线推进（控制律收不到路点会自然悬停）
        calcSerial++;
        calculating = false;
        bestDistToGoal = Float.MAX_VALUE;
        repaths = 0;
        path = List.of();
        waypointIndex = 0;
        pathReachedGoal = false;
        Entity nav = navEntity();
        if (nav != null) {
            requestPath(nav.blockPosition());
        }
    }

    private void requestPath(BlockPos from) {
        if (calculating || mc.level == null) {
            return;
        }
        calculating = true;
        long serial = ++calcSerial;
        ClientLevel level = mc.level;
        GoPathfinder.Goal goalEvaluator = activeGoal;
        if (goalEvaluator == null) {
            calculating = false;
            return;
        }
        long budgetMs = Configs.Go.GO_TIME_LIMIT.getIntegerValue();
        int maxFall = Configs.Go.GO_MAX_FALL.getIntegerValue();
        int costLimitFactor = Configs.Go.GO_COST_LIMIT_FACTOR.getIntegerValue();
        // 乐魂寻路：骑乘可操控乐魂时改用三维飞行寻路。并集箱规格与「原理图非空气」障碍
        // 快照必须在主线程取好（后者会清缓存/改 revision），后台线程只做只读判定。
        GhastPathfinder.BoxSpec boxSpec = null;
        LongOpenHashSet obstacles = null;
        if (Configs.Go.GHAST_PATHFIND.getBooleanValue() && GhastRideState.canFly(mc.player)) {
            boxSpec = currentBoxSpec();
            if (boxSpec != null) {
                obstacles = ghastObstacles(from);
            }
        }
        final GhastPathfinder.BoxSpec flyBox = boxSpec;
        final LongOpenHashSet flyObstacles = obstacles;
        CALC_EXECUTOR.execute(() -> {
            GoPathfinder.Result calcResult = null;
            try {
                if (flyBox != null) {
                    calcResult = GhastPathfinder.findPath(level, from, goalEvaluator, flyBox, flyObstacles,
                            budgetMs, costLimitFactor, () -> serial != calcSerial);
                } else {
                    calcResult = GoPathfinder.findPath(level, from, goalEvaluator, budgetMs, maxFall,
                            costLimitFactor, () -> serial != calcSerial);
                }
            } catch (Throwable ignored) {
            }
            final GoPathfinder.Result result = calcResult;
            Minecraft.getInstance().execute(() -> onPathResult(serial, result));
        });
    }

    /** 是否处于"乐魂飞行"模式（开关开 + 骑乘可操控乐魂） */
    private boolean isGhastFlying() {
        return Configs.Go.GHAST_PATHFIND.getBooleanValue() && GhastRideState.canFly(mc.player);
    }

    /**
     * 导航主体：乐魂飞行时是<b>恶魂</b>（A* 起点、路点、到达判定、卡住检测一律以它为准），
     * 否则是玩家。
     *
     * <p>绝不能混用：玩家位置比恶魂高约 3.4 格、且水平还偏约 1.7 格（随朝向旋转），
     * 混用会让碰撞校验整体抬升——恶魂本体那段高度失去碰撞判定（撞地形），
     * 同时多判上方空间（拒走真实可通行区域）。
     */
    @Nullable
    private Entity navEntity() {
        LocalPlayer p = mc.player;
        if (p == null) {
            return null;
        }
        if (Configs.Go.GHAST_PATHFIND.getBooleanValue()) {
            var ghast = GhastRideState.riddenGhast(p);
            if (ghast != null) {
                return ghast;
            }
        }
        return p;
    }

    /**
     * 供渲染等外部使用：当前导航主体的位置（乐魂飞行时为恶魂，否则为玩家）。
     *
     * <p>渲染连线必须以它为准：若起点用玩家位置（骑乘时在恶魂上方约 3.4 格），
     * 而终点用路点格（飞行模式下代表恶魂基准格），两端基准不同，画出来就是
     * 斜跨数格高度的"纠正线"。
     */
    @Nullable
    public Vec3 navPosition() {
        Entity nav = navEntity();
        return nav == null ? null : nav.position();
    }

    /** 当前并集碰撞箱规格（乐魂 + 骑乘者实时 AABB）；不可用时返回 null。包内共享给候选预检 */
    @Nullable
    GhastPathfinder.BoxSpec currentBoxSpec() {
        LocalPlayer p = mc.player;
        if (p == null) {
            return null;
        }
        var ghast = GhastRideState.riddenGhast(p);
        return ghast == null ? null : GhastPathfinder.BoxSpec.of(ghast, p);
    }

    /**
     * 「原理图预测非空气」障碍快照（主线程构建）：飞行寻路的第二重约束。
     * 范围：单目标 = 起点→目标的包围盒外扩 {@link #GHAST_OBSTACLE_PAD} 格；
     * <b>多目标 = 起点 ∪ 最近 {@link #GHAST_OBSTACLE_MULTI_CANDIDATES} 个候选的包围盒</b>
     * （候选在起点 30+ 格外时，旧实现"起点周围 {@link #GHAST_OBSTACLE_RANGE} 格"覆盖不到，
     * 那段路程没有约束、路径会直接穿过原理图的非空气方块）；两者都没有时退回起点周围若干格。
     * 按 16³ 子区块推进，并先用 {@code intersectsSchematic} 过滤——与原理图不相交的子区块整块跳过。
     * 结果按「修订号 + 起止子区块 + 候选集合指纹」缓存，避免每次派发重复构建。
     */
    private LongOpenHashSet ghastObstacles(BlockPos from) {
        BlockPos g = goal;
        int[] bounds = multiGoalBounds;
        long fromKey = BlockPos.asLong(from.getX() >> 4, from.getY() >> 4, from.getZ() >> 4);
        long toKey;
        if (g != null) {
            toKey = BlockPos.asLong(g.getX() >> 4, g.getY() >> 4, g.getZ() >> 4);
        } else if (bounds != null) {
            toKey = multiGoalBoundsKey;
        } else {
            toKey = Long.MIN_VALUE;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        long rev = SchematicStateCache.INSTANCE.getRevision();
        boolean sameTarget = ghastObstacleCache != null
                && fromKey == ghastObstacleFrom && toKey == ghastObstacleTo;
        if (sameTarget) {
            // 修订号在打印期几乎每刻递增（每次方块变化 +1），必须节流：
            // 同一目标在最短重建间隔内一律复用旧快照（原理图本身变化很慢，陈旧几秒无碍）
            if (rev == ghastObstacleRevision
                    || now - ghastObstacleBuiltTick < GHAST_OBSTACLE_MIN_REBUILD_TICKS) {
                return ghastObstacleCache;
            }
        }
        int minX = from.getX();
        int maxX = from.getX();
        int minY = from.getY();
        int maxY = from.getY();
        int minZ = from.getZ();
        int maxZ = from.getZ();
        if (g != null) {
            minX = Math.min(minX, g.getX());
            maxX = Math.max(maxX, g.getX());
            minY = Math.min(minY, g.getY());
            maxY = Math.max(maxY, g.getY());
            minZ = Math.min(minZ, g.getZ());
            maxZ = Math.max(maxZ, g.getZ());
        } else if (bounds != null) {
            // 多目标：把"最近一批候选的包围盒"并进范围（起点已含在内）
            minX = Math.min(minX, bounds[0]);
            minY = Math.min(minY, bounds[1]);
            minZ = Math.min(minZ, bounds[2]);
            maxX = Math.max(maxX, bounds[3]);
            maxY = Math.max(maxY, bounds[4]);
            maxZ = Math.max(maxZ, bounds[5]);
        } else {
            minX -= GHAST_OBSTACLE_RANGE;
            maxX += GHAST_OBSTACLE_RANGE;
            minY -= GHAST_OBSTACLE_RANGE;
            maxY += GHAST_OBSTACLE_RANGE;
            minZ -= GHAST_OBSTACLE_RANGE;
            maxZ += GHAST_OBSTACLE_RANGE;
        }
        int minChunkX = (minX - GHAST_OBSTACLE_PAD) >> 4;
        int maxChunkX = (maxX + GHAST_OBSTACLE_PAD) >> 4;
        int minChunkY = (minY - GHAST_OBSTACLE_PAD) >> 4;
        int maxChunkY = (maxY + GHAST_OBSTACLE_PAD) >> 4;
        int minChunkZ = (minZ - GHAST_OBSTACLE_PAD) >> 4;
        int maxChunkZ = (maxZ + GHAST_OBSTACLE_PAD) >> 4;
        LongOpenHashSet out = new LongOpenHashSet();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos secMin = new BlockPos.MutableBlockPos();
        for (int sx = minChunkX; sx <= maxChunkX; sx++) {
            for (int sz = minChunkZ; sz <= maxChunkZ; sz++) {
                for (int sy = minChunkY; sy <= maxChunkY; sy++) {
                    secMin.set(sx << 4, sy << 4, sz << 4);
                    if (!SchematicStateCache.INSTANCE.intersectsSchematic(
                            secMin, secMin.offset(15, 15, 15))) {
                        continue; // 与原理图不相交：其内必为图外，整块跳过
                    }
                    for (int x = sx << 4; x < (sx << 4) + 16; x++) {
                        for (int y = sy << 4; y < (sy << 4) + 16; y++) {
                            for (int z = sz << 4; z < (sz << 4) + 16; z++) {
                                var state = SchematicStateCache.INSTANCE.getSchematicState(m.set(x, y, z));
                                if (state != null && !state.isAir()) {
                                    out.add(BlockPos.asLong(x, y, z));
                                }
                            }
                        }
                    }
                }
            }
        }
        ghastObstacleCache = out;
        ghastObstacleRevision = rev;
        ghastObstacleFrom = fromKey;
        ghastObstacleTo = toKey;
        ghastObstacleBuiltTick = now;
        return out;
    }

    /** 供扫描器构造飞行目标集合：当前并集箱推得的悬停半径（无乐魂/未骑乘时给默认 3） */
    public int ghastHoverRadius() {
        GhastPathfinder.BoxSpec spec = currentBoxSpec();
        return spec != null ? GhastGoal.radius(spec.halfWidth()) : 3;
    }

    private void onPathResult(long serial, @Nullable GoPathfinder.Result result) {
        calculating = false;
        if (!active || serial != calcSerial) {
            return;
        }
        if (result == null) {
            stop(driveMode == DriveMode.AUTO ? null : "未找到可行路径");
            return;
        }
        if (result.reachedGoal) {
            bestDistToGoal = 0.0F;
            stuckRepaths = 0;
            path = result.positions;
            waypointIndex = 0;
            waypointKey = Long.MIN_VALUE; // 新路径：节点超时重新锚定
            pathReachedGoal = true;
            return;
        }
        // 部分路径：必须比上一次更接近目标，否则判定已到最近可达位置
        if (result.distanceToGoal >= bestDistToGoal - 0.5F) {
            stop(driveMode == DriveMode.AUTO ? null
                    : String.format("已走到离目标最近的位置（约 %.1f 格）", result.distanceToGoal));
            return;
        }
        bestDistToGoal = result.distanceToGoal;
        repaths++;
        path = result.positions;
        waypointIndex = 0;
        waypointKey = Long.MIN_VALUE; // 新路径：节点超时重新锚定
        // 距目标 <6.0 的"部分路径"＝节点已接近悬停包围盒、只是不在多目标的精确悬停格集合里
        // （眼位过滤/相邻候选映射所致）：视同到过目标，让"贴近终点也算到达"兜底生效——
        // 阈值取小了（实测 0.5、2.0 都出过）会在距 A* 终点一段距离处悬停到节点超时；
        // 误判到达也无碍：扫描器侧还有"玩家是否真在交互距离内"的二次校验兜底
        pathReachedGoal = result.distanceToGoal < 6.0F;
    }

    @Nullable
    private AbstractClientPlayer findPlayer(UUID id) {
        if (mc.level == null) {
            return null;
        }
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.getUUID().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 按名字查找其他玩家：精确（忽略大小写）优先，其次唯一前缀 */
    @Nullable
    public AbstractClientPlayer findPlayerByName(String name) {
        if (mc.level == null) {
            return null;
        }
        UUID selfId = mc.player != null ? mc.player.getUUID() : null;
        AbstractClientPlayer prefixMatch = null;
        String lower = name.toLowerCase();
        for (AbstractClientPlayer p : mc.level.players()) {
            if (p.getUUID().equals(selfId)) {
                continue;
            }
            String n = p.getName().getString();
            if (n.equalsIgnoreCase(name)) {
                return p;
            }
            if (n.toLowerCase().startsWith(lower)) {
                if (prefixMatch != null) {
                    return null; // 多个匹配
                }
                prefixMatch = p;
            }
        }
        return prefixMatch;
    }

    private static double sq(double d) {
        return d * d;
    }

    private void msg(String text) {
        LocalPlayer p = mc.player;
        if (p != null) {
            //#if MC >= 260100
            //$$ p.sendSystemMessage(Component.literal(text));
            //#else
            p.displayClientMessage(Component.literal(text), false);
            //#endif
        }
    }
}
