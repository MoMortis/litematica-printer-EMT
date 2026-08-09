package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 水源/含水方块的无状态兜底规则。
 * 跨 tick 破冰放水流程由 PrintTaskController 接管。
 */
public class WaterGuide extends Guide {
    public WaterGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected boolean canExecute() {
        return BlockStateUtils.isWaterBlock(requiredState);
    }

    @Override
    protected Result onBuildAction(BlockMatchResult state) {
        // 错误方块（如泥土占位）：放行给后续 Guide（DefaultGuide）先破坏，破坏完后再走破冰放水流程
        if (state == BlockMatchResult.WRONG_BLOCK) {
            return Result.PASS;
        }
        if (isWaterloggedTarget()) {
            return Result.PASS;
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionCorrect(BlockMatchResult state) {
        return isWaterloggedTarget() ? Result.PASS : Result.SKIP;
    }

    private boolean isWaterloggedTarget() {
        return requiredState.hasProperty(BlockStateProperties.WATERLOGGED)
                && requiredState.getValue(BlockStateProperties.WATERLOGGED);
    }
}
