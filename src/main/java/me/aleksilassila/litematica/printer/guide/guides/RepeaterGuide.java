package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.world.level.block.RepeaterBlock;

/**
 * 红石中继器
 */
public class RepeaterGuide extends Guide {

    public RepeaterGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        if (!getProperty(requiredState, RepeaterBlock.DELAY).equals(getProperty(currentState, RepeaterBlock.DELAY))) {
            return Result.success(new ClickAction());
        }
        // DELAY 一致时剩下的只有 POWERED/LOCKED 差异：由红石输入信号决定，无法用物品修正，
        // 挖掉重放后仍会随环境变回 → 必须跳过（同 ComparatorGuide）
        return Result.SKIP;
    }
}
