package me.aleksilassila.litematica.printer.go;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 纯行走 A* 寻路（/go 自动寻路核心）。
 *
 * <p>只使用"移动 + 跳跃 + 攀爬"可完成的动作（平移 / 对角 / 跳上一格 / 走下悬崖 /
 * 疾跑跳过同层缺口 / 爬梯子藤蔓），绝不挖掘或放置方块。成本单位为 tick；
 * 单次计算受时间预算约束，超时返回 best-so-far（已探索节点中离目标最近的），
 * 实现"走到离目标最近的位置"语义。
 * 每次寻路新建实例，线程封闭（在后台计算线程上运行）。
 *
 * <p>可选的<b>启发值权重</b>（{@code 配置 → 寻路 → 通用寻路参数 → 自动寻路 - 启发值权重}）：
 * 1.0＝标准 A*，启发值可采纳、在配置成本模型下取到最短路径；&gt;1＝加权 A*，按
 * f ＝ g ＋ 权重×h 排序，搜索更贪心——同样预算内更快锁定可用目标（预算被掐断时结果最多约该倍数，
 * 搜索跑完时仍是配置成本模型下的最短）。权重只改<b>排序</b>：成本上限、多目标收工比较、
 * best-so-far 的挑选一律用未加权的 h，剪枝也只剪"确实不可能更便宜"的节点，
 * 不会把走得通的目标误判为无解。
 *
 * <p>性能措施（均不改变搜索结果）：
 * <ul>
 * <li>地形探测记忆化——passable/walkableFloor/water/climbable 按坐标缓存，
 *     同一格子被不同邻居重复探测的碰撞求值只算一次（缓存设上限防大图内存膨胀）；</li>
 * <li>启发值收紧——水平单价与「强制疾跑」对齐（关=步行 4.633，开=疾跑 3.564，
 *     注意疾跑下 4 格跑酷跳实际 3.0 tick/格，启发值略激进，属既有取舍），
 *     距离用 octile（直线+对角混合），上升分量取跳上边扣除水平份额后的下界；</li>
 * <li>跑酷探测剪枝——4 个正交同层邻格都有地板的节点不可能起跳越缺口
 *     （gap=1 检查必失败），直接跳过整个跑酷探测，平地节点省 ~60 次探测。</li>
 * </ul>
 */
public final class GoPathfinder {
    public static final float COST_INF = 1_000_000.0F;

    // ===== 行走寻路各移动方式的代价（tick）：逐条对应一行的移动方式，全部来自配置，见 Configs.Go =====

    /** 步行（平移）1 格：默认 20/4.317 ≈ 4.633 */
    static float walkCost() {
        return (float) Configs.Go.GO_WALK_COST.getDoubleValue();
    }

    /** 同层斜走（面对角）1 格：默认 4.633×√2 ≈ 6.552 */
    static float diagonalCost() {
        return (float) Configs.Go.GO_WALK_DIAGONAL_COST.getDoubleValue();
    }

    /** 疾跑 1 格：仅作启发值下界与路径时长换算，不参与任何边成本（默认 ≈ 3.564） */
    static float sprintCost() {
        return (float) Configs.Go.GO_SPRINT_COST.getDoubleValue();
    }

    /** 跳上一格（跳入攀爬列亦按此价）：默认 4.633＋5 ＝ 9.633 */
    static float jumpUpCost() {
        return (float) Configs.Go.GO_JUMP_UP_COST.getDoubleValue();
    }

    /** 涉水 1 格（对角再乘 √2）：默认 20/2.2 ≈ 9.091 */
    static float waterCost() {
        return (float) Configs.Go.GO_WATER_COST.getDoubleValue();
    }

    /** 爬梯子/藤蔓沿列 1 格：默认 20/2.35 ≈ 8.511 */
    static float ladderCost() {
        return (float) Configs.Go.GO_LADDER_COST.getDoubleValue();
    }

    /** 从梯顶翻出（跳+侧移）：默认 8.511＋4 ＝ 12.511 */
    static float ladderExitCost() {
        return (float) Configs.Go.GO_LADDER_EXIT_COST.getDoubleValue();
    }

    private static final float MIN_IMPROVEMENT = 0.01F;
    private static final int MAX_EMPTY_CHUNKS = 50;
    private static final int MAX_NODES = 300_000;
    /** 超时/取消检查间隔（纳秒）：按时间检查而不是"每 N 个节点"——单节点扩展昂贵时，
     * 节点粒度的检查会让一次"时长预算"实际跑出数倍时长，后续重算只能在单线程队列里
     * 排队，观感就是"卡死不动" */
    private static final long CHECK_INTERVAL_NANOS = 1_000_000L;

