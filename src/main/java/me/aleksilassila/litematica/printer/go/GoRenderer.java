package me.aleksilassila.litematica.printer.go;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 寻路可视化：路径连线 + 终点方块描边 + 扫描候选/选中目标描边。
 * 通过 MixinDebugRenderer 在 vanilla DebugRenderer.emitGizmos 尾部借 gizmo 系统绘制
 *（1.21.11 与 26.1.2 均为原生 API，随 vanilla 调试渲染管线提交）。
 *
 * <p><b>扫描寻路的描边语义</b>（便于核对"最短路径优先"到底选了谁）：
 * <ul>
 * <li><b>黄色</b>＝本批参与竞争的全部候选（{@code AutoWalkScanner.getDispatchedCandidates()}）；</li>
 * <li><b>绿色</b>＝本批实际选中的目标（多目标腿 A* 一定稿即标绿，不必等到到达）；
 *     绿色缺省时看到的黄框就是"只看直线距离"的回退分支选的。</li>
 * </ul>
 * 这两类描边不依赖寻路是否激活（等待放置期间同样要能看出来选的是谁）。
 */
public final class GoRenderer {
    private static final int PATH_COLOR = 0xFF3DF53D;
    private static final int GOAL_COLOR = 0xFFFFD83D;
    /** 候选描边（黄）：本批参与"最短路径优先"竞争的全部候选 */
    private static final int CANDIDATE_COLOR = 0xFFFFD83D;
    /** 选中描边（绿）：本批实际选中的目标 */
    private static final int SELECTED_COLOR = 0xFF3DF53D;
    /** 候选描边上限：验证器候选可达上千，只画最近的一批防刷屏（与候选上限同量级） */
    private static final int MAX_CANDIDATE_BOXES = 256;

    private GoRenderer() {
    }

    public static void emitGizmos() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        // 扫描寻路的候选（黄）/选中目标（绿）：不依赖 GoManager 是否激活——到达后等待
        // 打印机放置期间任务已结束，但"选的是谁"仍要看得见
        BlockPos selected = GoManager.INSTANCE.isActive()
                ? GoManager.INSTANCE.getMultiGoalCurrentTarget()
                : null;
        if (selected == null) {
            selected = AutoWalkScanner.INSTANCE.getSelectedTarget();
        }
        int drawn = 0;
        for (BlockPos candidate : AutoWalkScanner.INSTANCE.getDispatchedCandidates()) {
            if (candidate.equals(selected)) {
                continue; // 选中的单独用绿框画
            }
            if (++drawn > MAX_CANDIDATE_BOXES) {
                break;
            }
            Gizmos.cuboid(new AABB(candidate), GizmoStyle.stroke(CANDIDATE_COLOR, 1.0F));
        }
        if (selected != null) {
            Gizmos.cuboid(new AABB(selected), GizmoStyle.stroke(SELECTED_COLOR, 2.0F));
        }

        if (!GoManager.INSTANCE.isActive()) {
            return; // 路径连线只在寻路进行时有意义
        }

        BlockPos goal = GoManager.INSTANCE.getGoal();
        if (goal != null) {
            if (!goal.equals(selected)) {
                Gizmos.cuboid(new AABB(goal), GizmoStyle.stroke(GOAL_COLOR, 2.0F));
            }
        } else {
            // 到达后等待放置：继续描边提示当前在等哪个方块
            BlockPos waiting = AutoWalkScanner.INSTANCE.getWaitingTarget();
            if (waiting != null && !waiting.equals(selected)) {
                Gizmos.cuboid(new AABB(waiting), GizmoStyle.stroke(GOAL_COLOR, 2.0F));
            }
        }

        List<BlockPos> path = GoManager.INSTANCE.getPath();
        int index = GoManager.INSTANCE.getWaypointIndex();
        // 起点必须是「导航主体」位置：乐魂飞行时为恶魂，走路时为玩家。
        // 若固定用玩家位置，飞行时起点会比路点基准高约 3.4 格，画出斜跨数格的线。
        Vec3 prev = GoManager.INSTANCE.navPosition();
        if (prev == null) {
            return;
        }
        for (int i = index; i < path.size(); i++) {
            Vec3 next = center(path.get(i));
            Gizmos.line(prev, next, PATH_COLOR);
            prev = next;
        }
    }

    private static Vec3 center(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.05, pos.getZ() + 0.5);
    }
}
