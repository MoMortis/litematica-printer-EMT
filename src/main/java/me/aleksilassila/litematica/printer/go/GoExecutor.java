package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.mc.ClientInputAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec2;

import java.util.List;

/**
 * 寻路执行器：把当前路点方向换算成与相机朝向无关的移动输入，
 * 通过覆写 {@code ClientInput.keyPresses} 与 {@code moveVector} 驱动玩家
 * 前进/左右/后退/跳跃/冲刺——不改变客户端视角，不挖掘不放置。
 * 在 {@code LocalPlayer.applyInput()} HEAD 调用（{@link me.aleksilassila.litematica.printer.mixin.printer.mc.MixinLocalPlayerGo}）。
 */
public final class GoExecutor {
    private static final double ARRIVE_DIST_SQ = 0.45 * 0.45;

    private GoExecutor() {
    }

    public static void onApplyInput(LocalPlayer player) {
        if (!GoManager.INSTANCE.isActive()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player != player || player.isPassenger()) {
            return;
        }
        // 寻路假设地面行走：飞行/旁观/睡觉时不接管输入（保持激活，落地自动恢复驱动）
        if (player.getAbilities().flying || player.isSpectator() || player.isSleeping()) {
            return;
        }
        // 不越权透传：shift 保留键盘/打印已写入的现状（原版 KeyboardInput 本就每 tick 从物理键盘重写），
        // 寻路自身绝不主动潜行，对打印的潜行流程零干预
        boolean shift = player.input.keyPresses.shift();
        BlockPos wp = GoManager.INSTANCE.getWaypoint();
        if (wp == null) {
            writeInput(player, 0.0F, 0.0F, false, false, shift);
            return;
        }

        double dx = (wp.getX() + 0.5) - player.getX();
        double dz = (wp.getZ() + 0.5) - player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1.0E-3) {
            writeInput(player, 0.0F, 0.0F, false, false, shift);
            return;
        }
        dx /= hDist;
        dz /= hDist;

        // 期望世界方向 → 相机系移动向量（yaw=0 面向 +Z：strafe+=左，forward+=前）
        float yawRad = player.getYRot() * (float) (Math.PI / 180.0);
        float sin = (float) Math.sin(yawRad);
        float cos = (float) Math.cos(yawRad);
        float strafe = (float) (dx * cos + dz * sin);
        float forward = (float) (dz * cos - dx * sin);

        int feetY = player.getBlockY();
        boolean jump = false;
        if (player.onGround() && wp.getY() > feetY && hDist * hDist < 1.8 * 1.8) {
            jump = true; // 跳上型路点
        }
        if (player.isInWater() && wp.getY() >= feetY) {
            jump = true; // 水中保持上浮游动
        }
        boolean sprint = shouldSprint(player, hDist);
        writeInput(player, strafe, forward, jump, sprint, shift);
    }

    /** 平坦路段冲刺：当前与随后路点同层且距离足够远 */
    private static boolean shouldSprint(LocalPlayer player, double hDist) {
        if (!Configs.Special.GO_SPRINT.getBooleanValue()) {
            return false;
        }
        if (!player.onGround() || player.isInWater()) {
            return false;
        }
        if (player.getFoodData().getFoodLevel() <= 6) {
            return false;
        }
        if (hDist * hDist < 1.5 * 1.5) {
            return false; // 临近路点收步
        }
        List<BlockPos> path = GoManager.INSTANCE.getPath();
        int i = GoManager.INSTANCE.getWaypointIndex();
        int n = path.size();
        if (i >= n) {
            return false;
        }
        int y = path.get(i).getY();
        if (i + 1 < n && path.get(i + 1).getY() != y) {
            return false;
        }
        return i + 2 >= n || path.get(i + 2).getY() == y;
    }

    /**
     * 覆写玩家输入：keyPresses 供 vanilla 同步服务端（ServerboundPlayerInputPacket）并驱动跳跃/冲刺，
     * moveVector（strafe, forward）为模拟量，方向与相机无关。
     * shift 位由调用方透传现状，本方法不做任何潜行决策。
     */
    private static void writeInput(LocalPlayer player, float strafe, float forward, boolean jump, boolean sprint, boolean shift) {
        player.input.keyPresses = new net.minecraft.world.entity.player.Input(
                forward > 0.05F, forward < -0.05F, strafe > 0.05F, strafe < -0.05F, jump, shift, sprint);
        ((ClientInputAccessor) player.input).printer$setMoveVector(new Vec2(strafe, forward));
    }
}
