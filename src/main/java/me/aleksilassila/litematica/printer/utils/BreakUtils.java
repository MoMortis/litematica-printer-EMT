package me.aleksilassila.litematica.printer.utils;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import fi.dy.masa.tweakeroo.tweaks.PlacementTweaks;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.FluidAvoidStrategyType;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.DragonEggBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.Queue;
import java.util.Set;

@Environment(EnvType.CLIENT)
public class BreakUtils {
    public static final Minecraft client = Minecraft.getInstance();
    public static final BreakUtils INSTANCE = new BreakUtils();

    private final Queue<BlockPos> breakQueue = new LinkedList<>();
    private final Set<BlockPos> queuedBreaks = new HashSet<>();
    private final Map<BlockPos, Integer> recentlyBroken = new HashMap<>();
    private final Map<BlockPos, Integer> pendingBroken = new HashMap<>();
    private BlockPos breakPos;
    private boolean forceDelayedDestroy;
    private int externalDestroyLockTicks;

    private static final Set<net.minecraft.world.level.block.Block> fluidAvoidBlocks =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static List<String> fluidListSnapshot = List.of();
    private static FluidAvoidStrategyType fluidStrategySnapshot;
    private static boolean fluidMatcherInitialized;
    // 非阻塞型挖掘：记录玩家最近一次手动挖掘的游戏刻（1 tick 防抖）
    private static long lastPlayerMineGameTime = -1L;

    private BreakUtils() {
    }

    // Add methods from LitematicaUtils
    public static boolean canBreakBlock(BlockPos pos) {
        ClientLevel world = LitematicaUtils.client.level;
        LocalPlayer player = LitematicaUtils.client.player;
        if (world == null || player == null || LitematicaUtils.client.gameMode == null) return false;
        BlockState currentState = world.getBlockState(pos);
        if (Configs.Break.BREAK_CHECK_HARDNESS.getBooleanValue() && currentState.getBlock().defaultDestroyTime() < 0) {
            return false;
        }
        if (Configs.Break.BREAK_AVOID_FLUID.getBooleanValue() && isFluidProtected(pos, world)) {
            return false;
        }
        if (Configs.Break.BREAK_AVOID_SUPPORT.getBooleanValue() && isSupportProtected(pos, world)) {
            return false;
        }
        return !currentState.isAir() &&
                !currentState.is(Blocks.AIR) &&
                !currentState.is(Blocks.CAVE_AIR) &&
                !currentState.is(Blocks.VOID_AIR) &&
                !(currentState.getBlock() instanceof LiquidBlock) &&
                !player.blockActionRestricted(LitematicaUtils.client.level, pos, LitematicaUtils.client.gameMode.getPlayerMode());
    }

    private static void ensureFluidAvoidMatcher() {
        List<String> configured = List.copyOf(Configs.Break.BREAK_FLUID_LIST.getStrings());
        FluidAvoidStrategyType strategy = (FluidAvoidStrategyType) Configs.Break.BREAK_FLUID_STRATEGY.getOptionListValue();
        if (fluidMatcherInitialized && fluidListSnapshot.equals(configured)) {
            fluidStrategySnapshot = strategy;
            return;
        }
        fluidAvoidBlocks.clear();
        for (net.minecraft.world.level.block.Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            BlockState state = block.defaultBlockState();
            boolean matched = configured.stream().anyMatch(entry -> PinYinSearchUtils.matchBlockName(entry, state));
            Item item = block.asItem();
            if (!matched && item != Items.AIR) {
                matched = configured.stream().anyMatch(entry -> PinYinSearchUtils.matchItemName(entry, new ItemStack(item)));
            }
            if (matched) fluidAvoidBlocks.add(block);
        }
        fluidListSnapshot = configured;
        fluidStrategySnapshot = strategy;
        fluidMatcherInitialized = true;
    }

    private static boolean isConfiguredFluid(BlockState state) {
        if (state == null) return false;
        if (fluidAvoidBlocks.contains(state.getBlock())) return true;
        return !state.getFluidState().isEmpty() && fluidAvoidBlocks.contains(state.getFluidState().createLegacyBlock().getBlock());
    }

