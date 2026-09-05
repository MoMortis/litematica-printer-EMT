package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

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

    private final Minecraft mc = Minecraft.getInstance();

    private volatile boolean active;
    private volatile boolean calculating;
    /** 请求序号：新请求/停止都会递增，使在途计算结果作废 */
    private volatile long calcSerial;
    private volatile BlockPos goal;
    private volatile UUID liveTargetId;
    private volatile List<BlockPos> path = List.of();

    // ===== 主线程专用状态 =====
    private int waypointIndex;
    private float bestDistToGoal = Float.MAX_VALUE;
    private int repaths;
    private int stuckRepaths;
    private long nextStuckCheckTick = -1L;
    private double stuckRefX;
    private double stuckRefZ;
    private long nextTargetCheckTick = -1L;

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
        begin(target, null, "§a[寻路] 目标: " + target.getX() + " " + target.getY() + " " + target.getZ());
    }

    /** /go <player>：跟随目标玩家（每秒刷新目标位置） */
    public void go(AbstractClientPlayer target) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        begin(target.blockPosition(), target.getUUID(), "§a[寻路] 跟随玩家: " + target.getName().getString());
    }

    public void stop(@Nullable String reason) {
        boolean wasActive = active;
        active = false;
        calcSerial++;
        calculating = false;
        path = List.of();
        waypointIndex = 0;
        liveTargetId = null;
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
            active = false;
            calcSerial++;
            calculating = false;
            path = List.of();
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();

        // 跟随玩家目标：定期刷新目标位置，偏移过大时重算
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
                double dx = tpos.getX() + 0.5 - player.getX();
                double dz = tpos.getZ() + 0.5 - player.getZ();
                if (!calculating && dx * dx + dz * dz > TARGET_REROUTE_DISTANCE_SQ) {
                    requestPath(player.blockPosition());
                }
            }
        }

        // 已在目标附近（同 XZ ±1 格）即成功
        BlockPos g = goal;
        if (g != null && GoPathfinder.blockGoal(g).isInGoal(player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
            stop("已到达目标附近");
            return;
        }

        // 路点推进
        advanceWaypoints(player);

        // 路径耗尽仍未到目标：从当前位置重算（onPathResult 的"距离不改进即终止"防死循环）
        if (waypointIndex >= path.size()) {
            if (!calculating) {
                if (repaths >= MAX_REPATHS) {
                    stop("多次重算仍未到达，已停止");
                    return;
                }
                requestPath(player.blockPosition());
            }
            return;
        }

        // 卡住检测：每 20 tick 检查水平位移，几乎未动则重算。
        // 打印开容器换料/补货（screen 或 isOpenHandler 流程）期间寻路主动停手属正常静止，
        // 冻结检测（只刷新基准点，不累计、不重算），避免把打印的正常流程误判为卡住
        boolean detectionPaused = mc.screen != null
                || me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler;
        if (detectionPaused) {
            stuckRefX = player.getX();
            stuckRefZ = player.getZ();
            nextStuckCheckTick = now + STUCK_CHECK_INTERVAL_TICKS;
        } else if (nextStuckCheckTick < 0L) {
            stuckRefX = player.getX();
            stuckRefZ = player.getZ();
            nextStuckCheckTick = now + STUCK_CHECK_INTERVAL_TICKS;
        } else if (now >= nextStuckCheckTick) {
            double movedSq = sq(player.getX() - stuckRefX) + sq(player.getZ() - stuckRefZ);
            if (movedSq > 2.0 * 2.0) {
                stuckRepaths = 0; // 正常前进，累积卡住计数清零
            }
            if (movedSq < STUCK_MIN_MOVE_SQ && player.onGround() && !calculating) {
                stuckRepaths++;
                if (stuckRepaths >= MAX_STUCK_REPATHS) {
                    stop("反复卡住，已停止寻路");
                    return;
                }
                requestPath(player.blockPosition());
            }
            stuckRefX = player.getX();
            stuckRefZ = player.getZ();
            nextStuckCheckTick = now + STUCK_CHECK_INTERVAL_TICKS;
        }
    }

    /** 水平距离小于阈值即推进到下一路点；跳上型路点须已站上才推进 */
    private void advanceWaypoints(LocalPlayer player) {
        List<BlockPos> p = this.path;
        while (waypointIndex < p.size()) {
            BlockPos wp = p.get(waypointIndex);
            double distSq = sq(wp.getX() + 0.5 - player.getX()) + sq(wp.getZ() + 0.5 - player.getZ());
            if (distSq > 0.45 * 0.45) {
                break;
            }
            if (player.getY() < wp.getY() - 0.2) {
                break; // 还没爬上该路点（跳上型）
            }
            waypointIndex++;
        }
    }

    private void begin(BlockPos target, @Nullable UUID liveId, String startMessage) {
        active = true;
        calcSerial++;
        calculating = false;
        goal = target;
        liveTargetId = liveId;
        path = List.of();
        waypointIndex = 0;
        bestDistToGoal = Float.MAX_VALUE;
        repaths = 0;
        stuckRepaths = 0;
        nextStuckCheckTick = -1L;
        nextTargetCheckTick = -1L;
        msg(startMessage);
        if (mc.player != null) {
            requestPath(mc.player.blockPosition());
        }
    }

    private void requestPath(BlockPos from) {
        if (calculating || mc.level == null) {
            return;
        }
        calculating = true;
        long serial = ++calcSerial;
        ClientLevel level = mc.level;
        BlockPos goalPos = goal;
        if (goalPos == null) {
            calculating = false;
            return;
        }
        long budgetMs = Configs.Special.GO_TIME_LIMIT.getIntegerValue();
        int maxFall = Configs.Special.GO_MAX_FALL.getIntegerValue();
        CALC_EXECUTOR.execute(() -> {
            GoPathfinder.Result calcResult = null;
            try {
                calcResult = GoPathfinder.findPath(level, from, GoPathfinder.blockGoal(goalPos), budgetMs, maxFall,
                        () -> serial != calcSerial);
            } catch (Throwable ignored) {
            }
            final GoPathfinder.Result result = calcResult;
            Minecraft.getInstance().execute(() -> onPathResult(serial, result));
        });
    }

    private void onPathResult(long serial, @Nullable GoPathfinder.Result result) {
        calculating = false;
        if (!active || serial != calcSerial) {
            return; // 已停止或被更新的请求取代
        }
        if (result == null) {
            stop("未找到可行路径");
            return;
        }
        if (result.reachedGoal) {
            bestDistToGoal = 0.0F;
            stuckRepaths = 0;
            path = result.positions;
            waypointIndex = 0;
            return;
        }
        // 部分路径：必须比上一次更接近目标，否则判定已到最近可达位置
        if (result.distanceToGoal >= bestDistToGoal - 0.5F) {
            stop(String.format("已走到离目标最近的位置（约 %.1f 格）", result.distanceToGoal));
            return;
        }
        bestDistToGoal = result.distanceToGoal;
        repaths++;
        path = result.positions;
        waypointIndex = 0;
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
