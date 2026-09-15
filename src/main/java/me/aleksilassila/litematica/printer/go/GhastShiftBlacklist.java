package me.aleksilassila.litematica.printer.go;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * 骑乘乐魂期间的「需要 shift 的放置」临时黑名单。
 *
 * <p>根因：骑在快乐恶魂上时<b>按潜行键（shift）＝ 下马</b>，因此飞行期间绝不能主动产生
 * shift。凡是放置动作需要 shift 的（显式指定：潜影盒/告示牌/箱子；或相邻是可交互方块的
 * 自动规避式潜行），一律跳过并记入本表，避免每轮迭代重复尝试。
 *
 * <p>生命周期：<b>玩家离开乐魂（下车/换乘）即整体清空</b>——不写配置、不持久化；
 * 清空检测内联在读写入口，无需额外 tick 钩子。
 */
public final class GhastShiftBlacklist {
    private static final LongOpenHashSet BLOCKED = new LongOpenHashSet();

    private GhastShiftBlacklist() {
    }

    /** 是否已被拉黑（未骑乘乐魂时恒 false，且顺带清空残留） */
    public static boolean contains(@Nullable LocalPlayer player, BlockPos pos) {
        if (GhastRideState.riddenGhast(player) == null) {
            if (!BLOCKED.isEmpty()) {
                BLOCKED.clear(); // 已离开乐魂：整表清空（这些放置可以正常执行了）
            }
            return false;
        }
        return BLOCKED.contains(pos.asLong());
    }

    /** 记入黑名单（仅骑乘乐魂时有效） */
    public static void add(@Nullable LocalPlayer player, BlockPos pos) {
        if (GhastRideState.riddenGhast(player) != null) {
            BLOCKED.add(pos.asLong());
        }
    }
}