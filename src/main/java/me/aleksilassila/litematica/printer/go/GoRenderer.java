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
 * 寻路可视化：路径连线 + 终点方块描边。
 * 通过 MixinDebugRenderer 在 vanilla DebugRenderer.emitGizmos 尾部借 gizmo 系统绘制
 *（1.21.11 与 26.1.2 均为原生 API，随 vanilla 调试渲染管线提交）。
 */
public final class GoRenderer {
    private static final int PATH_COLOR = 0xFF3DF53D;
    private static final int GOAL_COLOR = 0xFFFFD83D;

    private GoRenderer() {
    }

    public static void emitGizmos() {
        if (!GoManager.INSTANCE.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }

        BlockPos goal = GoManager.INSTANCE.getGoal();
        if (goal != null) {
            Gizmos.cuboid(new AABB(goal), GizmoStyle.stroke(GOAL_COLOR, 2.0F));
        } else {
            // 到达后等待放置：继续描边提示当前在等哪个方块
            BlockPos waiting = AutoWalkScanner.INSTANCE.getWaitingTarget();
            if (waiting != null) {
                Gizmos.cuboid(new AABB(waiting), GizmoStyle.stroke(GOAL_COLOR, 2.0F));
            }
        }

        List<BlockPos> path = GoManager.INSTANCE.getPath();
        int index = GoManager.INSTANCE.getWaypointIndex();
        Vec3 prev = player.position();
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
