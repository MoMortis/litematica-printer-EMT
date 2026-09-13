package me.aleksilassila.litematica.printer.printer.verifier;

import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 优化版验证器的跨维度注册表：键为放置的 {@code getHashId()}（UUID，随放置
 * JSON 往返稳定）。litematica 在维度切换时会清空并从 JSON 重建全部放置对象
 * （对象是新的、验证器字段为空），注册表使 {@code MixinSchematicPlacement}
 * 能按 hashId 把旧验证器实例重新挂接回新放置对象——错误数据、忽略设置、
 * 高亮选择与完成状态因此跨维度保留。
 *
 * <p>条目在 {@link OptimizedSchematicVerifier#reset()}（放置被删除/手动重置）
 * 时移除；断开连接时整体清空（防跨服务器泄漏）。
 */
public final class VerifierRegistry {
    private static final Map<UUID, OptimizedSchematicVerifier> BY_HASH = new ConcurrentHashMap<>();

    private VerifierRegistry() {
    }

    // 如果不注册无法监听断开连接
    public static void init() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BY_HASH.clear());
    }

    public static OptimizedSchematicVerifier get(UUID hashId) {
        return hashId == null ? null : BY_HASH.get(hashId);
    }

    public static void put(UUID hashId, OptimizedSchematicVerifier verifier) {
        if (hashId != null && verifier != null) {
            BY_HASH.put(hashId, verifier);
        }
    }

    public static void remove(UUID hashId) {
        if (hashId != null) {
            BY_HASH.remove(hashId);
        }
    }
}
