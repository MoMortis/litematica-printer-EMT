package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.HopperBlock;

/**
 * 漏斗
 */
public class HopperGuide extends Guide {

    public HopperGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        var hopperFacing = getProperty(requiredState, HopperBlock.FACING).orElseThrow();
        return Result.success(new Action().setSides(hopperFacing));
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        // 朝向放错的漏斗无法通过交互修正，只能破坏后重放；
        // 其他状态差异（如 ENABLED）不由放置控制，保持跳过
        Direction requiredFacing = getProperty(requiredState, HopperBlock.FACING).orElse(null);
        Direction currentFacing = getProperty(currentState, HopperBlock.FACING).orElse(null);
        if (requiredFacing != null && currentFacing != requiredFacing
                && Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                && Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue()
                && BreakUtils.canBreakBlock(blockPos)
                && BreakUtils.breakRestriction(level, blockPos, currentState)) {
            BreakUtils.INSTANCE.add(context);
        }
        return Result.SKIP;
    }
}