    // 判重：是否是会受重力作用下落的方块（沙/沙砾/红沙/混凝土粉末/铁砧/龙蛋等）
    private static boolean isGravityBlock(BlockState state) {
        if (state == null || state.isAir()) return false;
        net.minecraft.world.level.block.Block block = state.getBlock();
        return block instanceof FallingBlock
                || block instanceof AnvilBlock
                || block instanceof DragonEggBlock;
    }

    private static int avoidScanRadius() {
        int radius = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()
                ? (int) PlayerUtils.getInteractionRange(5)
                : ConfigUtils.getWorkRange();
        return radius + 2;
    }

    /**
     * 防流体挖掘判定：待挖方块的上/东/西/北/南侧是否存在目标流体；六面模式额外检查下侧。
     * 优先用逐tick缓存集合（O(1)命中）；可达半径过大时降级为内联直查。
     */
    private static boolean isFluidProtected(BlockPos pos, ClientLevel level) {
        LocalPlayer player = LitematicaUtils.client.player;
        if (player == null) return false;
        ensureFluidAvoidMatcher();
        if (isConfiguredFluid(level.getBlockState(pos.relative(Direction.UP)))
                || isConfiguredFluid(level.getBlockState(pos.relative(Direction.EAST)))
                || isConfiguredFluid(level.getBlockState(pos.relative(Direction.WEST)))
                || isConfiguredFluid(level.getBlockState(pos.relative(Direction.NORTH)))
                || isConfiguredFluid(level.getBlockState(pos.relative(Direction.SOUTH)))) return true;
        return fluidStrategySnapshot == FluidAvoidStrategyType.SIX_FACES
                && isConfiguredFluid(level.getBlockState(pos.relative(Direction.DOWN)));
    }

    /**
     * 防支撑破坏判定：待挖方块正上方是否是重力方块。
     * 该判断只需读取一格，直接查询可避免范围缓存造成方向或移动时的漏判。
     */
    private static boolean isSupportProtected(BlockPos pos, ClientLevel level) {
        return isGravityBlock(level.getBlockState(pos.relative(Direction.UP)));
    }


    public static boolean breakRestriction(BlockState blockState) {
        return breakRestriction(LitematicaUtils.client.level, null, blockState);
    }

