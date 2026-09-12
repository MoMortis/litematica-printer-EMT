package me.aleksilassila.litematica.printer.go;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.function.BooleanSupplier;

/**
 * 纯行走 A* 寻路（/go 自动寻路核心）。
 *
 * <p>只使用"移动 + 跳跃 + 攀爬"可完成的动作（平移 / 对角 / 跳上一格 / 走下悬崖 /
 * 疾跑跳过同层缺口 / 爬梯子藤蔓），绝不挖掘或放置方块。成本单位为 tick；
 * 单次计算受时间预算约束，超时返回 best-so-far（已探索节点中离目标最近的），
 * 实现"走到离目标最近的位置"语义。
 * 每次寻路新建实例，线程封闭（在后台计算线程上运行）。
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

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[][] DIAGS = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

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
        return new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return x == gx && z == gz && Math.abs(y - gy) <= 1;
            }

            @Override
            public float heuristic(int x, int y, int z) {
                float dx = x - gx;
                float dz = z - gz;
                float up = Math.max(0, y - gy);
                // 上升每格的启发值取跳上成本的保守低估
                return (float) Math.sqrt(dx * dx + dz * dz) * SPRINT_COST + up * 7.0F;
            }
        };
    }

    /**
     * 紧贴目标方块（"扫描自动寻路"派发的待放置方块）：
     * 站到与目标水平相邻（上下 ±1 层内）的格子上即到达，绝不占用目标格本身
     * （目标格是待放置方块的空位，站进去会挡住打印机放置）。
     */
    public static Goal adjacentGoal(BlockPos target) {
        return new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return isAdjacentArrived(target, x, y, z);
            }

            @Override
            public float heuristic(int x, int y, int z) {
                // 到目标剩余水平路程的下界：与目标中心距离减 1（相邻格距目标 1 格）
                float dx = x - target.getX();
                float dz = z - target.getZ();
                float flat = Math.max(0.0F, (float) Math.sqrt(dx * dx + dz * dz) - 1.0F);
                float up = Math.max(0, y - target.getY() - 1);
                return flat * SPRINT_COST + up * 7.0F;
            }
        };
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

        Result(ArrayList<BlockPos> positions, boolean reachedGoal, float distanceToGoal) {
            this.positions = positions;
            this.reachedGoal = reachedGoal;
            this.distanceToGoal = distanceToGoal;
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
    private final BlockPos.MutableBlockPos cursorA = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos cursorB = new BlockPos.MutableBlockPos();
    private int emptyChunks;

    private GoPathfinder(ClientLevel level, Goal goal, int maxFall) {
        this.level = level;
        this.goal = goal;
        this.maxFall = maxFall;
        this.fallTicks = buildFallTicks(maxFall + 4);
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
        for (int[] d : DIRS) {
            parkour(cur, d[0], d[1]);
        }
        climb(cur);
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
     * 路径自然绕开缺口或判定不可达。
     */
    private void parkour(Node cur, int dx, int dz) {
        if (!me.aleksilassila.litematica.printer.config.Configs.Special.GO_FORCE_SPRINT.getBooleanValue()) {
            return;
        }
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
     * 该格可通行：无碰撞且不是岩浆（水可通行，走水中动作有额外成本）。
     * 梯子/藤蔓等可攀爬方块按可通行处理——它们的碰撞体是贴墙薄片（原版未设
     * noCollission，getCollisionShape 返回薄片而非空），但对寻路而言攀爬格
     * 就是要走进/穿过的格子，按碰撞为空判定会把所有攀爬路线判死。
     */
    private boolean passable(int x, int y, int z) {
        BlockState state = level.getBlockState(cursorA.set(x, y, z));
        if (state.is(BlockTags.CLIMBABLE)) {
            return true;
        }
        if (!state.getCollisionShape(level, cursorA).isEmpty()) {
            return false;
        }
        return !state.getFluidState().is(FluidTags.LAVA);
    }

    /** (x,y,z) 脚位的下方方块是否有碰撞（可站立） */
    private boolean walkableFloor(int x, int y, int z) {
        BlockState below = level.getBlockState(cursorB.set(x, y - 1, z));
        return !below.getCollisionShape(level, cursorB).isEmpty();
    }

    private boolean waterAt(int x, int y, int z) {
        return level.getBlockState(cursorB.set(x, y, z)).getFluidState().is(FluidTags.WATER);
    }

    /** (x,y,z) 是否为可攀爬方块（梯子/藤蔓等） */
    private boolean climbableAt(int x, int y, int z) {
        return level.getBlockState(cursorB.set(x, y, z)).is(BlockTags.CLIMBABLE);
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
        return new Result(out, reachedGoal, end.h / SPRINT_COST);
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
