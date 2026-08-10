package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 侦测器安全放置守卫：只有输入面（侦测面）方块被服务器确认符合投影状态后，
 * 才允许放置侦测器。仅 SAFELY_OBSERVER 开启时生效。
 *
 * 等待表记录"待确认的输入面位置 + 登记游戏刻"。服务器方块更新包到达且状态匹配时移除；
 * 超过 20gt 兜底强制移除（环境固有方块服务器不会重发更新，此时放行）。
 */
public class ObserverPlacementGuard {
    public static final ObserverPlacementGuard INSTANCE = new ObserverPlacementGuard();

    /** 超时兜底（游戏刻）：登记后超过该时长未确认则放行 */
    private static final int CONFIRM_TIMEOUT_TICKS = 20;

    /** key = 输入面 pos.asLong()，value = 登记时的游戏刻 */
    private final Map<Long, Long> waiting = new HashMap<>();

    private ObserverPlacementGuard() {
    }

    /** 输入面是否存在未确认的本地预测（打印机刚放置、服务器尚未确认） */
    public boolean hasPendingPrediction(BlockPos inputPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || inputPos == null) {
            return false;
        }
        if (minecraft.level instanceof me.aleksilassila.litematica.printer.mixin_extension.ClientLevelExtension extension) {
            return extension.litematica_printer3$hasPendingPrediction(inputPos);
        }
        return false;
    }

    /** 登记输入面等待服务器确认（幂等） */
    public void requestConfirm(BlockPos inputPos) {
        if (inputPos == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        long tick = minecraft.level == null ? 0L : minecraft.level.getGameTime();
        waiting.putIfAbsent(inputPos.asLong(), tick);
    }

    /** 是否已确认（不在等待表即视为已确认/环境固有） */
    public boolean isConfirmed(BlockPos inputPos) {
        return inputPos == null || !waiting.containsKey(inputPos.asLong());
    }

    /** 服务器方块更新到达：命中等待表且新状态符合期望状态时解除等待 */
    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        if (pos == null || state == null || waiting.isEmpty()) {
            return;
        }
        long key = pos.asLong();
        if (!waiting.containsKey(key)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            waiting.remove(key);
            return;
        }
        // 状态与投影期望匹配（statesEqualIgnoreProperties，忽略 WATERLOGGED 等）
        BlockState required = minecraft.level.getBlockState(pos);
        if (BlockStateUtils.statesEqualIgnoreProperties(state, required)) {
            waiting.remove(key);
        }
    }

    /** 每游戏刻调用：超时兜底，强制放行卡住的输入面 */
    public void tick() {
        if (waiting.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            waiting.clear();
            return;
        }
        long currentTick = minecraft.level.getGameTime();
        Iterator<Map.Entry<Long, Long>> iterator = waiting.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Long> entry = iterator.next();
            long registered = entry.getValue();
            if (currentTick - registered >= CONFIRM_TIMEOUT_TICKS) {
                iterator.remove();
            }
        }
    }

    /** 世界切换/配置关闭时清空（防止跨维度残留） */
    public void reset() {
        waiting.clear();
    }
}