    /**
     * 寻路成本上限（0/1＝不设上限）：以「起点到最近目标的路程下界 {@code h0}」为基准，
     * 返回 {@code h0 × factor}，含义是"绕路最多绕几倍"。
     *
     * <p>比较口径必须与 {@code h0} 同单位、且<b>只含路程</b>（走路＝时间成本，飞行＝几何路程），
     * 不得混入"贴墙小心走/末端集中升降"这类<b>软偏好</b>加价——软偏好是"两条都能到的路选哪条"
     * 的事，混进来会把上限顶爆，把走得通的目标误判成"无解"（乐魂会表现为原地不动）。
     * 超限的节点由调用方按"该节点及其后继都不可能到达"剪枝，剪干净后自然收工返回。
     *
     * <p>{@code h0} 极小（起点已在目标内）时不剪枝；{@code factor ≤ 1} 视为关闭该上限。
     */
    static float costLimit(float h0, int factor) {
        if (factor <= 1 || h0 <= 0.5F) {
            return Float.MAX_VALUE;
        }
        return h0 * factor;
    }
    /** 地形探测缓存上限（条）：超出即整表清空。1.5M 条约 27MB，触及该量级的搜索本就被预算掐断 */
    private static final int PROBE_CACHE_MAX = 1_500_000;

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** 探测缓存位：有碰撞（可站立的地面） */
    private static final byte BIT_SOLID = 1;
    /** 探测缓存位：岩浆 */
    private static final byte BIT_LAVA = 2;
    /** 探测缓存位：水 */
    private static final byte BIT_WATER = 4;
    /** 探测缓存位：可攀爬（梯子/藤蔓等） */
    private static final byte BIT_CLIMB = 8;

    /** 启发值水平单价：与「强制疾跑」开关对齐。Goal 构造时快照，搜索中不读配置 */
    static float horizUnit() {
        return Configs.Go.GO_FORCE_SPRINT.getBooleanValue() ? sprintCost() : walkCost();
    }

    /** 启发值上升分量下界：跳上一格的成本扣除其水平位移份额（攀爬 8.51/格 仍覆盖） */
    static float upUnit() {
        return jumpUpCost() - horizUnit();
    }

    /** octile 距离（直线 + 对角混合的 8 向网格路径长度下界） */
    static float octile(float dx, float dz) {
        float ax = Math.abs(dx);
        float az = Math.abs(dz);
        return Math.max(ax, az) + 0.41421356F * Math.min(ax, az);
    }

    /** 寻路目标 */
    public interface Goal {
        boolean isInGoal(int x, int y, int z);

        float heuristic(int x, int y, int z);

        /**
         * 到目标（水平投影）的距离下界，用于乐魂飞行的「末端集中升降」加价：
         * 离目标越远，改变高度的代价越高，从而把升降推到末端。默认 0 = 不施加该机制。
         */
        default float horizDistanceTo(int x, int z) {
            return 0.0F;
        }
    }

    /** 目标方块：站在该方块上（允许同 XZ 的 ±1 格，兼容台阶/半砖落脚差异） */
    public static Goal blockGoal(BlockPos pos) {
        int gx = pos.getX();
        int gy = pos.getY();
        int gz = pos.getZ();
        float unit = horizUnit();
        float upB = upUnit();
        return new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return x == gx && z == gz && Math.abs(y - gy) <= 1;
            }

