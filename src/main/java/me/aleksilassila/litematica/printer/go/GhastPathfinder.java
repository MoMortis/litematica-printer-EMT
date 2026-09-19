package me.aleksilassila.litematica.printer.go;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
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
 * <p>与行走版 {@link GoPathfinder} 的区别：邻域为 26 向自由飞行；成本以几何距离为基准
 * （恶魂速度固定，无速度差异），并按"离目标的水平距离"给垂直分量加价，使路线呈
 * <b>长距离平飞 + 末端集中升降</b>（见「末端集中升降权重」配置）；另对"紧贴方块"的格
 * 施加<b>贴墙惩罚</b>（另见下文配置），使路线主动与方块拉开距离、给飞行动量的侧偏留出
 * 容错空间；合法性判定的主体是
 * <b>「乐魂 + 骑乘者」的并集碰撞箱</b>，而非单格可站立性。
 *
 * <p><b>代价全部可配置</b>（{@code 配置 → 寻路 → 乐魂寻路 ...}）：正交/面对角/体对角单价、
 * 上升倍率、下降倍率、贴墙惩罚、末端集中升降权重、转向惩罚，均在新建实例时快照一次，搜索过程中不再读配置。
 *
 * <p><b>启发值权重</b>（{@code 配置 → 寻路 → 通用寻路参数}，与走路版共用）：1.0＝标准 A*；
 * &gt;1＝加权 A*，按 f ＝ g ＋ soft ＋ 权重×h 排序，同样预算内更快锁定可用目标（预算掐断时
 * 结果最多约该倍数，搜索跑完时仍是配置成本模型下的最短）。权重只改排序，成本上限与
 * 多目标收工比较一律用未加权的几何下界 {@code g + h}，剪枝也只剪"确实不可能更便宜"的格。
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
    /** 展开节点上限（与时长预算双保险） */
    private static final int MAX_NODES = 120_000;
    /** 扫掠采样步长（格）：单条边最多跨 1 格，0.5 足以覆盖薄墙 */
    private static final double SWEEP_STEP = 0.5;
    /**
     * 「末端集中升降」加价的距离封顶（格）：超过此距离一律按此值计，防远处加价失控。
     * （权重本身是配置项，见 {@code Configs.Go.GO_GHAST_VERT_LATE_WEIGHT}）
     */
    private static final float VERT_LATE_CAP = 16.0F;
    /**
     * 路径允许的<b>最小离墙余量</b>（三维切比雪夫格）：1＝紧贴方块（箱子与方块只隔 0.5 格间隙），
     * 2＝与方块隔开一整格。
     *
     * <p>这是<b>硬约束</b>：贴着脸飞时，起步的推力与惯性就会把箱子擦上墙（现实碰撞会吃掉推力、
     * 或者直接蹭进"原理图排了方块、现实还是空气"的位置），所以"贴邻格"一律不作为路点。
     * 在此之上，紧贴格还会被「贴墙惩罚」加价（配置项），继续鼓励走更开阔的通道。
     */
    private static final int MIN_CLEARANCE = 2;
    /** 离墙余量分档：1＝紧贴方块（箱体与方块间隙 &lt;1 格，会被「贴墙惩罚」加价） */
    private static final int CLEARANCE_HUG = 1;
    /** 离墙余量分档：2＝与方块隔开一整格以上（不再有额外加价） */
    private static final int CLEARANCE_CLEAR = 2;
    /** 浮点比较容差 */
    private static final float EPS = 1.0E-4F;
    /** 超时/取消检查间隔（纳秒）：按时间检查而不是"每 N 个节点"——单节点扩展昂贵时
     * （大箱体 + 原理图逐格判定），节点粒度的检查会让一次"时长预算"实际跑出数倍时长，
     * 后续重算只能在单线程队列里排队，观感就是"卡死不动" */
    private static final long CHECK_INTERVAL_NANOS = 1_000_000L;

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

        /** 规格指纹：偏移/尺寸任一变化（服务器改实体大小等）即让预检缓存失效 */
        long fingerprint() {
            return Double.doubleToLongBits(offX) * 31
                    ^ Double.doubleToLongBits(offY) * 131
                    ^ Double.doubleToLongBits(offZ) * 1009
                    ^ Double.doubleToLongBits(sizeX) * 65537
                    ^ Double.doubleToLongBits(sizeY) * 131071
                    ^ Double.doubleToLongBits(sizeZ) * 524287;
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
        /** 几何路程累积（正交 1 / 对角 √2 / 体对角 √3，上升加倍）：<b>成本上限的唯一判定口径</b>，
         *  不含任何软偏好加价，故 {@code g + h} 就是"这条走法至少还要飞多远" */
        final float g;
        /** 软偏好加价累积（离墙惩罚 + 末端集中升降 + 转向惩罚）：只决定"选哪条路"，不参与"能不能到"的判定 */
        final float soft;
        /** 未加权的启发值（几何路程下界，可采纳）：成本上限、多目标收工比较一律用它 */
        final float h;
        /** 加权启发值 = h × 启发值权重：只用于堆排序（f = g + soft + hw） */
        final float hw;

        Node(@Nullable Node parent, BlockPos pos, float g, float soft, float h, float hw) {
            this.parent = parent;
            this.pos = pos;
            this.g = g;
            this.soft = soft;
            this.h = h;
            this.hw = hw;
        }

        /** 选路用的总代价（路程 + 软偏好），保证原"贴方块少走、末端集中升降"的路线偏好不变 */
        float f() {
            return g + soft + hw;
        }

        /** 路程 + 软偏好（不含启发值）：用于「该位置是否已有更优走法」的比较 */
        float total() {
            return g + soft;
        }
    }

    /** 已判定过的格位 → 「合法性 + 离墙余量」（memo）：26 邻域下同一格会被反复当作端点判定，
     *  缓存后碰撞求值与原理图遍历只算一次（判定只依赖固定的箱规格与障碍集合，故可缓存） */
    private final Long2ByteOpenHashMap clearanceMemo = new Long2ByteOpenHashMap();

    private final ClientLevel level;
    private final GoPathfinder.Goal goal;
    private final BoxSpec box;
    /** 原理图预测非空气的坐标集合（主线程构建的只读快照；可为空=不施加该约束） */
    @Nullable
    private final LongOpenHashSet schematicSolid;
    /** 已确定的最优 g（惰性删除用） */
    private final Long2FloatOpenHashMap bestG = new Long2FloatOpenHashMap();
    private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
    /** 成本上限倍数（0/1＝不设上限；主线程快照，搜索中不读配置） */
    private final int costLimitFactor;
    /** 启发值权重（配置快照）：1.0＝标准 A*（可采纳），&gt;1＝加权 A*（更贪心） */
    private final float heuristicWeight;
    /** 本次搜索要求的最小离墙余量（格）：严格档 {@link #MIN_CLEARANCE}，放宽档 1（只禁重叠） */
    private final int minClearance;
    /** 正交 1 格的路程单价（配置快照，见 {@code Configs.Go.GO_GHAST_COST_ORTHO}） */
    private final float costOrtho;
    /** 面对角（两轴各跨 1 格）的路程单价（配置快照） */
    private final float costDiag2;
    /** 体对角（三轴各跨 1 格）的路程单价（配置快照） */
    private final float costDiag3;
    /** 含上升的步在路程单价上乘的倍率（配置快照；空格上升推力只有水平的一半，默认 2） */
    private final float ascendMult;
    /** 含下降的步在路程单价上乘的倍率（配置快照；下降要先飞到位再低头，默认 2，与上升同价） */
    private final float descendMult;
    /** 紧贴方块（切比雪夫 1 格）的格单步加价（配置快照，0＝不加价） */
    private final float wallPenalty;
    /** 末端集中升降的加价权重（配置快照，0＝不施加该机制） */
    private final float vertLateWeight;
    /** 转向惩罚：与前一步方向不同的步的单次加价（配置快照，0＝不惩罚） */
    private final float turnPenalty;

    private GhastPathfinder(ClientLevel level, GoPathfinder.Goal goal, BoxSpec box,
                            @Nullable LongOpenHashSet schematicSolid, int costLimitFactor, int minClearance) {
        this.level = level;
        this.goal = goal;
        this.box = box;
        this.schematicSolid = schematicSolid;
        this.costLimitFactor = costLimitFactor;
        this.heuristicWeight = (float) Configs.Go.GO_HEURISTIC_WEIGHT.getDoubleValue();
        this.minClearance = minClearance;
        this.costOrtho = (float) Configs.Go.GO_GHAST_COST_ORTHO.getDoubleValue();
        this.costDiag2 = (float) Configs.Go.GO_GHAST_COST_DIAG2.getDoubleValue();
        this.costDiag3 = (float) Configs.Go.GO_GHAST_COST_DIAG3.getDoubleValue();
        this.ascendMult = Configs.Go.GO_GHAST_ASCEND_MULT.getIntegerValue();
        this.descendMult = Configs.Go.GO_GHAST_DESCEND_MULT.getIntegerValue();
        this.wallPenalty = (float) Configs.Go.GO_GHAST_WALL_PENALTY.getDoubleValue();
        this.vertLateWeight = (float) Configs.Go.GO_GHAST_VERT_LATE_WEIGHT.getDoubleValue();
        this.turnPenalty = (float) Configs.Go.GO_GHAST_TURN_PENALTY.getDoubleValue();
        this.bestG.defaultReturnValue(Float.POSITIVE_INFINITY);
    }

    /**
     * 从 start（恶魂基准格）出发寻路。后台线程调用。
     *
     * <p><b>两档口径</b>：先按 {@link #MIN_CLEARANCE}（离方块至少隔 1 格）找；这样都无解时，
     * 再放宽到"只不许与方块重叠"重试一次。放宽这一档是为了不把路走死——起点周围若只剩贴邻格
     * （乐魂贴着墙停着就是这么来的），严格档会直接判无解、乐魂原地不动，比"贴着飞"更糟。
     *
     * @param budgetMs        单次计算时长预算（毫秒），放宽档复用同一预算
     * @param costLimitFactor 成本上限倍数（0/1＝不设上限），见 {@link GoPathfinder#costLimit}
     * @param cancelled       取消信号
     */
    @Nullable
    public static GoPathfinder.Result findPath(ClientLevel level, BlockPos start, GoPathfinder.Goal goal, BoxSpec box,
                                               @Nullable LongOpenHashSet schematicSolid, long budgetMs,
                                               int costLimitFactor, BooleanSupplier cancelled) {
        long budgetNanos = Math.max(1L, budgetMs) * 1_000_000L;
        GoPathfinder.Result strict = new GhastPathfinder(level, goal, box, schematicSolid,
                costLimitFactor, MIN_CLEARANCE).search(start, budgetNanos, cancelled);
        if (strict != null || cancelled.getAsBoolean()) {
            return strict;
        }
        return new GhastPathfinder(level, goal, box, schematicSolid,
                costLimitFactor, 1).search(start, budgetNanos, cancelled);
    }

    @Nullable
    private GoPathfinder.Result search(BlockPos start, long budgetNanos, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + budgetNanos;
        long nextCheck = System.nanoTime() + CHECK_INTERVAL_NANOS;
        float startH = goal.heuristic(start.getX(), start.getY(), start.getZ());
        Node startNode = new Node(null, start, 0.0F, 0.0F, startH, startH * heuristicWeight);
        bestG.put(start.asLong(), 0.0F);
        open.add(startNode);
        int expanded = 0;
        Node best = startNode;
        // 成本上限：「这条走法至少还要飞多远」＝几何路程 + 到目标的路程下界，超过
        // 「起点到最近目标的路程下界 × 倍数」即不可达（0/1＝不设上限）。
        // 判定刻意只用路程，不含离墙惩罚/末端加价等软偏好——旧版拿含惩罚的总代价去比，
        // 贴墙飞行每步最多多算 6 分，几格就把上限顶爆，走得通的目标被误判"无解"（乐魂原地不动）。
        float limit = GoPathfinder.costLimit(startNode.h, costLimitFactor);
        // 已定稿的最优目标（多目标＝候选竞争；单目标＝悬停区里最便宜的那一格）。
        // 弹出目标格不再立即收工，而是继续搜索"下界仍可能更便宜"的分支，直到堆里最小
        // 的 f 都不小于它的总代价为止——先算出来的必须真的比没算完的便宜，才认它
        Node bestGoal = null;
        // 权重 > 1 时堆按「g + soft + 权重×h」排序，弹出序不再等价于 g+h 序（下面的收工判定
        // 用的是可采纳下界 g+h），故它只能作用于"当前格"，不能当"后面只会更贵"用
        boolean admissibleOrder = heuristicWeight <= 1.0F;

        while (!open.isEmpty()) {
            Node cur = open.poll();
            float known = bestG.get(cur.pos.asLong());
            if (cur.total() > known + EPS) {
                continue; // 过期条目（该位置已有更优走法）
            }
            if (goal.isInGoal(cur.pos.getX(), cur.pos.getY(), cur.pos.getZ())) {
                // 只认严格更便宜的目标；成本并列时保持先到者
                if (bestGoal == null || cur.total() < bestGoal.total()) {
                    bestGoal = cur;
                }
                continue; // 目标格不再扩展：穿过它只会更贵，stop 判定同样会拦下
            }
            if (bestGoal != null && cur.g + cur.h >= bestGoal.total()) {
                // 该格及其所有后继的总代价下界已不低于已定稿目标 → 其余目标不可能更便宜，收工
                if (admissibleOrder) {
                    break;
                }
                continue; // 加权 A*：弹出序是加权 f，不能据此断言后面都不行，只剪本格
            }
            if (cur.g + cur.h > limit) {
                // 该格及其所有后继的路程都必然超上限 → 剪掉不扩展；已定稿的目标不受连坐，
                // 剪干净后由循环出口返回它（一个都没定稿时才是"判无解"）
                continue;
            }
            if (cur.h < best.h) {
                best = cur;
            }
            if (System.nanoTime() >= nextCheck) {
                nextCheck = System.nanoTime() + CHECK_INTERVAL_NANOS;
                if (cancelled.getAsBoolean() || System.nanoTime() >= deadline || expanded > MAX_NODES) {
                    break;
                }
            }
            expanded++;
            expand(cur);
        }
        if (bestGoal != null) {
            return buildPath(bestGoal, true);
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
            float geo = cur.g + geoCost(d);
            float soft = cur.soft + softCost(d, next) + turnCost(cur, d);
            float tentative = geo + soft;
            long key = next.asLong();
            if (tentative >= bestG.get(key) - EPS) {
                continue;
            }
            bestG.put(key, tentative);
            float h = goal.heuristic(next.getX(), next.getY(), next.getZ());
            open.add(new Node(cur, next, geo, soft, h, h * heuristicWeight));
        }
    }

    /**
     * 转向惩罚（配置快照）：与前一步方向不同的步单次加价，0＝不惩罚。
     *
     * <p>恶魂转向需要身体朝向平滑收敛（先转后飞，见 {@link GhastFlyer#TURN_ALIGN_DEGREES}），
     * 每次折向都有真实的减速与绕行弧线成本，惩罚使路线更趋直线、减少折返。
     * 与贴墙惩罚同属软偏好：不参与可达性判定，也不进成本上限（上限只看几何路程 g）。
     *
     * <p>已知近似：bestG 按每格单一最优值去重，而转向代价使"到该格的最优值"依赖来向
     * （方向相关边成本的状态增广未做）——惩罚值远小于路程单价时误差可忽略。
     */
    private float turnCost(Node cur, int[] d) {
        if (turnPenalty <= 0.0F || cur.parent == null) {
            return 0.0F;
        }
        BlockPos pp = cur.parent.pos;
        return d[0] != cur.pos.getX() - pp.getX()
                || d[1] != cur.pos.getY() - pp.getY()
                || d[2] != cur.pos.getZ() - pp.getZ() ? turnPenalty : 0.0F;
    }

    /** 几何路程代价（成本上限的判定口径）：正交 1 格、面对角、体对角三档单价均为配置项；
     *  上升与下降各乘一个倍率（默认都是 2）——空格上升的推力只有水平的一半
     *  （源码 up += 0.5 对比 forward = 1.0），而下降要先水平到位再低头（见 GhastFlyer.drive），
     *  两者都不比平飞划算，不加价会让 A* 高估升降效率、选出实际很慢的路线。 */
    private float geoCost(int[] d) {
        int manhattan = Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]);
        float base = manhattan == 1 ? costOrtho : (manhattan == 2 ? costDiag2 : costDiag3);
        if (d[1] > 0) {
            return base * ascendMult;
        }
        return d[1] < 0 ? base * descendMult : base;
    }

    /** 软偏好加价（只决定"两条都能到的路选哪条"，不参与可达性判定）：
     *  「贴墙惩罚」＋「末端集中升降」，两者都是配置项。 */
    private float softCost(int[] d, BlockPos next) {
        // 「贴墙惩罚」：只罚"紧贴方块"（切比雪夫 1 格）的格，隔开一整格以上不加价。飞行的启动
        // 与惯性会让实际轨迹偏离路点，贴脸的路线一飞就擦墙卡死；惩罚把这类路线淘汰掉
        float cost = cellClearance(next) == CLEARANCE_HUG ? wallPenalty : 0.0F;
        // 「末端集中升降」：垂直分量再按"该步离目标的水平距离"加价。平飞的 L 形与"先降后平飞"
        // 的 L 形总成本本是完全并列的（Δy 与水平距离都相同），只靠乘系数选不出末端；
        // 这里让加价随离目标的距离增长，"改高度最便宜的位置"就唯一地落在目标处。
        // 加价恒 ≥ 0，故启发值仍可采纳、且边成本只增不减仍一致。
        if (d[1] != 0) {
            float horizToGoal = Math.min(goal.horizDistanceTo(next.getX(), next.getZ()), VERT_LATE_CAP);
            cost += vertLateWeight * Math.abs(d[1]) * horizToGoal;
        }
        return cost;
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

    /** 格级合法性（带 memo）：恶魂基准格在该格时的并集箱"不重叠、且离方块至少 {@link #minClearance} 格"
     *（严格档＝不贴邻、也不贴原理图非空气方块；放宽档＝只不许重叠） */
    private boolean cellFree(BlockPos p) {
        return cellClearance(p) >= minClearance;
    }

    /**
     * 格级「合法性 + 离墙余量」（带 memo）：返回值 &gt;0 为合法，其值只有两档——
     * {@link #CLEARANCE_HUG}＝紧贴方块（1 格内），{@link #CLEARANCE_CLEAR}＝隔开一整格以上；
     * 0＝非法。
     */
    private int cellClearance(BlockPos p) {
        long key = p.asLong();
        byte cached = clearanceMemo.get(key);
        if (cached != 0) {
            return cached;
        }
        int level = clearance(box.at(p.getX() + 0.5, p.getY(), p.getZ() + 0.5));
        clearanceMemo.put(key, (byte) level);
        return level;
    }

    /**
     * 并集箱在该位置的合法性 + 离墙余量：0＝非法；{@link #CLEARANCE_HUG}＝合法但紧贴方块
     * （三维切比雪夫距离 1 格）；{@link #CLEARANCE_CLEAR}＝合法且与方块隔开一整格以上。
     *
     * <p>只探一层壳就够：硬约束 {@code minClearance} 最多要到 2，加价也只区分"贴／不贴"，
     * 更远的余量无人使用，故不再逐层外扩。
     *
     * <p>真实方块一侧用「箱体外扩 0.5 格」探测：外扩 0.5 已能把"紧贴"（间隙 0）吃进来，
     * 即外扩量恰好对应切比雪夫格距；原理图一侧只扫距离 1 的那一圈壳。
     */
    private int clearance(AABB b) {
        if (!boxFree(b)) {
            return 0;
        }
        if (!level.noCollision(b.inflate(0.5))) {
            return CLEARANCE_HUG;
        }
        if (schematicSolid != null && !schematicSolid.isEmpty()
                && schematicShellHasSolid(CLEARANCE_HUG, Mth.floor(b.minX), Mth.floor(b.maxX - 1.0E-7),
                Mth.floor(b.minY), Mth.floor(b.maxY - 1.0E-7),
                Mth.floor(b.minZ), Mth.floor(b.maxZ - 1.0E-7))) {
            return CLEARANCE_HUG;
        }
        return CLEARANCE_CLEAR;
    }

    /** 双重判定：真实碰撞 + 原理图"非空气"约束 */
    private boolean boxFree(AABB b) {
        if (!chunksLoaded(b)) {
            return false; // 未加载区块读到的必是空气，绝不能当作可通行
        }
        if (!level.noCollision(b)) {
            return false;
        }
        return !schematicBoxHasSolid(b);
    }

    /** 箱体覆盖的格子是否命中"原理图预测非空气"（余量判定的第 0 层＝整箱） */
    private boolean schematicBoxHasSolid(AABB b) {
        if (schematicSolid == null || schematicSolid.isEmpty()) {
            return false;
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
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 离墙余量判定的第 k 层壳扫描：只遍历"外扩 k 格"相对"外扩 k−1 格"新增的那一圈格子
     * （内层在更小的 k 已判过），避免整箱重复遍历。
     */
    private boolean schematicShellHasSolid(int k, int baseMinX, int baseMaxX,
                                           int baseMinY, int baseMaxY,
                                           int baseMinZ, int baseMaxZ) {
        int loX = baseMinX - k;
        int hiX = baseMaxX + k;
        int loY = baseMinY - k;
        int hiY = baseMaxY + k;
        int loZ = baseMinZ - k;
        int hiZ = baseMaxZ + k;
        for (int y = loY; y <= hiY; y++) {
            boolean yEdge = y == loY || y == hiY;
            for (int z = loZ; z <= hiZ; z++) {
                if (yEdge || z == loZ || z == hiZ) {
                    for (int x = loX; x <= hiX; x++) {
                        if (schematicSolid.contains(BlockPos.asLong(x, y, z))) {
                            return true;
                        }
                    }
                } else if (schematicSolid.contains(BlockPos.asLong(loX, y, z))
                        || (hiX != loX && schematicSolid.contains(BlockPos.asLong(hiX, y, z)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 箱子横跨的区块是否都已加载（只查四个水平角，足够覆盖轴对齐箱；max 侧去 epsilon 防多查一列） */
    private boolean chunksLoaded(AABB b) {
        double e = 1.0E-7;
        return loaded(b.minX, b.minZ) && loaded(b.maxX - e, b.minZ)
                && loaded(b.minX, b.maxZ - e) && loaded(b.maxX - e, b.maxZ - e);
    }

    private boolean loaded(double x, double z) {
        return BlockStateUtils.isColumnLoaded(level, Mth.floor(x) >> 4, Mth.floor(z) >> 4);
    }

    // ===== 候选预检（乐魂飞行；主线程调用） =====

    /** 悬停位预检的<b>竖直</b>容差（格）：上下各 1 层（比到达判定的 ±3 严，属"只删必死"剪枝） */
    private static final int HOVER_SPOT_VERTICAL = 1;
    /** 预检结果缓存上限（条）：超出整表清空 */
    private static final int HOVER_SPOT_CACHE_MAX = 4096;
    /** 预检结果缓存：候选坐标 → 周围是否存在可悬停位（主线程专用） */
    private static final Long2BooleanOpenHashMap HOVER_SPOT_CACHE = new Long2BooleanOpenHashMap();
    /** 预检逐格缓存上限（条）：超出整表清空。逐格结果与候选无关，可跨候选共享 */
    private static final int HOVER_CELL_CACHE_MAX = 65536;
    /** 预检逐格缓存：悬停位格 → 1 可 / 2 不可（跨候选共享） */
    private static final Long2ByteOpenHashMap HOVER_CELL_CACHE = new Long2ByteOpenHashMap();
    /** 两个预检缓存的失效基准：世界修订号（方块/原理图/维度）与箱规格指纹 */
    private static long hoverSpotCacheRevision = Long.MIN_VALUE;
    private static long hoverCellCacheRevision = Long.MIN_VALUE;
    private static long hoverCellCacheSpec = Long.MIN_VALUE;

    /**
     * 候选预检（乐魂飞行・主线程调用）：目标方块周围<b>水平切比雪夫 {@code R}</b>
     * （{@link GhastGoal#radius}，默认 3）、<b>竖直 ±{@link #HOVER_SPOT_VERTICAL}</b>
     * （不含目标格本身）内，是否存在一个<b>放得下「乐魂 + 骑乘者」并集碰撞箱、且不是
     * 原理图预测实心</b>的空气位。
     *
     * <p>这是"只删必死"的剪枝：任一格满足即保留（判定有效 ≠ 一定能飞到，绕行仍由 A*
     * 裁决）；判定无效 = 目标周围连一个停得下的位置都没有（候选必死，A* 只会白烧预算、
     * 表现为"框在、路线不出"）。判定口径与 {@link #boxFree} 一致（真实碰撞 + 原理图
     * 非空气），否则预检放行的位置 A* 仍会拒走，剪枝就白做了。
     */
    static boolean hasHoverSpot(ClientLevel level, BlockPos target, @Nullable BoxSpec spec) {
        if (spec == null) {
            return true; // 拿不到箱规格（未骑乘等）：无法判定，按"不剪枝"放行
        }
        long rev = SchematicStateCache.INSTANCE.getRevision();
        if (rev != hoverSpotCacheRevision) {
            hoverSpotCacheRevision = rev;
            HOVER_SPOT_CACHE.clear();
        }
        long key = target.asLong();
        if (HOVER_SPOT_CACHE.containsKey(key)) {
            return HOVER_SPOT_CACHE.get(key);
        }
        boolean ok = computeHoverSpot(level, target, spec);
        if (HOVER_SPOT_CACHE.size() >= HOVER_SPOT_CACHE_MAX) {
            HOVER_SPOT_CACHE.clear();
        }
        HOVER_SPOT_CACHE.put(key, ok);
        return ok;
    }

    /** 由内向外逐层扫（水平壳 r=1..R × 竖直 ±{@link #HOVER_SPOT_VERTICAL}），命中即返回 */
    private static boolean computeHoverSpot(ClientLevel level, BlockPos target, BoxSpec spec) {
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        int rMax = GhastGoal.radius(spec.halfWidth());
        for (int r = 1; r <= rMax; r++) {
            for (int dy = -HOVER_SPOT_VERTICAL; dy <= HOVER_SPOT_VERTICAL; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    boolean xEdge = dx == -r || dx == r;
                    for (int dz = -r; dz <= r; dz++) {
                        if (!xEdge && !(dz == -r || dz == r)) {
                            continue; // 只扫本层水平壳（内层已扫过）
                        }
                        if (hoverSpotAt(level, spec, tx + dx, ty + dy, tz + dz)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** 单格悬停位判定（带跨候选共享的逐格缓存）：已加载 + 并集箱无碰撞 + 箱体覆盖格非原理图实心 */
    private static boolean hoverSpotAt(ClientLevel level, BoxSpec spec, int x, int y, int z) {
        long rev = SchematicStateCache.INSTANCE.getRevision();
        long specPrint = spec.fingerprint();
        if (rev != hoverCellCacheRevision || specPrint != hoverCellCacheSpec) {
            hoverCellCacheRevision = rev;
            hoverCellCacheSpec = specPrint;
            HOVER_CELL_CACHE.clear();
        }
        long key = BlockPos.asLong(x, y, z);
        byte cached = HOVER_CELL_CACHE.get(key);
        if (cached != 0) {
            return cached > 0;
        }
        boolean ok = computeHoverSpotAt(level, spec, x, y, z);
        if (HOVER_CELL_CACHE.size() >= HOVER_CELL_CACHE_MAX) {
            HOVER_CELL_CACHE.clear();
        }
        HOVER_CELL_CACHE.put(key, (byte) (ok ? 1 : 2));
        return ok;
    }

    private static boolean computeHoverSpotAt(ClientLevel level, BoxSpec spec, int x, int y, int z) {
        if (!BlockStateUtils.isColumnLoaded(level, x >> 4, z >> 4)) {
            return false; // 未加载：读到的必是空气，不能当依据
        }
        AABB box = spec.at(x + 0.5, y, z + 0.5);
        if (!level.noCollision(box)) {
            return false;
        }
        // 原理图一侧：箱体覆盖格里不得有"预测非空气"（将来会放上来的方块会挡住箱体）
        return !boxHitsSchematic(box);
    }

    /**
     * 箱体是否压在"原理图预测非空气"的格子上（<b>主线程</b>调用）。
     *
     * <p>寻路的第二重判定（{@link #boxFree}）只管"规划出来的格位与边"；实际飞行会被惯性、
     * 脱困推力带偏，偏出去的落点没有任何约束，于是乐魂可能钻进"现实里还是空气、原理图却
     * 已经排好方块"的位置。这个方法给<b>脱困方向探测</b>与<b>实时纠偏</b>复用，口径与寻路一致。
     */
    public static boolean boxHitsSchematic(AABB box) {
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX - 1.0E-7);
        int minY = Mth.floor(box.minY);
        int maxY = Mth.floor(box.maxY - 1.0E-7);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ - 1.0E-7);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState st = SchematicStateCache.INSTANCE.getSchematicState(m.set(x, y, z));
                    if (st != null && !st.isAir()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 结果复用行走版 {@link GoPathfinder.Result}（同包可构造），使 GoManager 的路径处理无需分支。
     *  路点先做「只留拐点」抽稀（见 {@link #simplifyToTurns}），绿色连线因此不再每格一个节点。 */
    private GoPathfinder.Result buildPath(Node end, boolean reachedGoal) {
        ArrayList<BlockPos> out = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            out.add(n.pos);
        }
        Collections.reverse(out);
        simplifyToTurns(out);
        return new GoPathfinder.Result(out, reachedGoal, end.h, reachedGoal ? end.pos : null, end.total());
    }

    /**
     * 「只留拐点」抽稀（原地修改）：保留起点、方向发生变化的拐点、终点，直线段上的中间点全部删除。
     *
     * <p>为什么不改变实际路线：被删掉的点与相邻保留点在<b>同一条直线段</b>上（逐格方向完全相同），
     * 飞行控制律本来就是"朝当前路点直飞"，删掉它们后朝远端保留点直飞，轨迹仍是同一条直线；
     * 而渲染的绿色连线（每格心一个节点）会明显变疏。
     */
    private static void simplifyToTurns(ArrayList<BlockPos> path) {
        if (path.size() <= 2) {
            return;
        }
        ArrayList<BlockPos> kept = new ArrayList<>(path.size());
        kept.add(path.get(0));
        for (int i = 1; i < path.size() - 1; i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            BlockPos c = path.get(i + 1);
            boolean turn = Integer.compare(b.getX(), a.getX()) != Integer.compare(c.getX(), b.getX())
                    || Integer.compare(b.getY(), a.getY()) != Integer.compare(c.getY(), b.getY())
                    || Integer.compare(b.getZ(), a.getZ()) != Integer.compare(c.getZ(), b.getZ());
            if (turn) {
                kept.add(b);
            }
        }
        kept.add(path.get(path.size() - 1));
        path.clear();
        path.addAll(kept);
    }
}