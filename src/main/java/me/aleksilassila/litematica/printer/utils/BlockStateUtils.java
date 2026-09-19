package me.aleksilassila.litematica.printer.utils;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Optional;
import java.util.Set;

@SuppressWarnings("EnhancedSwitchMigration")
public class BlockStateUtils extends BlockUtils {
    /**
     * 客户端"该区块列是否真的加载"。
     *
     * <p><b>不能用 {@code ClientLevel.hasChunk}：它在客户端恒返回 true</b>（字节码就是 {@code return true}），
     * 拿它当"已加载"判据等于没判——未加载列读到的是空气，会被当成"可通行 / 待放置候选"。
     * 这里走区块源：{@code getChunkSource().hasChunk(x, z)}，其默认实现是
     * {@code getChunk(x, z, FULL, false) != null}；客户端该分支在 load=false 时未加载直接返回 null
     *（load=true 才回退空区块），所以判据可靠。
     *
     * <p>纯读（只读客户端区块存储的原子数组），可在工作线程调用。
     */
    public static boolean isColumnLoaded(ClientLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().hasChunk(chunkX, chunkZ);
    }

    private final static BooleanProperty wallUpProperty = BlockStateProperties.UP;
    private final static EnumProperty<WallSide> wallNorthProperty = BlockStateProperties.NORTH_WALL;
    private final static EnumProperty<WallSide> wallSouthProperty = BlockStateProperties.SOUTH_WALL;
    private final static EnumProperty<WallSide> wallWestProperty = BlockStateProperties.WEST_WALL;
    private final static EnumProperty<WallSide> wallEastProperty = BlockStateProperties.EAST_WALL;
    private final static BooleanProperty northProperty = BlockStateProperties.NORTH;
    private final static BooleanProperty southProperty = BlockStateProperties.SOUTH;
    private final static BooleanProperty westProperty = BlockStateProperties.WEST;
    private final static BooleanProperty eastProperty = BlockStateProperties.EAST;

