package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.mc.ClientInputAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 寻路执行器：把当前路点方向换算成与相机朝向无关的移动输入，
 * 通过覆写 {@code ClientInput.keyPresses} 与 {@code moveVector} 驱动玩家
 * 前进/左右/后退/跳跃/冲刺；除可选的视角接管（GO_TAKEOVER_VIEW，只改偏航角，
 * 见 {@code onApplyInput} 接管段注释）外不改客户端视角；不挖掘不放置。
 * 另驱动两类寻路动作的原地执行：同层跑酷跳（边缘探测起跳，按缺口分级：
 * 1 格缺口停 1gt 普通跳、2 格缺口停 1gt 疾跑跳、3 格缺口直接疾跑跳，疾跑跳
 * 落地后再停 1gt 掐掉维持中的疾跑，见 {@code parkour} 分支注释；另在强制疾跑
 * 开启时的平地长直线路段（沿直线方向剩余路程 > 5 格、中间无拐弯节点）边跑边跳
 * 提速，方向锁定直线段远端路点，见 {@code sprintHopTarget}；腾空阶段用速率伺服微调 WASD 修正落点，
 * 见 {@code parkourAirControl}）与梯子/藤蔓攀爬（悬挂时朝附着墙推进，
 * 上爬附加跳跃键，见 {@code hanging} 分支）。
 * 在 {@code LocalPlayer.applyInput()} HEAD 调用（{@link me.aleksilassila.litematica.printer.mixin.printer.mc.MixinLocalPlayerGo}）。
 */
public final class GoExecutor {
    private static final double ARRIVE_DIST_SQ = 0.45 * 0.45;

    /**
     * 最近一次覆写写入的输入（含 shift 透传位）。寻路停止后用于识别并清掉我们
     * 自己写入的残留：tweakeroo 灵魂出窍的 DummyMovementInput.tick() 是空操作、
     * 永远不会从物理键盘重写 keyPresses，且其实例常驻玩家对象——寻路期间写入的
     * 前进/疾跑会一直留着，下次开灵魂出窍时被原版 applyInput（isControlledCamera
     * 被 tweakeroo 强制 true）原样消费，导致玩家无人操控地继续移动且 /go stop 无效。
     * 等值匹配保证只清我们写过的组合，绝不碰玩家真实的物理键盘输入。
     */
    @Nullable
    private static net.minecraft.world.entity.player.Input lastWritten;

    /**
     * 本会话写入过的 input 对象（至多两个：真身 KeyboardInput + 灵魂出窍的常驻
     * DummyMovementInput）。灵魂出窍是按 tick 换入/换出 input 的，若会话中途切换
     * 灵魂出窍，最后一次清理时"当前 input"不再是写入时的那个对象，等值匹配永远
     * 轮不到被换出的那个——其残留会在下次开灵魂出窍时被原样消费。清理时对
     * 非当前的按引用直接清零（换出状态的 input 不承载物理键盘输入，安全），
     * 当前的仍走等值匹配，避免误清玩家正在使用的真实键盘输入。
     */
    private static final java.util.ArrayList<net.minecraft.client.player.ClientInput> writtenInputs =
            new java.util.ArrayList<>(2);

    /**
     * 接管视角关闭时的跳跃临时转向：起跳 tick 暂存起跳前视角并转向跳跃方向，
     * 下一 tick 立即恢复原视角（NaN = 无暂存）。会话边界（新寻路开始/玩家失效）由
     * {@link #resetViewRestore} 清除，防止旧会话残留值在新会话里造成莫名回摆。
     */
    private static float preJumpYaw = Float.NaN;

    /**
     * 跑酷分级停顿（0 = 无停顿）：1~2 格缺口在起跳边缘先停 1gt 再跳。停顿 tick
     * 写零输入，原版疾跑维持检查（shouldStopRunSprinting：无前进输入即取消）在
     * 本 mod 的写入点之后执行，进行中的疾跑当 tick 被掐掉——1 格缺口得以普通跳
     * （不疾跑）获得落点精度，2 格缺口从近静止再疾跑跳依然够距离；3 格缺口不停
     * 顿，靠疾跑动量直接起跳。若停顿后下一 tick 边缘条件不再成立则放弃本次停顿，
     * 下次触发重新判定。
     */
    private static int parkourPauseTicks;

