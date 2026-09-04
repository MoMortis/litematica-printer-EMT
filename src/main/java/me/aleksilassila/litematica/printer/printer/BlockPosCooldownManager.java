package me.aleksilassila.litematica.printer.printer;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * 方块冷却管理器（方案三 long 键优化版）。
 *
 * <p>旧实现每次查询/设置都 {@code new Info(dimension, type, pos)} 并 {@code Objects.hash}，
 * 在每格扫描的热路径上是纯 GC 压力。现改为三层结构 {@code type -> dimension -> (posKey -> expiryTick)}：
 * 键为 {@code pos.asLong()}，值为到期游戏刻；懒过期 + 周期清扫，热路径零分配。
 * 对外语义（isOnCooldown / setCooldown 等）与旧实现完全一致。
 */
public class BlockPosCooldownManager {
    public static final BlockPosCooldownManager INSTANCE = new BlockPosCooldownManager();

    /** 清扫周期（tick）：过期条目懒移除，兜底防内存增长 */
    private static final long SWEEP_INTERVAL_TICKS = 100;

    /** type -> (dimension -> (posKey -> expiryTick)) */
    private final Map<String, Map<Identifier, Long2LongOpenHashMap>> cooldowns = new HashMap<>();
    private long lastSweepTick = Long.MIN_VALUE;

    /**
     * 冷却刻数维护：printer 关闭时整体清空（保持旧行为）；开启时周期清扫过期条目
     */
    public void tick() {
        if (!ConfigUtils.isPrinterEnable()) {
            if (!cooldowns.isEmpty()) {
                cooldowns.clear();
            }
            return;
        }
        if (cooldowns.isEmpty()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        if (lastSweepTick != Long.MIN_VALUE && now - lastSweepTick < SWEEP_INTERVAL_TICKS) {
            return;
        }
        lastSweepTick = now;
        for (Map<Identifier, Long2LongOpenHashMap> byDim : cooldowns.values()) {
            for (Long2LongOpenHashMap map : byDim.values()) {
                if (!map.isEmpty()) {
                    map.long2LongEntrySet().removeIf(e -> e.getLongValue() <= now);
                }
            }
        }
    }

    /**
     * 设置冷却
     */
    public void setCooldown(ClientLevel level, String type, BlockPos pos, int cooldownTicks) {
        if (cooldownTicks <= 0) return;
        mapForWrite(level, type).put(pos.asLong(), level.getGameTime() + cooldownTicks);
    }

    /**
     * 判断指定方块是否处于冷却中
     *
     * @return true=冷却中，false=未冷却/无冷却
     */
    public boolean isOnCooldown(ClientLevel level, String type, BlockPos pos) {
        Map<Identifier, Long2LongOpenHashMap> byDim = cooldowns.get(type);
        if (byDim == null) return false;
        Long2LongOpenHashMap map = byDim.get(level.dimension().identifier());
        if (map == null) return false;
        long key = pos.asLong();
        long expiry = map.get(key);
        if (expiry == 0L) return false;
        if (expiry <= level.getGameTime()) {
            map.remove(key);
            return false;
        }
        return true;
    }

    /**
     * 手动移除指定方块的冷却（强制取消冷却）
     */
    public void removeCooldown(ClientLevel level, String type, BlockPos pos) {
        Map<Identifier, Long2LongOpenHashMap> byDim = cooldowns.get(type);
        if (byDim == null) return;
        Long2LongOpenHashMap map = byDim.get(level.dimension().identifier());
        if (map != null) {
            map.remove(pos.asLong());
        }
    }

    /**
     * 获取指定方块的剩余冷却刻数
     *
     * @return 剩余冷却刻数，未冷却则返回0
     */
    public int getRemainingCooldown(ClientLevel level, String type, BlockPos pos) {
        Map<Identifier, Long2LongOpenHashMap> byDim = cooldowns.get(type);
        if (byDim == null) return 0;
        Long2LongOpenHashMap map = byDim.get(level.dimension().identifier());
        if (map == null) return 0;
        long expiry = map.get(pos.asLong());
        long remaining = expiry - level.getGameTime();
        return remaining > 0 ? (int) Math.min(remaining, Integer.MAX_VALUE) : 0;
    }

    /**
     * 清空指定维度的所有冷却数据
     */
    public void clearDimensionCooldowns(ClientLevel level) {
        Identifier dimension = level.dimension().identifier();
        for (Map<Identifier, Long2LongOpenHashMap> byDim : cooldowns.values()) {
            byDim.remove(dimension);
        }
    }

    /**
     * 清空指定维度+指定类型的所有冷却数据（如清空某维度所有打印冷却）
     */
    public void clearTypeCooldowns(ClientLevel level, String type) {
        Map<Identifier, Long2LongOpenHashMap> byDim = cooldowns.get(type);
        if (byDim != null) {
            byDim.remove(level.dimension().identifier());
        }
    }

    /**
     * 清空所有冷却数据（模组重载/退出游戏/全局重置时调用）
     */
    public void clearAllCooldowns() {
        cooldowns.clear();
    }

    private Long2LongOpenHashMap mapForWrite(ClientLevel level, String type) {
        Map<Identifier, Long2LongOpenHashMap> byDim = cooldowns.get(type);
        if (byDim == null) {
            byDim = new HashMap<>();
            cooldowns.put(type, byDim);
        }
        Identifier dim = level.dimension().identifier();
        Long2LongOpenHashMap map = byDim.get(dim);
        if (map == null) {
            map = new Long2LongOpenHashMap();
            byDim.put(dim, map);
        }
        return map;
    }
}
