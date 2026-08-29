package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

/**
 * 活塞放置
 */
public class PistonGuide extends Guide {

    public PistonGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, PistonBaseBlock.FACING).orElse(null);
        if (facing == null) return Result.SKIP;
        return Result.success(new Action()
                .setLookDirection(facing.getOpposite())
                .setNeedWaitModifyLook());
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.SKIP;
    }
}
