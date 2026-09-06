package me.aleksilassila.litematica.printer.go;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.mc.ClientInputAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.Vec2;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 寻路执行器：把当前路点方向换算成与相机朝向无关的移动输入，
 * 通过覆写 {@code ClientInput.keyPresses} 与 {@code moveVector} 驱动玩家
 * 前进/左右/后退/跳跃/冲刺——不改变客户端视角，不挖掘不放置。
 * 另驱动两类寻路动作的原地执行：同层疾跑跳（边缘探测起跳，见 {@code parkour} 分支）
 * 与梯子/藤蔓攀爬（悬挂时朝附着墙推进，上爬附加跳跃键，见 {@code hanging} 分支）。
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

    private GoExecutor() {
    }

    public static void onApplyInput(LocalPlayer player) {
        if (!GoManager.INSTANCE.isActive()) {
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
        BlockPos wp = GoManager.INSTANCE.getWaypoint();
        if (wp == null) {
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
                writeInput(player, 0.0F, 0.0F, false, false, shift);
                return;
            }
        }
        dx /= hDist;
        dz /= hDist;

        // 接管视角：把客户端偏航角转到路线方向（+ 角度偏转），只改 yaw 不动俯仰。
        // 在相机系换算之前设置，本 tick 的移动向量自然收敛为正前方向；
        // 渲染插值（yRotO → yRot）会让转向平滑。未开启时保持玩家自己的视角
        if (Configs.Special.GO_TAKEOVER_VIEW.getBooleanValue()) {
            float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz))
                    + (float) Configs.Special.GO_VIEW_OFFSET.getDoubleValue();
            player.setYRot(targetYaw);
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

        boolean jump = false;
        boolean parkour = false;
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
                // 疾跑跳腿：同层 2~4 格外的落点、中间无地板（寻路 parkour 边保证），
                // 探测步内前方无地板即处于边缘 → 起跳。跳跃距离依赖疾跑，强制 sprint
                jump = true;
                parkour = true;
            }
        }
        boolean sprint;
        if (parkour || Configs.Special.GO_FORCE_SPRINT.getBooleanValue()) {
            // 疾跑跳必须疾跑助力；强制疾跑：始终请求（等效一直按住 Ctrl），能否真正冲刺由原版条件决定
            sprint = true;
        } else {
            sprint = shouldSprint(player, hDist);
        }
        writeInput(player, strafe, forward, jump, sprint, shift);
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

    /** 探测步（0.6 格）内前方脚下一格是否无地板：判定疾跑跳的起跳边缘 */
    private static boolean floorAheadMissing(LocalPlayer player, double dx, double dz) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        BlockPos probe = BlockPos.containing(
                player.getX() + dx * 0.6, player.getBlockY() - 1, player.getZ() + dz * 0.6);
        return level.getBlockState(probe).getCollisionShape(level, probe).isEmpty();
    }

    /** 平坦路段冲刺：当前与随后路点同层且距离足够远（强制疾跑开启时无条件请求） */
    private static boolean shouldSprint(LocalPlayer player, double hDist) {
        if (!player.onGround() || player.isInWater()) {
            return false;
        }
        if (player.getFoodData().getFoodLevel() <= 6) {
            return false;
        }
        if (hDist * hDist < 1.0 * 1.0) {
            return false; // 临近路点收步（疾跑跳腿需要保持冲刺动量，收步窗口收紧到 1 格）
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
    }

    /**
     * 寻路停止后清除我们自己写入的残留输入（见 lastWritten 注释）。
     * 仅当当前 keyPresses 与最后一次覆写完全一致时才清零——灵魂出窍期间注入点
     * 照常触发，此刻 player.input 正是那个不重置的 DummyMovementInput，等值即残留；
     * 非灵魂出窍时真身 KeyboardInput 每 tick 已被物理键盘重写，等值不成立则不动。
     */
    private static void clearStaleInput(LocalPlayer player) {
        if (lastWritten == null || !lastWritten.equals(player.input.keyPresses)) {
            return;
        }
        player.input.keyPresses = new net.minecraft.world.entity.player.Input(
                false, false, false, false, false, false, false);
        ((ClientInputAccessor) player.input).printer$setMoveVector(Vec2.ZERO);
        lastWritten = null;
    }
}
