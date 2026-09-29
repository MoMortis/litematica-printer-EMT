package me.aleksilassila.litematica.printer.printer.zxy.inventory;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;

public class InventoryUtils {
    private static final int AUTOMATED_QUICK_SHULKER_SCREEN_PROTECTION_TIMEOUT_TICKS = 40;
    private static final long QUICK_SHULKER_SEARCH_TIMEOUT_NANOS = 1_000_000_000L;

    private static int shulkerCooldown = 0;
    private static long quickShulkerSearchDeadlineNanos;
    private static int automatedQuickShulkerScreenProtectionTimeout;
    private static boolean automatedQuickShulkerScreenProtection;
    private static boolean automatedQuickShulkerOpened;
    private static boolean preserveAutomatedQuickShulkerScreenOnClose;
    private static Screen protectedScreen;

    private static final Minecraft client = Minecraft.getInstance();

    public static boolean isInventory(Level world, BlockPos pos) {
        return fi.dy.masa.malilib.util.InventoryUtils.getInventory(world, pos) != null;
    }

    public static boolean canOpenInv(BlockPos pos) {
        if (client.level != null) {
            BlockState blockState = client.level.getBlockState(pos);
            BlockEntity blockEntity = client.level.getBlockEntity(pos);
            boolean isInventory = InventoryUtils.isInventory(client.level, pos);
            try {
                if ((isInventory && blockState.getMenuProvider(client.level, pos) == null) ||
                        (blockEntity instanceof ShulkerBoxBlockEntity entity &&
                                //#if MC > 12103
                                !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F, Vec3.atBottomCenterOf(pos)).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12103 && MC > 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(blockState.getValue(BlockStateProperties.FACING), 0.0f, 0.5f).move(pos).deflate(1.0E-6)) &&
                                //#endif
                                entity.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED)) {
                    return false;
                } else if (!isInventory) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    public static HashSet<Item> lastNeedItemList = new HashSet<>();
    public static boolean isOpenHandler = false;

    public static void addQuickShulkerDemand(Item item) {
        if (lastNeedItemList.isEmpty() && Configs.Core.QUICK_SHULKER.getBooleanValue()) {
            quickShulkerSearchDeadlineNanos = System.nanoTime() + QUICK_SHULKER_SEARCH_TIMEOUT_NANOS;
        }
        lastNeedItemList.add(item);
    }

    public static boolean shouldSuppressContainerScreen() {
        LocalPlayer player = client.player;
        return automatedQuickShulkerScreenProtection
                && player != null
                && !player.containerMenu.equals(player.inventoryMenu)
                && isOpenHandler;
    }

    public static void beginAutomatedQuickShulkerScreenProtection() {
        automatedQuickShulkerScreenProtection = true;
        automatedQuickShulkerOpened = false;
        automatedQuickShulkerScreenProtectionTimeout = AUTOMATED_QUICK_SHULKER_SCREEN_PROTECTION_TIMEOUT_TICKS;
        preserveAutomatedQuickShulkerScreenOnClose = false;
        //#if MC >= 260200
        //$$ protectedScreen = client.gui.screen();
        //#else
        protectedScreen = client.screen;
        //#endif
    }

    public static boolean shouldPreserveAutomatedQuickShulkerScreenOnClose(Screen screen) {
        if (!automatedQuickShulkerScreenProtection
                || !preserveAutomatedQuickShulkerScreenOnClose
                || screen != null) {
            return false;
        }
        preserveAutomatedQuickShulkerScreenOnClose = false;
        automatedQuickShulkerScreenProtection = false;
        automatedQuickShulkerScreenProtectionTimeout = 0;
        protectedScreen = null;
        return true;
    }

    public static void closeAutomatedQuickShulkerContainer(LocalPlayer player) {
        if (!automatedQuickShulkerOpened) {
            clearAutomatedQuickShulkerScreenProtection();
            return;
        }
        if (!automatedQuickShulkerScreenProtection) {
            player.closeContainer();
            automatedQuickShulkerOpened = false;
            return;
        }
        preserveAutomatedQuickShulkerScreenOnClose = protectedScreen != null;
        player.closeContainer();
        automatedQuickShulkerScreenProtection = false;
        automatedQuickShulkerOpened = false;
        automatedQuickShulkerScreenProtectionTimeout = 0;
        preserveAutomatedQuickShulkerScreenOnClose = false;
        protectedScreen = null;
    }

    public static void clearAutomatedQuickShulkerScreenProtection() {
        automatedQuickShulkerScreenProtection = false;
        automatedQuickShulkerOpened = false;
        automatedQuickShulkerScreenProtectionTimeout = 0;
        preserveAutomatedQuickShulkerScreenOnClose = false;
        protectedScreen = null;
    }

    public static boolean switchItem() {
        if (!lastNeedItemList.isEmpty() && !isOpenHandler) {
            LocalPlayer player = client.player;
            if (!player.containerMenu.equals(player.inventoryMenu)) return false;
            if (Configs.Core.QUICK_SHULKER.getBooleanValue() && openShulker(lastNeedItemList)) {
                return true;
            }
        }
        return false;
    }

    static int shulkerBoxSlot = -1;
    private static int quickShulkerEmptySlots;
    private static boolean quickShulkerHasNonShulkerItem;

    public static void switchInv() {
        LocalPlayer player = Minecraft.getInstance().player;
        AbstractContainerMenu sc = player.containerMenu;
        if (sc.equals(player.inventoryMenu)) {
            return;
        }
        NonNullList<Slot> slots = sc.slots;
        int maxStacks = Configs.Core.QUICK_SHULKER_MAX_STACKS.getIntegerValue();
        int allowedStacks = quickShulkerEmptySlots > 0
                ? Math.min(maxStacks, quickShulkerEmptySlots)
                : (quickShulkerHasNonShulkerItem ? 1 : 0);
        if (allowedStacks == 0) {
            MessageUtils.setOverlayMessage(I18n.INVENTORY_BACKPACK_FULL.getName());
            finishQuickShulkerTransfer(player);
            return;
        }
        int movedStacks = 0;
        for (Item item : lastNeedItemList) {
            for (int y = 0; y < slots.get(0).container.getContainerSize() && movedStacks < allowedStacks; y++) {
                ItemStack source = slots.get(y).getItem();
                if (!source.getItem().equals(item)) {
                    continue;
                }
                try {
                    if (quickShulkerEmptySlots > 0) {
                        client.gameMode.handleInventoryMouseClick(
                                sc.containerId,
                                y,
                                0,
                                ClickType.QUICK_MOVE,
                                player);
                        movedStacks++;
                        continue;
                    }
                    if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
                        break;
                    }
                    int c = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
                    if (c == -1) {
                        break;
                    }
                    if (BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(c).getItem()).toString().contains("shulker_box")
                            && Configs.Core.QUICK_SHULKER.getBooleanValue()) {
                        MessageUtils.setOverlayMessage(I18n.INVENTORY_SHULKER_PRESELECT.getName());
                        continue;
                    }
                    fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, y, c);
                    me.aleksilassila.litematica.printer.utils.InventoryUtils.setSelectedSlot(player.getInventory(), c);
                    movedStacks++;
                } catch (Exception e) {
                    System.out.println("切换物品异常");
                }
            }
        }
        finishQuickShulkerTransfer(player);
    }

    private static void finishQuickShulkerTransfer(LocalPlayer player) {
        // 登记本次补货使用的盒槽位（幽灵物品同步用）：补货完成后 100gt 内连续放置失败
        // 会指向"上次取出的物品是幽灵物品"
        if (shulkerBoxSlot >= 9) {
            lastRefillShulkerMenuSlot = shulkerBoxSlot;
            lastRefillHandlerTick = ClientPlayerTickManager.getCurrentHandlerTime();
        }
        shulkerBoxSlot = -1;
        quickShulkerEmptySlots = 0;
        quickShulkerHasNonShulkerItem = false;
        lastNeedItemList = new HashSet<>();
        quickShulkerSearchDeadlineNanos = 0L;
        isOpenHandler = false;
        consecutivePlaceFailures = 0;
        // 通知自动补货适配：趁潜影盒容器未关闭，与取出物品同一 tick 执行"物品放回消耗前槽位"
        me.aleksilassila.litematica.printer.utils.HandRestockShulkerCompat
                .onQuickShulkerTransferFinished(player);
        AbstractContainerMenu currentMenu = player.containerMenu;
        if (!currentMenu.equals(player.inventoryMenu)) {
            closeAutomatedQuickShulkerContainer(player);
        } else {
            clearAutomatedQuickShulkerScreenProtection();
        }
    }

    // ==================== 补货后幽灵物品同步 ====================

    /**
     * 快捷潜影盒取物走客户端预测：若客户端看到的盒内物品与服务端不一致（上次取物
     * 实际未被服务端执行），取出的"材料"就是幽灵物品——手持/背包看似有货，放置却
     * 一直失败。开盒会让服务端下发容器内容（含玩家背包槽），客户端据此校正幽灵物品。
     *
     * <p>同步手段是<b>复用补货链路的空取</b>：开盒时 {@code lastNeedItemList} 为空，
     * 容器内容包触发的 {@link #switchInv()} 不会取放任何物品、直接走
     * {@link #finishQuickShulkerTransfer} 关盒——即一次完整的"开关潜影盒"同步，
     * 且界面被自动化保护机制压住不弹出。
     */
    private static final int RESYNC_FAILURE_THRESHOLD = 3;
    /** 补货完成后多久内的放置失败才归因于幽灵物品（tick） */
    private static final long RESYNC_REFILL_WINDOW_TICKS = 100;
    /** 开盒后等待容器内容包的超时（tick） */
    private static final int RESYNC_OPEN_TIMEOUT_TICKS = 20;
    /** 两次幽灵物品同步的最小间隔（tick） */
    private static final int RESYNC_COOLDOWN_TICKS = 100;

    /** 上一次补货使用的背包菜单槽位（inventoryMenu 槽 9..） */
    private static int lastRefillShulkerMenuSlot = -1;
    /** 上一次补货完成时的处理器 tick */
    private static long lastRefillHandlerTick = -1L;
    /** 补货窗口内的连续放置失败计数 */
    private static int consecutivePlaceFailures;
    /** 已发起同步开盒、等待容器内容包 */
    private static boolean resyncOpenPending;
    /** 同步开盒的截止（处理器 tick） */
    private static long resyncDeadlineTick;

    /**
     * 记录一次放置结果（由 PrintHandler 在每次放置尝试后调用）：
     * 补货后 {@link #RESYNC_REFILL_WINDOW_TICKS} 内连续
     * {@link #RESYNC_FAILURE_THRESHOLD} 次放置失败 → 开关一次上次使用的快捷潜影盒，
     * 校正可能存在的幽灵物品。DEFERRED（暂缓，如守卫等待）不参与计数。
     */
    public static void onPlacementOutcome(boolean placed) {
        if (Configs.Core.QUICK_SHULKER.getBooleanValue()
                && lastRefillShulkerMenuSlot >= 9
                && ClientPlayerTickManager.getCurrentHandlerTime() - lastRefillHandlerTick
                <= RESYNC_REFILL_WINDOW_TICKS) {
            if (placed) {
                consecutivePlaceFailures = 0;
            } else if (++consecutivePlaceFailures >= RESYNC_FAILURE_THRESHOLD) {
                consecutivePlaceFailures = 0;
                tryResyncOpenLastShulker();
            }
        }
    }

    /** 发起一次幽灵物品同步开盒；false = 当前不适合（冷却中/补货中/盒已不在等） */
    private static boolean tryResyncOpenLastShulker() {
        if (resyncOpenPending || isOpenHandler || resyncCooldown > 0 || !lastNeedItemList.isEmpty()) {
            return false;
        }
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null || me.aleksilassila.litematica.printer.utils.EatUtils.isBusy()) {
            return false;
        }
        if (!player.containerMenu.equals(player.inventoryMenu)) {
            return false;
        }
        ItemStack box = player.inventoryMenu.slots.get(lastRefillShulkerMenuSlot).getItem();
        if (box.isEmpty() || box.getCount() != 1
                || !BuiltInRegistries.ITEM.getKey(box.getItem()).toString().contains("shulker_box")) {
            return false;
        }
        try {
            BlockUtils.openShulker(box, lastRefillShulkerMenuSlot);
            automatedQuickShulkerOpened = true;
            // 置 isOpenHandler：容器内容包到达时 switchInv 按空需求清单执行，
            // 不取放任何物品，直接走 finishQuickShulkerTransfer 关盒完成同步
            isOpenHandler = true;
            shulkerCooldown = Configs.Core.QUICK_SHULKER_COOLDOWN.getIntegerValue();
            resyncOpenPending = true;
            resyncDeadlineTick = ClientPlayerTickManager.getCurrentHandlerTime() + RESYNC_OPEN_TIMEOUT_TICKS;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 同步开盒超时兜底：容器内容包一直没来则复位状态（界面若已打开则一并关闭） */
    private static void tickResyncOpenClose() {
        if (resyncCooldown > 0) {
            resyncCooldown--;
        }
        if (!resyncOpenPending) {
            return;
        }
        LocalPlayer player = client.player;
        if (player == null) {
            resyncOpenPending = false;
            return;
        }
        if (!isOpenHandler) {
            // 容器内容包已到、finishQuickShulkerTransfer 已关盒：同步完成
            resyncOpenPending = false;
            resyncCooldown = RESYNC_COOLDOWN_TICKS;
        } else if (ClientPlayerTickManager.getCurrentHandlerTime() >= resyncDeadlineTick) {
            resyncOpenPending = false;
            isOpenHandler = false;
            resyncCooldown = RESYNC_COOLDOWN_TICKS;
            if (!player.containerMenu.equals(player.inventoryMenu)) {
                closeAutomatedQuickShulkerContainer(player);
            } else {
                clearAutomatedQuickShulkerScreenProtection();
            }
        }
    }

    private static int resyncCooldown;

    private static void snapshotQuickShulkerInventory(net.minecraft.world.entity.player.Inventory inventory) {
        quickShulkerEmptySlots = 0;
        quickShulkerHasNonShulkerItem = false;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                quickShulkerEmptySlots++;
            } else if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("shulker_box")) {
                quickShulkerHasNonShulkerItem = true;
            }
        }
    }

    private static boolean openShulker(HashSet<Item> items) {
        if (shulkerCooldown > 0) {
            return false;
        }
        for (Item item : items) {
            AbstractContainerMenu sc = Minecraft.getInstance().player.inventoryMenu;
            for (int i = 9; i < sc.slots.size(); i++) {
                ItemStack stack = sc.slots.get(i).getItem();
                String itemid = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (itemid.contains("shulker_box") && stack.getCount() == 1) {
                    NonNullList<ItemStack> items1 = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1);
                    if (items1.stream().anyMatch(s1 -> s1.getItem().equals(item))) {
                        try {
                            shulkerBoxSlot = i;
                            snapshotQuickShulkerInventory(Minecraft.getInstance().player.getInventory());
                            BlockUtils.openShulker(stack, shulkerBoxSlot);
                            automatedQuickShulkerOpened = true;
                            isOpenHandler = true;
                            shulkerCooldown = Configs.Core.QUICK_SHULKER_COOLDOWN.getIntegerValue();
                            return true;
                        } catch (Exception e) {
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void abandonExpiredQuickShulkerSearch() {
        if (quickShulkerSearchDeadlineNanos == 0L || isOpenHandler
                || System.nanoTime() < quickShulkerSearchDeadlineNanos) {
            return;
        }
        LocalPlayer player = client.player;
        if (player != null) {
            finishQuickShulkerTransfer(player);
        } else {
            lastNeedItemList = new HashSet<>();
            quickShulkerSearchDeadlineNanos = 0L;
            shulkerBoxSlot = -1;
            quickShulkerEmptySlots = 0;
            quickShulkerHasNonShulkerItem = false;
            isOpenHandler = false;
            clearAutomatedQuickShulkerScreenProtection();
        }
    }

    public static void tick() {
        if (me.aleksilassila.litematica.printer.utils.EatUtils.isBusy()) {
            return; // 暴饮暴食进食/取食中：手持与背包相关操作让路（冷却与超时清理延后无碍）
        }
        tickResyncOpenClose();
        if (client.player != null) {
            me.aleksilassila.litematica.printer.utils.HandRestockShulkerCompat.clientTick(client.player);
        }
        abandonExpiredQuickShulkerSearch();
        if (shulkerCooldown > 0) {
            shulkerCooldown--;
        }
        if (automatedQuickShulkerScreenProtectionTimeout > 0
                && --automatedQuickShulkerScreenProtectionTimeout == 0) {
            clearAutomatedQuickShulkerScreenProtection();
        }
    }
}