package me.aleksilassila.litematica.printer.printer.action;

import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

public class Action {
    private static final Direction[] DEFAULT_SIDE_ORDER = {
            Direction.UP,
            Direction.NORTH,
            Direction.SOUTH,
            Direction.EAST,
            Direction.WEST,
            Direction.DOWN
    };

    protected Map<Direction, Vec3> sides;
    protected boolean customSides;

    @Nullable
    @Getter
    protected PlayerLook playerLook = null;

    @Nullable
    protected Item[] clickItems; // null == 空手
    protected boolean requiresSupport = false;
    protected boolean clickLiquidSupport = false;

    /**
     * 固定放置方向：设置后 getValidSide 直接返回该方向，跳过所有支撑面校验
     * （用于睡莲等必须点击特定方块/液面的场景）
     */
    @Nullable
    protected Direction fixedSide = null;

    @Getter
    @Nullable
    protected Boolean shift = null;

    @Getter
    protected boolean consumeEffectiveExecution = true;

    @Getter
    protected int cooldownTicksOverride = -1;

    @Getter
    protected int clickRepeatCount = 1;

    @Getter
    protected Boolean needWaitModifyLook = false;

    @Getter
    protected boolean waitForHorizontalLook = true;

    @Getter
    @Nullable
    protected Predicate<ItemStack> requiredStackPredicate;

    @Getter
    @Nullable
    protected ItemStack requiredCreativeStack;

    @Getter
    protected ActionManager.ActionSource actionSource = ActionManager.ActionSource.GENERIC;

    public Action setActionSource(ActionManager.ActionSource actionSource) {
        this.actionSource = actionSource;
        return this;
    }

    public Action() {
        this.sides = createDefaultSides();
        this.customSides = false;
    }

    public Action setLookRotation(int lookRotation) {
        this.playerLook = new PlayerLook(lookRotation);
        return this;
    }

    public Action setLookDirection(Direction lookDirection) {
        this.playerLook = new PlayerLook(lookDirection);
        return this;
    }

    public Action setLookDirection(Direction lookDirectionYaw, Direction lookDirectionPitch) {
        this.playerLook = new PlayerLook(lookDirectionYaw, lookDirectionPitch);
        return this;
    }

    public Action setNeedWaitModifyLook(boolean needWaitModifyLook) {
        this.needWaitModifyLook = needWaitModifyLook;
        return this;
    }

    public Action setNeedWaitModifyLook() {
        return this.setNeedWaitModifyLook(true);
    }

    public Action setWaitForHorizontalLook(boolean waitForHorizontalLook) {
        this.waitForHorizontalLook = waitForHorizontalLook;
        return this;
    }

    public @Nullable Item[] getRequiredItems(Block backup) {
        return clickItems == null ? new Item[]{backup.asItem()} : clickItems;
    }

    public @NotNull Map<Direction, Vec3> getSides() {
        if (this.sides == null) {
            this.sides = createDefaultSides();
        }
        return this.sides;
    }

    protected @NotNull List<Direction> getOrderedSides() {
        return new ArrayList<>(getSides().keySet());
    }

    public Action setSides(Direction.Axis... axis) {
        Map<Direction, Vec3> sides = new LinkedHashMap<>();
        for (Direction.Axis a : axis) {
            for (Direction d : DEFAULT_SIDE_ORDER) {
                if (d.getAxis() == a) {
                    sides.put(d, new Vec3(0, 0, 0));
                }
            }
        }
        this.sides = sides;
        this.customSides = true;
        return this;
    }

    public Action setSides(Map<Direction, Vec3> sides) {
        this.sides = copySidesInDefaultOrder(sides);
        this.customSides = true;
        return this;
    }

    public Action setSides(Direction side, Vec3 offset) {
        this.sides = new LinkedHashMap<>();
        this.sides.put(side, offset);
        this.customSides = true;
        return this;
    }

    public Action setSides(Direction... directions) {
        Map<Direction, Vec3> sides = new LinkedHashMap<>();
        for (Direction d : directions) {
            sides.put(d, new Vec3(0, 0, 0));
        }
        this.sides = sides;
        this.customSides = true;
        return this;
    }