            @Override
            public float heuristic(int x, int y, int z) {
                float up = Math.max(0, y - gy);
                // octile 与上升下界均不高于真实路径成本（可采纳），且沿边变化不超过边成本（一致）
                return octile(x - gx, z - gz) * unit + up * upB;
            }
        };
    }

    /**
     * 紧贴目标方块（"扫描自动寻路"派发的待放置方块）：
     * 站到与目标水平相邻（上下 ±1 层内）的格子上即到达，绝不占用目标格本身
     * （目标格是待放置方块的空位，站进去会挡住打印机放置）。
     */
    public static Goal adjacentGoal(BlockPos target) {
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        float unit = horizUnit();
        float upB = upUnit();
        return new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return isAdjacentArrived(target, x, y, z);
            }

            @Override
            public float heuristic(int x, int y, int z) {
                // 到目标剩余水平路程的下界：octile 距离减 1（相邻格距目标 1 格，度量 1-Lipschitz）
                float flat = Math.max(0.0F, octile(x - tx, z - tz) - 1.0F);
                float up = Math.max(0, y - ty - 1);
                return flat * unit + up * upB;
            }
        };
    }

    /**
     * 目标集合（多目标 A*）：全部候选方块的紧邻站立格（水平曼哈顿 1、Y ±1）作为终点，
     * 第一个被搜索定稿的目标格即"路径成本最短"的候选——选目标与算路径一次完成。
     * 启发值 = min over 候选分桶的"节点到桶包围盒（候选外扩 1 格，覆盖全部站立格）的
     * 水平距离 × 水平单价 + 低于桶底的垂直攀爬 × 上升下界"：包围盒距离不高于盒内任意
     * 站立格的真实水平距离，可采纳；各桶项沿边 Lipschitz 且不超过边成本，min 后仍一致。
     */
    public static final class GoalSet implements Goal {
        /** 站立格 → 所属候选（相邻候选共享站立格时先到先得） */
        private final Long2ObjectOpenHashMap<BlockPos> cellToTarget;
        private final LongOpenHashSet standCells;
        /** 候选分桶包围盒（已外扩 1 格，覆盖站立格） */
        private final Bucket[] buckets;
        private final float unit;
        private final float upB;

        private GoalSet(Long2ObjectOpenHashMap<BlockPos> cellToTarget, Bucket[] buckets) {
            this.cellToTarget = cellToTarget;
            this.standCells = new LongOpenHashSet(cellToTarget.keySet());
            this.buckets = buckets;
            this.unit = horizUnit();
            this.upB = upUnit();
        }

        /** 站立格 → 候选方块映射（供派发方在到达后反查是哪个候选被选中） */
        public Long2ObjectOpenHashMap<BlockPos> cellToTarget() {
            return cellToTarget;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return standCells.contains(BlockPos.asLong(x, y, z));
        }

        @Override
        public float heuristic(int x, int y, int z) {
            float best = Float.MAX_VALUE;
            for (Bucket b : buckets) {
                float dx = Math.max(Math.max(b.loX - x, x - b.hiX), 0);
                float dz = Math.max(Math.max(b.loZ - z, z - b.hiZ), 0);
                float dv = y < b.loY ? (b.loY - y) : 0.0F;
                float h = (float) Math.sqrt(dx * dx + dz * dz) * unit + dv * upB;
                if (h < best) {
                    best = h;
                }
            }
            return best;
        }
    }

    /** 候选按 16³ 子区块的包围盒（坐标含外扩） */
    private static final class Bucket {
        int loX = Integer.MAX_VALUE;
        int loY = Integer.MAX_VALUE;
        int loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE;
        int hiY = Integer.MIN_VALUE;
        int hiZ = Integer.MIN_VALUE;
    }

    /**
     * 构造目标集合：候选按离 from 的直线距离排序预截 limit 个（直线距离只作预筛，
     * 最短目标仍由路径成本裁决），再展开为站立格集合与分桶包围盒。
     */
    public static GoalSet goalSet(List<BlockPos> targets, int limit, BlockPos from) {
        ArrayList<BlockPos> list = new ArrayList<>(targets);
        if (list.size() > limit) {
            list.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
            while (list.size() > limit) {
                list.remove(list.size() - 1);
            }
        }
        Long2ObjectOpenHashMap<BlockPos> cellToTarget = new Long2ObjectOpenHashMap<>(list.size() * 16);
        Map<Long, Bucket> bucketMap = new HashMap<>();
        for (BlockPos t : list) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int[] d : DIRS) {
                    long key = BlockPos.asLong(t.getX() + d[0], t.getY() + dy, t.getZ() + d[1]);
                    cellToTarget.putIfAbsent(key, t);
                }
            }
            long sk = BlockPos.asLong(t.getX() >> 4, t.getY() >> 4, t.getZ() >> 4);
            Bucket b = bucketMap.computeIfAbsent(sk, k -> new Bucket());
            b.loX = Math.min(b.loX, t.getX() - 1);
            b.loY = Math.min(b.loY, t.getY() - 1);
            b.loZ = Math.min(b.loZ, t.getZ() - 1);
            b.hiX = Math.max(b.hiX, t.getX() + 1);
            b.hiY = Math.max(b.hiY, t.getY() + 1);
            b.hiZ = Math.max(b.hiZ, t.getZ() + 1);
        }
        return new GoalSet(cellToTarget, bucketMap.values().toArray(new Bucket[0]));
    }

    /** 脚部方块坐标 (x,y,z) 是否处于目标方块的"紧邻可站立"位置（节点与玩家脚部共用同一判定） */
    public static boolean isAdjacentArrived(BlockPos target, int x, int y, int z) {
        int dx = Math.abs(x - target.getX());
        int dz = Math.abs(z - target.getZ());
        return dx + dz == 1 && Math.abs(y - target.getY()) <= 1;
    }

    /** 结果：起点→终点的可站立足位序列 */
    public static final class Result {
        public final ArrayList<BlockPos> positions;
        public final boolean reachedGoal;
        /** 终点到目标的启发值换算的粗略距离（格） */
        public final float distanceToGoal;
        /** 到达的目标格坐标（仅目标集合搜索且到达时有值，未到达为 null） */
        @Nullable
        public final BlockPos goalCell;
        /** 路径总代价（tick＝终点节点的 g）。仅供诊断：多目标选目标时用来对比"选中的是否代价最小" */
        public final float cost;

        Result(ArrayList<BlockPos> positions, boolean reachedGoal, float distanceToGoal,
               @Nullable BlockPos goalCell, float cost) {
            this.positions = positions;
            this.reachedGoal = reachedGoal;
            this.distanceToGoal = distanceToGoal;
            this.goalCell = goalCell;
            this.cost = cost;
        }
    }

    private static final class Node {
        @Nullable
        Node parent;
        final int x;
        final int y;
        final int z;
        /** 未加权的启发值（可采纳下界）：成本上限、多目标收工比较、best-so-far 一律用它 */
        final float h;
        /** 加权启发值 = h × 启发值权重：只用于堆排序（f = g + hw） */
        final float hw;
        float g;
        int heapIndex = -1;

        Node(@Nullable Node parent, int x, int y, int z, float g, float h, float hw) {
            this.parent = parent;
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.h = h;
            this.hw = hw;
        }

        float f() {
            return g + hw;
        }
    }

    /** 二叉最小堆（按 f 排序），节点内缓存堆下标实现 O(log n) decrease-key */
    private static final class Heap {
        private Node[] nodes = new Node[1024];
        private int size;

        boolean isEmpty() {
            return size == 0;
        }

        void push(Node node) {
            if (size == nodes.length - 1) {
                nodes = java.util.Arrays.copyOf(nodes, nodes.length * 2);
            }
            nodes[++size] = node;
            siftUp(size);
        }

        Node pop() {
            Node top = nodes[1];
            Node last = nodes[size];
            nodes[size--] = null;
            if (size > 0) {
                nodes[1] = last;
                siftDown(1);
            }
            top.heapIndex = -1;
            return top;
        }

        /** g 只会减小（f 减小），只需上浮 */
        void update(Node node) {
            if (node.heapIndex > 1) {
                siftUp(node.heapIndex);
            }
        }

        private void siftUp(int i) {
            Node n = nodes[i];
            while (i > 1) {
                int p = i >> 1;
                if (nodes[p].f() <= n.f()) {
                    break;
                }
                nodes[i] = nodes[p];
                nodes[i].heapIndex = i;
                i = p;
            }
            nodes[i] = n;
            n.heapIndex = i;
        }

        private void siftDown(int i) {
            Node n = nodes[i];
            while (true) {
                int c = i << 1;
                if (c > size) {
                    break;
                }
                if (c + 1 <= size && nodes[c + 1].f() < nodes[c].f()) {
                    c++;
                }
                if (nodes[c].f() >= n.f()) {
                    break;
                }
                nodes[i] = nodes[c];
                nodes[i].heapIndex = i;
                i = c;
            }
            nodes[i] = n;
            n.heapIndex = i;
        }
    }

    private final ClientLevel level;
    private final Goal goal;
    private final int maxFall;
    private final float[] fallTicks;
    /** 成本上限倍数（0/1＝不设上限；主线程快照，搜索中不读配置） */
    private final int costLimitFactor;
    /** 启发值权重（配置快照）：1.0＝标准 A*（可采纳），&gt;1＝加权 A*（更贪心） */
    private final float heuristicWeight;
    /** 各移动方式的单价（tick）：构造时从配置快照一次，搜索中不读配置 */
    private final float walkCost;
    private final float diagonalCost;
    private final float jumpUpCost;
    private final float waterCost;
    private final float ladderCost;
    private final float ladderExitUpCost;
    private final float parkourCost;
    private final float sprintCost;
    private final Long2ObjectOpenHashMap<Node> map = new Long2ObjectOpenHashMap<>(4096);
    private final Heap heap = new Heap();
    /** 地形探测缓存：bit0=solid bit1=lava bit2=water bit3=climbable，-1=未缓存 */
    private final Long2ByteOpenHashMap probeCache = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos cursorA = new BlockPos.MutableBlockPos();
    private int emptyChunks;

    private GoPathfinder(ClientLevel level, Goal goal, int maxFall, int costLimitFactor) {
        this.level = level;
        this.goal = goal;
        this.maxFall = maxFall;
        this.costLimitFactor = costLimitFactor;
        this.heuristicWeight = (float) Configs.Go.GO_HEURISTIC_WEIGHT.getDoubleValue();
        this.fallTicks = buildFallTicks(maxFall + 4);
        this.probeCache.defaultReturnValue((byte) -1);
        this.walkCost = walkCost();
        this.diagonalCost = diagonalCost();
        this.sprintCost = sprintCost();
        this.jumpUpCost = jumpUpCost();
        this.waterCost = waterCost();
        this.ladderCost = ladderCost();
        this.ladderExitUpCost = ladderExitCost();
        this.parkourCost = (float) Configs.Go.GO_PARKOUR_COST.getDoubleValue();
    }

    /**
     * 从 start 到 goal 的纯行走路径。
     *
     * @param budgetMs        单次计算时长预算（毫秒）
     * @param costLimitFactor 成本上限倍数（0/1＝不设上限），见 {@link #costLimit}
     * @param cancelled       取消信号（返回 true 时尽快结束并返回当前最优部分路径）
     */
    @Nullable
    public static Result findPath(ClientLevel level, BlockPos start, Goal goal,
                                  long budgetMs, int maxFall, int costLimitFactor, BooleanSupplier cancelled) {
        return new GoPathfinder(level, goal, maxFall, costLimitFactor)
                .find(start, budgetMs * 1_000_000L, cancelled);
    }

    @Nullable
    private Result find(BlockPos start, long budgetNanos, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + budgetNanos;
        long nextCheck = System.nanoTime() + CHECK_INTERVAL_NANOS;
        float h0 = goal.heuristic(start.getX(), start.getY(), start.getZ());
        Node startNode = new Node(null, start.getX(), start.getY(), start.getZ(), 0.0F, h0,
                h0 * heuristicWeight);
        map.put(BlockPos.asLong(startNode.x, startNode.y, startNode.z), startNode);
        heap.push(startNode);
        Node best = startNode;
        // 成本上限：起点到目标的距离下界 × 倍数（0/1＝不设上限）。用未加权的 h，与启发值权重无关
        float limit = costLimit(startNode.h, costLimitFactor);
        // 已定稿的最优目标（多目标＝候选竞争；单目标＝目标区域里最便宜的那一格）。
        // 弹出目标格不再立即收工，而是继续搜索"下界仍可能更便宜"的分支，直到堆里最小
        // 的 f 都不小于它的成本为止——先算出来的必须真的比没算完的便宜，才认它。
        // 注意判定用 bestGoal.g 实时读取：同格可能被 decrease-key 换成更便宜的走法。
        Node bestGoal = null;
        // 权重 > 1 时堆按「g + 权重×h」排序，弹出序不再等价于 g+h 序（下面的收工/上限判定
        // 用的是可采纳下界 g+h），故这些判定只能作用于"当前节点"，不能当"后面只会更贵"用
        boolean admissibleOrder = heuristicWeight <= 1.0F;
        while (!heap.isEmpty()) {
            Node cur = heap.pop();
            if (goal.isInGoal(cur.x, cur.y, cur.z)) {
                // 只认严格更便宜的目标；成本并列时保持先到者
                if (bestGoal == null || cur.g < bestGoal.g) {
                    bestGoal = cur;
                }
                continue; // 目标格不再扩展：穿过它只会更贵，stop 判定同样会拦下
            }
            if (bestGoal != null && cur.g + cur.h >= bestGoal.g) {
                // 该节点及其所有后继的总代价下界已不低于已定稿目标 → 其余目标不可能更便宜，收工
                if (admissibleOrder) {
                    break;
                }
                continue;
            }
            if (cur.g + cur.h > limit) {
                // 成本上限（纯路程口径，不含软偏好与权重）：该节点及其后继按"不可达"处理。
                // 标准 A* 的弹出序即 g+h 序，后面的只会更贵 → 直接收工；加权 A* 的弹出序是
                // 加权 f，不能据此断言后面都超限，只剪本节点继续搜
                if (admissibleOrder) {
                    return bestGoal == null ? null : buildPath(bestGoal, true);
                }
                continue;
            }
            if (cur.h < best.h) {
                best = cur;
            }
            if (System.nanoTime() >= nextCheck) {
                nextCheck = System.nanoTime() + CHECK_INTERVAL_NANOS;
                if (cancelled.getAsBoolean() || System.nanoTime() >= deadline || map.size() > MAX_NODES) {
                    break;
                }
            }
            if (emptyChunks >= MAX_EMPTY_CHUNKS) {
                break;
            }
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
        for (int[] d : DIRS) {
            traverse(cur, d[0], d[1]);
        }
        for (int[] d : DIAGS) {
            diagonal(cur, d[0], d[1]);
        }
        for (int[] d : DIRS) {
            ascend(cur, d[0], d[1]);
        }
        for (int[] d : DIRS) {
            descend(cur, d[0], d[1]);
        }
        // 跑酷探测剪枝：4 个正交同层邻格都有地板时，任何方向的 gap=1 检查必失败
        //（缺口格有地板直接放弃），不可能产生跑酷跳边，整段探测可跳过
        if (Configs.Go.GO_FORCE_SPRINT.getBooleanValue() && gapAdjacent(cur)) {
            for (int[] d : DIRS) {
                parkour(cur, d[0], d[1]);
            }
        }
        climb(cur);
    }

    /** 当前节点是否紧邻"同层无地板"的格子（跑酷跳的必要条件） */
    private boolean gapAdjacent(Node cur) {
        for (int[] d : DIRS) {
            if (!walkableFloor(cur.x + d[0], cur.y, cur.z)) {
                return true;
            }
        }
        return false;
    }

    /** 平移：同层走到相邻格 */
    private void traverse(Node cur, int dx, int dz) {
        int nx = cur.x + dx;
        int nz = cur.z + dz;
        if (!loaded(nx, nz)) {
            return;
        }
        if (!(walkableFloor(nx, cur.y, nz) && passable(nx, cur.y, nz) && passable(nx, cur.y + 1, nz))) {
            return;
        }
        float cost = waterAt(nx, cur.y, nz) ? waterCost : walkCost;
        offer(cur, nx, cur.y, nz, cost);
    }

    /** 对角：同层斜走，两相邻正交列须可通行以防切角 */
    private void diagonal(Node cur, int dx, int dz) {
        int nx = cur.x + dx;
        int nz = cur.z + dz;
        if (!loaded(nx, nz)) {
            return;
        }
        if (!(walkableFloor(nx, cur.y, nz) && passable(nx, cur.y, nz) && passable(nx, cur.y + 1, nz))) {
            return;
        }
        if (!(passable(cur.x + dx, cur.y, cur.z) && passable(cur.x + dx, cur.y + 1, cur.z)
                && passable(cur.x, cur.y, cur.z + dz) && passable(cur.x, cur.y + 1, cur.z + dz))) {
            return;
        }
        float cost = waterAt(nx, cur.y, nz) ? waterCost * 1.41421356F : diagonalCost;
        offer(cur, nx, cur.y, nz, cost);
    }

    /** 跳上一格 */
    private void ascend(Node cur, int dx, int dz) {
        int nx = cur.x + dx;
        int nz = cur.z + dz;
        int ny = cur.y + 1;
        if (!loaded(nx, nz)) {
            return;
        }
        if (!(walkableFloor(nx, ny, nz) && passable(nx, ny, nz) && passable(nx, ny + 1, nz))) {
            return;
        }
        // 起跳时头顶需留空
        if (!passable(cur.x, cur.y + 2, cur.z)) {
            return;
        }
        offer(cur, nx, ny, nz, jumpUpCost);
    }

    /** 走下悬崖：沿相邻列自然下落，落点必须有可站立足面且下落高度受限 */
    private void descend(Node cur, int dx, int dz) {
        int nx = cur.x + dx;
        int nz = cur.z + dz;
        if (!loaded(nx, nz)) {
            return;
        }
        // 走出边缘的通道（脚+头）
        if (!passable(nx, cur.y, nz) || !passable(nx, cur.y + 1, nz)) {
            return;
        }
        int feet = cur.y - 1;
        int minY = level.getMinY();
        while (true) {
            if (feet < minY) {
                return;
            }
            // 下落路径穿过攀爬格不可行：原版坠落触碰梯子/藤蔓会被中途接住而非落底
            if (climbableAt(nx, feet, nz)) {
                return;
            }
            if (!passable(nx, feet, nz)) {
                return;
            }
            if (walkableFloor(nx, feet, nz)) {
                break;
            }
            feet--;
        }
        int fallDist = cur.y - feet;
        if (fallDist > maxFall) {
            return;
        }
        offer(cur, nx, feet, nz, walkCost + fallTicks[fallDist]);
    }

    /**
     * 疾跑跳过缺口：正交方向跳过 1~3 格无地板缺口，落在同层 2~4 格外。
     * 需要疾跑助力，执行侧（GoExecutor）在边缘探测到前方无地板时起跳。
     * 起跳点头顶须留空（腾空弧线上升超过一格），缺口格全弧线（脚/头/头顶）无碰撞，
     * 中途遇到有地板的格子即放弃（那种地形由 平移+短跳 覆盖）。
     * 「自动寻路 - 强制疾跑」未开启时不生成跑酷跳边（执行侧不允许任何跑酷跳，含 1 格缺口），
     * 路径自然绕开缺口或判定不可达。调用方已保证开关开启且当前节点紧邻缺口。
     */
    private void parkour(Node cur, int dx, int dz) {
        if (waterAt(cur.x, cur.y, cur.z) || !passable(cur.x, cur.y + 2, cur.z)) {
            return; // 水中无法疾跑起跳；起跳点需头顶留空
        }
        int gx = cur.x;
        int gz = cur.z;
        for (int gap = 1; gap <= 3; gap++) {
            gx += dx;
            gz += dz;
            if (!loaded(gx, gz)) {
                return;
            }
            if (walkableFloor(gx, cur.y, gz) || climbableAt(gx, cur.y, gz) || !passable(gx, cur.y, gz)
                    || !passable(gx, cur.y + 1, gz) || !passable(gx, cur.y + 2, gz)) {
                return; // 缺口格含攀爬方块时放弃：腾空穿过会被原版中途接住，跳不完整
            }
            int lx = gx + dx;
            int lz = gz + dz;
            if (!loaded(lx, lz)) {
                continue;
            }
            if (walkableFloor(lx, cur.y, lz) && passable(lx, cur.y, lz) && passable(lx, cur.y + 1, lz)) {
                offer(cur, lx, cur.y, lz, parkourCost);
            }
        }
    }

    /**
     * 可攀爬方块（梯子/藤蔓等，BlockTags.CLIMBABLE）：走进攀爬格、沿列上爬/下爬、
     * 爬出到相邻站立格。攀爬格无碰撞体，节点不要求脚下有地板（悬挂状态）。
     */
    private void climb(Node cur) {
        int x = cur.x;
        int y = cur.y;
        int z = cur.z;
        if (climbableAt(x, y, z)) {
            // 沿列上爬：头部格 (y+2) 须留空，否则身体在上一格放不下
            if (climbableAt(x, y + 1, z) && passable(x, y + 2, z)) {
                offer(cur, x, y + 1, z, ladderCost);
            }
            // 沿列下爬
            if (climbableAt(x, y - 1, z)) {
                offer(cur, x, y - 1, z, ladderCost);
            }
            for (int[] d : DIRS) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (!loaded(nx, nz)) {
                    continue;
                }
                // 同层爬出：相邻站立格
                if (walkableFloor(nx, y, nz) && passable(nx, y, nz) && passable(nx, y + 1, nz)) {
                    offer(cur, nx, y, nz, walkCost);
                }
                // 翻上梯顶：相邻高一层的站立格（执行侧跳跃+侧移翻出）
                if (walkableFloor(nx, y + 1, nz) && passable(nx, y + 1, nz) && passable(nx, y + 2, nz)) {
                    offer(cur, nx, y + 1, nz, ladderExitUpCost);
                }
            }
            return;
        }
        // 从站立格进入攀爬列：同层相邻攀爬格（走进去），
        // 或低一层攀爬格（走出边缘坠落一格被接住，藤蔓顶端常低于平台面）
        if (!passable(x, y, z)) {
            return;
        }
        for (int[] d : DIRS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!loaded(nx, nz)) {
                continue;
            }
            if (climbableAt(nx, y, nz) && passable(nx, y + 1, nz)) {
                offer(cur, nx, y, nz, walkCost);
            } else if (climbableAt(nx, y + 1, nz) && passable(nx, y + 2, nz)) {
                // 跳入攀爬列：攀爬格底端高于站立面一格（墙基半埋、梯子/藤蔓底端悬空一格很常见），
                // 走不进去也够不着低一层——跳跃扑向攀爬格抓住。执行侧由既有的
                // "路点高一格且距离 1.8 格内即跳跃"分支完成起跳
                offer(cur, nx, y + 1, nz, jumpUpCost);
            } else if (passable(nx, y, nz) && climbableAt(nx, y - 1, nz)) {
                offer(cur, nx, y - 1, nz, walkCost + fallTicks[1]);
            }
        }
    }

    private void offer(Node parent, int x, int y, int z, float cost) {
        float tentative = parent.g + cost;
        long key = BlockPos.asLong(x, y, z);
        Node n = map.get(key);
        if (n == null) {
            float h = goal.heuristic(x, y, z);
            n = new Node(parent, x, y, z, tentative, h, h * heuristicWeight);
            map.put(key, n);
            heap.push(n);
        } else if (tentative < n.g - MIN_IMPROVEMENT) {
            n.g = tentative;
            n.parent = parent;
            heap.update(n);
        }
    }

    private boolean loaded(int x, int z) {
        if (BlockStateUtils.isColumnLoaded(level, x >> 4, z >> 4)) {
            return true;
        }
        emptyChunks++;
        return false;
    }

    /**
     * 单格地形探测（带缓存）：一次 getBlockState + 碰撞求值得出 4 个结论位，
     * passable/walkableFloor/water/climbable 共享同一份缓存——同一格子平均会被
     * 不同邻居重复探测 6~10 次，缓存后碰撞求值只算一次。
     */
    private byte probe(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte b = probeCache.get(key);
        if (b >= 0) {
            return b;
        }
        b = probeBlock(level, cursorA, x, y, z);
        if (probeCache.size() >= PROBE_CACHE_MAX) {
            probeCache.clear(); // 上限兜底：超大搜索的缓存收益递减，防内存膨胀
        }
        probeCache.put(key, b);
        return b;
    }

    /**
     * 单格地形位求值（无缓存、线程安全、包内共用）：
     * bit0=solid bit1=lava bit2=water bit3=climbable。
     * 寻路的 {@link #probe}（实例缓存）与主线程的候选落脚点预检（
     * {@link #hasStandableNeighbor}，独立缓存）都调用本方法，
     * 位规则只有一处实现，杜绝两边手工复刻产生偏差。
     */
    static byte probeBlock(ClientLevel level, BlockPos.MutableBlockPos mpos, int x, int y, int z) {
        BlockState state = level.getBlockState(mpos.set(x, y, z));
        byte b = 0;
        if (!state.getCollisionShape(level, mpos).isEmpty()) {
            b |= BIT_SOLID;
        } else if (state.getFluidState().is(FluidTags.LAVA)) {
            b |= BIT_LAVA;
        }
        if (state.getFluidState().is(FluidTags.WATER)) {
            b |= BIT_WATER;
        }
        if (state.is(BlockTags.CLIMBABLE)) {
            b |= BIT_CLIMB;
        }
        return b;
    }

    /** 预检结果缓存上限（条）：超出整表清空（候选坐标 → 是否有合法落脚点） */
    private static final int STAND_SPOT_CACHE_MAX = 4096;
    /**
     * 落脚点预检的<b>水平</b>半径（切比雪夫，格）：候选方块水平方向这个范围内的可站立位都算"有地方站"。
     * 由内向外逐层扫、命中即返回；放宽半径＝更保守的剪枝（宁可多留，交给 A* 裁决），
     * 同时会让池内"可到达"计数涨得更快（比较门禁更容易凑够）。
     */
    private static final int STAND_RADIUS = 3;
    /** 落脚点预检的<b>竖直</b>容差（格）：上下各 1 层（与站立格的 Y±1 语义一致，不做上下放宽） */
    private static final int STAND_VERTICAL = 1;
    /** 预检的逐格地形位缓存上限（条）：超出整表清空。逐格结果与候选无关，可跨候选共享 */
    private static final int STAND_PROBE_CACHE_MAX = 65536;
    /** 预检的逐格地形位缓存：格坐标 → 是否可站立（修订号变化即失效） */
    private static final Long2ByteOpenHashMap STAND_PROBE_CACHE = new Long2ByteOpenHashMap();
    private static long standProbeRevision = Long.MIN_VALUE;
    /** 预检结果缓存：仅主线程（派发处）访问，与后台寻路的实例缓存互不共享 */
    private static final Long2BooleanOpenHashMap STAND_SPOT_CACHE = new Long2BooleanOpenHashMap();
    /** 缓存构建时的世界修订号：方块/原理图/维度变化（SchematicStateCache 修订号）即失效重算 */
    private static long standSpotCacheRevision = Long.MIN_VALUE;

    /**
     * 候选方块周围是否存在至少一个可站立位（<b>水平切比雪夫半径 {@link #STAND_RADIUS}</b>、
     * <b>竖直 ±{@link #STAND_VERTICAL}</b>，不含候选格本身）。这是派发前的「只删必死」剪枝：
     * 任一格满足即保留，绝不因单格站不了判死候选；
     * 判定有效 ≠ 能到达（中间有沟/墙仍由寻路裁决），判定无效 = 周围连站的地方都没有。
     * 结果按候选坐标缓存，世界修订号变化即失效重算；逐格地形位另有一层按格缓存（跨候选共享）。
     */
    static boolean hasStandableNeighbor(ClientLevel level, BlockPos target) {
        long rev = SchematicStateCache.INSTANCE.getRevision();
        if (rev != standSpotCacheRevision) {
            standSpotCacheRevision = rev;
            STAND_SPOT_CACHE.clear();
        }
        long key = target.asLong();
        if (STAND_SPOT_CACHE.containsKey(key)) {
            return STAND_SPOT_CACHE.get(key);
        }
        boolean ok = computeStandableNeighbor(level, target);
        if (STAND_SPOT_CACHE.size() >= STAND_SPOT_CACHE_MAX) {
            STAND_SPOT_CACHE.clear(); // 上限兜底：防内存膨胀
        }
        STAND_SPOT_CACHE.put(key, ok);
        return ok;
    }

    /**
     * 由内向外逐层（水平切比雪夫壳 r=1..{@link #STAND_RADIUS} × 竖直 {@link #STAND_VERTICAL}）找落脚点，
     * 命中即返回：先看最近的位置，绝大多数候选在第 1~2 层就命中，比"整片扫完"便宜得多。
     */
    private static boolean computeStandableNeighbor(ClientLevel level, BlockPos target) {
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        for (int r = 1; r <= STAND_RADIUS; r++) {
            for (int dy = -STAND_VERTICAL; dy <= STAND_VERTICAL; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    boolean xEdge = dx == -r || dx == r;
                    for (int dz = -r; dz <= r; dz++) {
                        if (!xEdge && !(dz == -r || dz == r)) {
                            continue; // 只扫本层水平壳（内层已扫过）
                        }
                        if (standableAt(level, tx + dx, ty + dy, tz + dz)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** 单格"可站立位"判定（与全部移动生成器同条件），结果按格缓存、世界修订号变化即失效 */
    private static boolean standableAt(ClientLevel level, int x, int y, int z) {
        long rev = SchematicStateCache.INSTANCE.getRevision();
        if (rev != standProbeRevision) {
            standProbeRevision = rev;
            STAND_PROBE_CACHE.clear();
        }
        long key = BlockPos.asLong(x, y, z);
        byte cached = STAND_PROBE_CACHE.get(key);
        if (cached != 0) {
            return cached > 0;
        }
        boolean ok = computeStandable(level, x, y, z);
        if (STAND_PROBE_CACHE.size() >= STAND_PROBE_CACHE_MAX) {
            STAND_PROBE_CACHE.clear(); // 上限兜底：防内存膨胀
        }
        STAND_PROBE_CACHE.put(key, (byte) (ok ? 1 : -1));
        return ok;
    }

    /**
     * 单格地形判定：脚下实心 + 本格可通行 + 头部可通行 → 可站立；
     * 或本格是可攀爬方块（climb() 允许悬挂节点，不要求脚下实心——宁可保守漏删，
     * 也不误删本可攀爬到达的活目标）。
     */
    private static boolean computeStandable(ClientLevel level, int x, int y, int z) {
        if (!BlockStateUtils.isColumnLoaded(level, x >> 4, z >> 4)) {
            return false; // 未加载列：A* 的 loaded() 同样拒绝，不算合法落脚点
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        byte under = probeBlock(level, m, x, y - 1, z);
        if ((under & BIT_SOLID) != 0) {
            byte feet = probeBlock(level, m, x, y, z);
            byte head = probeBlock(level, m, x, y + 1, z);
            if (((feet & BIT_CLIMB) != 0 || (feet & (BIT_SOLID | BIT_LAVA)) == 0)
                    && ((head & BIT_CLIMB) != 0 || (head & (BIT_SOLID | BIT_LAVA)) == 0)) {
                return true;
            }
        }
        return (probeBlock(level, m, x, y, z) & BIT_CLIMB) != 0;
    }

    /**
     * 该格可通行：无碰撞且不是岩浆（水可通行，走水中动作有额外成本）。
     * 梯子/藤蔓等可攀爬方块按可通行处理——它们的碰撞体是贴墙薄片（原版未设
     * noCollission，getCollisionShape 返回薄片而非空），但对寻路而言攀爬格
     * 就是要走进/穿过的格子，按碰撞为空判定会把所有攀爬路线判死。
     */
    private boolean passable(int x, int y, int z) {
        byte b = probe(x, y, z);
        return (b & BIT_CLIMB) != 0 || (b & (BIT_SOLID | BIT_LAVA)) == 0;
    }

    /** (x,y,z) 脚位的下方方块是否有碰撞（可站立） */
    private boolean walkableFloor(int x, int y, int z) {
        return (probe(x, y - 1, z) & BIT_SOLID) != 0;
    }

    private boolean waterAt(int x, int y, int z) {
        return (probe(x, y, z) & BIT_WATER) != 0;
    }

    /** (x,y,z) 是否为可攀爬方块（梯子/藤蔓等） */
    private boolean climbableAt(int x, int y, int z) {
        return (probe(x, y, z) & BIT_CLIMB) != 0;
    }

    private Result buildPath(Node end, boolean reachedGoal) {
        int count = 0;
        for (Node n = end; n != null; n = n.parent) {
            count++;
        }
        ArrayList<BlockPos> out = new ArrayList<>(count);
        for (Node n = end; n != null; n = n.parent) {
            out.add(new BlockPos(n.x, n.y, n.z));
        }
        Collections.reverse(out);
        BlockPos goalCell = reachedGoal ? new BlockPos(end.x, end.y, end.z) : null;
        return new Result(out, reachedGoal, end.h / sprintCost, goalCell, end.g);
    }

    /** 模拟 MC 重力：下落 n 格所需 tick 数 */
    private static float[] buildFallTicks(int maxBlocks) {
        float[] ticks = new float[maxBlocks + 1];
        double velocity = 0.0;
        double fallen = 0.0;
        int t = 0;
        for (int n = 1; n <= maxBlocks; n++) {
            while (fallen < n) {
                velocity = (velocity - 0.08) * 0.98;
                fallen += -velocity;
                t++;
            }
            ticks[n] = t;
        }
        return ticks;
    }
}
