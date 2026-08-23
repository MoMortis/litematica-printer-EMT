package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.List;

/**
 * 侦测器
 */
public class ObserverGuide extends Guide {

    public ObserverGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, ObserverBlock.FACING).orElseThrow();

        if (!Configs.Print.SAFELY_OBSERVER.getBooleanValue()) {
            return Result.success(new Action()
                    .setLookDirection(facing)
                    .setNeedWaitModifyLook());
        }

        // 安全放置模式：只检查输入面（侦测面）是否与原理图一致。
        // 一致则放置，不一致则跳过，等下一轮遍历再试。
        SchematicBlockContext input = context.offset(facing);
        if (!LitematicaUtils.isSchematicBlock(input.blockPos)) {
            return Result.success(placementAction(facing));
        }
        List<Property<?>> inputPropertiesToIgnore = new ArrayList<>();
        BlockMatchResult inputState = BlockMatchResult.compare(
                input,
                inputPropertiesToIgnore.toArray(new Property<?>[0])
        );
        if (inputState != BlockMatchResult.CORRECT) {
            return Result.SKIP;
        }
        return Result.success(placementAction(facing));
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.SKIP;
    }

    private static Action placementAction(Direction facing) {
        return new Action()
                .setLookDirection(facing)
                .setNeedWaitModifyLook();
    }
}
