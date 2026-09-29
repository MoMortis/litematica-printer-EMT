package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 栅栏门
 */
public class FenceGateGuide extends Guide {

    public FenceGateGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, net.minecraft.world.level.block.FenceGateBlock.FACING).orElse(null);
        return Result.success(new Action().setLookDirection(facing));
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, FenceGateBlock.FACING).orElseThrow();

        Direction currentFacing = getProperty(currentState, BlockStateProperties.HORIZONTAL_FACING).orElse(null);
        boolean openMismatch = getProperty(requiredState, BlockStateProperties.OPEN)
                .map(open -> !open.equals(getProperty(currentState, BlockStateProperties.OPEN).orElse(null)))
                .orElse(false);

        // 原版对已放置的栅栏门右键只能切换 OPEN，无法旋转朝向。
        // 只有在朝向一致、仅开关不符时点击才有意义；朝向相反时点击会陷入
        // 无限"开门-关门"循环（开→开态不符→关→关态不符→…）且永不修正，
        // 必须交给 DefaultGuide 的破坏重放路径修复
        boolean facingOk = facing == currentFacing;
        if (facingOk && openMismatch) {
            return Result.success(new ClickAction()
                    .setSides(facing.getOpposite())
                    .setLookDirection(facing));
        }
        return Result.PASS;
    }
}
