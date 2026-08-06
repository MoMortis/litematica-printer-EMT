package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import me.aleksilassila.litematica.printer.utils.ToolSelectionUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

final class MineBreakExecutor {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final float CURRENT_TOOL_MIN_EFFICIENCY_RATIO = 0.75F;

    private final Map<BlockState, Float> currentProgressCache = new IdentityHashMap<>();
    private final Map<BlockState, ToolChoice> bestToolCache = new IdentityHashMap<>();
    private boolean resolveBestTool;

    public void beginTick() {
        this.currentProgressCache.clear();
        this.bestToolCache.clear();
        this.resolveBestTool = Configs.Break.BREAK_AUTO_TOOL.getBooleanValue()
                || ModUtils.isTweakerooLoaded() && ModUtils.isToolSwitchEnabled();
    }

    public void reset() {
        this.currentProgressCache.clear();
        this.bestToolCache.clear();
        this.resolveBestTool = false;
    }

    @Nullable
    public Target analyze(BlockPos pos) {
        LocalPlayer player = CLIENT.player;
        ClientLevel level = CLIENT.level;
        if (player == null || level == null || pos == null) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!BreakUtils.canBreakBlock(pos)) {
            return null;
        }
        ItemStack currentStack = player.getMainHandItem();
        if (player.getAbilities().instabuild) {
            return new Target(
                    pos.immutable(),
                    state,
                    1.0F,
                    1.0F,
                    Direction.DOWN,
                    currentStack.getItem(),
                    false,
                    false
            );
        }
        float currentProgress = this.getCurrentProgress(player, state, currentStack);
        ToolChoice toolChoice = this.getBestToolChoice(player, state, currentStack, currentProgress);
        float bestProgress = toolChoice.progress();
        if (bestProgress <= 0.0F) {
            return null;
        }
        return new Target(
                pos.immutable(),
                state,
                currentProgress,
                bestProgress,
                Direction.DOWN,
                toolChoice.item(),
                toolChoice.currentPreservesDrops(),
                toolChoice.preservesDrops()
        );
    }

    public boolean isCurrentToolEffective(Target target) {
        LocalPlayer player = CLIENT.player;
        if (player == null || !this.shouldResolveBestTool()) {
            return true;
        }
        if (target.bestPreservesDrops && !target.currentPreservesDrops) {
            return false;
        }
        return target.currentProgress >= target.bestProgress * CURRENT_TOOL_MIN_EFFICIENCY_RATIO;
    }

    public boolean hasSameBestTool(Target target, @Nullable Item item) {
        return target != null && target.bestToolItem == item;
    }

    private ToolChoice getBestToolChoice(LocalPlayer player, BlockState state, ItemStack currentStack, float currentProgress) {
        ToolChoice cached = this.bestToolCache.get(state);
        if (cached != null) {
            return cached;
        }
        float bestProgress = currentProgress;
        Item bestItem = currentStack.getItem();
        boolean preferSilkTouch = ToolSelectionUtils.prefersSilkTouchForDrops(state);
        boolean currentPreservesDrops = preferSilkTouch
                && BreakUtils.isToolAllowedByDurabilityProtection(currentStack)
                && ToolSelectionUtils.hasSilkTouch(currentStack);
        boolean bestPreservesDrops = currentPreservesDrops;
        if (!this.shouldResolveBestTool()) {
            ToolChoice choice = new ToolChoice(bestItem, bestProgress, currentPreservesDrops, bestPreservesDrops);
            this.bestToolCache.put(state, choice);
            return choice;
        }
        for (ItemStack stack : InventoryUtils.getMainStacks(player.getInventory())) {
            if (stack.isEmpty() || !BreakUtils.isToolAllowedByDurabilityProtection(stack)) {
                continue;
            }
            float progress = this.getDestroyProgress(player, state, stack);
            boolean stackPreservesDrops = preferSilkTouch && ToolSelectionUtils.hasSilkTouch(stack);
            if ((stackPreservesDrops && !bestPreservesDrops)
                    || stackPreservesDrops == bestPreservesDrops && progress > bestProgress) {
                bestProgress = progress;
                bestItem = stack.getItem();
                bestPreservesDrops = stackPreservesDrops;
            }
        }
        ToolChoice choice = new ToolChoice(bestItem, bestProgress, currentPreservesDrops, bestPreservesDrops);
        this.bestToolCache.put(state, choice);
        return choice;
    }

    private boolean shouldResolveBestTool() {
        return this.resolveBestTool;
    }

    private float getDestroyProgress(LocalPlayer player, BlockState state, ItemStack stack) {
        if (!BreakUtils.isToolAllowedByDurabilityProtection(stack)) {
            return 0.0F;
        }
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0.0F) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return 1.0F;
        }
        int divisor = (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) ? 30 : 100;
        return PlayerUtils.getBlockBreakingSpeed(player, state, stack) / hardness / (float) divisor;
    }

    private float getCurrentProgress(LocalPlayer player, BlockState state, ItemStack stack) {
        Float cached = this.currentProgressCache.get(state);
        if (cached != null) {
            return cached;
        }
        float progress = this.getDestroyProgress(player, state, stack);
        this.currentProgressCache.put(state, progress);
        return progress;
    }

    public static final class Target {
        private final BlockPos pos;
        private final BlockState state;
        private final float currentProgress;
        private final float bestProgress;
        private final Direction direction;
        private final Item bestToolItem;
        private final boolean currentPreservesDrops;
        private final boolean bestPreservesDrops;

        private Target(BlockPos pos, BlockState state, float currentProgress, float bestProgress, Direction direction, Item bestToolItem,
                       boolean currentPreservesDrops, boolean bestPreservesDrops) {
            this.pos = pos;
            this.state = state;
            this.currentProgress = currentProgress;
            this.bestProgress = bestProgress;
            this.direction = direction;
            this.bestToolItem = bestToolItem;
            this.currentPreservesDrops = currentPreservesDrops;
            this.bestPreservesDrops = bestPreservesDrops;
        }

        public BlockPos pos() {
            return this.pos;
        }

        public BlockState state() {
            return this.state;
        }

        public float progress() {
            return this.bestProgress;
        }

        public float currentProgress() {
            return this.currentProgress;
        }

        public Item bestToolItem() {
            return this.bestToolItem;
        }

        public boolean shouldSwitchToRecoveryTool(ItemStack currentStack) {
            return this.bestPreservesDrops && !ToolSelectionUtils.hasSilkTouch(currentStack);
        }
    }

    private record ToolChoice(Item item, float progress, boolean currentPreservesDrops, boolean preservesDrops) {
    }
}