    /**
     * 跑酷分级停顿的每 tick 防重入：onApplyInput 每 tick 可能被两个注入点各调用
     * 一次（LocalPlayer.aiStep 的 input.tick() 之后 + applyInput HEAD），状态推进
     * （停顿计数/缺口判定）只允许发生一次，第二次调用沿用本 tick 已定的结论。
     * 顺带缓存本 tick 已判定的缺口格数（-1 = 本 tick 不起跳）。
     */
    private static long parkourStateTick = -1L;
    private static int parkourDecidedGap = -1;

    /**
     * 跑酷跳空中微调的锁定落点（缺口对岸路点，起跳 tick 锁定；null = 无窗口）。
     * 腾空阶段（起跳后视角恢复的 tick 起，到落地）用它做速率伺服微调：目标锁定
     * 而不是取当前路点，因为腾空中水平距离小于 0.45 会提前推进到下一路点；
     * 着地/入水/悬挂即清除窗口，会话边界由 {@link #resetViewRestore} 清除。
     */
    @Nullable
    private static BlockPos parkourAirTarget;

    /**
     * 本次跑酷跳是否为疾跑跳（起跳 tick 随 {@link #parkourAirTarget} 一起锁定）：
     * 疾跑跳落地时原版疾跑仍在维持，落地 tick 零输入停 1gt 掐掉疾跑动量（普通跳
     * 落地时不在疾跑，无需停顿）。会话边界由 {@link #resetViewRestore} 清除。
     */
    private static boolean parkourAirSprinted;

    /**
     * 平地连跳空中锁定的直线段远端路点（起跳 tick 锁定；null = 无窗口）。滞空期
     * 朝它全速推进——不追当前路点：连跳会飞越路点，当前路点可能因漏推进落在玩家
     * 身后/侧面，追它会往反方向跳。着地/入水/悬挂即清除（落地不停顿，下一 tick
     * 重新判定是否续跳），会话边界由 {@link #resetViewRestore} 清除。
     */
    @Nullable
    private static BlockPos sprintHopAirTarget;

    /** 清除跳跃临时转向的暂存：寻路会话开始/玩家失效时调用 */
    public static void resetViewRestore() {
        preJumpYaw = Float.NaN;
        selfJumpAirborne = false;
        parkourPauseTicks = 0;
        parkourStateTick = -1L;
        parkourDecidedGap = -1;
        parkourAirTarget = null;
        parkourAirSprinted = false;
        sprintHopAirTarget = null;
    }

    /**
     * 自主起跳暂挂：在着地/水中写入跳跃键时置位，落地后第一次写入（着地且非跳跃）
     * 时清零。跑酷跳/跳上一格/出水跳的整个空中阶段据此被偏离检测放行——缺口跳跃
     * 中点距两端路点必然超过 1 格，属合法离路瞬间；外力击退/冲走/失足的腾空没有
     * 该暂挂，不被放行（否则寻路自己的空中操控会把玩家拉回路径内，带离永远判不到）。
     */
    private static boolean selfJumpAirborne;

    /** 是否处于自主跳跃的空中阶段（供 GoManager 偏离检测区分合法离路腾空） */
    public static boolean isSelfJumpAirborne() {
        return selfJumpAirborne;
    }

    private GoExecutor() {
    }

