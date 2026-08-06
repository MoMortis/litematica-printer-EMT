package me.aleksilassila.litematica.printer.printer;

import lombok.Setter;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin_extension.MultiPlayerGameModeExtension;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.SwitchItem;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.PacketUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.item.ItemStack;

//#if MC > 12105
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
//#else
//$$ import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
//#endif

@SuppressWarnings("SpellCheckingInspection")
public class ActionManager {
    public static final ActionManager INSTANCE = new ActionManager();
    private static final float LOOK_SETTLED_EPSILON_DEGREES = 1.0F;
    private static final double STALE_WAIT_MOVE_DISTANCE_SQR = 0.75D * 0.75D;
    private static final long PRINT_SIGN_EDIT_ARM_TIMEOUT_NANOS = 30_000_000_000L;
    private static final long PRINT_SIGN_EDIT_RESPONSE_TIMEOUT_NANOS = 5_000_000_000L;
    private static final long PRINT_SIGN_EDIT_PRUNE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long TASK_ANVIL_SCREEN_RESPONSE_TIMEOUT_NANOS = 5_000_000_000L;
    private static final int MAX_PENDING_TASK_ANVIL_SCREENS = 64;

    private QueuedClick queuedClick;
    private final Map<Long, Long> pendingPrintSignEdits = new HashMap<>();
    private long nextPrintSignEditPruneNanos;
    private int pendingTaskAnvilScreens;
    private long taskAnvilScreenSuppressionDeadlineNanos;
    private long manualAnvilScreenAllowanceDeadlineNanos;

    // A 侧兼容字段:PrintHandler 会直接对这两个字段赋值(精确放置/数据包协议)。
    public Vec3 hitModifier;
    public boolean useProtocol = false;

    @Setter
    @Nullable
    public PlayerLook look;
    public boolean needWaitModifyLook = false;
    private boolean waitForHorizontalLook = true;
    private boolean actionRequiresWaitModifyLook = false;
    private long lastQueuedLookTick = Long.MIN_VALUE;
    private float lastQueuedLookYaw;
    private float lastQueuedLookPitch;
    private boolean printerInteractionActive;
    private boolean easyPlaceProtocolActive;
    private ActionSource activeSource = ActionSource.GENERIC;

    // 打印耗材"在途未确认消耗"计数（按手持物品）。客户端背包数在服务端确认前不会下降，
    // 高速连续放置时单纯按 count - reserve 重算预算会被绕过，故累计已发送未确认的消耗量，
    // 待背包数实际下降（count < lastSeen）时按下降量消化。
    // 若停止放置后长时间（RESERVE_PENDING_EXPIRE_TICKS）未被服务端消化，说明该批在途已
    // 处理完毕（成功已入账 count / 失败不消耗），强制结清，让 count - reserve 恢复为可用预算。
    private static final long RESERVE_PENDING_EXPIRE_TICKS = 20L;
    private final Map<Item, Integer> reservePendingConsumed = new HashMap<>();
    private final Map<Item, Integer> reserveLastSeenCount = new HashMap<>();
    private final Map<Item, Long> reservePendingLastMoveTick = new HashMap<>();

    public enum ActionSource {
        GENERIC,
        PRINT,
        FILL,
        FLUID
    }

    public enum SendResult {
        SENT,
        WAITING_FOR_LOOK,
        NO_QUEUED_ACTION,
        NO_PLAYER,
        STALE_POSITION,
        HELD_ITEM_CHANGED,
        RESERVE_LIMIT,
        NO_GAME_MODE,
        INTERACTION_REJECTED;

        public boolean isSent() {
            return this == SENT;
        }

        public boolean isWaiting() {
            return this == WAITING_FOR_LOOK;
        }
    }

    private ActionManager() {
    }

