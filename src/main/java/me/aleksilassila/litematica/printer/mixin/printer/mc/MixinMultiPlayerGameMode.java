package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings({"DataFlowIssue", "DuplicateCondition"})
@Mixin(value = MultiPlayerGameMode.class, priority = 1020)
public abstract class MixinMultiPlayerGameMode implements MultiPlayerGameModeExtension {
    @Unique
    private static final float MINE_FAST_FINISH_PROGRESS = 0.5F;
    @Unique
    private static final float MINE_FAST_FINISH_COMPLETED_PROGRESS = 0.6F;

    // @formatter:off
    @Shadow private BlockPos destroyBlockPos;
    @Shadow private ItemStack destroyingItem;
    @Shadow private float destroyProgress;
    @Shadow private boolean isDestroying;
    @Shadow @Final private Minecraft minecraft;
    @Unique
    private BlockPos delayedDestroyPos;
    @Unique
    private boolean hasDelayedDestroy;
    @Unique
    private boolean delayedDestroyLocalPrediction;
    @Unique
    private long delayedDestroyStartTick;
    @Unique
    private final Map<BlockPos, Long> litematica_printer$pendingDelayedDestroys = new LinkedHashMap<>();
    // @formatter:on

    @Override
    public void litematica_printer$resetRuntime() {
        LocalPlayer player = this.minecraft.player;
        if (this.isDestroying) {
            this.litematica_printer$clearDestroyProgress(player, this.destroyBlockPos);
        }
        if (this.hasDelayedDestroy && this.delayedDestroyPos != null) {
            this.litematica_printer$clearDestroyProgress(player, this.delayedDestroyPos);
        }
        this.isDestroying = false;
        this.destroyProgress = 0.0F;
        this.destroyBlockPos = BlockPos.ZERO;
        this.destroyingItem = ItemStack.EMPTY;
        this.delayedDestroyPos = null;
        this.hasDelayedDestroy = false;
        this.delayedDestroyLocalPrediction = false;
        this.delayedDestroyStartTick = 0L;
        this.litematica_printer$pendingDelayedDestroys.clear();
    }

    @Unique
    private void litematica_printer$clearDestroyProgress(LocalPlayer player, BlockPos pos) {
        if (player != null && pos != null && this.minecraft.level != null) {
            int playerId;
            try {
                playerId = player.getId();
            } catch (IllegalStateException ignored) {
                return;
            }
            this.minecraft.level.destroyBlockProgress(playerId, pos, -1);
        }
    }

    @Unique
    private void litematica_printer$resetDestroyState(LocalPlayer player, BlockPos pos) {
        this.isDestroying = false;
        this.destroyProgress = 0.0F;
        this.litematica_printer$clearDestroyProgress(player, pos);
    }

    @Unique
    private void litematica_printer$addPendingDelayedDestroy(BlockPos pos) {
        if (pos != null) {
            this.litematica_printer$pendingDelayedDestroys.put(pos.immutable(), getClientTickCount());
        }
    }

    @Unique
    private boolean litematica_printer$hasPendingDelayedDestroy(BlockPos pos) {
        return pos != null && this.litematica_printer$pendingDelayedDestroys.containsKey(pos);
    }

    @Unique
    private void litematica_printer$removePendingDelayedDestroy(BlockPos pos) {
        if (pos != null) {
            this.litematica_printer$pendingDelayedDestroys.remove(pos);
        }
    }

