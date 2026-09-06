package me.aleksilassila.litematica.printer.guide;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import net.minecraft.world.level.block.LiquidBlock;

/**
 * 通用兜底指南。
 * 处理所有没有被专用 Guide 接管的方块。
 *
 * <p>优先级最低，应最后注册。
 */
public class DefaultGuide extends Guide {

    public DefaultGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.success(buildTargetStateContext(new Action()));
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        boolean printBreakWrongBlock = Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue();
        boolean printBreakExtraBlock = Configs.Print.BREAK_EXTRA_BLOCK.getBooleanValue();
        if (printBreakWrongBlock || printBreakExtraBlock) {
            if (BreakUtils.canBreakBlock(blockPos) && BreakUtils.breakRestriction(level, blockPos, currentState)) {
                if (printBreakWrongBlock && !requiredState.isAir()) {
                    BreakUtils.INSTANCE.add(context);
                } else if (printBreakExtraBlock && requiredState.isAir()
                        && !(currentState.getBlock() instanceof LiquidBlock)) {
                    BreakUtils.INSTANCE.add(context);
                }
            }
        }
        return Result.PASS;
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                && Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue()
                && BreakUtils.canBreakBlock(blockPos)
                && BreakUtils.breakRestriction(level, blockPos, currentState)) {
            BreakUtils.INSTANCE.add(context);
        }
        return Result.PASS;
    }
}
