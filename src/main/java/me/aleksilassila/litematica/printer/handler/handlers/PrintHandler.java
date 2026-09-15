package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.go.GhastRideState;
import me.aleksilassila.litematica.printer.go.GhastShiftBlacklist;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.guide.guides.ShulkerPlacementGuard;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class PrintHandler extends ClientPlayerTickHandler {
    public final static String NAME = "print";

    @Getter
    @Setter
    private boolean pistonNeedFix;

    @Getter
    @Setter
    private boolean printerMemorySync;

    private Action action;
    private boolean icePlacementTask;

    @Nullable
    private Item activePlacementItem;
    private long nextPlacementItemTick;
    private long lastActivePlacementTick;

    private SchematicBlockContext ctx;

    public PrintHandler() {
        super(NAME, PrintModeType.PRINTER, Configs.Core.PRINT, Configs.Print.PRINT_SELECTION_TYPE, true);
    }

    public SchematicBlockContext getContext() {
        return ctx;
    }

    @Override
    protected int getTickInterval() {
        return Configs.Print.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Print.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean isSchematicHandler() {
        return true;
    }

    /** 方案一：判定缓存确认 CORRECT 的格位无需任何打印处理 */
    @Override
    protected boolean isVerifiedNoWork(BlockPos pos) {
        return level != null && SchematicStateCache.INSTANCE.isVerifiedNoWork(pos, level);
    }

    /** 扫描白名单：生效时主循环里非白名单格位在点查缓存处直接跳过，预算全部留给白名单方块 */
    @Override
    protected boolean shouldSkipFromScan(BlockPos pos) {
        if (!ScanWhitelistCache.PRINT.active() || level == null) {
            return false;
        }
        BlockState state = SchematicStateCache.INSTANCE.getSchematicState(pos);
        return state != null && !ScanWhitelistCache.PRINT.isWhitelisted(state);
    }

    /** 方案六：打印模式启用空闲退避（空轮后 1→2→4→…→10 tick，失效信号立即恢复） */
    @Override
    protected boolean idleBackoffEnabled() {
        return true;
    }

    /** 存在待快速重试的失败方块：不应进入空闲退避跳过 */
    @Override
    protected boolean hasUrgentRetries() {
        return Configs.Print.PRINT_USE_PACKET.getBooleanValue() && !retryTable.isEmpty();
    }

    /**
     * 优先同种方块定向扫描：活跃物品存在时直接取判定缓存的待办清单
     * （已判定需要工作且目标物品匹配），免去整盒遍历"路过"寻找同种方块的成本。
     * 额度用满外层会跳过本 tick 整盒遍历；额度有余时正常遍历仍兜底发现未判定格位（不漏扫）。
     */
    @Override
    protected int processTargetedScan(int remainingExecs, AtomicReference<Boolean> skipIteration) {
        if (!Configs.Print.PLACE_SAME_ITEM_FIRST.getBooleanValue()
                || activePlacementItem == null || level == null) {
            return 0;
        }
        PrinterBox box = boxRef == null ? null : boxRef.get();
        if (box == null) return 0;
        Item item = activePlacementItem;
        int executed = 0;
        // 失败尝试不消耗放置额度，但有单 gt 尝试上限（失败位置会进入放置冷却，下轮自动跳过）
        int attemptLimit = remainingExecs > 0 ? Math.max(remainingExecs * 2, 16) : 64;
        int attempts = 0;
        // 迭代时长限制：每 8 次尝试检查一次耗时，超时项留在待办清单下 gt 续作
        int timeLimit = getIterationTimeLimit();
        long budgetNanos = timeLimit > 0 ? timeLimit * 1_000_000L : 0L;
        long startNanos = System.nanoTime();
        for (BlockPos pos : SchematicStateCache.INSTANCE.getPendingPositions(item)) {
            if (skipIteration.get() || (remainingExecs > 0 && executed >= remainingExecs)) {
                break;
            }
            if (budgetNanos > 0 && attempts % 8 == 0 && System.nanoTime() - startNanos >= budgetNanos) {
                break;
            }
            if (!box.contains(pos) || !PlayerUtils.canInteracted(pos)) continue;
            if (isOnCooldown(pos) || isVerifiedNoWork(pos)) continue;
            if (++attempts > attemptLimit) break;
            if (!canProcessPos(pos)) continue;
            executeIteration(pos, skipIteration);
            // 只有实际放置成功才消耗额度
            if (lastOutcome == ExecuteOutcome.PLACED) {
                executed++;
            }
        }
        return executed;
    }

    /**
     * 失败重试快速路径：每 tick 在盒子遍历前执行。
     * 到期项直接做"世界状态 vs 原理图"比较并尝试放置，不等遍历扫到该位置。
     */
    @Override
    protected int processFastRetry(int maxExecs, AtomicReference<Boolean> skipIteration) {
        if (level == null || (retryTable.isEmpty() && pendingConfirm.isEmpty())) {
            return 0;
        }
        if (!Configs.Print.PRINT_USE_PACKET.getBooleanValue()) {
            retryTable.clear();
            pendingConfirm.clear();
            return 0;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        int executed = 0;

        // 1) 确认簿记：服务器已确认放置到位 → 出表；到期仍未确认 → 判失败转入重试表
        ObjectIterator<Long2LongMap.Entry> confirmIt = pendingConfirm.long2LongEntrySet().iterator();
        while (confirmIt.hasNext()) {
            Long2LongMap.Entry entry = confirmIt.next();
            // fastutil entry 在 iterator.remove() 后 index 置 -1 不可再读，key 必须先取出
            long key = entry.getLongKey();
            BlockPos pos = BlockPos.of(key);
            if (isVerifiedNoWork(pos)) {
                confirmIt.remove();
            } else if (now >= entry.getLongValue()) {
                long sentAt = entry.getLongValue() - CONFIRM_WINDOW_TICKS;
                confirmIt.remove();
                retryTable.put(key,
                        Math.max(now, sentAt + Math.max(CONFIRM_WINDOW_TICKS, getPlaceCooldown())));
            }
        }

        // 2) 失败重试：先收集到期项再逐个处理（避免迭代中修改表）
        if (retryTable.isEmpty()) {
            return executed;
        }
        LongArrayList dueKeys = new LongArrayList();
        for (Long2LongMap.Entry entry : retryTable.long2LongEntrySet()) {
            if (now >= entry.getLongValue()) {
                dueKeys.add(entry.getLongKey());
            }
        }
        // 尝试上限与额度分离：失败尝试不消耗放置额度（只统计成功放置），
        // 但单 gt 尝试次数有上限，避免大量失败项拖垮本 tick
        int attemptLimit = maxExecs > 0 ? Math.max(maxExecs * 2, 16) : 64;
        int attempts = 0;
        // 工作时长预算：每 8 次尝试检查一次耗时，超时项留在表内下 gt 续作
        int timeLimit = getIterationTimeLimit();
        long budgetNanos = timeLimit > 0 ? timeLimit * 1_000_000L : 0L;
        long startNanos = System.nanoTime();
        for (long key : dueKeys.toLongArray()) {
            if (skipIteration.get() || (maxExecs > 0 && executed >= maxExecs)) {
                break;
            }
            if (budgetNanos > 0 && attempts % 8 == 0 && System.nanoTime() - startNanos >= budgetNanos) {
                break;
            }
            retryTable.remove(key);
            BlockPos pos = BlockPos.of(key);
            // 已放置到位 → 出表
            if (isVerifiedNoWork(pos)) continue;
            // 不在交互范围内 → 出表，交还给正常盒子遍历覆盖
            if (!PlayerUtils.canInteracted(pos)) continue;
            if (++attempts > attemptLimit) break;
            // 被跳过名单/潜影盒守卫/破冰任务等规则排除 → 出表
            if (!canProcessPos(pos)) continue;
            executeIteration(pos, skipIteration);
            // 只有实际放置成功才消耗额度；失败项已由 executeIteration 重新入表冷却
            if (lastOutcome == ExecuteOutcome.PLACED) {
                executed++;
            }
            // 队列等待/预留上限：本轮停止
            if (skipIteration.get()) break;
        }
        return executed;
    }

    @Override
    public boolean canProcessPos(BlockPos blockPos) {
        if (!Configs.Print.PLACE_SAME_ITEM_FIRST.getBooleanValue()) {
            activePlacementItem = null;
            nextPlacementItemTick = 0L;
            lastActivePlacementTick = 0L;
        }
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;
        if (LitematicaUtils.getSchematicBlockState(blockPos) == null) return false;
        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos);
        if (SkipListCache.isSkipped(ctx.requiredState)) {
            return false;
        }
        // 扫描白名单：生效时只处理列表内方块与验证器高亮的"缺失方块"（并集，见 ScanWhitelistCache），
        // 其余不放置、不重试、不参与定向扫描
        if (!ScanWhitelistCache.PRINT.isWhitelisted(ctx.requiredState)) {
            return false;
        }
        // 跳过潜影盒打印：直接跳过所有潜影盒的放置
        if (Configs.Print.PRINT_SKIP_SHULKER.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            return false;
        }
        // 潜影盒后置：交换范围∩渲染层内还有普通方块（不含水/含水，不含其他潜影盒）未放完 → 跳过本位置
        if (Configs.Print.PRINT_SHULKER_AFTER_ORDINARY.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                && PrintTaskController.INSTANCE.hasPendingOrdinaryInRange(true)) {
            return false;
        }
        // 破冰放水：水源/含水方块缺水时由任务控制器优先接管（放冰），避免普通 Guide 先放"干方块"
        Action waterTask = PrintTaskController.INSTANCE.handle(ctx);
        if (waterTask != null) {
            this.action = waterTask;
            this.icePlacementTask = true;
            return true;
        }
        this.icePlacementTask = false;
        // 等待水源出现：跳过本位置（保留状态等待水出现）
        if (PrintTaskController.INSTANCE.isIcePlaced(blockPos)
                || PrintTaskController.INSTANCE.isWaitingWater(blockPos)) {
            return false;
        }
        // 破冰阶段：位置是冰，入破坏队列由 tweakeroo 决定工具破掉
        if (PrintTaskController.INSTANCE.isBreaking(blockPos)) {
            this.action = new Action();
            return true;
        }
        Action action = Guides.INSTANCE.buildAction(ctx).orElse(null);
        if (action == null) {
            if (Configs.Print.SAFELY_OBSERVER.getBooleanValue()
                    && ctx.requiredState.getBlock() instanceof ObserverBlock) {
                setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            }
            return false;
        }
        Item placementItem = getPlacementItem(action);
        if (placementItem != null && !canPlaceItemNow(placementItem)) return false;
        this.action = action;
        return true;
    }

    /** 单次放置尝试结果：PLACED=已发送；FAILED=本次尝试失败；DEFERRED=主动暂缓（守卫/破冰等待） */
    private enum ExecuteOutcome { PLACED, FAILED, DEFERRED }

    /**
     * 数据包打印失败重试表（posKey -> 重试到期tick）：
     * 放置失败的方块入表，每 tick 在盒子遍历前直接对表内方块比较放置，
     * 不依赖遍历扫到该位置才重试（大原理图一轮可能扫不完）。
     */
    private final Long2LongOpenHashMap retryTable = new Long2LongOpenHashMap();
    /** 已发送待服务器确认（posKey -> 确认截止tick）：到期仍未确认视为失败转入重试表 */
    private final Long2LongOpenHashMap pendingConfirm = new Long2LongOpenHashMap();
    /** 服务器确认窗口（tick）：超过仍未看到方块放置到位即判失败 */
    private static final int CONFIRM_WINDOW_TICKS = 5;

    /** 最近一次 doExecute 的结果（供快速路径统计"实际放置"次数） */
    private ExecuteOutcome lastOutcome = ExecuteOutcome.DEFERRED;

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        ExecuteOutcome outcome = doExecute(blockPos, skipIteration);
        this.lastOutcome = outcome;
        // 放置额度只统计成功放置；失败/暂缓尝试不消耗额度
        setExecuteConsumedQuota(outcome == ExecuteOutcome.PLACED);
        // 失败重试表仅数据包打印模式启用（无本地预测，放置失败不会被本地状态掩盖）
        if (!Configs.Print.PRINT_USE_PACKET.getBooleanValue()) {
            return;
        }
        long key = blockPos.asLong();
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        switch (outcome) {
            case PLACED -> {
                retryTable.remove(key);
                pendingConfirm.put(key, now + CONFIRM_WINDOW_TICKS);
            }
            case FAILED -> {
                pendingConfirm.remove(key);
                retryTable.put(key, now + Math.max(1, getPlaceCooldown()));
            }
            case DEFERRED -> {
                retryTable.remove(key);
                pendingConfirm.remove(key);
            }
        }
    }

    private ExecuteOutcome doExecute(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        // 任何针对该位置的处理结果都必须等待冷却后才能再次尝试。
        setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
        // 破冰放水：破冰阶段直接把冰入破坏队列，工具切换交给 tweakeroo
        if (PrintTaskController.INSTANCE.isBreaking(blockPos)) {
            BreakUtils.INSTANCE.add(blockPos);
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            return ExecuteOutcome.DEFERRED;
        }
        // 潜影盒放置守卫：只打印空盒时，后置放置 + 关容器 + 5gt 确认空盒，避免误放打开中的盒子
        if (Configs.Print.PRINT_ONLY_EMPTY_SHULKER.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            ShulkerPlacementGuard.GuardResult guardResult = ShulkerPlacementGuard.INSTANCE.evaluate(ctx);
            switch (guardResult) {
                case WAIT_OTHER_BLOCKS:
                case WAIT_CLOSE:
                case WAIT_CONFIRM:
                    setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                    return ExecuteOutcome.DEFERRED;
                case NO_EMPTY:
                    requestCloudStoreRefill(new Item[]{ctx.requiredState.getBlock().asItem()});
                    setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                    return ExecuteOutcome.DEFERRED;
                case READY:
                    // 守卫已切换主手为空盒，直接走放置
                    break;
            }
        }
        if (Configs.Print.FALLING_CHECK.getBooleanValue() && ctx.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();

            if (FallingBlock.isFree(level.getBlockState(downPos))) {
                MessageUtils.setOverlayMessage(I18n.BLOCK_NO_SUPPORT.getName(ctx.getRequiredBlockName().getString()));
                return ExecuteOutcome.FAILED;
                    } else if (LitematicaUtils.getSchematicBlockState(downPos) == null
                            || !BlockStateUtils.statesEqualIgnoreProperties(
                            level.getBlockState(downPos), LitematicaUtils.getSchematicBlockState(downPos))) {
                    MessageUtils.setOverlayMessage(I18n.BLOCK_MISMATCH.getName(ctx.getRequiredBlockName().getString()));
                    return ExecuteOutcome.FAILED;
                }

        }
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        Item placementItem = getPlacementItem(action);
        // 潜影盒守卫 READY 时主手已切好，跳过 switchToItems
        boolean shulkerReady = Configs.Print.PRINT_ONLY_EMPTY_SHULKER.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                && ShulkerPlacementGuard.INSTANCE.isReady(blockPos);
        // 换挡类交互（ClickAction 未指定物品，如中继器/比较器/活板门/红石线等右键切换状态）：
        // 保持当前手持任意物品直接右键，不再要求切空手
        if (shulkerReady) {
            // 主手已由守卫设置
        } else if (isFreeHandClick(action, reqItems)) {
            // 不切换物品，保持当前手持任意物品非潜行右键目标方块
        } else if (!InventoryUtils.switchToItems(player, reqItems)) {
            requestCloudStoreRefill(reqItems);
            return ExecuteOutcome.FAILED;
        }
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) return ExecuteOutcome.FAILED;
        // 凭空放置的目标必须仍为空/可替换；并发玩家已先占位时不要发送旧点击请求。
        if (Configs.Print.PLACE_IN_AIR.getBooleanValue()
                && !action.requiresSupport()
                && !(action instanceof ClickAction)
                && !BlockUtils.isReplaceable(level.getBlockState(blockPos))) {
            return ExecuteOutcome.FAILED;
        }
        // 骑乘乐魂期间：按潜行键＝下马。故「始终潜行」视为关闭，且需要 shift 的放置一律跳过
        //（记入临时黑名单，直到玩家离开乐魂——下车/换乘即清空）。
        boolean ridingGhast = GhastRideState.riddenGhast(player) != null;
        // 必须无条件调用：contains 同时承担"离开乐魂时清空黑名单"的职责，
        // 若用 ridingGhast 短路，未骑乘期间永不清理（只增不减，且下次骑乘旧条目仍生效）
        if (GhastShiftBlacklist.contains(player, blockPos)) {
            return ExecuteOutcome.DEFERRED; // 已拉黑：暂缓（不消耗放置额度）
        }
        boolean useShift;
        if (action.getShift() == null) {
            useShift = (Implementation.isInteractive(level.getBlockState(blockPos.relative(side)).getBlock()) && !(action instanceof ClickAction))
                    || (Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue() && !ridingGhast);
        } else {
            useShift = action.getShift();
        }
        if (useShift && ridingGhast) {
            GhastShiftBlacklist.add(player, blockPos);
            return ExecuteOutcome.DEFERRED; // 骑乘时不能潜行：跳过，留到下车后再打
        }
        action.setActionSource(ActionManager.ActionSource.PRINT);
        action.queueAction(blockPos, side, useShift, player, reqItems);
        if (icePlacementTask) {
            ActionManager.INSTANCE.setQueueCompletionListener(sendResult -> {
                if (sendResult.isSent()) {
                    PrintTaskController.INSTANCE.onIcePlaceSent(blockPos);
                }
            });
        }
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, ctx.requiredState);
        if (hitModifier != null) {
            ActionManager.INSTANCE.hitModifier = hitModifier;
            ActionManager.INSTANCE.useProtocol = true;
        }
        ActionManager.INSTANCE.setQueuedDirectionalPlacement(
                Configs.Print.PRINT_FAST_DIRECTIONAL_PLACEMENT.getBooleanValue() && action.isDirectional());
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        ActionManager.INSTANCE.setWaitForHorizontalLook(action.isWaitForHorizontalLook());
        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(player);
        if (sendResult.isSent() && Configs.Print.PRINT_USE_PACKET.getBooleanValue()) {
            PacketSoundConfirmationTracker.trackPlacement(blockPos, ctx.requiredState);
        }
        if (sendResult.isSent() && placementItem != null && Configs.Print.PLACE_SAME_ITEM_FIRST.getBooleanValue()) {
            activePlacementItem = placementItem;
            lastActivePlacementTick = level.getGameTime();
        }
        if (sendResult.isWaiting() || sendResult == ActionManager.SendResult.RESERVE_LIMIT) {
            skipIteration.set(true);
        }
        if (action.getCooldownTicksOverride() >= 0) {
            setCooldown(blockPos, action.getCooldownTicksOverride());
        } else {
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
        }
        return sendResult.isSent() ? ExecuteOutcome.PLACED : ExecuteOutcome.FAILED;
    }

    @Nullable
    private Item getPlacementItem(Action action) {
        Item targetItem = ctx.requiredState.getBlock().asItem();
        if (targetItem == Items.AIR) return null;
        Item[] requiredItems = action.getRequiredItems(ctx.requiredState.getBlock());
        if (isFreeHandClick(action, requiredItems)) return null;
        for (Item requiredItem : requiredItems) {
            if (requiredItem == targetItem) return targetItem;
        }
        return null;
    }

    private boolean canPlaceItemNow(Item item) {
        long tick = level.getGameTime();
        if (tick < nextPlacementItemTick) return false;
        if (activePlacementItem == null || activePlacementItem == item) return true;
        if (hasPendingPlacement(activePlacementItem)
                && tick - lastActivePlacementTick <= Math.max(Configs.Print.ITEM_SWITCH_INTERVAL.getIntegerValue() * 5L, 1)) {
            return false;
        }
        // 活跃物品的待放方块已放完，或虽有余量但长时间(物品切换间隔*5)未成功放置
        // (可能被侦测器安全放置、下落方块检查等规则卡住)，先跳过它尝试下一种物品
        activePlacementItem = null;
        int interval = Configs.Print.ITEM_SWITCH_INTERVAL.getIntegerValue();
        nextPlacementItemTick = tick + interval;
        return interval == 0;
    }

    /** 全盒兜底扫描记忆化 TTL（tick）：结论仅在方块变化（修订号）或盒子重建时才会翻转 */
    private static final int PENDING_SCAN_TTL_TICKS = 10;

    @Nullable
    private Item memoScanItem;
    private int memoScanBoxId;
    private long memoScanRevision = Long.MIN_VALUE;
    private long memoScanTick = Long.MIN_VALUE;
    private boolean memoScanResult;

    private boolean hasPendingPlacement(Item item) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        PrinterBox box = boxRef == null ? null : boxRef.get();
        if (schematic == null || box == null) return false;

        // 记忆化：同一物品 + 同一盒子 + 修订号未变 + TTL 内直接复用结论。
        // 该结论只在方块放置到位（revision++）或盒子重建时才会翻转，短 TTL 仅作保底；
        // 主循环内多个不同物品候选格的重复调用不再各自触发一次全盒扫描
        int boxId = System.identityHashCode(box);
        long now = level.getGameTime();
        long revision = SchematicStateCache.INSTANCE.getRevision();
        if (memoScanItem == item && memoScanBoxId == boxId
                && memoScanRevision == revision && now - memoScanTick < PENDING_SCAN_TTL_TICKS) {
            return memoScanResult;
        }

        boolean result = false;
        // 方案五：快速路径——判定缓存中已确认"需要工作且目标物品为 item"的格位直接复核，
        // 免掉整盒遍历的 schematic 点查；未命中再走下方权威全盒扫描（语义不变）
        for (BlockPos pos : SchematicStateCache.INSTANCE.getPendingPositions(item)) {
            if (!box.contains(pos) || !PlayerUtils.canInteracted(pos)) continue;
            BlockState required = LitematicaUtils.getSchematicBlockState(pos);
            if (required == null || required.getBlock().asItem() != item) continue;
            if (!BlockStateUtils.statesEqualIgnoreProperties(level.getBlockState(pos), required)) {
                result = true;
                break;
            }
        }

        // 权威路径：全盒逐格扫描（结论与旧实现完全一致；schematic 点查已由缓存加速）
        if (!result) {
            for (BlockPos pos : box) {
                if (!PlayerUtils.canInteracted(pos) || !LitematicaUtils.isSchematicBlock(pos)) continue;
                BlockState required = LitematicaUtils.getSchematicBlockState(pos);
                if (required == null) continue;
                if (required.getBlock().asItem() == item
                        && !BlockStateUtils.statesEqualIgnoreProperties(level.getBlockState(pos), required)) {
                    result = true;
                    break;
                }
            }
        }

        memoScanItem = item;
        memoScanBoxId = boxId;
        memoScanRevision = revision;
        memoScanTick = now;
        memoScanResult = result;
        return result;
    }

    /**
     * 方案三：PRINT_SKIP 名单缓存。
     * 旧实现每个候选格都重建 HashSet + 拼音流匹配（拼音转换非常昂贵）；
     * 现在仅在名单内容/开关变化时重建，并按方块状态缓存匹配结论
     * （同一状态在图中大量重复出现，实际拼音匹配次数趋近于零）。
     * 主线程专用（仅 canProcessPos / getRequiredItemsFor 调用）。
     */
    private static final class SkipListCache {
        private static List<String> source = List.of();
        private static boolean enabled;
        private static List<String> patterns = List.of();
        private static final Map<BlockState, Boolean> matchCache = new HashMap<>();

        static boolean isSkipped(BlockState requiredState) {
            boolean en = Configs.Print.PRINT_SKIP.getBooleanValue();
            List<String> cur = Configs.Print.PRINT_SKIP_LIST.getStrings();
            if (en != enabled || cur.size() != source.size() || !cur.equals(source)) {
                enabled = en;
                source = List.copyOf(cur);
                patterns = List.copyOf(cur);
                matchCache.clear();
            }
            if (!en) {
                return false;
            }
            return matchCache.computeIfAbsent(requiredState, st -> {
                for (String s : patterns) {
                    if (PinYinSearchUtils.matchName(s, st)) {
                        return true;
                    }
                }
                return false;
            });
        }
    }

    /**
     * 是否为"空手右键换挡"类交互（ClickAction 且未指定实际物品）。
     * 这类交互（中继器/比较器/活板门/红石线/拉杆等右键切换状态）不再要求空手，
     * 保持当前手持任意物品直接右键即可。
     */
    private static boolean isFreeHandClick(Action action, Item[] reqItems) {
        if (!(action instanceof ClickAction)) {
            return false;
        }
        if (reqItems == null) {
            return true;
        }
        for (Item item : reqItems) {
            if (item != null && item != Items.AIR) {
                return false;
            }
        }
        return true;
    }

    /**
     * 统计工作范围内所有需要打印、但背包中数量为 0 的材料种类。
     * 两步走：先逐格收集需要的物品种类（去重，带 8ms 时间预算截断），
     * 再对去重后的种类逐个统计可用量——旧实现每格重复统计背包/潜影盒，同一物品被扫描上千次。
     * 注意：这里使用只读判定（isRequiredForPlacement），不调用 canProcessPos / Guides.buildAction。
     * 之前的实现会经 Guides.buildAction 触发 DefaultGuide.onBuildActionWrongBlock，把多余/错误方块
     * 全部入队破坏，造成"开启云仓库补货时挖到原理图之外"的副作用。
     */
    private Set<Item> collectMissingMaterials() {
        PrinterBox box = this.boxRef == null ? null : this.boxRef.get();
        if (box == null) return new HashSet<>();
        // 潜影盒取货流程进行中时不视为缺货（与旧语义一致，缺货判断整体跳过）
        if (InventoryUtils.hasRecentlyOpenedShulker(player)) {
            return new HashSet<>();
        }
        int timeLimit = getIterationTimeLimit();
        long budgetNanos = timeLimit > 0 ? timeLimit * 1_000_000L : 0L;
        long startNanos = System.nanoTime();

        // 第一步：逐格收集需要的物品种类（去重），周期性检查耗时，超预算截断
        Set<Item> required = new HashSet<>();
        int scanned = 0;
        for (BlockPos pos : box) {
            if (++scanned > 20000) break;
            if (budgetNanos > 0 && scanned % 256 == 0 && System.nanoTime() - startNanos >= budgetNanos) break;
            if (!PlayerUtils.canInteracted(pos)) continue;
            if (!LitematicaUtils.isSchematicBlock(pos)) continue;
            if (getSelectionType() != null
                    && !PlayerUtils.isPositionInSelectionRange(player, pos, getSelectionType())) continue;
            Item[] reqItems = getRequiredItemsFor(pos);
            if (reqItems == null) continue;
            for (Item reqItem : reqItems) {
                if (reqItem != null && reqItem != net.minecraft.world.item.Items.AIR) {
                    required.add(reqItem);
                }
            }
        }

        // 第二步：去重后的种类逐个统计可用量（主背包 + 潜影盒内容）
        Set<Item> missing = new HashSet<>();
        for (Item reqItem : required) {
            if (InventoryUtils.countAvailableIncludingShulkers(player, reqItem) == 0) {
                missing.add(reqItem);
            }
        }
        return missing;
    }

    /**
     * 只读判定该位置是否需要打印（世界缺失目标方块或存在错误方块需替换），纯比较、不产生任何构建动作副作用。
     * 返回需要的物品 []；不需要或不可打印时返回 null。
     */
    @Nullable
    private Item[] getRequiredItemsFor(BlockPos pos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return null;
        SchematicBlockContext context = new SchematicBlockContext(client, level, schematic, pos);
        if (SkipListCache.isSkipped(context.requiredState)) {
            return null;
        }
        // 跳过潜影盒打印：直接跳过所有潜影盒的放置（也不为它们补货）
        if (Configs.Print.PRINT_SKIP_SHULKER.getBooleanValue()
                && context.requiredState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            return null;
        }
        BlockState required = context.requiredState;
        if (required.isAir() || required.getBlock() instanceof LiquidBlock) {
            return null;
        }
        BlockMatchResult match = BlockMatchResult.compare(context);
        // 空气/可替换（MISSING）需要放方块；错误方块（WRONG_BLOCK）需要先破坏再放，
        // 两者都会消耗目标方块材料，都应纳入云仓库缺货统计。
        if (match != BlockMatchResult.MISSING && match != BlockMatchResult.WRONG_BLOCK) {
            return null;
        }
        return new Item[]{required.getBlock().asItem()};
    }

    /**
     * 背包缺货时发起云仓库补货订单。
     * 空集兜底：只读扫描结果为空时，仍把当前缺货的 reqItems 加入订单，避免静默失效（无任何提示）。
     */
    private void requestCloudStoreRefill(Item[] reqItems) {
        if (!Configs.Special.PRINT_CLOUD_STORE_REFILL.getBooleanValue()
                || CloudStoreUtils.isRefillInCooldown()) {
            return;
        }
        Set<Item> missing = collectMissingMaterials();
        if (reqItems != null) {
            for (Item reqItem : reqItems) {
                if (reqItem == null || reqItem == net.minecraft.world.item.Items.AIR) continue;
                if (InventoryUtils.countAvailableIncludingShulkers(player, reqItem) == 0
                        && !InventoryUtils.hasRecentlyOpenedShulker(player)) {
                    missing.add(reqItem);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        CloudStoreUtils.tryRequestRefillMany(
                player,
                missing,
                Configs.Special.PRINT_CLOUD_STORE_REFILL_AMOUNT.getIntegerValue()
        );
    }
}
