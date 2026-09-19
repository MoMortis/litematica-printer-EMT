package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.world.level.block.ComparatorBlock;

/**
 * 红石比较器
 */
public class ComparatorGuide extends Guide {

    public ComparatorGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        if (!getProperty(requiredState, ComparatorBlock.MODE).equals(getProperty(currentState, ComparatorBlock.MODE))) {
            return Result.success(new ClickAction());
        }
        // MODE 一致时剩下的只有 POWERED 差异：激活状态由红石输入信号决定，玩家无法用物品修正，
        // 挖掉重放后仍会随环境变回 → 必须跳过，否则 BREAK_WRONG_STATE_BLOCK 开启时会无限破坏重放
        return Result.SKIP;
    }
}
