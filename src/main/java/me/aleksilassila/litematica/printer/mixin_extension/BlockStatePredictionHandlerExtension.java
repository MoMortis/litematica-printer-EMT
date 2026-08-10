package me.aleksilassila.litematica.printer.mixin_extension;

import net.minecraft.core.BlockPos;

/**
 * BlockStatePredictionHandler 扩展接口：暴露"服务器待确认状态"包含查询，
 * 供侦测器安全放置守卫判断输入面方块是否刚放置、服务器尚未确认。
 */
public interface BlockStatePredictionHandlerExtension {
    /** 该位置是否存在服务器待确认状态（本地预测中） */
    boolean litematica_printer3$contains(BlockPos pos);
}
