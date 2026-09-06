package me.aleksilassila.litematica.printer.guide;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

public abstract class Guide extends BlockStateUtils {
    protected final SchematicBlockContext context;
    public final Minecraft client;
    public final ClientLevel level;
    public final WorldSchematic schematic;
    public final BlockPos blockPos;
    public final BlockState currentState;
    public final BlockState requiredState;
    protected final Block currentBlock;
    protected final Block requiredBlock;

    public Guide(SchematicBlockContext context) {
        this.context = context;
        this.client = context.client;
        this.level = context.level;
        this.schematic = context.schematic;
        this.blockPos = context.blockPos;
        this.currentBlock = context.currentState.getBlock();
        this.requiredBlock = context.requiredState.getBlock();
        this.currentState = context.currentState;
        this.requiredState = context.requiredState;
    }

    /**
     * 构建 Action 的入口方法。
     */
    public final Result buildAction(BlockMatchResult state) {
        // 前置检查: 完全一致
        if (state == BlockMatchResult.CORRECT) {
            return this.onBuildActionCorrect(state);
        }

        if (state == BlockMatchResult.MISSING) {
            // 水相关方块由 WaterGuide 特判处理，不能在这里提前拦掉
            if (!BlockStateUtils.isWaterBlock(requiredState) && !requiredState.canSurvive(level, blockPos)) {
                return Result.PASS;
            }
            // 双格方块的上半部分由下半部分生成，缺失时不独立放置
            if (requiredState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && requiredState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
                return Result.PASS;
            }
        }

        // 水生植物（海草等）需要水环境才能放置
        if (BlockStateUtils.requiresWaterToPlace(requiredBlock)) {
            if (!BlockStateUtils.hasSourceWaterFluid(level.getBlockState(blockPos))) {
                return Result.PASS;
            }
        }

        // 交给子类的 onBuildAction 拦截钩子
        Result result = this.onBuildAction(state);
        if (!result.passToNext() || result.skipOtherGuide()) {
            return result;
        }

        // 分状态分发
        return switch (state) {
            case MISSING -> this.onBuildActionMissingBlock(state);
            case WRONG_BLOCK -> this.onBuildActionWrongBlock(state);
            case WRONG_STATE -> this.onBuildActionWrongState(state);
            default -> Result.PASS;
        };
    }

    /**
     * 检查此 Guide 是否应该处理当前方块
     * 可被子类覆盖以实现更细粒度的过滤
     * @return true 表示应该执行此 Guide
     */
    protected boolean canExecute() {
        return true;
    }

    // -------------------------------------------------------
    // 子类钩子
    // -------------------------------------------------------

    /**
     * 所有状态均会先经过此钩子，可在此拦截任意状态
     */
    protected Result onBuildAction(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 位置为空气 / 可替换方块：需要放置
     */
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 方块类型完全不同：需要先破坏再放置
     */
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 方块类型相同但状态不对：可能需要交互修正
     */
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 完全正确：通常无需操作
     */
    protected Result onBuildActionCorrect(BlockMatchResult state) {
        return Result.PASS;
    }

    /**
     * 按目标方块状态推导通用放置上下文（附着面/轴向/朝向/上下半等），
     * 供 DefaultGuide 兜底放置与代替放置（代替方块套用目标状态）共用。
     */
    protected Action buildTargetStateContext(Action action) {
        // 获取属性
        Direction facing = getProperty(requiredState, BlockStateProperties.FACING)
                .or(() -> getProperty(requiredState, BlockStateProperties.HORIZONTAL_FACING))
                .or(() -> getProperty(requiredState, BlockStateProperties.VERTICAL_DIRECTION))
                .or(() -> getProperty(requiredState, BlockStateProperties.FACING_HOPPER))
                .orElse(null);
        Direction.Axis axis = getProperty(requiredState, BlockStateProperties.AXIS)
                .or(() -> getProperty(requiredState, BlockStateProperties.HORIZONTAL_AXIS))
                .orElse(null);
        Half half = getProperty(requiredState, BlockStateProperties.HALF).orElse(null);
        AttachFace attachFace = getProperty(requiredState, BlockStateProperties.ATTACH_FACE).orElse(null);

        // 1. 附着面方块（按钮、拉杆等 FaceAttachedHorizontalDirectionalBlock）
        if (requiredBlock instanceof FaceAttachedHorizontalDirectionalBlock && facing != null && attachFace != null) {
            Direction sidePitch = attachFace == AttachFace.CEILING ? Direction.UP
                    : attachFace == AttachFace.FLOOR ? Direction.DOWN
                    : facing;
            Direction clickSide = attachFace == AttachFace.WALL ? facing : facing.getOpposite();
            return action
                    .setSides(clickSide)
                    .setLookDirection(clickSide.getOpposite(), sidePitch)
                    .setNeedWaitModifyLook();
        }

        // 2. 轴向方块（原木、锁链等）
        if (axis != null) {
            action.setSides(axis);
        }

        // 3. 朝向方块
        if (facing != null && axis == null) {
            // 水平方向方块（HorizontalDirectionalBlock、石切机等）
            if (requiredBlock instanceof HorizontalDirectionalBlock
                    || requiredBlock instanceof StonecutterBlock
                    //#if MC >= 12105
                    || requiredBlock instanceof FlowerBedBlock
                    //#endif
            ) {
                // 栅栏门已由 FenceGateGuide 处理，这里不再特殊反向
                action.setLookDirection(facing.getOpposite());
            }
            // BaseEntityBlock（篝火、装饰盆等）
            if (requiredBlock instanceof BaseEntityBlock) {
                if (requiredState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                    Direction entityFacing = facing;
                    //#if MC >= 11904
                    if (requiredBlock instanceof DecoratedPotBlock || requiredBlock instanceof CampfireBlock) {
                        entityFacing = entityFacing.getOpposite();
                    }
                    //#endif
                    action.setSides(entityFacing).setLookDirection(entityFacing.getOpposite());
                }
                if (requiredState.hasProperty(BlockStateProperties.FACING)) {
                    Direction entityFacing = facing;
                    if (requiredBlock instanceof ShulkerBoxBlock) {
                        entityFacing = entityFacing.getOpposite();
                        action.setShift();
                    }
                    if (requiredBlock instanceof BarrelBlock || requiredBlock instanceof DispenserBlock) {
                        action.setNeedWaitModifyLook();
                    }
                    action.setSides(entityFacing).setLookDirection(entityFacing.getOpposite());
                }
            }
            // 普通观察器、楼梯、栅栏门：看正向
            if (requiredBlock instanceof ObserverBlock
                    || requiredBlock instanceof StairBlock
                    || requiredBlock instanceof FenceGateBlock) {
                action.setLookDirection(facing);
            } else if (!(requiredBlock instanceof HorizontalDirectionalBlock)
                    && !(requiredBlock instanceof BaseEntityBlock)) {
                // 其余方块反向放置
                action.setLookDirection(facing.getOpposite());
            }
        }

        // 4. Half 属性兜底
        if (half != null && facing == null) {
            action.setSides(half == Half.BOTTOM
                    ? Direction.DOWN : Direction.UP);
        }

        return action;
    }

}
