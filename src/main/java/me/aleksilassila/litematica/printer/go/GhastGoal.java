package me.aleksilassila.litematica.printer.go;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「乐魂寻路」的目标判定：<b>悬停位</b>（恶魂基准格的集合），替代走路版的"紧邻站立格"。
 *
 * <p><b>到达判定</b>（切比雪夫＝方形范围）：{@code max(|dx|, |dz|) <= R && |dy| <= 3}。
 * {@code R} 由并集箱水平半宽<b>动态推导</b>：箱是轴对齐的，"目标格被箱子罩住"
 * ⟺ {@code |dx| <= 半宽 && |dz| <= 半宽}  切比雪夫 ≤ 半宽；故须
 * {@code R >= ceil(半宽 + 0.5)} 才能把目标格排除在箱外。用户设定的 3 作为下限
 * （乐魂默认 4 宽 → 半宽 2 → 需 ≥2.5 → 取整 3，与设定吻合）。
 *
 * <p>注意：悬停位只保证"目标格不被箱体占用"这一几何条件；无线碰撞、原理图空气约束、
 * 玩家眼到目标 ≤ 交互距离等条件由 {@link GhastPathfinder} 的扫掠判定与派发侧共同保证。
 */
public final class GhastGoal {
    /** 垂直容差（格）：目标上下各允许 3 层 */
    public static final int VERT_TOLERANCE = 3;

    private GhastGoal() {
    }

    /** 悬停判定半径：由并集箱水平半宽动态推导（下限＝用户设定的 3） */
    public static int radius(double halfWidth) {
        return Math.max(3, (int) Math.ceil(halfWidth + 0.5));
    }

    /** 单目标悬停 Goal：恶魂基准格进入"目标周围 R 格"即到达 */
    public static GoPathfinder.Goal hoverGoal(BlockPos target, int r) {
        int tx = target.getX();
        int ty = target.getY();
        int tz = target.getZ();
        return new GoPathfinder.Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                // 与 hoverGoalSet 一致：排除目标格本身（站进去会挡住打印机放置）
                if (x == tx && y == ty && z == tz) {
                    return false;
                }
                return Math.max(Math.abs(x - tx), Math.abs(z - tz)) <= r
                        && Math.abs(y - ty) <= VERT_TOLERANCE;
            }

            @Override
            public float heuristic(int x, int y, int z) {
                // 到"悬停盒"的欧氏距离：实际成本 ≥ 几何距离 ≥ 到盒距离 → 可采纳
                double dx = Math.max(0, Math.abs(x - tx) - r);
                double dy = Math.max(0, Math.abs(y - ty) - VERT_TOLERANCE);
                double dz = Math.max(0, Math.abs(z - tz) - r);
                return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        };
    }

    /**
     * 多目标悬停 GoalSet（对应走路版 {@code GoPathfinder.GoalSet}）：把全部候选展开成
     * 各自的悬停位集合，第一个被搜索定稿的即"路径成本最短"的候选——选目标与算路径一次完成。
     *
     * <p>启发值 = min over 候选分桶的"节点到桶包围盒（候选外扩 R 格/上下 3 格）的欧氏距离"，
     * 包围盒距离不高于盒内任意悬停位的真实距离，可采纳且一致。
     */
    public static final class HoverGoalSet implements GoPathfinder.Goal {
        /** 悬停位 → 所属候选（相邻候选共享悬停位时先到先得） */
        private final Long2ObjectOpenHashMap<BlockPos> cellToTarget;
        private final LongOpenHashSet cells;
        private final Bucket[] buckets;

        private HoverGoalSet(Long2ObjectOpenHashMap<BlockPos> cellToTarget, Bucket[] buckets) {
            this.cellToTarget = cellToTarget;
            this.cells = new LongOpenHashSet(cellToTarget.keySet());
            this.buckets = buckets;
        }

        /** 悬停位 → 候选方块映射（供派发方在到达后反查是哪个候选被选中） */
        public Long2ObjectOpenHashMap<BlockPos> cellToTarget() {
            return cellToTarget;
        }

        @Override
        public boolean isInGoal(int x, int y, int z) {
            return cells.contains(BlockPos.asLong(x, y, z));
        }

        @Override
        public float heuristic(int x, int y, int z) {
            float best = Float.MAX_VALUE;
            for (Bucket b : buckets) {
                double dx = Math.max(Math.max(b.loX - x, x - b.hiX), 0);
                double dy = Math.max(Math.max(b.loY - y, y - b.hiY), 0);
                double dz = Math.max(Math.max(b.loZ - z, z - b.hiZ), 0);
                float h = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (h < best) {
                    best = h;
                }
            }
            return best;
        }
    }

    /** 候选分桶包围盒（按 16³ 子区块分组，已外扩 R 格/上下 3 格） */
    private static final class Bucket {
        int loX = Integer.MAX_VALUE;
        int loY = Integer.MAX_VALUE;
        int loZ = Integer.MAX_VALUE;
        int hiX = Integer.MIN_VALUE;
        int hiY = Integer.MIN_VALUE;
        int hiZ = Integer.MIN_VALUE;
    }

    /**
     * 构造多目标悬停集合：候选按离 from 的直线距离预截 limit 个（直线距离只作预筛，
     * 最短目标仍由路径成本裁决），再展开为悬停位集合与分桶包围盒。
     */
    public static HoverGoalSet hoverGoalSet(List<BlockPos> targets, int limit, BlockPos from, int r) {
        ArrayList<BlockPos> list = new ArrayList<>(targets);
        if (list.size() > limit) {
            list.sort(Comparator.comparingDouble(p -> p.distSqr(from)));
            while (list.size() > limit) {
                list.remove(list.size() - 1);
            }
        }
        Long2ObjectOpenHashMap<BlockPos> cellToTarget = new Long2ObjectOpenHashMap<>(list.size() * 352);
        Map<Long, Bucket> bucketMap = new HashMap<>();
        for (BlockPos t : list) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -VERT_TOLERANCE; dy <= VERT_TOLERANCE; dy++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue; // 目标格本身不是悬停位（站进去会挡住打印机放置）
                        }
                        cellToTarget.putIfAbsent(
                                BlockPos.asLong(t.getX() + dx, t.getY() + dy, t.getZ() + dz), t);
                    }
                }
            }
            long sk = BlockPos.asLong(t.getX() >> 4, t.getY() >> 4, t.getZ() >> 4);
            Bucket b = bucketMap.computeIfAbsent(sk, k -> new Bucket());
            b.loX = Math.min(b.loX, t.getX() - r);
            b.loY = Math.min(b.loY, t.getY() - VERT_TOLERANCE);
            b.loZ = Math.min(b.loZ, t.getZ() - r);
            b.hiX = Math.max(b.hiX, t.getX() + r);
            b.hiY = Math.max(b.hiY, t.getY() + VERT_TOLERANCE);
            b.hiZ = Math.max(b.hiZ, t.getZ() + r);
        }
        return new HoverGoalSet(cellToTarget, bucketMap.values().toArray(new Bucket[0]));
    }
}