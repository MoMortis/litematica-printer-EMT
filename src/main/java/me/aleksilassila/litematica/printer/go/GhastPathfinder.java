package me.aleksilassila.litematica.printer.go;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

/**
 * 「乐魂寻路」的三维飞行 A*（纯飞行移动，不挖不放）。
 *
 * <p>与行走版 {@link GoPathfinder} 的区别：邻域为 26 向自由飞行；成本即几何距离
 * （恶魂速度固定，无速度差异）；合法性判定的主体是<b>「乐魂 + 骑乘者」的并集碰撞箱</b>，
 * 而非单格可站立性。
 *
 * <p><b>合法性 = 双重判定</b>（沿位移按 0.5 格采样，防"跨格穿薄墙"）：
 * <ol>
 * <li>真实碰撞：{@code level.noCollision(box)} 为真（未加载区块一律视为不可通行）；</li>
 * <li>原理图约束：箱子覆盖的格子不得命中"原理图预测非空气"的坐标集合
 *     （该集合由<b>主线程</b>构建后传入——{@code SchematicStateCache} 的查询会清缓存/改
 *     revision，后台线程不可直接调用）。图外坐标不在集合中，自然放行。</li>
 * </ol>
 *
 * <p><b>线程</b>：每次寻路新建实例，在后台计算线程运行；只读 {@code level} 与传入的只读集合，
 * 不触碰任何主线程状态。
 */
public final class GhastPathfinder {
    /** 移动成本：正交 1 格 = 1，面对角 = √2，体对角 = √3（成本即几何距离） */
    private static final float COST_DIAG2 = 1.41421356F;
    private static final float COST_DIAG3 = 1.7320508F;
    /** 展开节点上限（与时长预算双保险） */
    private static final int MAX_NODES = 120_000;
    /** 扫掠采样步长（格）：单条边最多跨 1 格，0.5 足以覆盖薄墙 */
    private static final double SWEEP_STEP = 0.5;
    /** 浮点比较容差 */
    private static final float EPS = 1.0E-4F;

    /** 26 邻域方向（不含零向量） */
    private static final int[][] DIRS26 = buildDirs26();

    private static int[][] buildDirs26() {
        ArrayList<int[]> list = new ArrayList<>(26);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        list.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        return list.toArray(new int[0][]);
    }

    /**
     * 并集碰撞箱规格：以「乐魂位置」（其位置的 x/z 为水平中心、y 为脚底）为基准的相对偏移。
     * 由实时 AABB 构造，因而自动跟随服务器对乐魂／玩家实体大小的修改。
     */
    public static final class BoxSpec {
        private final double offX;
        private final double offY;
        private final double offZ;
        private final double sizeX;
        private final double sizeY;
        private final double sizeZ;

        private BoxSpec(double offX, double offY, double offZ,
                        double sizeX, double sizeY, double sizeZ) {
            this.offX = offX;
            this.offY = offY;
            this.offZ = offZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
        }

        /** 由「乐魂 + 骑乘者」的实时碰撞箱并集构造（相对乐魂位置） */
        public static BoxSpec of(Entity ghast, Entity rider) {
            AABB g = ghast.getBoundingBox();
            AABB r = rider.getBoundingBox();
            double minX = Math.min(g.minX, r.minX);
            double minY = Math.min(g.minY, r.minY);
            double minZ = Math.min(g.minZ, r.minZ);
            double maxX = Math.max(g.maxX, r.maxX);
            double maxY = Math.max(g.maxY, r.maxY);
            double maxZ = Math.max(g.maxZ, r.maxZ);
            double px = ghast.getX();
            double py = ghast.getY();
            double pz = ghast.getZ();
            return new BoxSpec(minX - px, minY - py, minZ - pz,
                    maxX - minX, maxY - minY, maxZ - minZ);
        }

        /** 水平半宽（用于悬停判定半径等推导） */
        public double halfWidth() {
            return Math.max(sizeX, sizeZ) / 2.0;
        }