    @SuppressWarnings("SequencedCollectionMethodCanBeUsed")
    public @Nullable Direction getValidSide(ClientLevel world, BlockPos pos) {
        // 固定方向由具体 Guide 优先决定，例如睡莲等必须点击特定面。
        if (this.fixedSide != null) {
            return this.fixedSide;
        }
        // 全局强制方向作用于所有未设置固定方向的放置动作。
        Direction forcedDirection = getForcedDirection();
        if (forcedDirection != null) {
            return forcedDirection;
        }
        List<Direction> orderedSides = getOrderedSides();
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue() && !this.requiresSupport) {
            return orderedSides.isEmpty() ? null : orderedSides.get(0);
        }
        if (!this.customSides) {
            sortSidesByPlayerView(orderedSides, pos);
        }
        Direction firstValidSide = null;
        BlockState currentState = world.getBlockState(pos);
        for (Direction side : orderedSides) {
            BlockPos neighborPos = pos.relative(side);
            BlockState neighborState = world.getBlockState(neighborPos);
            boolean liquidSupport = clickLiquidSupport && !neighborState.getFluidState().isEmpty();
            boolean clickable = BlockUtils.canBeClicked(world, neighborPos) || liquidSupport;
            boolean replaceable = BlockUtils.isReplaceable(neighborState) && !liquidSupport;
            if (clickable && !replaceable) {
                if (firstValidSide == null) {
                    firstValidSide = side;
                }
                // 选择一个不需要潜行放置的面
                if (!Implementation.isInteractive(neighborState.getBlock()) && currentState.canSurvive(world, pos)) {
                    return side;
                }
            }
        }
        return firstValidSide;
    }

    /**
     * 返回全局强制放置方向；仅由未设置具体 sides 的普通方块使用。
     */
    private static @Nullable Direction getForcedDirection() {
        me.aleksilassila.litematica.printer.enums.DefaultPlaceDirectionType forcedDirection =
                (me.aleksilassila.litematica.printer.enums.DefaultPlaceDirectionType) Configs.Print.PLACE_DEFAULT_DIRECTION.getOptionListValue();
        return forcedDirection != null ? forcedDirection.toDirection() : null;
    }

    private static void sortSidesByPlayerView(List<Direction> sides, BlockPos pos) {
        if (!Configs.Print.PRINT_SORT_SIDES.getBooleanValue() || sides.size() < 2) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        sides.sort(Comparator.comparingDouble(side -> -getClickedFaceScore(eye, center, side)));
    }

    private static double getClickedFaceScore(Vec3 eye, Vec3 center, Direction side) {
        Vec3 toEye = eye.subtract(center);
        Vec3 clickedFaceNormal = Vec3.atLowerCornerOf(BlockUtils.getVector(side.getOpposite()));
        return clickedFaceNormal.dot(toEye);
    }

    public Action setItem(Item item) {
        return this.setItems(item);
    }

    public Action setItems(Item... items) {
        this.clickItems = items;
        return this;
    }

    public Action setRequiresSupport(boolean requiresSupport) {
        this.requiresSupport = requiresSupport;
        return this;
    }

    public boolean requiresSupport() {
        return this.requiresSupport;
    }

    public Action setRequiresSupport() {
        return this.setRequiresSupport(true);
    }

    public Action setClickLiquidSupport(boolean clickLiquidSupport) {
        this.clickLiquidSupport = clickLiquidSupport;
        return this;
    }

    public Action setClickLiquidSupport() {
        return this.setClickLiquidSupport(true);
    }

    public Action setFixedSide(@Nullable Direction fixedSide) {
        this.fixedSide = fixedSide;
        return this;
    }

    public Action setShift(boolean useShift) {
        this.shift = useShift;
        return this;
    }

    public Action setShift() {
        return this.setShift(true);
    }

    public Action setRequiredStackPredicate(@Nullable Predicate<ItemStack> requiredStackPredicate) {
        this.requiredStackPredicate = requiredStackPredicate;
        return this;
    }

    public Action setRequiredCreativeStack(@Nullable ItemStack requiredCreativeStack) {
        this.requiredCreativeStack = requiredCreativeStack == null ? null : requiredCreativeStack.copy();
        return this;
    }

    public Action setConsumeEffectiveExecution(boolean consumeEffectiveExecution) {
        this.consumeEffectiveExecution = consumeEffectiveExecution;
        return this;
    }

    public Action setCooldownTicksOverride(int cooldownTicksOverride) {
        this.cooldownTicksOverride = cooldownTicksOverride;
        return this;
    }

    public Action setClickRepeatCount(int clickRepeatCount) {
        this.clickRepeatCount = Math.max(1, clickRepeatCount);
        return this;
    }

    public Action queueAction(@NotNull BlockPos blockPos, @NotNull Direction side, boolean useShift, @NotNull LocalPlayer player) {
        return this.queueAction(blockPos, side, useShift, player, null);
    }

    public Action queueAction(@NotNull BlockPos blockPos, @NotNull Direction side, boolean useShift, @NotNull LocalPlayer player, @Nullable Item[] expectedItems) {
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue() && !this.requiresSupport) {
            ActionManager.INSTANCE.queueClick(
                    blockPos,
                    side.getOpposite(),
                    getSides().getOrDefault(side, Vec3.ZERO),
                    useShift,
                    this.clickRepeatCount,
                    expectedItems,
                    this.actionSource
            );
        } else {
            ActionManager.INSTANCE.queueClick(
                    blockPos.relative(side),
                    side.getOpposite(),
                    getSides().getOrDefault(side, Vec3.ZERO),
                    useShift,
                    this.clickRepeatCount,
                    expectedItems,
                    this.actionSource
            );
        }
        return this;
    }

    private static @NotNull Map<Direction, Vec3> createDefaultSides() {
        Map<Direction, Vec3> sides = new LinkedHashMap<>();
        for (Direction direction : DEFAULT_SIDE_ORDER) {
            sides.put(direction, Vec3.ZERO);
        }
        return sides;
    }

    private static @NotNull Map<Direction, Vec3> copySidesInDefaultOrder(@NotNull Map<Direction, Vec3> source) {
        Map<Direction, Vec3> ordered = new LinkedHashMap<>();
        for (Direction direction : DEFAULT_SIDE_ORDER) {
            if (source.containsKey(direction)) {
                ordered.put(direction, source.get(direction));
            }
        }
        for (Map.Entry<Direction, Vec3> entry : source.entrySet()) {
            ordered.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return ordered;
    }
}
