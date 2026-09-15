package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * 「乐魂寻路」的飞行控制律。
 *
 * <p>把三维路径当作分段来驱动（恶魂的移动方向由骑乘者视线决定，见
 * {@code HappyGhast.getRiddenInput}）：
 * <ul>
 * <li><b>水平段</b>：{@code pitch = 0} + W 朝目标水平飞；目标更高时叠加空格上升
 *     （空格与视线解耦，可与水平移动同时生效）；</li>
 * <li><b>上升段</b>：空格——唯一有专用键的方向，且不受视线影响；</li>
 * <li><b>下降段</b>：{@code pitch = +90°} + W。源码 {@code forward = cos(xRot)}、
 *     {@code up = -sin(xRot)}，只有低头到底才是纯垂直下降；此时水平分量为 0，
 *     <b>无法同时水平移动</b>，故只在水平到位之后才下降；</li>
 * <li><b>到位</b>：松开输入（推力归零，靠 0.91/tick 阻尼自然停稳）。</li>
 * </ul>
 *
 * <p><b>强制接管视角</b>：恶魂的升降由玩家 {@code xRot} 决定、身体朝向跟随玩家 yaw，
 * 不改视线就无法导航，因此飞行期间<b>无视</b>「接管视角」「角度偏转」设置，始终接管
 * yaw + pitch。
 *
 * <p><b>shift 恒为 false</b>：骑乘时按潜行键＝下马，绝不能主动产生；
 * 需要 shift 的放置由打印机侧跳过（见 PrintHandler 的骑乘限制）。
 */
public final class GhastFlyer {
    /** 水平接近段判定：水平距离小于该值即视为"水平到位"，转入垂直处理 */
    private static final double HORIZ_ARRIVE = 1.6;
    /** 垂直死区：高度差小于该值不主动升降，避免抖动 */
    private static final double VERT_DEADZONE = 1.0;
    /** 下降俯仰角：低头到底才是纯垂直下降（cos90° = 0、−sin90° = −1） */
    private static final float DESCEND_PITCH = 90.0F;
    /**
     * 朝向对准阈值（度）：|目标朝向 − 恶魂身体朝向| 超过该值就先只转不飞。
     *
     * <p>必要性：恶魂的位移方向由<b>身体朝向</b>决定（{@code Entity.moveRelative} 用实体 yRot），
     * 而身体按 {@code 0.08/tick} 平滑跟随视线。若边飞边转，实际轨迹是一条弧线，而控制律
     * 每 tick 按"当前位置→路点"重算方向，就形成"追点振荡"——表现为绕着当前路点转圈
     * （转向滞后是固定比例速率，与绝对速度无关，故降速也无法消除）。先原地转、对准再飞
     * 可从根本上消除这条弧线。
     */
    private static final float TURN_ALIGN_DEGREES = 40.0F;

    /** 飞行期间是否由我们接管过 pitch（用于停止接管时回正，见 {@link #releasePitch}） */
    private static boolean pitchOwned;

    private GhastFlyer() {
    }

    /**
     * 驱动一 tick 飞行（在 {@code LocalPlayer.applyInput} 注入点、主线程调用）。
     * 无目标路点时不做任何驱动（保持悬停与视角不动）。
     */
    public static void drive(LocalPlayer player) {
        BlockPos wp = GoManager.INSTANCE.getWaypoint();
        // 导航主体是<b>恶魂</b>：A* 节点、路点、到达判定一律以恶魂基准格为准。
        // 若改用玩家坐标会整体偏移——玩家比恶魂高约 3.4 格、水平还偏约 1.7 格（随朝向旋转）。
        Entity nav = player.getVehicle();
        if (wp == null || nav == null) {
            // 无路点/无载具：写零输入，避免残留上一 tick 的推进量（走路分支停止时也是写零）
            GoExecutor.writeInput(player, 0.0F, 0.0F, false, false, false);
            return;
        }
        double dx = wp.getX() + 0.5 - nav.getX();
        double dz = wp.getZ() + 0.5 - nav.getZ();
        double dy = wp.getY() + 0.5 - nav.getY();
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = player.getYRot();
        float pitch = 0.0F;
        float forward = 0.0F;
        boolean jump = false;

        boolean aligned = true;
        if (horiz > HORIZ_ARRIVE) {
            // 水平接近：朝目标平飞；目标更高时叠加空格上升（与视线解耦，可同时水平移动）
            yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            pitch = 0.0F;
            // 朝向未对准则先只转不飞：恶魂移动方向取决于身体朝向（见 TURN_ALIGN_DEGREES 注释），
            // 边飞边转会画出弧线并触发追点振荡。原地转向照常进行（tickRidden 一直生效）。
            aligned = Math.abs(Mth.wrapDegrees(yaw - nav.getYRot())) <= TURN_ALIGN_DEGREES;
            forward = aligned ? 1.0F : 0.0F;
            jump = aligned && dy > VERT_DEADZONE;
        } else if (dy > VERT_DEADZONE) {
            // 水平已到位 → 纯上升（空格）
            jump = true;
        } else if (dy < -VERT_DEADZONE) {
            // 水平已到位 → 低头到底 + W 纯垂直下降
            pitch = DESCEND_PITCH;
            forward = 1.0F;
        }
        // 其余情况＝到位：forward/jump 保持 0，松开后靠阻尼停稳

        player.setYRot(yaw);
        player.setXRot(pitch);
        pitchOwned = true;
        // 「强制疾跑」按配置照常请求（恶魂无疾跑加成，仅为状态一致）；
        // shift 恒 false（= 下马键，骑乘期间绝不能主动产生）
        boolean sprint = Configs.Go.GO_FORCE_SPRINT.getBooleanValue();
        GoExecutor.writeInput(player, 0.0F, forward, jump, sprint, false);
    }

    /**
     * 飞行接管结束时归还俯仰角：飞行期间我们把 pitch 打到 90° 做纯垂直下降，
     * 若停止接管后不回正，玩家按 W 会继续朝下飞。
     */
    public static void releasePitch(LocalPlayer player) {
        if (pitchOwned) {
            player.setXRot(0.0F);
            pitchOwned = false;
        }
    }
}