    public static void onApplyInput(LocalPlayer player) {
        if (!GoManager.INSTANCE.isActive()) {
            // 寻路已停止：跳跃临时转向还没恢复的话把视角还回去
            restorePreJumpYaw(player, false);
            clearStaleInput(player);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != player || player.isPassenger()) {
            return;
        }
        // 仅容器类界面暂停（打印换料/补货/箱子界面，走远会被服务端强制关闭容器）；
        // 聊天栏等普通界面照常寻路（原版 applyInput/isControlledCamera 与界面无关）
        if (isContainerUiOpen(player)) {
            return;
        }
        // 寻路假设地面行走：飞行/旁观/睡觉时不接管输入（保持激活，落地自动恢复驱动）
        if (player.getAbilities().flying || player.isSpectator() || player.isSleeping()) {
            return;
        }
        // 不越权透传：shift 保留键盘/打印已写入的现状（原版 KeyboardInput 本就每 tick 从物理键盘重写），
        // 寻路自身绝不主动潜行，对打印的潜行流程零干预
        boolean shift = player.input.keyPresses.shift();
        boolean takeover = Configs.Special.GO_TAKEOVER_VIEW.getBooleanValue();

        // 跑酷跳空中微调窗口：起跳后（视角恢复的 tick 起）到落地，用速率伺服微调
        // WASD 把落点修正到锁定目标（见 parkourAirControl）；着地/入水/悬挂即退出
        if (parkourAirTarget != null) {
            if (!player.onGround() && !player.isInWater() && !hangingOnClimbable(player)) {
                parkourAirControl(player, takeover, shift);
                return;
            }
            // 跑酷跳结束（着地/入水/悬挂）：疾跑跳落地时原版疾跑仍在维持，落地 tick
            // 零输入停 1gt 掐掉疾跑动量（普通跳落地不在疾跑，无需停顿）
            parkourAirTarget = null;
            if (parkourAirSprinted) {
                parkourAirSprinted = false;
                restorePreJumpYaw(player, takeover);
                writeInput(player, 0.0F, 0.0F, false, false, shift);
                return;
            }
        }

        // 平地连跳空中窗口：朝锁定的直线段远端路点全速推进（见 sprintHopAirControl）；
        // 着地/入水/悬挂即退出，落地 tick 起重新判定是否续跳（连跳落地不停顿）
        if (sprintHopAirTarget != null) {
            if (!player.onGround() && !player.isInWater() && !hangingOnClimbable(player)) {
                sprintHopAirControl(player, takeover, shift);
                return;
            }
            sprintHopAirTarget = null;
        }

        BlockPos wp = GoManager.INSTANCE.getWaypoint();
        if (wp == null) {
            restorePreJumpYaw(player, takeover);
            writeInput(player, 0.0F, 0.0F, false, false, shift);
            return;
        }

        boolean hanging = hangingOnClimbable(player);
        int feetY = player.getBlockY();

        double dx = (wp.getX() + 0.5) - player.getX();
        double dz = (wp.getZ() + 0.5) - player.getZ();
        double hDist = Math.sqrt(dx * dx + dz * dz);
        if (hDist < 1.0E-3) {
            if (hanging) {
                restorePreJumpYaw(player, takeover); // 起跳抓梯后已挂上：恢复原视角
                // 梯子/藤蔓列内的竖直段（路点正上/正下方）：朝附着墙方向推进——
                // 藤蔓爬升需要实际碰撞到攀爬面，梯子下滑也需要前推。仅 ladder 时
                // 找不到墙也能靠跳跃键上升
                double[] wall = attachmentWall(player);
                if (wall != null) {
                    dx = wall[0];
                    dz = wall[1];
                    hDist = 1.0;
                } else if (wp.getY() > feetY) {
                    writeInput(player, 0.0F, 0.0F, true, false, shift);
                    return;
                } else {
                    writeInput(player, 0.0F, 0.0F, false, false, shift);
                    return;
                }
            } else {
                restorePreJumpYaw(player, takeover);
                writeInput(player, 0.0F, 0.0F, false, false, shift);
                return;
            }
        }
        dx /= hDist;
        dz /= hDist;

        // 跳跃/跑酷判定先于视角接管：跑酷起跳 tick 需要在设 yaw 前就知道是否归零偏转
        boolean jump = false;
        boolean parkour = false;
        // 跑酷起跳 tick 的缺口格数（<0 = 非起跳 tick，即停顿 tick 或非跑酷）
        int parkourGap = -1;
        if (hanging) {
            if (wp.getY() > feetY) {
                jump = true; // 攀爬上升：跳跃键在梯子上直接上升；藤蔓靠前推进入攀爬面
            }
            // 悬挂中下降（路点在下方）：前推方向（墙向/侧向）即沿梯下滑或走出，不加跳跃
        } else {
            if (player.onGround() && wp.getY() > feetY && hDist * hDist < 1.8 * 1.8) {
                jump = true; // 跳上型路点
            }
            if (player.isInWater() && wp.getY() >= feetY) {
                jump = true; // 水中保持上浮游动
            }
            if (player.onGround() && wp.getY() == feetY && hDist > 1.5 && hDist < 5.0
                    && floorAheadMissing(player, dx, dz)) {
                // 「自动寻路 - 强制疾跑」未开启：不允许任何跑酷跳（含 1 格缺口）。
                // 寻路侧同步不生成跑酷跳边（见 GoPathfinder.parkour），此处仅在
                // 配置切换后残留的旧路径经过缺口时兜底——在边缘停住（零输入），
                // 交给卡住检测重算绕开缺口的路径，绝不前走进缺口
                if (!Configs.Special.GO_FORCE_SPRINT.getBooleanValue()) {
                    parkourPauseTicks = 0; // 丢弃可能残留的停顿状态
                    restorePreJumpYaw(player, takeover);
                    writeInput(player, 0.0F, 0.0F, false, false, shift);
                    return;
                }
                // 跑酷跳腿：同层 2~4 格外的落点、中间无地板（寻路 parkour 边保证），
                // 前方 0.4 格探测点越过缺口边缘即处于边缘 → 起跳（见 floorAheadMissing）。
                // 缺口按到落点路点的水平距离分级
                // （跑酷只生成正交方向、落点在缺口后一格，触发时 hDist ≈ 缺口+1）：
                // 1~2 格缺口先停 1gt 再跳——停顿 tick 零输入会当 tick 掐掉进行中的
                // 疾跑，1 格缺口得以普通跳精确落点，2 格缺口从近静止再疾跑跳依然够远；
                // 3 格缺口不停顿，直接疾跑跳保住动量（强制疾跑开启是跑酷跳的前提；
                // 疾跑跳落地后另停 1gt 掐掉疾跑，见落地分支）
                parkour = true;
                long now = player.tickCount;
                if (parkourStateTick != now) {
                    parkourStateTick = now; // 状态推进每 tick 仅一次（见字段注释）
                    int gap = (int) Math.round(hDist) - 1;
                    if (parkourPauseTicks > 0) {
                        parkourPauseTicks--; // 停顿后的下一 tick：起跳
                    } else if (gap <= 2) {
                        parkourPauseTicks = 1; // 本 tick 先停 1gt
                    }
                    parkourDecidedGap = parkourPauseTicks == 0 ? gap : -1;
                }
                if (parkourPauseTicks == 0) {
                    jump = true;
                    parkourGap = parkourDecidedGap;
                    parkourAirTarget = wp; // 锁定落点：腾空期空中微调的目标（见字段注释）
                    parkourAirSprinted = parkourGap >= 2; // 疾跑跳落地后停 1gt 掐疾跑（见落地分支）
                }
            } else if (parkourPauseTicks != 0) {
                parkourPauseTicks = 0; // 边缘条件中断：放弃本次停顿，下次触发重新判定
            }
        }

        // 视角处理（在相机系换算之前设置 yaw，本 tick 的移动向量自然收敛为正前方向，
        // 所以无论哪种模式，转向都不影响移动方向）。
        // 起跳动作 = 跑酷跳、跳上一格（含起跳抓梯：路点格为攀爬方块的跳上型路点）、
        // 平地直线冲刺跳。
        // ① 接管视角开启：每 tick 把偏航角转到路线方向（+ 角度偏转，只改 yaw 不动俯仰），
        //    渲染插值（yRotO → yRot）让转向平滑；偏转在悬挂攀爬（上爬/下滑/爬出）与
        //    起跳动作瞬间临时归零——正对动作方向且保证前进分量为正（原版疾跑的启动与
        //    维持都要求 hasForwardImpulse），动作结束后恢复偏转。
        // ② 接管视角关闭：平时完全不碰视角，仅起跳动作的起跳 tick 临时把视角转向跳跃
        //    方向（不带偏转），下一 tick 立即恢复起跳前的原视角（不等到落地；
        //    连续跳跃时每跳各自保存/恢复一对）。
        // 接管模式下与目标偏航相差 1° 以内不重写：路线方向随位置逐 tick 微变，
        // 逐次覆写会变成持续的视角抖动
        boolean jumpUp = jump && !hanging && player.onGround() && !player.isInWater()
                && wp.getY() > feetY; // 跳上一格（含起跳抓梯）
        // 平地直线冲刺跳（边跑边跳提速）：强制疾跑开启、在地面平坦路段（当前路点
        // 与脚同层）、非跑酷/跳上/悬挂/水中，且沿直线方向的剩余路程超过 5 格时起跳。
        // 方向锁定直线段远端路点而不是当前路点——当前路点可能因连跳飞越漏推进而
        // 落在玩家身后/侧面，追它会往反方向跳；远端带轻微向内收敛，滞空自然回到
        // 直线上。起跳 tick 与跑酷跳同一套视角逻辑（转向移动方向，下一 tick 恢复）
        boolean sprintHop = false;
        if (!hanging && !parkour && !jumpUp && player.onGround() && !player.isInWater()
                && wp.getY() == feetY) {
            BlockPos hopEnd = sprintHopTarget(player);
            if (hopEnd != null) {
                double ex = hopEnd.getX() + 0.5 - player.getX();
                double ez = hopEnd.getZ() + 0.5 - player.getZ();
                double el = Math.sqrt(ex * ex + ez * ez);
                if (el > 1.0E-3) {
                    sprintHop = true;
                    jump = true;
                    dx = ex / el;
                    dz = ez / el;
                    sprintHopAirTarget = hopEnd; // 滞空期继续朝远端方向推进（见空中窗口）
                }
            }
        }
        if (takeover) {
            float offset = (hanging || parkour || jumpUp || sprintHop)
                    ? 0.0F
                    : (float) Configs.Special.GO_VIEW_OFFSET.getIntegerValue();
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + offset;
            if (Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot())) >= 1.0F) {
                player.setYRot(targetYaw);
            }
        } else if (parkour || jumpUp || sprintHop) {
            if (Float.isNaN(preJumpYaw)) {
                preJumpYaw = player.getYRot();
            }
            player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        } else if (!Float.isNaN(preJumpYaw)) {
            restorePreJumpYaw(player, takeover);
        }

        // 期望世界方向 → 相机系移动向量（yaw=0 面向 +Z：strafe+=左，forward+=前）
        float yawRad = player.getYRot() * (float) (Math.PI / 180.0);
        float sin = (float) Math.sin(yawRad);
        float cos = (float) Math.cos(yawRad);
        float strafe = (float) (dx * cos + dz * sin);
        float forward = (float) (dz * cos - dx * sin);

        // 最大速度限制：按「自动寻路 - 最大速度」（格/秒）缩放移动向量；
        // 疾跑全速 = 20 / 3.5638 ≈ 5.612 格/秒，超过全速的值等效不限速（原版物理会把 >1 的输入归一化）
        float maxSpeed = (float) Configs.Special.GO_MAX_SPEED.getDoubleValue();
        float speedFactor = Math.min(1.0F, maxSpeed * (GoPathfinder.SPRINT_COST / 20.0F));
        strafe *= speedFactor;
        forward *= speedFactor;
        if (hanging && (wp.getX() != player.getBlockX() || wp.getZ() != player.getBlockZ())) {
            // 悬挂中的侧向路点（同层爬出/翻上梯顶）：减速侧移，防止跳出/漂过一格宽的落点
            strafe *= 0.4F;
            forward *= 0.4F;
        }
        if (parkour && parkourGap < 0) {
            // 跑酷停顿 tick：零输入原地下站 1gt（见 parkour 分支注释）
            strafe = 0.0F;
            forward = 0.0F;
        }

        boolean sprint;
        if (parkour && parkourGap >= 1) {
            // 跑酷起跳：2~3 格缺口疾跑跳助力（普通跳够不到），1 格缺口普通跳保落点
            // 精度；疾跑跳落地后由停顿 tick 掐掉疾跑（见落地分支）
            sprint = parkourGap >= 2;
        } else if (parkour) {
            sprint = false; // 跑酷停顿 tick：零输入已让原版取消疾跑，位上也不再请求
        } else {
            // 仅「自动寻路 - 强制疾跑」开启时请求疾跑（等效一直按住 Ctrl），能否
            // 真正冲刺由原版条件决定；关闭时绝不请求疾跑
            sprint = Configs.Special.GO_FORCE_SPRINT.getBooleanValue();
        }
        writeInput(player, strafe, forward, jump, sprint, shift);
    }

    /**
     * 跑酷跳空中微调（速率伺服）：把"落点要落在目标"转成每 tick 的 WASD 输入。
     * 期望水平速度 = 水平残差 / 纵向模拟得到的剩余空中 tick 数；输入 = （期望速度
     * − 当前速度 × 空气阻尼 0.91）/ 空气加速度（0.02，疾跑 0.026），限幅 1。误差为零
     * 时自动输出恰好维持当前速度的输入（抵消阻尼）；快了自动松 W 甚至按 S 减速，
     * 偏了按 A/D 侧移——即"结合目标落点位置和玩家视角"的空中微调，方向经相机系
     * 换算后与视角无关地指向需要的修正方向。
     */
    private static void parkourAirControl(LocalPlayer player, boolean takeover, boolean shift) {
        // 起跳后第一 tick 恢复起跳前视角（接管视角开启时视角由接管逻辑管理，不在此处理）
        restorePreJumpYaw(player, takeover);
        double tx = parkourAirTarget.getX() + 0.5 - player.getX();
        double tz = parkourAirTarget.getZ() + 0.5 - player.getZ();
        if (takeover) {
            float offset = (float) Configs.Special.GO_VIEW_OFFSET.getIntegerValue();
            float targetYaw = (float) Math.toDegrees(Math.atan2(-tx, tz)) + offset;
            if (Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot())) >= 1.0F) {
                player.setYRot(targetYaw);
            }
        }
        // 剩余空中 tick：按原版纵向物理（vy = (vy − 0.08) × 0.98）模拟到脚部落回目标层
        double y = player.getY();
        double vy = player.getDeltaMovement().y;
        int ticksLeft = 1;
        int targetY = parkourAirTarget.getY();
        while (y > targetY && ticksLeft < 40) {
            vy = (vy - 0.08) * 0.98;
            y += vy;
            ticksLeft++;
        }
        double accel = player.isSprinting() ? 0.026 : 0.02;
        double inX = (tx / ticksLeft - player.getDeltaMovement().x * 0.91) / accel;
        double inZ = (tz / ticksLeft - player.getDeltaMovement().z * 0.91) / accel;
        double mag = Math.sqrt(inX * inX + inZ * inZ);
        if (mag > 1.0) {
            inX /= mag;
            inZ /= mag;
        }
        // 世界系修正向量 → 相机系移动向量（yaw=0 面 +Z：strafe+=左，forward+=前）
        float yawRad = player.getYRot() * (float) (Math.PI / 180.0);
        float sin = (float) Math.sin(yawRad);
        float cos = (float) Math.cos(yawRad);
        float strafe = (float) (inX * cos + inZ * sin);
        float forward = (float) (inZ * cos - inX * sin);
        writeInput(player, strafe, forward, false, false, shift);
    }

    /** 脚部所在格是否为可攀爬方块（梯子/藤蔓等）：悬挂攀爬状态 */
    private static boolean hangingOnClimbable(LocalPlayer player) {
        ClientLevel level = Minecraft.getInstance().level;
        return level != null && level.getBlockState(player.blockPosition()).is(BlockTags.CLIMBABLE);
    }

    /** 脚下攀爬方块附着的固体墙方向（列内推进需要有实际可碰撞的面）；找不到返回 null */
    @Nullable
    private static double[] attachmentWall(LocalPlayer player) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            BlockPos p = new BlockPos(x + d[0], y, z + d[1]);
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                return new double[]{d[0], d[1]};
            }
        }
        return null;
    }

    /**
     * 探测步（0.4 格）内前方脚下一格是否无地板：判定跑酷跳的起跳边缘。
     * 探测点越过缺口边缘即触发（起跳点距边缘约 0.1 格以内；触发窗口 0.68 格
     * 宽于单 tick 步进 ≤0.28，不会漏检走到悬空）。
     */
    private static boolean floorAheadMissing(LocalPlayer player, double dx, double dz) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        BlockPos probe = BlockPos.containing(
                player.getX() + dx * 0.4, player.getBlockY() - 1, player.getZ() + dz * 0.4);
        return level.getBlockState(probe).getCollisionShape(level, probe).isEmpty();
    }

    /** 恢复跳跃临时转向暂存的起跳前视角；接管视角开启时不动作（视角由接管逻辑管理） */
    private static void restorePreJumpYaw(LocalPlayer player, boolean takeover) {
        if (takeover || Float.isNaN(preJumpYaw)) {
            return;
        }
        player.setYRot(preJumpYaw);
        preJumpYaw = Float.NaN;
    }

    /**
     * 平地直线冲刺跳判定：「自动寻路 - 强制疾跑」开启，且从当前路点起的后续路点
     * 保持同一方向不变层（中间无拐弯节点）。返回直线段远端路点（起跳方向与滞空
     * 目标都锁定它，不追当前路点——当前路点可能因连跳飞越漏推进而落在身后/侧面）；
     * 剩余直线路程按玩家到远端路点在直线方向上的投影计（当前路点在身后也以实际
     * 剩余路程为准），不超过 5 格返回 null（保证起跳后仍有足够直线路程，不会冲进
     * 弯道）。
     */
    @Nullable
    private static BlockPos sprintHopTarget(LocalPlayer player) {
        if (!Configs.Special.GO_FORCE_SPRINT.getBooleanValue()) {
            return null;
        }
        List<BlockPos> path = GoManager.INSTANCE.getPath();
        int i = GoManager.INSTANCE.getWaypointIndex();
        int n = path.size();
        if (i + 1 >= n) {
            return null;
        }
        BlockPos a = path.get(i);
        BlockPos b = path.get(i + 1);
        if (b.getY() != a.getY()) {
            return null; // 下一节点变层：不是平地直线
        }
        int dx = Integer.compare(b.getX(), a.getX());
        int dz = Integer.compare(b.getZ(), a.getZ());
        if (dx == 0 && dz == 0) {
            return null; // 重合节点
        }
        BlockPos far = b;
        for (int k = i + 2; k < n; k++) {
            BlockPos c = path.get(k);
            if (c.getY() != a.getY() || Integer.compare(c.getX(), far.getX()) != dx
                    || Integer.compare(c.getZ(), far.getZ()) != dz) {
                break; // 拐弯/变层节点：直线段到此为止
            }
            far = c;
        }
        double dirLen = Math.sqrt((double) dx * dx + (double) dz * dz);
        double proj = ((far.getX() + 0.5) - player.getX()) * dx
                + ((far.getZ() + 0.5) - player.getZ()) * dz;
        if (proj <= 5.0 * dirLen) {
            return null; // 剩余直线路程不足
        }
        return far;
    }

    /**
     * 平地连跳空中驱动：朝锁定的直线段远端路点全速推进——落点不求精确，"尽量向
     * 路径方向移动"；远端带轻微向内收敛，滞空自然回到直线上。疾跑位保持请求
     * （强制疾跑开启是连跳前提）。起跳后第一 tick 恢复起跳前视角；接管视角开启时
     * 朝远端方向 + 偏转持续对齐。
     */
    private static void sprintHopAirControl(LocalPlayer player, boolean takeover, boolean shift) {
        restorePreJumpYaw(player, takeover);
        BlockPos target = sprintHopAirTarget;
        double dx = target.getX() + 0.5 - player.getX();
        double dz = target.getZ() + 0.5 - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 1.0E-3) {
            dx /= dist;
            dz /= dist;
            if (takeover) {
                float offset = (float) Configs.Special.GO_VIEW_OFFSET.getIntegerValue();
                float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + offset;
                if (Math.abs(Mth.wrapDegrees(targetYaw - player.getYRot())) >= 1.0F) {
                    player.setYRot(targetYaw);
                }
            }
            // 世界系方向 → 相机系移动向量（yaw=0 面 +Z），与视角无关地指向远端；
            // 与地面驱动同一套限速（「自动寻路 - 最大速度」）
            float yawRad = player.getYRot() * (float) (Math.PI / 180.0);
            float sin = (float) Math.sin(yawRad);
            float cos = (float) Math.cos(yawRad);
            float strafe = (float) (dx * cos + dz * sin);
            float forward = (float) (dz * cos - dx * sin);
            float maxSpeed = (float) Configs.Special.GO_MAX_SPEED.getDoubleValue();
            float speedFactor = Math.min(1.0F, maxSpeed * (GoPathfinder.SPRINT_COST / 20.0F));
            strafe *= speedFactor;
            forward *= speedFactor;
            writeInput(player, strafe, forward, false, true, shift);
        } else {
            writeInput(player, 0.0F, 0.0F, false, true, shift);
        }
    }

    /**
     * 是否打开了容器类界面：容器屏幕或存在打开的容器菜单（含打印换料/补货流程）。
     * 此时寻路暂停驱动（ walking away 会让服务端强制关闭容器），聊天等普通界面不受影响。
     */
    public static boolean isContainerUiOpen(LocalPlayer player) {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
                || player.containerMenu != player.inventoryMenu;
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
        lastWritten = player.input.keyPresses;
        if (!writtenInputs.contains(player.input)) {
            writtenInputs.add(player.input);
        }
        if (player.onGround() || player.isInWater()) {
            selfJumpAirborne = jump;
        } // 腾空期间保持原值：空中是否属于自主跳跃由起跳时刻决定
    }

    /**
     * 寻路停止后清除我们自己写入的残留输入（见 lastWritten 注释）。
     * 仅当当前 keyPresses 与最后一次覆写完全一致时才清零——灵魂出窍期间注入点
     * 照常触发，此刻 player.input 正是那个不重置的 DummyMovementInput，等值即残留；
     * 非灵魂出窍时真身 KeyboardInput 每 tick 已被物理键盘重写，等值不成立则不动。
     */
    private static void clearStaleInput(LocalPlayer player) {
        // 被灵魂出窍换出的 input 按引用清零：它们不在消费链路上（原版 applyInput 只吃
        // 当前的 player.input），残留不清会在下次开灵魂出窍时驱动玩家
        for (net.minecraft.client.player.ClientInput written : writtenInputs) {
            if (written == player.input) {
                continue;
            }
            written.keyPresses = new net.minecraft.world.entity.player.Input(
                    false, false, false, false, false, false, false);
            ((ClientInputAccessor) written).printer$setMoveVector(Vec2.ZERO);
        }
        writtenInputs.clear();
        // 当前在用的 input 走等值匹配：灵魂出窍未开时它每 tick 被物理键盘重写
        // （等值不成立则不碰玩家真实输入）；开着时它只可能装着我们写过的组合
        if (lastWritten == null || !lastWritten.equals(player.input.keyPresses)) {
            return;
        }
        player.input.keyPresses = new net.minecraft.world.entity.player.Input(
                false, false, false, false, false, false, false);
        ((ClientInputAccessor) player.input).printer$setMoveVector(Vec2.ZERO);
        lastWritten = null;
    }
}
