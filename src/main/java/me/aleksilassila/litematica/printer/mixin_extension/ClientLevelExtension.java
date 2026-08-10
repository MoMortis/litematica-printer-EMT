package me.aleksilassila.litematica.printer.mixin_extension;

import net.minecraft.core.BlockPos;

/**
 * ClientLevel 扩展接口：暴露本地预测待确认状态查询，
 * 供侦测器安全放置守卫判断输入面方块是否"刚放置、服务器尚未确认"。
 */
public interface ClientLevelExtension {
    /** 该位置是否存在未确认的本地预测（isPredicting 且 serverVerifiedStates 含该 pos） */
    boolean litematica_printer3$hasPendingPrediction(BlockPos pos);
}
