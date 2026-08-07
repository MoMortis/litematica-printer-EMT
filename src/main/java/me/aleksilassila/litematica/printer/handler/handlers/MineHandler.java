package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.malilib.config.IConfigOptionListEntry;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import fi.dy.masa.tweakeroo.tweaks.PlacementTweaks;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.ExcavateListMode;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.BlockPosCooldownManager;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class MineHandler extends ClientPlayerTickHandler {
    public final static String NAME = "mine";

    private final MineBreakExecutor analyzer = new MineBreakExecutor();
    private final MineToolSession toolSession = new MineToolSession();
    private final List<MineBreakExecutor.Target> candidates = new ArrayList<>();
    @Nullable
    private BlockPos activeMinePos;

    public MineHandler() {
        super(NAME, PrintModeType.MINE, Configs.Core.MINE, Configs.Mine.MINE_SELECTION_TYPE, true);
    }

    private boolean isParallelMode() {
        return Configs.Break.BREAK_PARALLEL.getBooleanValue();
    }

    public static boolean mineRestriction(BlockState blockState) {
        if (!BreakUtils.breakRestriction(blockState)) {
            return false;
        }
        if (Configs.Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.TWEAKEROO)) {
            if (!ModUtils.isTweakerooLoaded()) return true;
            UsageRestriction.ListType listType = PlacementTweaks.BLOCK_TYPE_BREAK_RESTRICTION.getListType();
            if (listType == UsageRestriction.ListType.BLACKLIST) {
                return fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
            } else if (listType == UsageRestriction.ListType.WHITELIST) {
                return fi.dy.masa.tweakeroo.config.Configs.Lists.BLOCK_TYPE_BREAK_RESTRICTION_WHITELIST.getStrings().stream()
                        .anyMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
            } else {
                return true;
            }
        } else {
            IConfigOptionListEntry optionListValue = Configs.Mine.EXCAVATE_LIMIT.getOptionListValue();
            if (optionListValue == UsageRestriction.ListType.BLACKLIST) {
                return Configs.Mine.EXCAVATE_BLACKLIST.getStrings().stream()
                        .noneMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
            } else if (optionListValue == UsageRestriction.ListType.WHITELIST) {
                return Configs.Mine.EXCAVATE_WHITELIST.getStrings().stream()
                        .anyMatch(string -> PinYinSearchUtils.matchBlockName(string, blockState));
            } else {
                return true;
            }
        }
    }

    @Override
    protected int getTickInterval() {
        return Configs.Break.BREAK_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return isParallelMode() ? 0 : Configs.Break.BREAK_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean canIterate() {
        // 非阻塞型挖掘：玩家手动挖掘时暂停整个挖掘迭代
        if (Configs.Break.BREAK_NON_BLOCKING.getBooleanValue() && BreakUtils.isPlayerMining()) {
            return false;
        }
        if (!isParallelMode()) {
            return true;
        }
        return this.activeMinePos == null && !BreakUtils.INSTANCE.hasActiveDestroyTarget();
    }

    @Override
    protected void preprocess() {
        if (!isParallelMode()) {
            return;
        }
        this.candidates.clear();
        this.analyzer.beginTick();
        this.toolSession.beginTick();
        this.continueActiveMineTarget();
    }

    @Override
    public boolean canProcessPos(BlockPos pos) {
        // 非阻塞型挖掘：玩家手动挖掘时停止收集新候选
        if (Configs.Break.BREAK_NON_BLOCKING.getBooleanValue() && BreakUtils.isPlayerMining()) {
            return false;
        }
        if (isOnCooldown(pos) || BlockPosCooldownManager.INSTANCE.isOnCooldown(level, FluidHandler.NAME, pos)) {
            return false;
        }
        return BreakUtils.canBreakBlock(pos) && mineRestriction(level.getBlockState(pos));
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (!isParallelMode()) {
            BlockBreakResult result = BreakUtils.INSTANCE.continueDestroyBlock(blockPos);
            if (result == BlockBreakResult.IN_PROGRESS || result == BlockBreakResult.COMPLETED_WAIT) {
                skipIteration.set(true);
            }
            this.setCooldown(blockPos, ConfigUtils.getBreakCooldown());
            return;
        }

        if (BreakUtils.INSTANCE.isRecentlyBroken(blockPos)
                || BreakUtils.INSTANCE.isPendingDelayedDestroy(blockPos)) {
            return;
        }
        MineBreakExecutor.Target target = this.analyzer.analyze(blockPos);
        if (target == null) {
            return;
        }
        this.candidates.add(target);
    }

    @Override
    protected void stopIteration(boolean interrupt) {
        if (!isParallelMode() || interrupt) {
            return;
        }
        if (ActionManager.INSTANCE.needWaitModifyLook
                || this.activeMinePos != null
                || this.candidates.isEmpty()) {
            return;
        }
        this.candidates.sort(this.toolSession.comparator(player));
        MineBreakExecutor.Target nearest = this.candidates.get(0);
        MineBreakExecutor.Target selected = this.toolSession.selectTarget(this.candidates, this.analyzer, player);
        this.executeToolSession(selected, MineToolSession.distanceScore(player, nearest));
    }

    private void continueActiveMineTarget() {
        BlockPos pos = this.activeMinePos;
        if (pos == null) {
            return;
        }
        if (!this.canContinueActiveMineTarget(pos)) {
            this.activeMinePos = null;
            return;
        }
        BlockBreakResult result = BreakUtils.INSTANCE.continueDestroyBlockForMine(pos, Direction.DOWN, true);
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeAction();
        }
        this.toolSession.onTargetResolved(result, pos);
        if (result != BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = null;
            this.setCooldown(pos, ConfigUtils.getBreakCooldown());
        }
    }

    private boolean canContinueActiveMineTarget(BlockPos pos) {
        return pos != null
                && PlayerUtils.canInteracted(pos)
                && BreakUtils.canBreakBlock(pos)
                && mineRestriction(level.getBlockState(pos));
    }

    private void executeToolSession(MineBreakExecutor.Target firstTarget, double nearestDistance) {
        this.toolSession.startSession(firstTarget);
        if (!this.toolSession.ensureHandToolProtected(player, firstTarget)) {
            return;
        }
        BlockBreakResult result = this.executeSessionTarget(firstTarget, !this.analyzer.isCurrentToolEffective(firstTarget));
        if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
            return;
        }
        for (MineBreakExecutor.Target target : this.candidates) {
            if (target == firstTarget) {
                continue;
            }
            if (!this.toolSession.hasInstantBudget()) {
                break;
            }
            if (!this.toolSession.matchesSessionTool(this.analyzer, target)) {
                continue;
            }
            if (!this.toolSession.isInsideFrontier(player, target, nearestDistance)) {
                break;
            }
            if (!this.toolSession.ensureHandToolProtected(player, target)) {
                break;
            }
            result = this.executeSessionTarget(target, false);
            if (this.toolSession.shouldStop(result, this.activeMinePos != null)) {
                break;
            }
        }
    }

    private BlockBreakResult executeSessionTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        boolean switchForRecovery = player != null
                && target.shouldSwitchToRecoveryTool(player.getMainHandItem());
        BlockBreakResult result = this.executeMineTarget(target, allowToolSwitch || switchForRecovery);
        if (result != BlockBreakResult.FAILED) {
            this.setCooldown(target.pos(), ConfigUtils.getBreakCooldown());
        }
        if (result == BlockBreakResult.COMPLETED || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeInstantBudget();
        }
        if (result == BlockBreakResult.IN_PROGRESS
                || result == BlockBreakResult.COMPLETED
                || result == BlockBreakResult.COMPLETED_WAIT) {
            this.toolSession.consumeAction();
        }
        this.toolSession.onTargetResolved(result, target.pos());
        return result;
    }

    private BlockBreakResult executeMineTarget(MineBreakExecutor.Target target, boolean allowToolSwitch) {
        BlockBreakResult result = BreakUtils.INSTANCE.continueDestroyBlockForMine(target.pos(), Direction.DOWN, allowToolSwitch);
        if (result == BlockBreakResult.IN_PROGRESS) {
            this.activeMinePos = target.pos();
        }
        return result;
    }

}