    @Unique
    private void litematica_printer$cleanupPendingDelayedDestroys(LocalPlayer player, ClientLevel level) {
        if (this.litematica_printer$pendingDelayedDestroys.isEmpty()) {
            return;
        }
        long currentTick = getClientTickCount();
        Iterator<Map.Entry<BlockPos, Long>> iterator = this.litematica_printer$pendingDelayedDestroys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            BlockPos pos = entry.getKey();
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                iterator.remove();
                continue;
            }
            int timeoutTicks = this.litematica_printer$getPendingDelayedDestroyTimeoutTicks(player, level, pos, state);
            if (currentTick - entry.getValue() >= timeoutTicks) {
                iterator.remove();
            }
        }
    }

    @Unique
    private int litematica_printer$getPendingDelayedDestroyTimeoutTicks(LocalPlayer player, ClientLevel level, BlockPos pos, BlockState state) {
        float progressPerTick = state.getDestroyProgress(player, level, pos);
        if (progressPerTick <= 0.0F) {
            return 200;
        }
        int estimatedTicks = (int) Math.ceil(1.0F / progressPerTick);
        return Math.max(8, Math.min(estimatedTicks + 10, 200));
    }

    @Override
    public boolean litematica_printer$isPendingDelayedDestroy(BlockPos blockPos) {
        return this.litematica_printer$hasPendingDelayedDestroy(blockPos);
    }

    @Shadow
    public abstract boolean destroyBlock(final BlockPos pos);

    @Shadow
    protected abstract boolean sameDestroyTarget(final BlockPos pos);

    @Shadow
    protected abstract void ensureHasSentCarriedItem();

    //#if MC > 11802
    @Shadow public abstract InteractionResult useItemOn(LocalPlayer player, InteractionHand hand, BlockHitResult blockHitResult);
    //#else
    //$$ @Shadow public abstract InteractionResult useItemOn(LocalPlayer player,ClientLevel level, InteractionHand hand, BlockHitResult blockHitResult);
    //#endif

    // @formatter:on

    @Inject(at = @At("HEAD"), method = "tick")
    public void tick(CallbackInfo ci) {
        if (this.hasDelayedDestroy) {
            LocalPlayer player = this.minecraft.player;
            ClientLevel level = this.minecraft.level;
            if (player == null || level == null) {
                return;
            }
            this.litematica_printer$cleanupPendingDelayedDestroys(player, level);
            BlockState blockState = level.getBlockState(this.delayedDestroyPos);
            if (blockState.isAir()) {
                this.litematica_printer$removePendingDelayedDestroy(this.delayedDestroyPos);
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
                this.litematica_printer$clearDestroyProgress(player, this.delayedDestroyPos);
                return;
            }
            long currentTick = getClientTickCount();
            int elapsedTicks = (int) (currentTick - this.delayedDestroyStartTick);
            float delayedDestroyProgress = blockState.getDestroyProgress(player, level, this.delayedDestroyPos) * elapsedTicks;
            if (delayedDestroyProgress >= 1.0F) {
                this.litematica_printer$playBreakEffect(this.delayedDestroyPos, blockState);
if (this.delayedDestroyLocalPrediction) {
                    this.litematica_printer$destroyBlockSilently(this.delayedDestroyPos);
                }
                this.litematica_printer$removePendingDelayedDestroy(this.delayedDestroyPos);
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
            }
        } else {
            LocalPlayer player = this.minecraft.player;
            ClientLevel level = this.minecraft.level;
            if (player != null && level != null) {
                this.litematica_printer$cleanupPendingDelayedDestroys(player, level);
            }
        }
    }

    @Override
    public BlockPos litematica_printer$destroyBlockPos() {
        return destroyBlockPos;
    }

    @Override
    public boolean litematica_printer$isDestroying() {
        return isDestroying;
    }

    @Override
    public void litematica_printer$startPrediction(PredictiveAction predictiveAction) {
        PacketUtils.sendPacket(predictiveAction);
    }

    @Override
    public InteractionResult litematica_printer$useItemOn(boolean localPrediction, InteractionHand hand, BlockHitResult blockHit) {
        if (localPrediction) {
            //#if MC > 11802
            return useItemOn(minecraft.player, hand, blockHit);
            //#else
            //$$ return useItemOn(minecraft.player, minecraft.level, hand, blockHit);
            //#endif
        }
        this.ensureHasSentCarriedItem();
        if (!this.minecraft.level.getWorldBorder().isWithinBounds(blockHit.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        //#if MC > 11802
        PacketUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(hand, blockHit, sequence));
        //#else
        //$$ PacketUtils.sendPacket(sequence -> new ServerboundUseItemOnPacket(hand, blockHit));
        //#endif
        return InteractionResult.PASS;
    }

    @Unique
    private int litematica_printer$getDestroyStage() {
        float breakingProgress = this.destroyProgress >= ConfigUtils.getBreakProgressThreshold() ? 1.0F : this.destroyProgress;
        return breakingProgress > 0.0F ? (int) (breakingProgress * 10.0F) : -1;
    }

    @Unique
    private ServerboundPlayerActionPacket getActionPacket(Action action, BlockPos blockPos, Direction direction, int sequence) {
        //#if MC > 11802
        return new ServerboundPlayerActionPacket(action, blockPos, direction, sequence);
        //#else
        //$$ return new ServerboundPlayerActionPacket(action, blockPos, direction);
        //#endif
    }

    /**
     * 这很体面：数据包挖掘模式下，跳过一切校验，同一游戏刻内直接发送开始+结束挖掘包。
     * 独立于 Beta2.5 的破坏状态机，作为最快的挖掘路径保留。
     */
    @Unique
    private boolean litematica_printer$instantMine(BlockPos blockPos, Direction direction) {
        if (!Configs.Break.BREAK_USE_PACKET.getBooleanValue()
                || !Configs.Break.BREAK_INSTANT_MINE.getBooleanValue()) {
            return false;
        }
        if (this.isDestroying && !this.sameDestroyTarget(blockPos)) {
            PacketUtils.sendPacket(getActionPacket(Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction, 0));
        }
        this.isDestroying = false;
        this.destroyProgress = 0.0F;
        PacketUtils.sendPacket(sequence -> getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
        PacketUtils.sendPacket(sequence -> getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence));
        return true;
    }

    @Override
    public BlockBreakResult litematica_printer$continueDestroyBlockForMine(BlockPos blockPos, Direction direction, boolean allowToolSwitch) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null) {
            return BlockBreakResult.FAILED;
        }
        // 非阻塞型挖掘：玩家手动挖掘时让出破坏状态（含同 tick 秒破路径）
        if (Configs.Break.BREAK_NON_BLOCKING.getBooleanValue() && BreakUtils.isPlayerMining()) {
            return BlockBreakResult.ABORTED;
        }

        // 这很体面：同 tick 数据包秒破，跳过全部校验
        if (this.litematica_printer$instantMine(blockPos, direction)) {
            return BlockBreakResult.COMPLETED;
        }

        BlockState blockState = level.getBlockState(blockPos);
        if (blockState.isAir() || blockState.getBlock() instanceof LiquidBlock) {
            this.litematica_printer$removePendingDelayedDestroy(blockPos);
            if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
            }
            if (this.isDestroying && this.sameDestroyTarget(blockPos)) {
                this.litematica_printer$resetDestroyState(player, blockPos);
            }
            return BlockBreakResult.COMPLETED;
        }

        if (!level.getWorldBorder().isWithinBounds(blockPos)) {
            return BlockBreakResult.FAILED;
        }

        if (!allowToolSwitch || !BreakUtils.trySwitchToEffectiveTool(blockPos, blockState)) {
            ensureHasSentCarriedItem();
        }
        if (!BreakUtils.protectCurrentToolBeforeBreak(blockState)) {
            return BlockBreakResult.FAILED;
        }
        if (!BreakUtils.isRecoveryToolReadyForBreak(blockState)) {
            return BlockBreakResult.FAILED;
        }
        ensureHasSentCarriedItem();

        float destroyProgress = blockState.getDestroyProgress(player, level, blockPos);
        boolean fastPath = player.getAbilities().instabuild || destroyProgress >= MINE_FAST_FINISH_PROGRESS;

        if (!fastPath) {
            if (this.hasDelayedDestroy) {
                if (blockPos.equals(this.delayedDestroyPos)) {
                    return BlockBreakResult.IN_PROGRESS;
                }
                return BlockBreakResult.ABORTED;
            }
            if (this.litematica_printer$hasPendingDelayedDestroy(blockPos)) {
                return BlockBreakResult.IN_PROGRESS;
            }

            BlockBreakResult result = this.litematica_printer$continueDestroyBlock(false, blockPos, direction, false, allowToolSwitch);
            if (result == BlockBreakResult.FAILED) {
                return BlockBreakResult.FAILED;
            }
            if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
                return BlockBreakResult.IN_PROGRESS;
            }
            if (this.litematica_printer$hasPendingDelayedDestroy(blockPos)) {
                return BlockBreakResult.IN_PROGRESS;
            }
            return result;
        }

        if (this.isDestroying) {
            this.litematica_printer$resetDestroyState(player, this.destroyBlockPos);
        }

        PacketUtils.sendPacket(sequence -> getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
        if (!player.getAbilities().instabuild) {
            PacketUtils.sendPacket(sequence -> getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence));
        }
        this.litematica_printer$playBreakEffect(blockPos, blockState);
        return BlockBreakResult.COMPLETED_WAIT;
    }

    @Override
    public BlockBreakResult litematica_printer$continueDestroyBlock(boolean localPrediction, BlockPos blockPos, Direction direction, boolean forceDelayedDestroy, boolean allowToolSwitch) {
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || level == null || gameMode == null) {
            return BlockBreakResult.FAILED;
        }
        // 非阻塞型挖掘：玩家手动挖掘时让出破坏状态
        if (Configs.Break.BREAK_NON_BLOCKING.getBooleanValue() && BreakUtils.isPlayerMining()) {
            return BlockBreakResult.ABORTED;
        }
        // 这很体面：同 tick 数据包秒破（打印流程的普通破坏队列路径）
        if (this.litematica_printer$instantMine(blockPos, direction)) {
            return BlockBreakResult.COMPLETED;
        }
        boolean localEffects = localPrediction || forceDelayedDestroy;
        boolean localBlockRemoval = localPrediction && !forceDelayedDestroy;
        BlockState blockState = level.getBlockState(blockPos);
        if (blockState.isAir() || blockState.getBlock() instanceof LiquidBlock) {
            this.litematica_printer$removePendingDelayedDestroy(blockPos);
            return BlockBreakResult.COMPLETED;
        }
        if (this.hasDelayedDestroy) {
            BlockState blockState2 = minecraft.level.getBlockState(this.delayedDestroyPos);
            long currentTick = getClientTickCount();
            int elapsedTicks = (int) (currentTick - this.delayedDestroyStartTick);
            float delayedDestroyProgress = blockState2.getDestroyProgress(player, level, this.delayedDestroyPos) * elapsedTicks;
            if (delayedDestroyProgress >= 1.0F) {
                this.litematica_printer$playBreakEffect(this.delayedDestroyPos, blockState2);
                if (this.delayedDestroyLocalPrediction) {
                    this.litematica_printer$destroyBlockSilently(this.delayedDestroyPos);
                }
                this.litematica_printer$removePendingDelayedDestroy(this.delayedDestroyPos);
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
            }
        }
        if (!level.getWorldBorder().isWithinBounds(blockPos)) {
            return BlockBreakResult.FAILED;
        }
        if (player.getAbilities().instabuild) {
            PacketUtils.sendPacket(sequence -> {
                if (localBlockRemoval) {
                    litematica_printer$destroyBlockSilently(blockPos);
                }
                return getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence);
            });
            this.litematica_printer$playBreakEffect(blockPos, blockState);
            return BlockBreakResult.COMPLETED;
        }
        if (allowToolSwitch) {
            BreakUtils.trySwitchToEffectiveTool(blockPos, blockState);
        }
        ensureHasSentCarriedItem();
        if (!BreakUtils.protectCurrentToolBeforeBreak(blockState)) {
            return BlockBreakResult.FAILED;
        }
        if (!BreakUtils.isRecoveryToolReadyForBreak(blockState)) {
            return BlockBreakResult.FAILED;
        }
        ensureHasSentCarriedItem();
        boolean useDelayedDestroy = forceDelayedDestroy || Configs.Break.BREAK_USE_DELAYED_DESTROY.getBooleanValue();
        if (blockState.isAir()) {
            if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
                this.hasDelayedDestroy = false;
                this.delayedDestroyLocalPrediction = false;
                this.litematica_printer$clearDestroyProgress(player, blockPos);
            }
            this.litematica_printer$removePendingDelayedDestroy(blockPos);
            if (this.isDestroying && this.sameDestroyTarget(blockPos)) {
                this.litematica_printer$resetDestroyState(player, blockPos);
            }
            return BlockBreakResult.COMPLETED;
        }
        if (this.litematica_printer$hasPendingDelayedDestroy(blockPos)
                && (!this.hasDelayedDestroy || !blockPos.equals(this.delayedDestroyPos))) {
            return BlockBreakResult.COMPLETED_WAIT;
        }
        if (this.hasDelayedDestroy && blockPos.equals(this.delayedDestroyPos)) {
            return isDestroying ? BlockBreakResult.IN_PROGRESS : BlockBreakResult.COMPLETED;
        }
        if (this.isDestroying && !blockPos.equals(this.destroyBlockPos)) {
            PacketUtils.sendPacket(getActionPacket(Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction, 0));
            this.litematica_printer$resetDestroyState(player, this.destroyBlockPos);
        }
        if (blockPos.equals(this.destroyBlockPos)) {
            this.destroyProgress = this.destroyProgress + blockState.getDestroyProgress(player, level, blockPos);
            if (localEffects) {
                level.destroyBlockProgress(player.getId(), blockPos, this.litematica_printer$getDestroyStage());
            }
            if (this.destroyProgress >= ConfigUtils.getBreakProgressThreshold()) {
                PacketUtils.sendPacket(sequence -> {
                    if (localBlockRemoval) {
                        litematica_printer$destroyBlockSilently(blockPos);
                    }
                    this.litematica_printer$resetDestroyState(player, blockPos);
                    return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                });
                if (localEffects) {
                    level.destroyBlockProgress(player.getId(), blockPos, -1);
                }
                this.litematica_printer$playBreakEffect(blockPos, blockState);
                return BlockBreakResult.COMPLETED;
            }
            return BlockBreakResult.IN_PROGRESS;
        }
        if (!this.isDestroying || !blockPos.equals(this.destroyBlockPos)) {
            if (this.isDestroying) {
                PacketUtils.sendPacket(getActionPacket(Action.ABORT_DESTROY_BLOCK, this.destroyBlockPos, direction, 0));
                this.litematica_printer$resetDestroyState(player, this.destroyBlockPos);
            }
            float destroyProgress = blockState.getDestroyProgress(player, level, blockPos);
            boolean mineFastFinish = forceDelayedDestroy && destroyProgress > MINE_FAST_FINISH_PROGRESS;
            if (destroyProgress >= ConfigUtils.getBreakProgressThreshold() || mineFastFinish) {
                boolean waitForServerState = mineFastFinish
                        && destroyProgress < ConfigUtils.getBreakProgressThreshold()
                        && destroyProgress <= MINE_FAST_FINISH_COMPLETED_PROGRESS;
                PacketUtils.sendPacket(sequence -> getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
                PacketUtils.sendPacket(sequence -> {
                    if (localBlockRemoval) {
                        litematica_printer$destroyBlockSilently(blockPos);
                    }
                    this.litematica_printer$resetDestroyState(player, blockPos);
                    return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                });
                if (localEffects) {
                    level.destroyBlockProgress(player.getId(), blockPos, -1);
                }
                if (!waitForServerState) {
                    this.litematica_printer$playBreakEffect(blockPos, blockState);
                }
                return waitForServerState ? BlockBreakResult.COMPLETED_WAIT : BlockBreakResult.COMPLETED;
            }
            PacketUtils.sendPacket(sequence -> getActionPacket(Action.START_DESTROY_BLOCK, blockPos, direction, sequence));
            if (destroyProgress >= 1.0F) {
                if (localEffects) {
                    level.destroyBlockProgress(player.getId(), blockPos, -1);
                }
                this.litematica_printer$resetDestroyState(player, blockPos);
                this.litematica_printer$playBreakEffect(blockPos, blockState);
                return BlockBreakResult.COMPLETED;
            }
            if (useDelayedDestroy) {
                if (destroyProgress >= ConfigUtils.getBreakProgressThreshold()) {
                    PacketUtils.sendPacket(sequence -> {
                        if (localBlockRemoval) {
                            litematica_printer$destroyBlockSilently(blockPos);
                        }
                        this.hasDelayedDestroy = false;
                        return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                    });
                    if (localEffects) {
                        level.destroyBlockProgress(player.getId(), blockPos, -1);
                    }
                    this.litematica_printer$playBreakEffect(blockPos, blockState);
                    return BlockBreakResult.COMPLETED;
                } else {
                    // 发送STOP让服务端当前处理位置状态转移到延迟破坏位置中
                    PacketUtils.sendPacket(sequence -> {
                        this.hasDelayedDestroy = true;
                        this.delayedDestroyPos = blockPos;
                        this.delayedDestroyLocalPrediction = localBlockRemoval;
                        this.delayedDestroyStartTick = getClientTickCount();
                        this.litematica_printer$addPendingDelayedDestroy(blockPos);
                        this.litematica_printer$resetDestroyState(player, blockPos);
                        return getActionPacket(Action.STOP_DESTROY_BLOCK, blockPos, direction, sequence);
                    });
                    level.destroyBlockProgress(player.getId(), blockPos, this.litematica_printer$getDestroyStage());
                    return BlockBreakResult.COMPLETED_WAIT;
                }
            }
            this.isDestroying = true;
            this.destroyBlockPos = blockPos;
            this.destroyProgress = destroyProgress;
            this.destroyingItem = player.getMainHandItem();
            if (localEffects) {
                level.destroyBlockProgress(player.getId(), blockPos, this.litematica_printer$getDestroyStage());
            }
            return BlockBreakResult.IN_PROGRESS;
        }
        return BlockBreakResult.FAILED;
    }

    @Unique
    private void litematica_printer$destroyBlockSilently(BlockPos pos) {
        if (pos == null || this.minecraft.level == null) {
            return;
        }
        ClientLevel level = this.minecraft.level;
        LocalPlayer player = this.minecraft.player;
        if (!level.getBlockState(pos).isAir()) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
        }
        if (player != null) {
            level.destroyBlockProgress(player.getId(), pos, -1);
        }
    }

    // 非数据包挖掘/打印：本地播放方块破坏音效与粒子（数据包模式保持静音）
    @Unique
    private void litematica_printer$playBreakEffect(BlockPos pos, BlockState state) {
        if (Configs.Break.BREAK_USE_PACKET.getBooleanValue()) {
            return;
        }
        ClientLevel level = this.minecraft.level;
        if (level == null || pos == null) {
            return;
        }
        if (state == null || state.isAir() || state.getBlock() instanceof LiquidBlock) {
            state = level.getBlockState(pos);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                return;
            }
        }
        level.addDestroyBlockEffect(pos, state);
        level.playLocalSound(pos, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.8F, false);
    }

    @Unique
    private long getClientTickCount() {
        ClientLevel level = this.minecraft.level;
        return level == null ? 0L : level.getGameTime();
    }
}
