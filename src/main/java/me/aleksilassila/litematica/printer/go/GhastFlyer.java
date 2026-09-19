package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.SchematicStateCache;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「乐魂寻路」的飞行控制律（视角只允许 6 个正方向，其余靠 WASD+空格组合完成）。
 *
 * <p>恶魂的移动方向由骑乘者视线决定（{@code HappyGhast.getRiddenInput}：
 * {@code forward = cos(xRot)}、{@code up = -sin(xRot)}、空格 up += 0.5）：
 * <ul>
 * <li><b>视角只取六个正方向</b>：水平朝向就近吸附到 4 个正方向（0°/90°/180°/270°），
 *     纯升降段才仰/低头到 ±90°，俯仰不取中间值；</li>
 * <li><b>水平段</b>：方位角偏差（≤45°）折算成侧移键（|偏差| ≤22.5° 只按 W，否则 W 叠加
 *     一个侧移键，原版归一化后斜向组合与单键同速）；目标更高时叠加空格斜升；</li>
 * <li><b>纯上升段</b>：仰视到底 + W——垂直分量 1.0 全推力，终速是空格的两倍，
 *     与下降完全对称；空格只用于水平移动时的斜升组合；</li>
 * <li><b>纯下降段</b>：低头到底 + W，水平分量为 0，只在水平到位后下降；</li>
 * <li><b>每 gt 速度闭环</b>：末端刹车区按"朝目标分速度"收放油门（高速反推 S 刹车、
 *     滑行中松手、停稳未到位恢复正推蠕进），垂直段按下落/升速收油门防冲过头；</li>
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
    /** 上升仰角：仰到底 + W，垂直分量 = −sin(−90°) = 1.0 全推力——终速是空格（+0.5）
     *  的两倍，与下降完全对称；空格只留给"水平移动同时斜升"的组合场景 */
    private static final float ASCEND_PITCH = -90.0F;
    /**
     * 朝向对准阈值（度）：|目标朝向 − 恶魂身体朝向| 超过该值就先只转不飞。
     * 身体朝向按 0.08/tick 平滑跟随视线，边飞边转实际轨迹是弧线（追点振荡）；
     * 吸附后误差最多 45°，收敛到该门限只需 1~2 tick，停顿代价极小。
     */
    private static final float TURN_ALIGN_DEGREES = 40.0F;
    /**
     * 末端刹车区（格）：瞄准点已是最后路点、且水平距离进入该范围后，按"当前速度滑行距离
     * 是否超过剩余距离"动态判定要不要反向刹车（S）。固定阈值版会在低速形成极限环。
     */
    private static final double BRAKE_ZONE = 3.0;
    /** 垂直滑行判定：剩余高度 < 当前升降速度 × 该系数 + 垂直死区时松手滑行
     *  （0.91 阻尼下纯滑行距离 ≈ 10.1 × 速度，取 9 留余量），防升降冲过头 */
    private static final double VERT_COAST_FACTOR = 9.0;
    /** 脱困飞行的持续 tick：够先转过身（身体朝向平滑跟随视线，约 10 tick）再飞出一个箱位
     *  并让惯性稳定下来。24 tick 时转身吃掉近半，推力段只剩约 1.5 格，挪不到 1 格就判失败 */
    private static final int ESCAPE_TICKS = 30;
    /** 脱困方向探测位移（格）：箱子挪到该处仍无碰撞、也不压原理图方块，才认为"这个方向走得通" */
    private static final double ESCAPE_PROBE = 3.0;
    /** 脱困方向探测的沿途取样步长（格）：只查终点那一个箱位会漏掉"途中有棱角"——
     *  箱子挪到 3 格外放得下，路上却可能蹭上方块（恶魂箱体 4×4，拐角处最容易） */
    private static final double ESCAPE_PROBE_STEP = 1.0;
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
    /**
     * 脱困档位（0 = 未脱困）：1 = 常规（终点不压原理图方块）；
     * 2 = 二档（全部方向被原理图约束否决时，终点允许压原理图方块、只查真实碰撞，
     * 并选终点最开阔的方向——困在结构内部腔体时一档永远无解，只能飞进"原理图预留空间"
     * 逃出去；二档期间打印机与扫描器暂停，见 {@link #isEscapingTier2()}）。
     */
    private static int escapeTier;
    /** 脱困起点与方向（世界系单位向量）；卡住时朝它飞一小段，越过阻塞点后自动失效 */
    private static double escapeX;
    private static double escapeY;
    private static double escapeZ;
    private static double escapeDirX;
    private static double escapeDirY;
    private static double escapeDirZ;
    /**
     * 上一次"跑满仍未脱离"的失败方向环形缓冲（单位向量）：选向时跳过。方向表是确定性顺序，
     * 几何不变时每次重探都会再选中同一个飞不动/逃不出的方向，一轮空转。
     *
     * <p><b>必须多槽</b>：单槽记忆会被两个都失败的方向轮流覆盖（#2 失败记 #2 → #5 失败
     * 覆盖成 #5 → #2 又被选中……），实测日志里表现为两个方向无限乒乓、卡死 17 秒。
     */
    private static final int FAILED_DIRECTION_SLOTS = 8;
    private static final double[][] failedDirs = new double[FAILED_DIRECTION_SLOTS][3];
    /** 各失败方向记录的过期游戏刻（不含）：过期后允许重试（世界几何可能已变化） */
    private static final long[] failedDirExpire = new long[FAILED_DIRECTION_SLOTS];
    private static int failedDirCursor;
    /** 失败方向记忆的有效期（tick） */
    private static final int FAILED_DIRECTION_TTL_TICKS = 100;

    /**
     * 一档脱困连续失败次数（任意档成功即清零，二档失败也清零换回一档）。
     * 连续失败说明"探测能过、真飞不动"——再按一档选向只是重复失败，
     * 达到阈值后跳过一档直接按二档（开阔度）选向。
     */
    private static int tier1FailStreak;
    /** 一档连续失败多少次后升二档 */
    private static final int TIER2_AFTER_TIER1_FAILS = 3;

    /** 飞行期间是否由我们接管过 pitch（用于停止接管时回正，见 {@link #releasePitch}） */
    private static boolean pitchOwned;
    /** 下一次"原理图越界检查"的倒计时（tick） */
    private static int schematicCheckCountdown;

    private GhastFlyer() {
    }

    /** 是否正处于脱困飞行（供节点超时等外部逻辑停表：脱困期间乐魂被有意开离路径） */
    public static boolean isEscaping() {
        return escapeTicks > 0;
    }

    /** 是否正处于<b>二档</b>脱困（终点允许压原理图方块的逃生飞行）：打印机与扫描器据此暂停 */
    public static boolean isEscapingTier2() {
        return escapeTicks > 0 && escapeTier == 2;
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
            escapeTier = 0;
            GoExecutor.writeInput(player, 0.0F, 0.0F, false, false, false);
            return;
        }
        // 每 2 拍核对一次"并集箱是否压在原理图方块上"：压上了就朝空阔方向蹭出去
        //（脱困探测要求终点箱体不压原理图方块，不会逃进建筑内部；沿途只查真实碰撞）。
        // 正在脱困时不打断。
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
            // 「够离开就行」：常规档要求"不再压住方块 + 至少挪开 ESCAPE_MIN_MOVE 格"；
            // 二档本来就是逃进"原理图预留空间"，压不压原理图不作要求，只看挪开距离。
            // 单看"不再重叠"不行——箱体挪 0.1 格就可能不压那一格，脱困会立刻结束、下次检查又压上（来回抖）。
            boolean tier2 = escapeTier == 2;
            if ((tier2 || !boxHitsSchematic(player, nav)) && moved >= ESCAPE_MIN_MOVE) {
                escapeTicks = 0;
                escapeTier = 0;
                tier1FailStreak = 0;
                clearFailedDirection(); // 该方向被证实可行：失败记录作废
                // 抛弃原路线：自动腿静默结束（扫描器重扫选新目标、派新腿），
                // 手动腿从当前位置重算到原目标的路线
                GoManager.INSTANCE.onEscapeSucceeded();
            } else if (escapeTicks == 0) {
                // 跑满仍未脱离（推力被碰撞吃掉/重叠逃不出去）：记住该方向，选向时跳过
                escapeTicks = 0;
                escapeTier = 0;
                if (tier2) {
                    tier1FailStreak = 0; // 二档也没逃出去：换回一档再试一轮
                } else {
                    tier1FailStreak++;
                }
                ClientLevel lvl = Minecraft.getInstance().level;
                if (lvl != null) {
                    markFailedDirection(lvl.getGameTime());
                }
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

        // 每 gt 的速度闭环：位置决定推哪个方向，速度决定收放油门/是否刹车
        Vec3 vel = nav.getDeltaMovement();
        List<BlockPos> pathList = GoManager.INSTANCE.getPath();
        boolean finalLeg = !pathList.isEmpty()
                && GoManager.INSTANCE.getWaypointIndex() >= pathList.size() - 1;

        float yaw = player.getYRot();
        // 六向视角：水平朝向只允许 4 个正方向（俯仰两端由纯升降段设置）
        yaw = Mth.wrapDegrees(Math.round(yaw / 90.0F) * 90.0F);
        float pitch = 0.0F;
        float forward = 0.0F;
        float strafe = 0.0F;
        boolean jump = false;

        boolean aligned = true;
        if (horiz > HORIZ_ARRIVE) {
            // 水平接近：视角就近吸附到 4 个水平正方向，与目标方位角的偏差（≤45°）折算成侧移键——
            // |偏差| ≤22.5° 只按 W，否则 W 叠加一个侧移键（原版归一化后斜向组合与单键同速）；
            // 目标更高时叠加空格斜升（W+跳组合被归一化，水平推力 ×0.894，仍优于先平后升）。
            float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
            yaw = Mth.wrapDegrees(Math.round(bearing / 90.0F) * 90.0F);
            pitch = 0.0F;
            // 朝向未对准（身体朝向 0.08/tick 平滑跟随视线）先只转不飞：
            // 侧移键的方向以身体朝向为基准，对准前推键方向误差过大；
            // 吸附后误差最多 45°，收敛到门限只需 1~2 tick，停顿代价极小
            aligned = Math.abs(Mth.wrapDegrees(yaw - nav.getYRot())) <= TURN_ALIGN_DEGREES;
            if (aligned) {
                forward = 1.0F;
                float rel = Mth.wrapDegrees(bearing - yaw); // ∈ [-45°, 45°]
                if (rel > 22.5F) {
                    strafe = -1.0F; // 目标偏右 → 右移键（strafe 正＝左，见 writeInput）
                } else if (rel < -22.5F) {
                    strafe = 1.0F;  // 目标偏左 → 左移键
                }
                if (dy > VERT_DEADZONE) {
                    jump = true;
                }
                // 末端刹车：瞄准点已是最后路点、进入刹车区后按"会不会冲过头"动态判定——
                // 当前速度的滑行距离（≈10×v）超过剩余距离+0.5 格余量才反向刹车（S）；
                // 其余情况一律正推。旧的固定阈值+松手滑行会在 ~0.02 格/tick 处形成
                // "推→滑→推"极限环（日志实测原地抖 44 tick 直到节点超时）
                if (finalLeg && horiz < BRAKE_ZONE) {
                    double vAlong = (vel.x * dx + vel.z * dz) / horiz;
                    if (vAlong > (horiz + 0.5) / VERT_COAST_FACTOR) {
                        forward = -1.0F;
                        strafe = 0.0F;
                        jump = false;
                    }
                    // 未达刹车线：保持正推+侧移+斜升（低速永不停推，无死锁）
                }
            }
        } else if (dy > VERT_DEADZONE) {
            // 水平已到位 → 纯上升（6 正方向中的"上"）：仰视到底 + W 全推力；
            // 剩余高度小于"当前升速的滑行距离 + 死区"时松手滑行，防冲过头
            if (!(vel.y > 0.0 && dy < vel.y * VERT_COAST_FACTOR + VERT_DEADZONE)) {
                pitch = ASCEND_PITCH;
                forward = 1.0F;
            }
        } else if (dy < -VERT_DEADZONE) {
            // 水平已到位 → 纯下降（6 正方向中的"下"）：低头到底 + W；
            // 同理按下落速度收油门
            if (!(vel.y < 0.0 && -dy < -vel.y * VERT_COAST_FACTOR + VERT_DEADZONE)) {
                pitch = DESCEND_PITCH;
                forward = 1.0F;
            }
        }
        // 其余情况＝到位：forward/jump 保持 0，松开后靠阻尼停稳

        player.setYRot(yaw);
        player.setXRot(pitch);
        pitchOwned = true;
        // 「强制疾跑」按配置照常请求（恶魂无疾跑加成，仅为状态一致）；
        // shift 恒 false（= 下马键，骑乘期间绝不能主动产生）
        boolean sprint = Configs.Go.GO_FORCE_SPRINT.getBooleanValue();
        GoExecutor.writeInput(player, strafe, forward, jump, sprint, false);
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
            escapeTier = 0;
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
     * <p>方向表覆盖<b>全向</b>：以「指向路点的前方 a」与「左向 s」为基，一组 18 个方向——
     * 正上/正下、水平 8 向（前、前左、左、后左、后、后右、右、前右）以及它们的斜上/斜下变体。
     * 优先级：正上 → 斜上侧向/前方/后方 → 正侧向 → 正后 → 正前 → 斜下侧/后/前 → 正下。
     * 先挑正上是因为贴墙时上方通常开阔，且上升键（空格）与视线解耦、最可控；正前是既定被堵
     * 的方向，探测代价仅一次判定，故放在水平向的最后；下降排最后是因为下降与水平移动互斥
     * （见 {@link #drive}），飞起来最别扭。选中后朝 {@link #ESCAPE_DRIVE} 格外飞：
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
        // 表项＝{前方分量, 垂直分量, 左向分量}（前/左两个基见上，垂直分量 1/0/−1＝升/平/降）
        double[][] table = {
                {0.0, 1.0, 0.0},        // 正上
                {0.0, 1.0, 1.0},        // 斜上左
                {0.0, 1.0, -1.0},       // 斜上右
                {1.0, 1.0, 1.0},        // 斜上左前
                {1.0, 1.0, -1.0},       // 斜上右前
                {-1.0, 1.0, 1.0},       // 斜上左后
                {-1.0, 1.0, -1.0},      // 斜上右后
                {0.0, 0.0, 1.0},        // 正左
                {0.0, 0.0, -1.0},       // 正右
                {-1.0, 0.0, 0.0},       // 正后（原路退回）
                {1.0, 0.0, 0.0},        // 正前（被堵方向，通常一探即否）
                {0.0, -1.0, 1.0},       // 斜下左
                {0.0, -1.0, -1.0},      // 斜下右
                {-1.0, -1.0, 1.0},      // 斜下左后
                {-1.0, -1.0, -1.0},     // 斜下右后
                {1.0, -1.0, 1.0},       // 斜下左前
                {1.0, -1.0, -1.0},      // 斜下右前
                {0.0, -1.0, 0.0}        // 正下（最后手段）
        };
        // 升档：一档连续失败 TIER2_AFTER_TIER1_FAILS 次，说明"探测能过、真飞不动"，
        // 跳过一档直接按二档（开阔度）选向；二档也无解时回落一档再试一轮
        if (tier1FailStreak >= TIER2_AFTER_TIER1_FAILS
                && tryTier2Escape(level, spec, table, x, y, z, ax, az, sx, sz, true)) {
            return true;
        }
        // 第一档：全规则（沿途真实碰撞 + 终点不压原理图方块），按 18 向优先级表取第一个可行方向
        for (int dirIdx = 0; dirIdx < table.length; dirIdx++) {
            double[] dir = probeDirection(level, spec, table[dirIdx], x, y, z,
                    ax, az, sx, sz, true);
            if (dir == null) {
                continue;
            }
            enterEscape(x, y, z, dir[0], dir[1], dir[2], 1);
            return true;
        }
        // 第二档兜底：一档 18 向全被原理图约束否决（困在结构内部腔体的特征——周围"现实空气、
        // 原理图排了方块"，一档"终点不压原理图"在腔内永远满足不了）
        if (tryTier2Escape(level, spec, table, x, y, z, ax, az, sx, sz, false)) {
            return true;
        }
        return false;
    }

    /**
     * 二档选向：终点允许压原理图方块、只查真实碰撞，并在可行方向里选终点最开阔的。
     * 开阔度打分把「原理图预测非空气」也算占位，选的是真正逃出结构占用区的方向；
     * 不用 A*、贴墙惩罚那一套，直接按空格占比打分。
     *
     * @param escalating true=因一档连续失败而升档（日志注明原因）；false=一档全否决的兜底
     * @return 是否成功进入二档脱困
     */
    private static boolean tryTier2Escape(ClientLevel level, GhastPathfinder.BoxSpec spec,
                                          double[][] table,
                                          double x, double y, double z,
                                          double ax, double az, double sx, double sz,
                                          boolean escalating) {
        int bestIdx = -1;
        double bestOpen = -1.0;
        double[] bestDir = null;
        for (int dirIdx = 0; dirIdx < table.length; dirIdx++) {
            double[] dir = probeDirection(level, spec, table[dirIdx], x, y, z,
                    ax, az, sx, sz, false);
            if (dir == null) {
                continue;
            }
            double open = endpointOpenness(level,
                    x + dir[0] * ESCAPE_PROBE, y + dir[1] * ESCAPE_PROBE, z + dir[2] * ESCAPE_PROBE);
            if (open > bestOpen) {
                bestOpen = open;
                bestIdx = dirIdx;
                bestDir = dir;
            }
        }
        if (bestDir == null) {
            return false;
        }
        enterEscape(x, y, z, bestDir[0], bestDir[1], bestDir[2], 2);
        return true;
    }

    /**
     * 探测一个脱困方向：按方向表基向量归一化，跳过 TTL 内的失败方向，
     * 再按 {@code endpointMustClearSchematic} 决定终点是否受原理图约束。
     *
     * @return 归一化方向向量；该方向不可用返回 null
     */
    @Nullable
    private static double[] probeDirection(ClientLevel level, GhastPathfinder.BoxSpec spec, double[] c,
                                           double x, double y, double z,
                                           double ax, double az, double sx, double sz,
                                           boolean endpointMustClearSchematic) {
        double dirX = ax * c[0] + sx * c[2];
        double dirZ = az * c[0] + sz * c[2];
        double dirY = c[1];
        double len = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        dirX /= len;
        dirY /= len;
        dirZ /= len;
        if (isFailedDirection(level.getGameTime(), dirX, dirY, dirZ)) {
            return null; // 刚失败过的方向：几何没变时重选还会选它，先给其他方向机会
        }
        if (!escapeRouteClear(level, spec, x, y, z, dirX, dirY, dirZ, endpointMustClearSchematic)) {
            return null;
        }
        return new double[]{dirX, dirY, dirZ};
    }

    /** 进入脱困状态：记录起点、方向、剩余 tick 与档位 */
    private static void enterEscape(double x, double y, double z,
                                    double dirX, double dirY, double dirZ, int tier) {
        escapeX = x;
        escapeY = y;
        escapeZ = z;
        escapeDirX = dirX;
        escapeDirY = dirY;
        escapeDirZ = dirZ;
        escapeTicks = ESCAPE_TICKS;
        escapeTier = tier;
    }

    /**
     * 二档脱困的终点开阔度：以终点格为中心的 5×5×5 采样里"空格"占比（0~1）。
     * 真实非空气方块与「原理图预测非空气」方块都算占位——不能只是从一个原理图腔
     * 挪进另一个原理图腔；未加载区块按占位算。
     */
    private static double endpointOpenness(ClientLevel level, double ex, double ey, double ez) {
        int cx = Mth.floor(ex);
        int cy = Mth.floor(ey);
        int cz = Mth.floor(ez);
        int free = 0;
        int total = 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    total++;
                    int bx = cx + dx;
                    int by = cy + dy;
                    int bz = cz + dz;
                    if (!BlockStateUtils.isColumnLoaded(level, bx >> 4, bz >> 4)) {
                        continue; // 未加载：按占位算
                    }
                    m.set(bx, by, bz);
                    if (!level.getBlockState(m).isAir()) {
                        continue; // 真实方块：占位
                    }
                    var sch = SchematicStateCache.INSTANCE.getSchematicState(m);
                    if (sch == null || sch.isAir()) {
                        free++;
                    }
                }
            }
        }
        return (double) free / total;
    }

    /**
     * 脱困方向是否走得通：按<b>控制律实际会飞的轨迹</b>逐档取样，而不是只看终点那一个箱位。
     *
     * <p><b>原理图约束只判整条路线的终点</b>（终点箱体不得压任何"原理图预测非空气"格子），
     * 沿途只查真实碰撞——脱困的起点本来就压着原理图，挪出重叠区的途中箱子必然还要扫过
     * "现实里是空气、原理图排了方块"的格子，中途查会让压深 ≥2 格的重叠在所有方向上
     * 第一档就被否掉、永远逃不出去。终点不压即不会"逃进建筑内部"。
     *
     * <p>轨迹形状取决于方向类型（见 {@link #drive}）：
     * <ul>
     * <li>上升/水平：水平推进与空格上升<b>同时生效</b>，走的是一条直线；</li>
     * <li>下降：控制律要等水平剩余 ≤{@link #HORIZ_ARRIVE} 才低头——故实际是"平飞到距目标
     *     水平投影剩 {@link #HORIZ_ARRIVE} 处、在该处垂直下落"的折线。</li>
     * </ul>
     *
     * @param endpointMustClearSchematic 一档（true）：终点箱体不得压任何「原理图预测非空气」
     *                                   格子，否则只是把重叠挪了个地方；二档（false）：
     *                                   终点允许压原理图方块，只查沿途真实碰撞
     */
    private static boolean escapeRouteClear(ClientLevel level, GhastPathfinder.BoxSpec spec,
                                           double x, double y, double z,
                                           double dirX, double dirY, double dirZ,
                                           boolean endpointMustClearSchematic) {
        double hd = Math.sqrt(dirX * dirX + dirZ * dirZ);
        double endX;
        double endY;
        double endZ;
        if (dirY < 0.0 && hd > 1.0E-6) {
            // 下降折线：水平段（保持当前高度）只飞到"距目标水平投影剩 HORIZ_ARRIVE"处，
            // 随后在该处垂直下落——更远的水平位置控制律根本不会经过
            double horizFlown = Math.max(0.0, ESCAPE_PROBE * hd - HORIZ_ARRIVE);
            if (horizFlown > 0.0
                    && !escapeSegmentClear(level, spec, x, y, z, dirX / hd, 0.0, dirZ / hd, horizFlown)) {
                return false;
            }
            endX = x + (dirX / hd) * horizFlown;
            endY = y;
            endZ = z + (dirZ / hd) * horizFlown;
            double dropLen = ESCAPE_PROBE * -dirY;
            if (!escapeSegmentClear(level, spec, endX, endY, endZ, 0.0, -1.0, 0.0, dropLen)) {
                return false;
            }
            endY -= dropLen;
        } else {
            if (!escapeSegmentClear(level, spec, x, y, z, dirX, dirY, dirZ, ESCAPE_PROBE)) {
                return false;
            }
            endX = x + dirX * ESCAPE_PROBE;
            endY = y + dirY * ESCAPE_PROBE;
            endZ = z + dirZ * ESCAPE_PROBE;
        }
        // 终点双查中的原理图一侧（仅一档）：整个箱体不得再压任何原理图方块；
        // 二档跳过——逃进"原理图预留空间"本来就是二档的目的
        if (endpointMustClearSchematic) {
            return !GhastPathfinder.boxHitsSchematic(spec.at(endX, endY, endZ));
        }
        return true;
    }

    /**
     * 沿单位方向逐档取样（步长 {@link #ESCAPE_PROBE_STEP}，终点必验）：
     * 每一档都要求"区块已加载 + 箱体无真实碰撞"。原理图约束不在这里判——
     * 由 {@link #escapeRouteClear} 只对整条路线的终点判定（见其注释）。
     */
    private static boolean escapeSegmentClear(ClientLevel level, GhastPathfinder.BoxSpec spec,
                                              double x, double y, double z,
                                              double ux, double uy, double uz, double length) {
        for (double d = ESCAPE_PROBE_STEP; d < length - 1.0E-6; d += ESCAPE_PROBE_STEP) {
            if (!escapePointLoadedAndClear(level, spec, x, y, z, ux, uy, uz, d)) {
                return false;
            }
        }
        return escapePointLoadedAndClear(level, spec, x, y, z, ux, uy, uz, length);
    }

    /** 单个采样点：区块已加载 + 箱体无真实碰撞 */
    private static boolean escapePointLoadedAndClear(ClientLevel level, GhastPathfinder.BoxSpec spec,
                                                     double x, double y, double z,
                                                     double ux, double uy, double uz, double d) {
        double px = x + ux * d;
        double py = y + uy * d;
        double pz = z + uz * d;
        if (!BlockStateUtils.isColumnLoaded(level, Mth.floor(px) >> 4, Mth.floor(pz) >> 4)) {
            return false; // 未加载区块：飞进去拿不到地形，放弃该方向
        }
        return level.noCollision(spec.at(px, py, pz));
    }

    /** 记录"跑满仍未脱离"的当前脱困方向（环形缓冲，{@link #FAILED_DIRECTION_TTL_TICKS} 内选向跳过） */
    private static void markFailedDirection(long gameTick) {
        failedDirs[failedDirCursor][0] = escapeDirX;
        failedDirs[failedDirCursor][1] = escapeDirY;
        failedDirs[failedDirCursor][2] = escapeDirZ;
        failedDirExpire[failedDirCursor] = gameTick + FAILED_DIRECTION_TTL_TICKS;
        failedDirCursor = (failedDirCursor + 1) % FAILED_DIRECTION_SLOTS;
    }

    /** 清除全部失败方向记录（该方向被证实可行/状态作废） */
    private static void clearFailedDirection() {
        java.util.Arrays.fill(failedDirExpire, Long.MIN_VALUE);
    }

    /** 候选方向是否是 TTL 内刚失败过的方向（单位向量点积 &gt;0.999 ≈ 夹角 &lt;2.6°，视为同一方向） */
    private static boolean isFailedDirection(long gameTick, double dx, double dy, double dz) {
        for (int i = 0; i < FAILED_DIRECTION_SLOTS; i++) {
            if (gameTick >= failedDirExpire[i]) {
                continue;
            }
            if (dx * failedDirs[i][0] + dy * failedDirs[i][1] + dz * failedDirs[i][2] > 0.999) {
                return true;
            }
        }
        return false;
    }

    /**
     * 飞行接管结束时归还俯仰角：飞行期间我们把 pitch 打到 90° 做纯垂直下降，
     * 若停止接管后不回正，玩家按 W 会继续朝下飞。
     */
    public static void releasePitch(LocalPlayer player) {
        escapeTicks = 0; // 停止寻路：脱困状态不再保留
        escapeTier = 0;
        if (pitchOwned) {
            player.setXRot(0.0F);
            pitchOwned = false;
        }
    }
}