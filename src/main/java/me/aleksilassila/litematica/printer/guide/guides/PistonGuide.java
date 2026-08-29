package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;

import java.util.HashSet;
import java.util.Set;

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
        if (Configs.Print.SAFELY_OBSERVER.getBooleanValue() && !areObserverOutputChainsReady()) {
            return Result.SKIP;
        }
        return Result.success(new Action()
                .setLookDirection(facing.getOpposite())
                .setNeedWaitModifyLook());
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.SKIP;
    }

    private boolean areObserverOutputChainsReady() {
        for (Direction direction : Direction.values()) {
            if (!isObserverOutputChainReady(direction)) {
                return false;
            }
        }
        return true;
    }

    private boolean isObserverOutputChainReady(Direction direction) {
        Set<BlockPos> visited = new HashSet<>();
        SchematicBlockContext temp = context.offset(direction);
        // 链上位置可能超出投影范围，此时 requiredState 为 null，视为非观察者（链路就绪）
        while (temp.requiredState != null && temp.requiredState.getBlock() instanceof ObserverBlock) {
            if (!visited.add(temp.blockPos)) {
                return true;
            }
            Direction observerFacing = getProperty(temp.requiredState, ObserverBlock.FACING).orElse(null);
            if (observerFacing == null) {
                return false;
            }
            SchematicBlockContext observed = temp.offset(observerFacing);
            if (observerFacing == direction && observed.requiredState != null
                    && BlockMatchResult.compare(observed) != BlockMatchResult.CORRECT) {
                return false;
            }
            temp = observed;
        }
        return true;
    }
}
