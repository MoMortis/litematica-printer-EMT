package me.aleksilassila.litematica.printer.go;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.happyghast.HappyGhast;
import org.jetbrains.annotations.Nullable;

/**
 * 「乐魂寻路」的骑乘状态检测。
 *
 * <p>飞行模式的一切前置条件都收敛在这里：玩家是否骑了乐魂、骑的是不是自己能操控的那只、
 * 以及不可操控时的具体原因（供 HUD 提示区分）。寻路与控制律都以此为门禁。
 *
 * <p>判定依据（1.21.11 / 26.1.2 一致）：
 * <ul>
 * <li>乐魂的可操控者判定 {@code HappyGhast.getControllingPassenger()} 要求
 *     「装备挽具（body armor）&& 非静默超时 && 第一乘客是玩家」；</li>
 * <li>{@code isOnStillTimeout()}：只要有玩家站在乐魂上方，服务端每 tick 置 10，
 *     此时乐魂完全不吃输入（表现为"卡住"，须与寻路卡住区分）；</li>
 * <li>只有第一乘客（最先上鞍者）才可能是操控者。</li>
 * </ul>
 */
public final class GhastRideState {
    /** 骑乘状态：除 OK 外都需要在 HUD 上提示用户 */
    public enum Status {
        /** 未骑乘快乐恶魂 */
        NOT_RIDING,
        /** 骑的不是"自己第一上鞍"的那只乐魂 */
        NOT_CONTROLLER,
        /** 乐魂未装备挽具（harness），无法操控 */
        NO_HARNESS,
        /** 静默态：有玩家站在乐魂上方，服务端持续置 stillTimeout，乐魂不吃输入 */
        STILL_TIMEOUT,
        /** 可操控 */
        OK
    }

    private GhastRideState() {
    }

    /** 玩家当前骑乘的乐魂；未骑乘（或骑的不是乐魂）时为 null */
    @Nullable
    public static HappyGhast riddenGhast(@Nullable LocalPlayer player) {
        if (player == null) {
            return null;
        }
        Entity vehicle = player.getVehicle();
        return vehicle instanceof HappyGhast ghast ? ghast : null;
    }

    /**
     * 检测骑乘状态。细分不可操控的原因供 HUD 提示：
     * 非第一乘客 / 静默态 / 未装备挽具（由乐魂自身的操控者判定兜底推断）。
     */
    public static Status check(@Nullable LocalPlayer player) {
        HappyGhast ghast = riddenGhast(player);
        if (ghast == null) {
            return Status.NOT_RIDING;
        }
        if (ghast.getFirstPassenger() != player) {
            return Status.NOT_CONTROLLER;
        }
        if (ghast.isOnStillTimeout()) {
            return Status.STILL_TIMEOUT;
        }
        // 挽具与操控者的完整判定交给乐魂自身：非上述两者却仍不可操控 → 视为未装备挽具
        if (ghast.getControllingPassenger() != player) {
            return Status.NO_HARNESS;
        }
        return Status.OK;
    }

    /** 是否处于"可飞行"状态（乐魂寻路的门禁） */
    public static boolean canFly(@Nullable LocalPlayer player) {
        return check(player) == Status.OK;
    }
}