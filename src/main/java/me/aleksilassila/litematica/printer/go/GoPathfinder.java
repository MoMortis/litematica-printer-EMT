package me.aleksilassila.litematica.printer.go;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
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

    static final float WALK_COST = 20.0F / 4.317F;            // 步行一格 4.633 tick
    static final float DIAGONAL_COST = WALK_COST * 1.41421356F;
    static final float JUMP_UP_COST = WALK_COST + 5.0F;       // 跳上一格
    static final float SPRINT_COST = 20.0F / 5.612F;          // 疾跑一格（启发下界）
    static final float WATER_COST = 20.0F / 2.2F;             // 涉水一格
    static final float LADDER_COST = 20.0F / 2.35F;           // 爬梯子/藤蔓一格（原版攀爬速度）
    static final float LADDER_EXIT_UP_COST = LADDER_COST + 4.0F; // 从梯顶翻出（跳+侧移）
    static final float PARKOUR_COST = 12.0F;                  // 疾跑跳腾空约 12 tick，可覆盖 2~4 格
    private static final float MIN_IMPROVEMENT = 0.01F;
    private static final int MAX_EMPTY_CHUNKS = 50;
    private static final int MAX_NODES = 300_000;
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
        return Configs.Go.GO_FORCE_SPRINT.getBooleanValue() ? SPRINT_COST : WALK_COST;
    }

    /** 启发值上升分量下界：跳上一格的成本扣除其水平位移份额（攀爬 8.51/格 仍覆盖） */
    static float upUnit() {
        return JUMP_UP_COST - horizUnit();
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

        Result(ArrayList<BlockPos> positions, boolean reachedGoal, float distanceToGoal, @Nullable BlockPos goalCell) {
            this.positions = positions;
            this.reachedGoal = reachedGoal;
            this.distanceToGoal = distanceToGoal;
            this.goalCell = goalCell;
        }
    }

    private static final class Node {
        @Nullable
        Node parent;
        final int x;
        final int y;
        final int z;
        final float h;
        float g;
        int heapIndex = -1;

        Node(@Nullable Node parent, int x, int y, int z, float g, float h) {
            this.parent = parent;
            this.x = x;
            this.y = y;
            this.z = z;
            this.g = g;
            this.h = h;
        }

        float f() {
            return g + h;
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
    private final Long2ObjectOpenHashMap<Node> map = new Long2ObjectOpenHashMap<>(4096);
    private final Heap heap = new Heap();
    /** 地形探测缓存：bit0=solid bit1=lava bit2=water bit3=climbable，-1=未缓存 */
    private final Long2ByteOpenHashMap probeCache = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos cursorA = new BlockPos.MutableBlockPos();
    private int emptyChunks;

    private GoPathfinder(ClientLevel level, Goal goal, int maxFall) {
        this.level = level;
        this.goal = goal;
        this.maxFall = maxFall;
        this.fallTicks = buildFallTicks(maxFall + 4);
        this.probeCache.defaultReturnValue((byte) -1);
    }

    /**
     * 从 start 到 goal 的纯行走路径。
     *
     * @param budgetMs  单次计算时长预算（毫秒）
     * @param cancelled 取消信号（返回 true 时尽快结束并返回当前最优部分路径）
     */
    @Nullable
    public static Result findPath(ClientLevel level, BlockPos start, Goal goal,
                                  long budgetMs, int maxFall, BooleanSupplier cancelled) {
        return new GoPathfinder(level, goal, maxFall).find(start, budgetMs * 1_000_000L, cancelled);
    }

    @Nullable
    private Result find(BlockPos start, long budgetNanos, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + budgetNanos;
        Node startNode = new Node(null, start.getX(), start.getY(), start.getZ(), 0.0F, goal.heuristic(start.getX(), start.getY(), start.getZ()));
        map.put(BlockPos.asLong(startNode.x, startNode.y, startNode.z), startNode);
        heap.push(startNode);
        Node best = startNode;
        int expanded = 0;
        while (!heap.isEmpty()) {
            Node cur = heap.pop();
            if (goal.isInGoal(cur.x, cur.y, cur.z)) {
                return buildPath(cur, true);
            }
            if (cur.h < best.h) {
                best = cur;
            }
            if ((++expanded & 63) == 0
                    && (cancelled.getAsBoolean() || System.nanoTime() >= deadline || map.size() > MAX_NODES)) {
                break;
            }
            if (emptyChunks >= MAX_EMPTY_CHUNKS) {
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
        float cost = waterAt(nx, cur.y, nz) ? WATER_COST : WALK_COST;
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
        float cost = waterAt(nx, cur.y, nz) ? WATER_COST * 1.41421356F : DIAGONAL_COST;
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
        offer(cur, nx, ny, nz, JUMP_UP_COST);
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
        offer(cur, nx, feet, nz, WALK_COST + fallTicks[fallDist]);
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
                offer(cur, lx, cur.y, lz, PARKOUR_COST);
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
                offer(cur, x, y + 1, z, LADDER_COST);
            }
            // 沿列下爬
            if (climbableAt(x, y - 1, z)) {
                offer(cur, x, y - 1, z, LADDER_COST);
            }
            for (int[] d : DIRS) {
                int nx = x + d[0];
                int nz = z + d[1];
                if (!loaded(nx, nz)) {
                    continue;
                }
                // 同层爬出：相邻站立格
                if (walkableFloor(nx, y, nz) && passable(nx, y, nz) && passable(nx, y + 1, nz)) {
                    offer(cur, nx, y, nz, WALK_COST);
                }
                // 翻上梯顶：相邻高一层的站立格（执行侧跳跃+侧移翻出）
                if (walkableFloor(nx, y + 1, nz) && passable(nx, y + 1, nz) && passable(nx, y + 2, nz)) {
                    offer(cur, nx, y + 1, nz, LADDER_EXIT_UP_COST);
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
                offer(cur, nx, y, nz, WALK_COST);
            } else if (climbableAt(nx, y + 1, nz) && passable(nx, y + 2, nz)) {
                // 跳入攀爬列：攀爬格底端高于站立面一格（墙基半埋、梯子/藤蔓底端悬空一格很常见），
                // 走不进去也够不着低一层——跳跃扑向攀爬格抓住。执行侧由既有的
                // "路点高一格且距离 1.8 格内即跳跃"分支完成起跳
                offer(cur, nx, y + 1, nz, JUMP_UP_COST);
            } else if (passable(nx, y, nz) && climbableAt(nx, y - 1, nz)) {
                offer(cur, nx, y - 1, nz, WALK_COST + fallTicks[1]);
            }
        }
    }

    private void offer(Node parent, int x, int y, int z, float cost) {
        float tentative = parent.g + cost;
        long key = BlockPos.asLong(x, y, z);
        Node n = map.get(key);
        if (n == null) {
            n = new Node(parent, x, y, z, tentative, goal.heuristic(x, y, z));
            map.put(key, n);
            heap.push(n);
        } else if (tentative < n.g - MIN_IMPROVEMENT) {
            n.g = tentative;
            n.parent = parent;
            heap.update(n);
        }
    }

    private boolean loaded(int x, int z) {
        if (level.hasChunk(x >> 4, z >> 4)) {
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
    /** 预检结果缓存：仅主线程（派发处）访问，与后台寻路的实例缓存互不共享 */
    private static final Long2BooleanOpenHashMap STAND_SPOT_CACHE = new Long2BooleanOpenHashMap();
    /** 缓存构建时的世界修订号：方块/原理图/维度变化（SchematicStateCache 修订号）即失效重算 */
    private static long standSpotCacheRevision = Long.MIN_VALUE;

    /**
     * 候选方块周围是否存在至少一个 A* 实际可产生的紧邻落脚格
     * （水平曼哈顿 1、Y±1，与 {@link #goalSet} 展开的站立格一致，不含候选格本身）。
     * 这是派发前的「只删必死」剪枝：任一紧邻格满足即保留，绝不因单格站不了判死候选；
     * 判定有效 ≠ 能到达（中间有沟/墙仍由寻路裁决），判定无效 = 任何起点都站不到，
     * 因此不会误删可到达目标。结果按坐标缓存，世界修订号变化即失效重算。
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

    private static boolean computeStandableNeighbor(ClientLevel level, BlockPos target) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        for (int dy = -1; dy <= 1; dy++) {
            for (int[] d : DIRS) {
                int x = tx + d[0];
                int y = ty + dy;
                int z = tz + d[1];
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue; // 未加载列：A* 的 loaded() 同样拒绝，不算合法落脚点
                }
                // 地面站立：脚下实心 + 本格可通行 + 头部可通行（与全部移动生成器同条件）
                byte under = probeBlock(level, m, x, y - 1, z);
                if ((under & BIT_SOLID) != 0) {
                    byte feet = probeBlock(level, m, x, y, z);
                    byte head = probeBlock(level, m, x, y + 1, z);
                    if (((feet & BIT_CLIMB) != 0 || (feet & (BIT_SOLID | BIT_LAVA)) == 0)
                            && ((head & BIT_CLIMB) != 0 || (head & (BIT_SOLID | BIT_LAVA)) == 0)) {
                        return true;
                    }
                }
                // 攀爬悬挂：本格是可攀爬方块（climb() 允许悬挂节点，不要求脚下实心）。
                // 只认「本格可爬」、不收窄到邻层——宁可保守漏删（多派死候选由 A* 证伪），
                // 也不误删本可攀爬到达的活目标
                if ((probeBlock(level, m, x, y, z) & BIT_CLIMB) != 0) {
                    return true;
                }
            }
        }
        return false;
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
        return new Result(out, reachedGoal, end.h / SPRINT_COST, goalCell);
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