        /** 乐魂位于给定连续坐标（x/z 中心、y 脚底）时的并集箱 */
        public AABB at(double centerX, double feetY, double centerZ) {
            return new AABB(centerX + offX, feetY + offY, centerZ + offZ,
                    centerX + offX + sizeX, feetY + offY + sizeY, centerZ + offZ + sizeZ);
        }
    }

    private static final class Node {
        @Nullable
        final Node parent;
        final BlockPos pos;
        final float g;
        final float h;

        Node(@Nullable Node parent, BlockPos pos, float g, float h) {
            this.parent = parent;
            this.pos = pos;
            this.g = g;
            this.h = h;
        }

        float f() {
            return g + h;
        }
    }

    /** 已判定过的格位 → 箱子是否合法（memo）：26 邻域下同一格会被反复当作端点判定，
     *  缓存后碰撞求值与原理图遍历只算一次（判定只依赖固定的箱规格与障碍集合，故可缓存） */
    private final Long2ByteOpenHashMap cellMemo = new Long2ByteOpenHashMap();

    private final ClientLevel level;
    private final GoPathfinder.Goal goal;
    private final BoxSpec box;
    /** 原理图预测非空气的坐标集合（主线程构建的只读快照；可为空=不施加该约束） */
    @Nullable
    private final LongOpenHashSet schematicSolid;
    /** 已确定的最优 g（惰性删除用） */
    private final Long2FloatOpenHashMap bestG = new Long2FloatOpenHashMap();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));

    private GhastPathfinder(ClientLevel level, GoPathfinder.Goal goal, BoxSpec box,
                            @Nullable LongOpenHashSet schematicSolid) {
        this.level = level;
        this.goal = goal;
        this.box = box;
        this.schematicSolid = schematicSolid;
        this.bestG.defaultReturnValue(Float.POSITIVE_INFINITY);
    }

    /**
     * 从 start（恶魂基准格）出发寻路。后台线程调用。
     *
     * @param budgetMs  单次计算时长预算（毫秒）
     * @param cancelled 取消信号
     */
    @Nullable
    public static GoPathfinder.Result findPath(ClientLevel level, BlockPos start, GoPathfinder.Goal goal, BoxSpec box,
                                               @Nullable LongOpenHashSet schematicSolid, long budgetMs,
                                               BooleanSupplier cancelled) {
        return new GhastPathfinder(level, goal, box, schematicSolid)
                .search(start, Math.max(1L, budgetMs) * 1_000_000L, cancelled);
    }

    @Nullable
    private GoPathfinder.Result search(BlockPos start, long budgetNanos, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + budgetNanos;
        Node startNode = new Node(null, start, 0.0F,
                goal.heuristic(start.getX(), start.getY(), start.getZ()));
        bestG.put(start.asLong(), 0.0F);
        open.add(startNode);
        int expanded = 0;
        Node best = startNode;

        while (!open.isEmpty()) {
            Node cur = open.poll();
            float known = bestG.get(cur.pos.asLong());
            if (cur.g > known + EPS) {
                continue; // 过期条目（该位置已有更优 g）
            }
            if (goal.isInGoal(cur.pos.getX(), cur.pos.getY(), cur.pos.getZ())) {
                return buildPath(cur, true);
            }
            if (cur.h < best.h) {
                best = cur;
            }
            if ((++expanded & 63) == 0
                    && (cancelled.getAsBoolean() || System.nanoTime() >= deadline || expanded > MAX_NODES)) {
                break;
            }
            expand(cur);
        }
        if (best == startNode) {
            return null;
        }
        return buildPath(best, false);
    }

    private void expand(Node cur) {
        for (int[] d : DIRS26) {
            BlockPos next = cur.pos.offset(d[0], d[1], d[2]);
            if (!sweepFree(cur.pos, next)) {
                continue;
            }
            float tentative = cur.g + moveCost(d);
            long key = next.asLong();
            if (tentative >= bestG.get(key) - EPS) {
                continue;
            }
            bestG.put(key, tentative);
            open.add(new Node(cur, next, tentative,
                    goal.heuristic(next.getX(), next.getY(), next.getZ())));
        }
    }

    private static float moveCost(int[] d) {
        int manhattan = Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]);
        float base = manhattan == 1 ? 1.0F : (manhattan == 2 ? COST_DIAG2 : COST_DIAG3);
        // 上升成本加倍：空格上升的推力只有水平的一半（源码 up += 0.5 对比 forward = 1.0），
        // 不修正会让 A* 高估爬升效率、选出实际很慢的垂直路线
        return d[1] > 0 ? base * 2.0F : base;
    }

    /**
     * 单条边（相邻格，最多跨 1 格）的连续扫掠检测：箱子沿 from→to 的位移按 0.5 格采样，
     * 逐采样点做双重判定。端点合法不等于轨迹合法（原版移动会沿墙滑行），故必须扫掠。
     */
    private boolean sweepFree(BlockPos from, BlockPos to) {
        double x0 = from.getX() + 0.5;
        double y0 = from.getY();
        double z0 = from.getZ() + 0.5;
        double dx = to.getX() + 0.5 - x0;
        double dy = to.getY() - y0;
        double dz = to.getZ() + 0.5 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(dist / SWEEP_STEP));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            // 终点用带 memo 的格级判定；中间采样点按连续位置判定（防"跨格穿薄墙"）
            if (i == steps) {
                if (!cellFree(to)) {
                    return false;
                }
            } else if (!boxFree(box.at(x0 + dx * t, y0 + dy * t, z0 + dz * t))) {
                return false;
            }
        }
        return true;
    }

    /** 格级合法性（带 memo）：恶魂基准格在该格时的并集箱是否合法 */
    private boolean cellFree(BlockPos p) {
        long key = p.asLong();
        byte cached = cellMemo.get(key);
        if (cached != 0) {
            return cached > 0;
        }
        boolean ok = boxFree(box.at(p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
        cellMemo.put(key, (byte) (ok ? 1 : -1));
        return ok;
    }

    /** 双重判定：真实碰撞 + 原理图"非空气"约束 */
    private boolean boxFree(AABB b) {
        if (!chunksLoaded(b)) {
            return false; // 未加载区块读到的必是空气，绝不能当作可通行
        }
        if (!level.noCollision(b)) {
            return false;
        }
        if (schematicSolid == null || schematicSolid.isEmpty()) {
            return true;
        }
        int minX = Mth.floor(b.minX);
        int maxX = Mth.floor(b.maxX - 1.0E-7);
        int minY = Mth.floor(b.minY);
        int maxY = Mth.floor(b.maxY - 1.0E-7);
        int minZ = Mth.floor(b.minZ);
        int maxZ = Mth.floor(b.maxZ - 1.0E-7);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (schematicSolid.contains(BlockPos.asLong(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 箱子横跨的区块是否都已加载（只查四个水平角，足够覆盖轴对齐箱；max 侧去 epsilon 防多查一列） */
    private boolean chunksLoaded(AABB b) {
        double e = 1.0E-7;
        return loaded(b.minX, b.minZ) && loaded(b.maxX - e, b.minZ)
                && loaded(b.minX, b.maxZ - e) && loaded(b.maxX - e, b.maxZ - e);
    }

    private boolean loaded(double x, double z) {
        return level.hasChunk(Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    /** 结果复用行走版 {@link GoPathfinder.Result}（同包可构造），使 GoManager 的路径处理无需分支 */
    private GoPathfinder.Result buildPath(Node end, boolean reachedGoal) {
        ArrayList<BlockPos> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            out.add(n.pos);
        }
        Collections.reverse(out);
        return new GoPathfinder.Result(out, reachedGoal, end.h, reachedGoal ? end.pos : null);
    }
}