    public static boolean breakRestriction(@Nullable ClientLevel level, @Nullable BlockPos pos, BlockState blockState) {
        if (Configs.Break.BREAK_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)) {
            if (!ModUtils.isTweakerooLoaded()) return true;
            UsageRestriction.ListType listType = PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION.getListType();
            if (listType == UsageRestriction.ListType.BLACKLIST) {
                return fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> matchesRule(string, level, pos, blockState));
            } else if (listType == UsageRestriction.ListType.WHITELIST) {
                return fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings().stream()
                        .anyMatch(string -> matchesRule(string, level, pos, blockState));
            } else {
                return true;
            }
        } else {
            IConfigOptionListEntry optionListValue = Configs.Break.BREAK_LIMIT.getOptionListValue();
            if (optionListValue == UsageRestriction.ListType.BLACKLIST) {
                return Configs.Break.BREAK_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> matchesRule(string, level, pos, blockState));
            } else if (optionListValue == UsageRestriction.ListType.WHITELIST) {
                return Configs.Break.BREAK_WHITELIST.getStrings().stream()
                        .anyMatch(string -> matchesRule(string, level, pos, blockState));
            } else {
                return true;
            }
        }
    }

    public static boolean matchesRule(String rule, @Nullable ClientLevel level, @Nullable BlockPos pos, BlockState state) {
        BlockNbtRule parsed = BlockNbtRule.parse(rule);
        return PinYinSearchUtils.matchBlockName(parsed.blockMatcher(), state)
                && parsed.matchesState(state);
    }

    public static boolean trySwitchToEffectiveTool(BlockPos pos, BlockState blockState) {
        if (pos == null || blockState == null || blockState.isAir() || blockState.getBlock() instanceof LiquidBlock) {
            return false;
        }
        LocalPlayer player = client.player;
        boolean tweakerooToolSwitch = ModUtils.isTweakerooLoaded() && ModUtils.isToolSwitchEnabled();
        if (player != null
                && tweakerooToolSwitch
                && ToolSelectionUtils.prefersSilkTouchForDrops(blockState)
                && (!isToolAllowedByDurabilityProtection(player.getMainHandItem())
                || !ToolSelectionUtils.hasSilkTouch(player.getMainHandItem()))
                && InventoryUtils.hasUsableSilkTouchTool(player)) {
            return InventoryUtils.switchToBestTool(player, blockState);
        }
        if (tweakerooToolSwitch) {
            ModUtils.trySwitchToEffectiveTool(pos);
            return protectCurrentToolBeforeBreak(blockState);
        }
        return false;
    }

    public static boolean isToolAllowedByDurabilityProtection(ItemStack stack) {
        return !ModUtils.isToolTooDamagedForBreaking(stack);
    }

    public static boolean isRecoveryToolReadyForBreak(BlockState blockState) {
        LocalPlayer player = client.player;
        if (player == null || player.getAbilities().instabuild
                || !ToolSelectionUtils.prefersSilkTouchForDrops(blockState)) {
            return true;
        }
        boolean toolSwitchEnabled = ModUtils.isTweakerooLoaded() && ModUtils.isToolSwitchEnabled();
        if (!toolSwitchEnabled) {
            return true;
        }
        if (isToolAllowedByDurabilityProtection(player.getMainHandItem())
                && ToolSelectionUtils.hasSilkTouch(player.getMainHandItem())) {
            return true;
        }
        return !InventoryUtils.hasUsableSilkTouchTool(player);
    }

    public static int getCurrentToolSafeBreakBudget() {
        LocalPlayer player = client.player;
        if (player == null || player.getAbilities().instabuild) {
            return Integer.MAX_VALUE;
        }
        return ModUtils.getSafeBreakBudget(player.getMainHandItem());
    }

    public static boolean protectCurrentToolBeforeBreak() {
        return protectCurrentToolBeforeBreak(null);
    }

    public static boolean protectCurrentToolBeforeBreak(@Nullable BlockState blockState) {
        LocalPlayer player = client.player;
        if (player == null || player.getAbilities().instabuild) {
            return true;
        }
        if (isToolAllowedByDurabilityProtection(player.getMainHandItem())) {
            return true;
        }
        ModUtils.trySwapCurrentToolIfNearlyBroken();
        if (isToolAllowedByDurabilityProtection(player.getMainHandItem())) {
            return true;
        }
        if (blockState != null && InventoryUtils.switchToBestTool(player, blockState)) {
            return isToolAllowedByDurabilityProtection(player.getMainHandItem());
        }
        return false;
    }

    public void add(BlockPos pos) {
        if (pos == null) return;
        BlockPos queuedPos = pos.immutable();
        if (queuedPos.equals(this.breakPos)
                || this.recentlyBroken.containsKey(queuedPos)
                || this.pendingBroken.containsKey(queuedPos)
                || !this.queuedBreaks.add(queuedPos)) {
            return;
        }
        breakQueue.add(queuedPos);
    }

    public void add(SchematicBlockContext ctx) {
        if (ctx == null) return;
        this.add(ctx.blockPos);
    }

    private void tickRecentlyBroken() {
        tickBreakMarkerMap(this.recentlyBroken);
        tickBreakMarkerMap(this.pendingBroken);
    }

    private void tickBreakMarkerMap(Map<BlockPos, Integer> markerMap) {
        if (markerMap.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, Integer>> iterator = markerMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = iterator.next();
            int remainingTicks = entry.getValue() - 1;
            if (remainingTicks <= 0) {
                iterator.remove();
            } else {
                entry.setValue(remainingTicks);
            }
        }
    }

    public void preprocess() {
        this.tickRecentlyBroken();
        if (this.externalDestroyLockTicks > 0) {
            this.externalDestroyLockTicks--;
        }
        if (!ConfigUtils.isPrinterEnable()) {
            if (!breakQueue.isEmpty()) {
                breakQueue.clear();
                queuedBreaks.clear();
            }
            if (breakPos != null) {
                breakPos = null;
            }
            if (!this.recentlyBroken.isEmpty()) {
                this.recentlyBroken.clear();
            }
            if (!this.pendingBroken.isEmpty()) {
                this.pendingBroken.clear();
            }
            this.externalDestroyLockTicks = 0;
            this.forceDelayedDestroy = false;
        }
    }

    public void resetRuntime() {
        this.breakQueue.clear();
        this.queuedBreaks.clear();
        this.recentlyBroken.clear();
        this.pendingBroken.clear();
        this.breakPos = null;
        this.forceDelayedDestroy = false;
        this.externalDestroyLockTicks = 0;
    }

    public boolean isNeedHandle() {
        return !breakQueue.isEmpty() || breakPos != null;
    }

    // 非阻塞型挖掘：检测玩家是否正在手动挖掘（仅生存/冒险；创造模式忽略）
    public static boolean isPlayerMining() {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null || player.getAbilities().instabuild || player.isSpectator()) {
            return false;
        }
        boolean mining = client.options.keyAttack.isDown()
                && client.hitResult != null
                && client.hitResult.getType() == HitResult.Type.BLOCK;
        if (mining) {
            lastPlayerMineGameTime = level.getGameTime();
        }
        // 1 tick 防抖：松手后仍短暂暂停，避免连挖/换目标时打印机抖动恢复
        // 钳制差值：避免跨维度/重连后 gameTime 变小导致的负值恒判（负数 <= 1 恒真）
        long diff = level.getGameTime() - lastPlayerMineGameTime;
        return diff >= 0 && diff <= 1;
    }

    public void onTick() {
        LocalPlayer player = client.player;
        ClientLevel level = client.level;
        if (player == null || level == null) {
            return;
        }
        // 非阻塞型挖掘：玩家手动挖掘时整体暂停，保留队列/目标
        if (Configs.Break.BREAK_NON_BLOCKING.getBooleanValue() && isPlayerMining()) {
            return;
        }
        if (this.externalDestroyLockTicks > 0) {
            return;
        }
        if (breakPos == null && breakQueue.isEmpty()) {
            return;
        }
        if (breakPos == null) {
            while (!breakQueue.isEmpty()) {
                BlockPos pos = breakQueue.poll();
                queuedBreaks.remove(pos);
                if (pos == null) {
                    continue;
                }
                if (!PlayerUtils.canInteracted(pos) || !canBreakBlock(pos)
                        || !breakRestriction(level, pos, level.getBlockState(pos))) {
                    continue;
                }
                BlockBreakResult result = continueDestroyBlock(pos, Direction.DOWN);
                if (result == BlockBreakResult.IN_PROGRESS) {
                    breakPos = pos;
                    break;
                }
                if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
                    this.markRecentlyBroken(pos);
                    if (result == BlockBreakResult.COMPLETED_WAIT) {
                        this.markPendingBroken(pos, ConfigUtils.getBreakCooldown());
                    }
                }
            }
        } else {
            // 检查当前目标是否仍可破坏（如冰挖掘后生成水/流体，流体不可破坏）
            if (!canBreakBlock(breakPos)) {
                breakPos = null;
                this.forceDelayedDestroy = false;
                onTick();
                return;
            }
            BlockBreakResult result = continueDestroyBlock(breakPos, Direction.DOWN);
            if (result != BlockBreakResult.IN_PROGRESS) {
                if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
                    this.markRecentlyBroken(breakPos);
                    if (result == BlockBreakResult.COMPLETED_WAIT) {
                        this.markPendingBroken(breakPos, ConfigUtils.getBreakCooldown());
                    }
                }
                breakPos = null;
                this.forceDelayedDestroy = false;
                onTick();
            }
        }
    }

    public boolean hasActiveDestroyTarget() {
        return this.breakPos != null;
    }

    public void suppressQueuedBreaks(int ticks) {
        this.externalDestroyLockTicks = Math.max(this.externalDestroyLockTicks, ticks);
    }

    public void markRecentlyBroken(BlockPos pos) {
        if (pos != null) {
            this.recentlyBroken.put(pos.immutable(), 2);
        }
    }

    public void markPendingBroken(BlockPos pos, int timeoutTicks) {
        if (pos != null) {
            this.pendingBroken.put(pos.immutable(), Math.max(timeoutTicks, 1));
        }
    }

    public void confirmServerBlockUpdate(BlockPos pos) {
        if (pos == null) {
            return;
        }
        this.recentlyBroken.remove(pos);
        this.pendingBroken.remove(pos);
    }

    public void clearPendingBroken(BlockPos pos) {
        if (pos != null) {
            this.pendingBroken.remove(pos);
        }
    }

    public boolean isRecentlyBroken(BlockPos pos) {
        return pos != null && (this.recentlyBroken.containsKey(pos) || this.pendingBroken.containsKey(pos));
    }

    public BlockBreakResult continueDestroyBlock(final BlockPos blockPos, Direction direction, boolean localPrediction, boolean trackBreakPos) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        if (gameMode == null || blockPos == null || direction == null) {
            return BlockBreakResult.FAILED;
        }
        BlockBreakResult result = gameMode.litematica_printer$continueDestroyBlock(localPrediction, blockPos, direction, this.forceDelayedDestroy);
        if (trackBreakPos && result == BlockBreakResult.IN_PROGRESS) {
            breakPos = blockPos;
        }
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.forceDelayedDestroy = false;
        }
        return result;
    }

    public BlockBreakResult continueDestroyBlock(final BlockPos blockPos, Direction direction, boolean localPrediction) {
        return this.continueDestroyBlock(blockPos, direction, localPrediction, true);
    }

    public BlockBreakResult continueDestroyBlock(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlock(blockPos, direction, true);
    }

    public BlockBreakResult continueDestroyBlock(BlockPos blockPos) {
        return this.continueDestroyBlock(blockPos, Direction.DOWN);
    }

    public BlockBreakResult continueDestroyBlockForMine(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlockForMine(blockPos, direction, true);
    }

    public BlockBreakResult continueDestroyBlockForMine(BlockPos blockPos, Direction direction, boolean allowToolSwitch) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        if (gameMode == null) {
            return BlockBreakResult.FAILED;
        }
        return gameMode.litematica_printer$continueDestroyBlockForMine(blockPos, direction, allowToolSwitch);
    }

    public BlockBreakResult continueDestroyBlockForMine(BlockPos blockPos) {
        return this.continueDestroyBlockForMine(blockPos, Direction.DOWN);
    }

    public boolean isPendingDelayedDestroy(BlockPos blockPos) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        return gameMode != null && gameMode.litematica_printer$isPendingDelayedDestroy(blockPos);
    }

    public BlockBreakResult continueDestroyBlockWithoutTracking(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlock(blockPos, direction, true, false);
    }

    public BlockBreakResult continueDestroyBlockWithoutTracking(BlockPos blockPos) {
        return this.continueDestroyBlockWithoutTracking(blockPos, Direction.DOWN);
    }

    public BlockBreakResult continueDestroyBlockWithoutToolSwitch(BlockPos blockPos, Direction direction, boolean trackBreakPos) {
        MultiPlayerGameModeExtension gameMode = (@Nullable MultiPlayerGameModeExtension) client.gameMode;
        if (gameMode == null) {
            return BlockBreakResult.FAILED;
        }
        BlockBreakResult result = gameMode.litematica_printer$continueDestroyBlock(
                true,
                blockPos,
                direction,
                this.forceDelayedDestroy,
                false
        );
        if (trackBreakPos && result == BlockBreakResult.IN_PROGRESS) {
            breakPos = blockPos;
        }
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.forceDelayedDestroy = false;
        }
        return result;
    }

    public BlockBreakResult continueDestroyBlockWithoutToolSwitch(BlockPos blockPos, Direction direction) {
        return this.continueDestroyBlockWithoutToolSwitch(blockPos, direction, true);
    }
}
