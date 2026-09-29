package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.world.level.block.LadderBlock;

/**
 * 梯子
 */
public class LadderGuide extends Guide {

    public LadderGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        var ladderFacing = getProperty(requiredState, LadderBlock.FACING).orElseThrow();
        // 原版 canSurvive 要求 FACING.getOpposite()（梯子背后）有支撑：
        // sides 的 key = 从目标位指向被点击邻块的方向，支撑墙在 FACING 反侧，
        // 关闭凭空放置时点击前方空气会导致 getValidSide 返回 null、动作被丢弃
        return Result.success(new Action()
                .setSides(ladderFacing.getOpposite())
                .setLookDirection(ladderFacing.getOpposite()));
    }
}
