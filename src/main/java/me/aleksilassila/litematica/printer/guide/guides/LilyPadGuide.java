package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.WaterlilyBlock;

/**
 * 睡莲（水生植物，放置在水面上方）
 */
public class LilyPadGuide extends Guide {

    public LilyPadGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected boolean canExecute() {
        return requiredBlock instanceof WaterlilyBlock;
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        // 睡莲必须放置在水面上：固定点击下方那格水方块（使用放置方块的方式）
        return Result.success(new Action()
                .setSides(Direction.DOWN)
                .setFixedSide(Direction.DOWN)
                .setRequiresSupport());
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        return Result.PASS;
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.PASS;
    }
}