    public boolean queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift) {
        return this.queueClick(target, side, hitModifier, useShift, 1);
    }

    public boolean queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift, int clickRepeatCount) {
        return this.queueClick(target, side, hitModifier, useShift, clickRepeatCount, null);
    }

    public boolean queueClick(@NotNull BlockPos target, @NotNull Direction side, @NotNull Vec3 hitModifier, boolean useShift, int clickRepeatCount, @Nullable Item[] expectedItems) {
        return this.queueClick(target, side, hitModifier, useShift, clickRepeatCount, expectedItems, ActionSource.GENERIC);
    }

    public boolean queueClick(
            @NotNull BlockPos target,
            @NotNull Direction side,
            @NotNull Vec3 hitModifier,
            boolean useShift,
            int clickRepeatCount,
            @Nullable Item[] expectedItems,
            @NotNull ActionSource source
    ) {
        if (this.queuedClick != null) {
            return false;
        }
        this.queuedClick = new QueuedClick(target, side, hitModifier, useShift, clickRepeatCount, source);
        this.queuedClick.expectItems(expectedItems);
        return true;
    }

    public void useProtocolHitModifier(@NotNull Vec3 hitModifier) {
        if (this.queuedClick != null) {
            this.queuedClick.useProtocolHit(hitModifier);
        }
    }

    public boolean setQueueCompletionListener(@Nullable Consumer<SendResult> completionListener) {
        if (this.queuedClick == null) {
            return false;
        }
        this.queuedClick.onCompletion(completionListener);
        return true;
    }

    public boolean setExpectedStackPredicate(@Nullable Predicate<ItemStack> expectedStackPredicate) {
        if (this.queuedClick == null) {
            return false;
        }
        this.queuedClick.expectStack(expectedStackPredicate);
        return true;
    }

    public SendResult sendQueue(@Nullable LocalPlayer player) {
        QueuedClick click = this.queuedClick;
        if (click == null) {
            return SendResult.NO_QUEUED_ACTION;
        }
        // A 侧兼容:PrintHandler 在 queueAction 之后直接赋值这两个字段。
        if (this.useProtocol && this.hitModifier != null) {
            click.useProtocolHit(this.hitModifier);
        }
        if (player == null) {
            return this.finish(click, SendResult.NO_PLAYER);
        }
        if (shouldDropStaleQueuedClick(player, click)) {
            return this.finish(click, SendResult.STALE_POSITION);
        }
        if (!needWaitModifyLook && look != null && shouldSendQueuedLook(look)) {
            PacketUtils.sendLookPacket(player, look);
            this.recordQueuedLook(look);
        }
        if (shouldWaitForServerLook(player, click)) {
            needWaitModifyLook = true;
            return SendResult.WAITING_FOR_LOOK;
        }
        if (needWaitModifyLook) {
            needWaitModifyLook = false;
        }
        if (!isHoldingExpectedItem(player, click)) {
            return this.finish(click, SendResult.HELD_ITEM_CHANGED);
        }
        int reserveAllowance = getReserveAllowance(player, click);
        if (reserveAllowance <= 0) {
            return this.finish(click, SendResult.RESERVE_LIMIT);
        }
        Direction direction;
        if (look == null) {
            direction = click.side;
        } else {
            direction = BlockUtils.getHorizontalDirection(look.yaw());
        }
        Vec3 hitVec;
        if (!click.useProtocol) {
            Vec3 targetCenter = Vec3.atCenterOf(click.target);
            Vec3 sideOffset = Vec3.atLowerCornerOf(BlockUtils.getVector(click.side)).scale(0.5);
            Vec3 rotatedHitModifier = click.hitModifier.yRot((direction.toYRot() + 90) % 360).scale(0.5);
            hitVec = targetCenter.add(sideOffset).add(rotatedHitModifier);
        } else {
            hitVec = click.hitModifier;
        }
        if (InventoryUtils.getOrderlyStoreItem() != null) {
            if (InventoryUtils.getOrderlyStoreItem().isEmpty()) {
                SwitchItem.removeItem(InventoryUtils.getOrderlyStoreItem());
            } else {
                SwitchItem.syncUseTime(InventoryUtils.getOrderlyStoreItem());
            }
        }
        boolean wasSneak = player.isShiftKeyDown();
        if (click.useShift && !wasSneak) {
            setShift(player, true);
        } else if (!click.useShift && wasSneak) {
            setShift(player, false);
        }
        if (!(Reference.MINECRAFT.gameMode instanceof MultiPlayerGameModeExtension gameModeExtension)) {
            restoreShift(player, click, wasSneak);
            return this.finish(click, SendResult.NO_GAME_MODE);
        }

        boolean accepted = false;
        this.printerInteractionActive = true;
        this.easyPlaceProtocolActive = click.useProtocol;
        this.activeSource = click.source;
        try {
            BlockHitResult blockHitResult = new BlockHitResult(hitVec, click.side, click.target, false);
            boolean localPrediction = !Configs.Placement.PRINT_USE_PACKET.getBooleanValue();
            for (int i = 0; i < click.repeatCount; i++) {
                // 每次放置前实时重查库存预算（count - 保留数 - 在途未确认消耗），
                // 预算耗尽立即停止本 click 的放置。
                int allowance = getReserveAllowance(player, click);
                if (allowance <= 0) {
                    break;
                }
                boolean interactionAccepted = gameModeExtension.litematica_printer$useItemOn(
                        localPrediction,
                        InteractionHand.MAIN_HAND,
                        blockHitResult
                ) != net.minecraft.world.InteractionResult.FAIL;
                accepted |= interactionAccepted;
                if (interactionAccepted) {
                    this.armTaskAnvilScreenSuppression(click);
                }
                if (interactionAccepted && allowance != Integer.MAX_VALUE) {
                    Item consumedItem = player.getMainHandItem().getItem();
                    this.reservePendingConsumed.merge(consumedItem, 1, Integer::sum);
                    this.reservePendingLastMoveTick.put(
                            consumedItem,
                            Reference.MINECRAFT.level == null
                                    ? 0L
                                    : Reference.MINECRAFT.level.getGameTime()
                    );
                }
            }
        } finally {
            this.printerInteractionActive = false;
            this.easyPlaceProtocolActive = false;
            this.activeSource = ActionSource.GENERIC;
            restoreShift(player, click, wasSneak);
        }
        return this.finish(click, accepted ? SendResult.SENT : SendResult.INTERACTION_REJECTED);
    }

    private void armTaskAnvilScreenSuppression(QueuedClick click) {
        if (!this.hasManualAnvilScreenAllowance()
                && (click.source == ActionSource.PRINT || click.source == ActionSource.FILL)
                && Reference.MINECRAFT.level != null
                && Reference.MINECRAFT.level.getBlockState(click.target).getBlock() instanceof AnvilBlock) {
            this.pendingTaskAnvilScreens = Math.min(
                    MAX_PENDING_TASK_ANVIL_SCREENS,
                    this.pendingTaskAnvilScreens + 1
            );
            this.taskAnvilScreenSuppressionDeadlineNanos =
                    System.nanoTime() + TASK_ANVIL_SCREEN_RESPONSE_TIMEOUT_NANOS;
        }
    }

    public boolean consumeTaskAnvilScreenSuppression() {
        if (this.pendingTaskAnvilScreens <= 0) {
            return false;
        }
        if (this.taskAnvilScreenSuppressionDeadlineNanos < System.nanoTime()) {
            this.clearTaskAnvilScreenSuppressions();
            return false;
        }
        this.pendingTaskAnvilScreens--;
        if (this.pendingTaskAnvilScreens == 0) {
            this.taskAnvilScreenSuppressionDeadlineNanos = 0L;
        }
        return true;
    }

    public void prioritizeManualAnvilScreen() {
        this.clearTaskAnvilScreenSuppressions();
        this.manualAnvilScreenAllowanceDeadlineNanos =
                System.nanoTime() + TASK_ANVIL_SCREEN_RESPONSE_TIMEOUT_NANOS;
    }

    public boolean consumeManualAnvilScreenAllowance() {
        if (!this.hasManualAnvilScreenAllowance()) {
            return false;
        }
        this.manualAnvilScreenAllowanceDeadlineNanos = 0L;
        return true;
    }

    public void clearTaskAnvilScreenSuppressions() {
        this.pendingTaskAnvilScreens = 0;
        this.taskAnvilScreenSuppressionDeadlineNanos = 0L;
    }

    private boolean hasManualAnvilScreenAllowance() {
        if (this.manualAnvilScreenAllowanceDeadlineNanos == 0L) {
            return false;
        }
        if (this.manualAnvilScreenAllowanceDeadlineNanos < System.nanoTime()) {
            this.manualAnvilScreenAllowanceDeadlineNanos = 0L;
            return false;
        }
        return true;
    }

    private int getReserveAllowance(LocalPlayer player, QueuedClick click) {
        if (click.source != ActionSource.PRINT
                || !Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue()) {
            return Integer.MAX_VALUE;
        }
        ItemStack held = player.getMainHandItem();
        int reserveCount = Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue();
        if (reserveCount < 0
                || PlayerUtils.getAbilities(player).instabuild
                || held.isEmpty()
                || held.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }
        Predicate<ItemStack> predicate = click.expectedStackPredicate != null
                ? click.expectedStackPredicate
                : candidate -> candidate.is(held.getItem());
        Item item = held.getItem();
        int count = InventoryUtils.countMatchingMainInventory(player, predicate);
        int lastSeen = this.reserveLastSeenCount.getOrDefault(item, count);
        int pending = this.reservePendingConsumed.getOrDefault(item, 0);
        long changeTick = this.reservePendingLastMoveTick.getOrDefault(item, Long.MIN_VALUE);
        if (count > lastSeen) {
            // 数量真正增加（补充了新物品）才能认为在途消耗已全部入账
            pending = 0;
        } else if (count < lastSeen) {
            pending = Math.max(0, pending - (lastSeen - count));
        }
        if (pending > 0 && changeTick != Long.MIN_VALUE) {
            long tick = Reference.MINECRAFT.level == null ? 0L : Reference.MINECRAFT.level.getGameTime();
            if (tick - changeTick > RESERVE_PENDING_EXPIRE_TICKS) {
                pending = 0;
            }
        }
        this.reservePendingConsumed.put(item, pending);
        if (pending == 0) {
            this.reservePendingLastMoveTick.remove(item);
        }
        this.reserveLastSeenCount.put(item, count);
        return Math.max(0, count - reserveCount - pending);
    }

    private void restoreShift(LocalPlayer player, QueuedClick click, boolean wasSneak) {
        if (click.useShift && !wasSneak) {
            setShift(player, false);
        } else if (!click.useShift && wasSneak) {
            setShift(player, true);
        }
    }

    private SendResult finish(QueuedClick click, SendResult result) {
        Consumer<SendResult> completionListener = click.completionListener;
        this.clearQueue();
        if (completionListener != null) {
            completionListener.accept(result);
        }
        return result;
    }

    public boolean isPrinterInteractionActive() {
        return this.printerInteractionActive;
    }

    public boolean isPrintInteractionActive() {
        return this.printerInteractionActive && this.activeSource == ActionSource.PRINT;
    }

    public boolean isEasyPlaceProtocolActive() {
        return this.printerInteractionActive
                && this.activeSource == ActionSource.PRINT
                && this.easyPlaceProtocolActive;
    }

    public void armPrintSignEdit(BlockPos blockPos) {
        long now = System.nanoTime();
        this.pruneExpiredPrintSignEdits(now);
        this.pendingPrintSignEdits.put(blockPos.asLong(), now + PRINT_SIGN_EDIT_ARM_TIMEOUT_NANOS);
    }

    public void confirmPrintSignEditSent(BlockPos blockPos) {
        this.pendingPrintSignEdits.replace(
                blockPos.asLong(),
                System.nanoTime() + PRINT_SIGN_EDIT_RESPONSE_TIMEOUT_NANOS
        );
    }

    public void cancelPrintSignEdit(BlockPos blockPos) {
        this.pendingPrintSignEdits.remove(blockPos.asLong());
    }

    public boolean consumePrintSignEdit(BlockPos blockPos) {
        long now = System.nanoTime();
        Long deadline = this.pendingPrintSignEdits.remove(blockPos.asLong());
        return deadline != null && deadline >= now;
    }

    private void pruneExpiredPrintSignEdits(long now) {
        if (now < this.nextPrintSignEditPruneNanos) {
            return;
        }
        this.nextPrintSignEditPruneNanos = now + PRINT_SIGN_EDIT_PRUNE_INTERVAL_NANOS;
        this.pendingPrintSignEdits.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    public void setShift(LocalPlayer player, boolean shift) {
        //#if MC > 12105
        Input input = new Input(player.input.keyPresses.forward(), player.input.keyPresses.backward(), player.input.keyPresses.left(), player.input.keyPresses.right(), player.input.keyPresses.jump(), shift, player.input.keyPresses.sprint());
        ServerboundPlayerInputPacket packet = new ServerboundPlayerInputPacket(input);
        //#else
        //$$ ServerboundPlayerCommandPacket packet = new ServerboundPlayerCommandPacket(player, shift ? ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY : ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY);
        //#endif
        player.setShiftKeyDown(shift);
        PacketUtils.sendPacket(packet);
    }

    public void setWaitForHorizontalLook(boolean waitForHorizontalLook) {
        this.waitForHorizontalLook = waitForHorizontalLook;
    }

    public void setNeedWaitModifyLookFromAction(boolean actionRequiresWaitModifyLook) {
        this.actionRequiresWaitModifyLook = actionRequiresWaitModifyLook;
    }

    private boolean shouldWaitForServerLook(LocalPlayer player, QueuedClick click) {
        if ((!this.waitForHorizontalLook && !this.actionRequiresWaitModifyLook)
                || click.useProtocol
                || this.needWaitModifyLook
                || this.look == null) {
            return false;
        }
        Direction lookDirection = BlockUtils.orderedByNearest(this.look.yaw(), this.look.pitch())[0];
        return lookDirection.getAxis().isHorizontal()
                && !isPlayerLookSettled(player, this.look);
    }

    private boolean shouldDropStaleQueuedClick(LocalPlayer player, QueuedClick click) {
        if (!this.needWaitModifyLook || click.queuedPlayerPosition == null) {
            return false;
        }
        long currentTick = Reference.MINECRAFT.level == null ? Long.MIN_VALUE : Reference.MINECRAFT.level.getGameTime();
        if (currentTick == Long.MIN_VALUE || currentTick <= click.queuedTick) {
            return false;
        }
        return player.position().distanceToSqr(click.queuedPlayerPosition) > STALE_WAIT_MOVE_DISTANCE_SQR;
    }

    private static boolean isHoldingExpectedItem(LocalPlayer player, QueuedClick click) {
        if (click.expectedStackPredicate != null
                && !click.expectedStackPredicate.test(player.getMainHandItem())) {
            return false;
        }
        if (click.expectedItems == null || click.expectedItems.length == 0) {
            return true;
        }
        Item heldItem = player.getMainHandItem().getItem();
        for (Item expectedItem : click.expectedItems) {
            if (heldItem.equals(expectedItem)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlayerLookSettled(LocalPlayer player, PlayerLook look) {
        return Math.abs(Mth.wrapDegrees(player.getYRot() - look.yaw())) <= LOOK_SETTLED_EPSILON_DEGREES
                && Math.abs(player.getXRot() - look.pitch()) <= LOOK_SETTLED_EPSILON_DEGREES;
    }

    private boolean shouldSendQueuedLook(PlayerLook look) {
        long tick = Reference.MINECRAFT.level == null ? Long.MIN_VALUE : Reference.MINECRAFT.level.getGameTime();
        if (tick == Long.MIN_VALUE || this.lastQueuedLookTick != tick) {
            return true;
        }
        return Math.abs(Mth.wrapDegrees(this.lastQueuedLookYaw - look.yaw())) > LOOK_SETTLED_EPSILON_DEGREES
                || Math.abs(this.lastQueuedLookPitch - look.pitch()) > LOOK_SETTLED_EPSILON_DEGREES;
    }

    private void recordQueuedLook(PlayerLook look) {
        this.lastQueuedLookTick = Reference.MINECRAFT.level == null ? Long.MIN_VALUE : Reference.MINECRAFT.level.getGameTime();
        this.lastQueuedLookYaw = look.yaw();
        this.lastQueuedLookPitch = look.pitch();
    }

    public void clearQueue() {
        this.queuedClick = null;
        this.hitModifier = null;
        this.useProtocol = false;
        this.needWaitModifyLook = false;
        this.waitForHorizontalLook = true;
        this.actionRequiresWaitModifyLook = false;
        this.look = null;
        this.printerInteractionActive = false;
        this.easyPlaceProtocolActive = false;
        this.activeSource = ActionSource.GENERIC;
    }

    public void resetRuntime() {
        this.clearQueue();
        this.pendingPrintSignEdits.clear();
        this.nextPrintSignEditPruneNanos = 0L;
        this.clearTaskAnvilScreenSuppressions();
        this.manualAnvilScreenAllowanceDeadlineNanos = 0L;
    }
}