    public static boolean statesEqualIgnoreProperties(BlockState state1, BlockState state2, Property<?>... propertiesToIgnore) {
        if (state1.getBlock() != state2.getBlock()) {
            return false;
        }
        loop:
        for (Property<?> property : state1.getProperties()) {
            if (property == BlockStateProperties.WATERLOGGED && !(state1.getBlock() instanceof CoralPlantBlock)) {
                continue;
            }
            for (Property<?> ignoredProperty : propertiesToIgnore) {
                if (property == ignoredProperty) {
                    continue loop;
                }
            }
            try {
                if (!state1.getValue(property).equals(state2.getValue(property))) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    /**
     * 环境动态属性（按属性名匹配，跨版本稳定）：取值由红石信号、随机刻、邻居更新等环境因素决定，
     * 既无法通过放置固化，玩家也没有物品交互能直接设置（拉杆等少数例外由各自 Guide 的点击动作处理，
     * 不会落到破坏判定）。这类属性的差异破坏重放后仍会随环境变回，只产生无限"破坏→放置"循环。
     */
    private static final Set<String> DYNAMIC_STATE_PROPERTIES = Set.of(
            // 红石供能类：按钮/压力板/拉杆/标靶/避雷针/钟/讲台/绊线/红石火把/红石灯/篝火/蜡烛等
            "powered", "lit",
            // 供能衍生态：中继器锁定、活塞推拉、漏斗锁停、发射器脉冲、阳光传感器反转
            "extended", "locked", "enabled", "triggered", "inverted",
            // 信号强度类：红石线/阳光传感器/潜声传感器 0-15，以及容器类"level"（堆肥桶/炼药锅等）
            "power", "level",
            // 振动/瞬态：潜声传感器相位与嘶吼、大垂叶倾倒、TNT 点燃态、绊线连接与拆除态
            "sculk_sensor_phase", "shrieking", "can_summon", "tilt", "unstable", "attached", "disarmed",
            // 随机刻生长/环境衰退：作物/甘蔗/仙人掌/海带/紫颂花 AGE、竹子 STAGE/LEAVES、
            // 蜂巢储蜜、重生锚充能、蛋糕被食用、耕地湿润、草方块积雪、霜冰融化
            "age", "stage", "leaves", "honey_level", "charges", "bites", "moisture", "snowy",
            // 邻居重算类：火苗蔓延方向、脚手架/树叶距离与底部标记、讲台有无书
            "north", "east", "south", "west", "up", "down", "distance", "bottom", "has_book"
    );

    /**
     * 与 {@link #statesEqualIgnoreProperties(BlockState, BlockState, Property[])} 同口径（忽略 WATERLOGGED），
     * 再忽略 {@link #DYNAMIC_STATE_PROPERTIES} 中的环境动态属性。
     *
     * @return true 表示存在"非动态"的状态差异（如朝向/朝半/旋转等放置相关属性），
     *         破坏重放才可能修正；false 表示差异全部来自环境动态属性，破坏无意义
     */
    public static boolean hasFixableStateDifference(BlockState state1, BlockState state2) {
        if (state1.getBlock() != state2.getBlock()) {
            return true;
        }
        for (Property<?> property : state1.getProperties()) {
            if (property == BlockStateProperties.WATERLOGGED && !(state1.getBlock() instanceof CoralPlantBlock)) {
                continue;
            }
            if (DYNAMIC_STATE_PROPERTIES.contains(property.getName())) {
                continue;
            }
            try {
                if (!state1.getValue(property).equals(state2.getValue(property))) {
                    return true;
                }
            } catch (Exception e) {
                return true;
            }
        }
        return false;
    }

    public static <T extends Comparable<T>> Optional<T> getProperty(BlockState blockState, Property<T> property) {
        if (blockState.hasProperty(property)) {
            return Optional.of(blockState.getValue(property));
        }
        return Optional.empty();
    }

    public static boolean statesEqual(BlockState state1, BlockState state2) {
        return statesEqualIgnoreProperties(state1, state2);
    }

    protected static boolean canBeClicked(Level world, BlockPos pos) {
        return getOutlineShape(world, pos) != Shapes.empty();
    }

    private static VoxelShape getOutlineShape(Level level, BlockPos pos) {
        return level.getBlockState(pos).getShape(level, pos);
    }

    private static VoxelShape getOutlineShape(BlockState state, Level level, BlockPos pos) {
        return state.getShape(level, pos);
    }

    public static Optional<Property<?>> getWallFacingProperty(Direction wallFacing) {
        switch (wallFacing) {
            case UP:
                return Optional.of(wallUpProperty);
            case NORTH:
                return Optional.of(wallNorthProperty);
            case SOUTH:
                return Optional.of(wallSouthProperty);
            case WEST:
                return Optional.of(wallWestProperty);
            case EAST:
                return Optional.of(wallEastProperty);
        }
        return Optional.empty();
    }

    public static Optional<Property<?>> getCrossCollisionBlock(Direction wallFacing) {
        switch (wallFacing) {
            case NORTH:
                return Optional.of(northProperty);
            case SOUTH:
                return Optional.of(southProperty);
            case WEST:
                return Optional.of(westProperty);
            case EAST:
                return Optional.of(eastProperty);
        }
        return Optional.empty();
    }

    /**
     * 判断该方块是否是含水方块
     *
     * @param blockState 要判断的方块
     * @return 是否含水（是水）
     */
    public static boolean isWaterBlock(BlockState blockState) {
        return blockState.is(Blocks.WATER) && blockState.getValue(LiquidBlock.LEVEL) == 0
                || (blockState.hasProperty(BlockStateProperties.WATERLOGGED) && blockState.getValue(BlockStateProperties.WATERLOGGED))
                || blockState.getBlock() instanceof BubbleColumnBlock;
    }

    public static boolean hasSourceWaterFluid(BlockState blockState) {
        FluidState fluidState = blockState.getFluidState();
        return fluidState.is(FluidTags.WATER) && fluidState.isSource();
    }

    private static boolean isSourceWaterOrBubbleColumn(BlockState blockState) {
        if (!hasSourceWaterFluid(blockState)) {
            return false;
        }
        return blockState.is(Blocks.WATER) || blockState.getBlock() instanceof BubbleColumnBlock;
    }

    /**
     * 判断该方块是否需要水中才能放置（水生植物等）。
     * 这些方块虽然 canSurvive 可能返回 true（只检查支撑），但实际放置需要水。
     * 没有水时跳过放置，避免死循环切换物品。
     *
     * @param block 要判断的方块
     * @return 是否需要水环境
     */
    public static boolean requiresWaterToPlace(Block block) {
        return block instanceof SeagrassBlock
                || block instanceof KelpBlock
                || block instanceof KelpPlantBlock;
    }

    public static boolean isCorrectWaterLevel(BlockState requiredState, BlockState currentState) {
        if (requiredState.is(Blocks.WATER)) {
            if (currentState.is(Blocks.WATER)) {
                return currentState.getValue(LiquidBlock.LEVEL).equals(requiredState.getValue(LiquidBlock.LEVEL));
            }
            return requiredState.getValue(LiquidBlock.LEVEL) == 0
                    && currentState.getBlock() instanceof BubbleColumnBlock
                    && hasSourceWaterFluid(currentState);
        }
        if (requiredState.getBlock() instanceof BubbleColumnBlock) {
            return isSourceWaterOrBubbleColumn(currentState);
        }
        return isSourceWaterOrBubbleColumn(currentState);
    }
}
