package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

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

    /** 脱困飞行的持续 tick：够飞出一个箱位并让惯性稳定下来 */
    private static final int ESCAPE_TICKS = 24;
    /** 脱困方向探测位移（格）：箱子挪到该处仍无碰撞、也不压原理图方块，才认为"这个方向走得通" */
    private static final double ESCAPE_PROBE = 3.0;
    /**
     * 脱困的飞行目标距离（格）：比"松手误差"（水平 {@link #HORIZ_ARRIVE} 格、垂直 1 格）大一点就够，
     * 真正收工看的是"已脱离重叠 + 至少挪开 {@link #ESCAPE_MIN_MOVE} 格"（见 {@link #drive}），
     * 不必飞太远。
     */
    private static final double ESCAPE_DRIVE = 3.0;
    /** 脱困收工的"最小挪动"（格）：已不再压住方块、且至少挪开这么多才收工。
     *  不设下限的话，箱体挪 0.1 格就可能判"已脱离"→ 立刻停 → 下次检查又压上 → 反复抖 */
    private static final double ESCAPE_MIN_MOVE = 1.0;
    /** 越界检查间隔（tick）：核对「乐魂 + 骑乘者」并集箱有没有压在"原理图预测非空气"的格子上。
     *  寻路只管规划出来的格位与边，实际飞行会被惯性、脱困推力带偏，偏出去的落点无人把关——
     *  那边现实里没有碰撞（方块还没放上去），乐魂会直接钻进建筑内部 */
    private static final int SCHEMATIC_CHECK_INTERVAL_TICKS = 2;

    /** 脱困剩余 tick（0 = 未脱困） */
    private static int escapeTicks;
    /** 脱困起点与方向（世界系单位向量）；卡住时朝它飞一小段，越过阻塞点后自动失效 */
    private static double escapeX;
    private static double escapeY;
    private static double escapeZ;
    private static double escapeDirX;
    private static double escapeDirY;
    private static double escapeDirZ;

    /** 飞行期间是否由我们接管过 pitch（用于停止接管时回正，见 {@link #releasePitch}） */
    private static boolean pitchOwned;
    /** 下一次"原理图越界检查"的倒计时（tick） */
    private static int schematicCheckCountdown;

    private GhastFlyer() {
    }

    /**
     * 驱动一 tick 飞行（在 {@code LocalPlayer.applyInput} 注入点、主线程调用）。
     * 无目标路点时不做任何驱动（保持悬停与视角不动）；处于脱困状态时以脱困目标点为准。
     */
    public static void drive(LocalPlayer player) {
        // 导航主体是<b>恶魂</b>：A* 节点、路点、到达判定一律以恶魂基准格为准。
        // 若改用玩家坐标会整体偏移——玩家比恶魂高约 3.4 格、水平还偏约 1.7 格（随朝向旋转）。
        Entity nav = player.getVehicle();
        if (nav == null) {
            escapeTicks = 0; // 无载具（下马/换乘）：脱困状态作废
            GoExecutor.writeInput(player, 0.0F, 0.0F, false, false, false);
            return;
        }
        // 每 2 拍核对一次"并集箱是否压在原理图方块上"：压上了就朝空阔方向蹭出去
        //（脱困方向探测带原理图约束，不会又选到方块里）。正在脱困时不打断。
        // 只在打印机处于打印模式工作时才做——总开关关掉后不该再自作主张地动乐魂。
        if (--schematicCheckCountdown <= 0) {
            schematicCheckCountdown = SCHEMATIC_CHECK_INTERVAL_TICKS;
            if (escapeTicks <= 0 && ConfigUtils.isPrintModeActive() && boxHitsSchematic(player, nav)) {
                tryEscape(player);
            }
        }
        // 脱困进行中：先判能不能收工，再决定本 tick 的驱动目标
        if (escapeTicks > 0) {
            escapeTicks--;
            double moved = Math.abs(nav.getX() - escapeX) + Math.abs(nav.getY() - escapeY)
                    + Math.abs(nav.getZ() - escapeZ);
            // 「够离开就行」：已不再压住方块、且至少挪开 ESCAPE_MIN_MOVE 格就收工；否则跑满时长。
            // 单看"不再重叠"不行——箱体挪 0.1 格就可能不压那一格，脱困会立刻结束、下次检查又压上（来回抖）。
            if (escapeTicks == 0 || (!boxHitsSchematic(player, nav) && moved >= ESCAPE_MIN_MOVE)) {
                escapeTicks = 0;
            }
        }
        double targetX;
        double targetY;
        double targetZ;
        if (escapeTicks > 0) {
            // 脱困：临时目标点＝脱困起点 + 脱困方向 × 脱困飞行距离（见 ESCAPE_DRIVE）
            targetX = escapeX + escapeDirX * ESCAPE_DRIVE;
            targetY = escapeY + escapeDirY * ESCAPE_DRIVE;
            targetZ = escapeZ + escapeDirZ * ESCAPE_DRIVE;
        } else {
            BlockPos wp = GoManager.INSTANCE.getWaypoint();
            if (wp == null) {
                // 无路点：写零输入，避免残留上一 tick 的推进量（走路分支停止时也是写零）
                GoExecutor.writeInput(player, 0.0F, 0.0F, false, false, false);
                return;
            }
            targetX = wp.getX() + 0.5;
            targetY = wp.getY() + 0.5;
            targetZ = wp.getZ() + 0.5;
        }
        double dx = targetX - nav.getX();
        double dz = targetZ - nav.getZ();
        double dy = targetY - nav.getY();
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
     * 「待命期」的飞行维护：寻路没在跑（等待放置、两条腿之间的空隙）时调用。
     *
     * <p>与 {@link #drive} 的区别：<b>没有脱困需求时完全不写任何输入</b>——所以玩家手动骑乐魂飞
     * 不会被"无路点就写零输入"抹掉；只有箱体压住原理图方块、需要蹭出去时才临时接管一程。
     *
     * <p>为什么需要它：寻路一旦停止，{@code GoExecutor.onApplyInput} 就不再调用 {@link #drive}，
     * 于是脱困会被中途掐断（实测"只上升一半就停"，连收尾日志都打不出来）；而"压住方块"这件事
     * 与寻路是否在跑毫无关系，必须在待命期继续维护。
     */
    public static void driveStandby(LocalPlayer player) {
        Entity nav = player.getVehicle();
        if (nav == null) {
            escapeTicks = 0; // 无载具（下马/换乘）：脱困状态作废
            return;
        }
        if (escapeTicks <= 0) {
            if (--schematicCheckCountdown <= 0) {
                schematicCheckCountdown = SCHEMATIC_CHECK_INTERVAL_TICKS;
                if (ConfigUtils.isPrintModeActive() && boxHitsSchematic(player, nav)) {
                    tryEscape(player);
                }
            }
        }
        if (escapeTicks > 0) {
            drive(player); // 正在脱困：借正常驱动按脱困目标点飞（此时 drive 里不看路点）
        }
    }

    /** 当前「乐魂 + 骑乘者」并集箱是否压在"原理图预测非空气"的格子上（主线程，口径与寻路一致） */
    private static boolean boxHitsSchematic(LocalPlayer player, Entity nav) {
        GhastPathfinder.BoxSpec spec = GhastPathfinder.BoxSpec.of(nav, player);
        return GhastPathfinder.boxHitsSchematic(spec.at(nav.getX(), nav.getY(), nav.getZ()));
    }

    /**
     * 卡住脱困：挑一个"并集箱放得下"的方向，把控制律的临时目标点挪过去飞一小段。
     *
     * <p><b>为什么需要</b>：路线贴墙时，前进方向上箱子正好被方块挡住，推力全被碰撞吃掉，
     * 表现为原地不动；此时原地重算只会得到同一条贴墙路线（起点没变），偏航纠正也因转向
     * 后仍被挡而卡死。朝"另一个能容下箱子的方向"飞一段，就能把乐魂挪出阻塞点。
     *
     * <p>方向按优先级探测（箱子挪到 {@link #ESCAPE_PROBE} 格外仍无碰撞、且不压原理图方块才选中）：
     * 正上 → 斜上侧向 → 正侧向 → 斜上后方 → 正后 → 正下。优先向上是因为贴墙时上方
     * 通常开阔，且上升键（空格）与视线解耦、最可控。选中后朝 {@link #ESCAPE_DRIVE} 格外飞：
     * 驱动距离必须大于探测距离，否则控制律在 1.6 格内就松手，实际只挪出去一点点（仍压着方块）。
     *
     * @return 是否成功进入脱困状态（无可用方向时返回 false，交由调用方重算路径）
     */
    public static boolean tryEscape(LocalPlayer player) {
        Entity nav = player.getVehicle();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (nav == null || level == null) {
            return false;
        }
        GhastPathfinder.BoxSpec spec = GhastPathfinder.BoxSpec.of(nav, player);
        double x = nav.getX();
        double y = nav.getY();
        double z = nav.getZ();
        // 朝路点的方向＝"被堵住"的方向；脱困方向以它为参照取侧向/后方/上方
        double ax = 1.0;
        double az = 0.0;
        BlockPos wp = GoManager.INSTANCE.getWaypoint();
        if (wp != null) {
            double wx = wp.getX() + 0.5 - x;
            double wz = wp.getZ() + 0.5 - z;
            double len = Math.sqrt(wx * wx + wz * wz);
            if (len > 1.0E-3) {
                ax = wx / len;
                az = wz / len;
            }
        }
        double sx = -az; // 侧向（左）
        double sz = ax;
        double[][] candidates = {
                {0.0, 1.0, 0.0},          // 正上
                {sx, 0.7, sz},            // 斜上侧向
                {-sx, 0.7, -sz},
                {sx, 0.0, sz},            // 正侧向
                {-sx, 0.0, -sz},
                {-ax, 0.7, -az},          // 斜上后方
                {-ax, 0.0, -az},          // 正后（原路退回）
                {0.0, -1.0, 0.0}          // 正下（最后手段）
        };
        for (double[] c : candidates) {
            double len = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]);
            double dirX = c[0] / len;
            double dirY = c[1] / len;
            double dirZ = c[2] / len;
            double probeX = x + dirX * ESCAPE_PROBE;
            double probeY = y + dirY * ESCAPE_PROBE;
            double probeZ = z + dirZ * ESCAPE_PROBE;
            if (!BlockStateUtils.isColumnLoaded(level, Mth.floor(probeX) >> 4, Mth.floor(probeZ) >> 4)) {
                continue; // 未加载区块：飞进去拿不到地形，放弃该方向
            }
            AABB probe = spec.at(probeX, probeY, probeZ);
            if (!level.noCollision(probe)) {
                continue;
            }
            // 原理图一侧：脱困方向同样不能钻进"现实里还是空气、原理图却排了方块"的格子
            //（那边没有真实碰撞，只看 noCollision 会一路蹭进建筑内部）
            if (GhastPathfinder.boxHitsSchematic(probe)) {
                continue;
            }
            escapeX = x;
            escapeY = y;
            escapeZ = z;
            escapeDirX = dirX;
            escapeDirY = dirY;
            escapeDirZ = dirZ;
            escapeTicks = ESCAPE_TICKS;
            return true;
        }
        return false;
    }

    /**
     * 飞行接管结束时归还俯仰角：飞行期间我们把 pitch 打到 90° 做纯垂直下降，
     * 若停止接管后不回正，玩家按 W 会继续朝下飞。
     */
    public static void releasePitch(LocalPlayer player) {
        escapeTicks = 0; // 停止寻路：脱困状态不再保留
        if (pitchOwned) {
            player.setXRot(0.0F);
            pitchOwned = false;
        }
    }
}