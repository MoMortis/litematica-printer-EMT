package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 快捷潜影盒 - 自动补货。
 *
 * 自主检测主手/副手物品的消耗（Mixin 捕获 MultiPlayerGameMode#useItem /
 * useItemOn 前后的手部物品快照，数量减少即视为消耗）：
 * 消耗后若主背包已没有该物品、但背包内潜影盒里还有，
 * 则交给"快捷潜影盒"流程取出物品放入背包；取出完成后再把物品放回
 * 该物品消耗前所在的手部槽位（主手/副手）。
 */
public final class HandRestockShulkerCompat {
    private HandRestockShulkerCompat() {
    }

    /** 取货完成后等待容器关闭/数据同步的延迟（tick）。 */
    private static final int HAND_RETURN_DELAY_TICKS = 2;
    /** 待回置请求的最长有效期（tick），超时自动丢弃。 */
    private static final int HAND_RETURN_TIMEOUT_TICKS = 100;
    /** 副手在玩家背包（Inventory）中的槽位号。 */
    private static final int OFFHAND_INVENTORY_SLOT = 40;
    /** 副手在玩家背包界面（InventoryMenu）中的槽位号。 */
    private static final int OFFHAND_MENU_SLOT = 45;

    private static Item pendingItem;
    private static int pendingTargetInventorySlot = -1;
    private static long pendingExecuteTick;
    private static long pendingExpireTick;

    /**
     * 由 Mixin 在 MultiPlayerGameMode#useItem / useItemOn 结束时调用：
     * 对比使用前后该手部的物品快照，数量减少（或耗尽为空）即判定发生消耗。
     */
    public static void onHandStackConsumed(net.minecraft.world.entity.player.Player player,
                                           InteractionHand hand,
                                           ItemStack before,
                                           ItemStack after) {
        if (!(player instanceof LocalPlayer localPlayer)
                || before == null || before.isEmpty()) {
            return;
        }

        Item item = before.getItem();
        boolean shrunk = after == null || after.isEmpty() || (!after.is(item) ? false : after.getCount() < before.getCount());

        if (item == Items.FIREWORK_ROCKET && !player.isCreative()) {
            // 烟花火箭：客户端不预测扣减（反编译确认消耗仅在服务端执行），
            // 直接预测：生存模式使用必然消耗 1 个，预测剩余为 0 时立即补货
            tryRestockFromShulker(localPlayer, hand, item, 1);
        } else if (shrunk) {
            tryRestockFromShulker(localPlayer, hand, item, 0);
        }
    }

    /**
     * 补货入口：主背包（含副手）已没有该物品、但潜影盒里有时，
     * 交给快捷潜影盒取出并回置到手部槽位。
     */
    private static void tryRestockFromShulker(LocalPlayer localPlayer,
                                              InteractionHand hand,
                                              Item item,
                                              int predictedDepletion) {
        if (!Configs.Core.HAND_RESTOCK_SHULKER_COMPAT.getBooleanValue()
                || !Configs.Core.QUICK_SHULKER.getBooleanValue()) {
            return;
        }

        // 快捷潜影盒正在开盒取货（此时本地 CONTAINER 数据被临时清空，不可信）
        if (me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler
                || InventoryUtils.hasRecentlyOpenedShulker(localPlayer)) {
            return;
        }

        // 主背包（含副手槽，含预测消耗量）没有该物品，但潜影盒里有 → 交给快捷潜影盒取出
        if (countMainInventoryIncludingOffhand(localPlayer, item) - predictedDepletion <= 0
                && InventoryUtils.countAvailableIncludingShulkers(localPlayer, item) > 0) {
            recordPendingReturn(localPlayer, hand, item);
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.addQuickShulkerDemand(item);
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem();
        }
    }

    /** 统计主背包 0-35 与副手槽 40 中某物品的总数量。 */
    private static int countMainInventoryIncludingOffhand(LocalPlayer player, Item item) {
        int count = InventoryUtils.countMatchingMainInventory(player, s -> s.is(item));
        ItemStack offhand = player.getInventory().getItem(OFFHAND_INVENTORY_SLOT);
        if (!offhand.isEmpty() && offhand.is(item)) {
            count += offhand.getCount();
        }
        return count;
    }

    /** 记录"取货完成后把物品放回原手部槽位"的待办（仅主手/副手补货请求）。 */
    private static void recordPendingReturn(LocalPlayer player, InteractionHand hand, Item item) {
        int inventorySlot = hand == InteractionHand.OFF_HAND
                ? OFFHAND_INVENTORY_SLOT
                : player.getInventory().getSelectedSlot();
        if (inventorySlot < 0) {
            return;
        }
        pendingItem = item;
        pendingTargetInventorySlot = inventorySlot;
        long tick = currentTick(player);
        pendingExecuteTick = tick + HAND_RETURN_DELAY_TICKS;
        pendingExpireTick = tick + HAND_RETURN_TIMEOUT_TICKS;
    }

    /**
     * 由快捷潜影盒取货流程在取货结束时调用（zxy InventoryUtils#finishQuickShulkerTransfer）。
     */
    public static void onQuickShulkerTransferFinished(net.minecraft.world.entity.player.Player player) {
        if (pendingItem == null || pendingTargetInventorySlot < 0) {
            return;
        }
        // 放回目标槽位已被占用则直接放弃本次回置
        if (!player.getInventory().getItem(pendingTargetInventorySlot).isEmpty()) {
            clearPendingReturn();
            return;
        }
        if (pendingExecuteTick == 0L) {
            long tick = player.level() == null ? 0L : player.level().getGameTime();
            pendingExecuteTick = tick + HAND_RETURN_DELAY_TICKS;
        }
    }

    /** 每客户端 tick 调用（zxy InventoryUtils#tick），执行延迟的"放回手部槽位"。 */
    public static void clientTick(LocalPlayer player) {
        if (pendingItem == null || pendingTargetInventorySlot < 0) {
            return;
        }
        long tick = currentTick(player);
        if (tick > pendingExpireTick) {
            clearPendingReturn();
            return;
        }
        if (tick < pendingExecuteTick) {
            return;
        }
        // 只在玩家自身背包界面（无其他容器打开、光标空闲）时执行
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null
                || !player.containerMenu.equals(player.inventoryMenu)
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return;
        }
        if (!player.getInventory().getItem(pendingTargetInventorySlot).isEmpty()) {
            // 目标槽位已有物品则不移动
            clearPendingReturn();
            return;
        }
        moveStackToInventorySlot(player, pendingItem, pendingTargetInventorySlot);
        clearPendingReturn();
    }

    public static void clearPendingReturn() {
        pendingItem = null;
        pendingTargetInventorySlot = -1;
        pendingExecuteTick = 0L;
        pendingExpireTick = 0L;
    }

    /**
     * 把主背包中一件目标物品移动到指定的玩家背包槽位（调用方保证目标槽位为空）。
     * 通过背包点击完成：拾起源槽位 → 放入目标槽位；若目标槽位被服务器侧数据
     * 占用导致交换，则把光标物品放回源槽位并放弃。
     */
    private static void moveStackToInventorySlot(LocalPlayer player, Item item, int targetInventorySlot) {
        int targetMenuSlot = toMenuSlot(targetInventorySlot);
        if (targetMenuSlot < 0) {
            return;
        }
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        int size = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            if (slot == targetInventorySlot) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) {
                continue;
            }
            int sourceMenuSlot = toMenuSlot(slot);
            if (sourceMenuSlot < 0) {
                continue;
            }
            Minecraft client = Minecraft.getInstance();
            client.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId, sourceMenuSlot, 0, ClickType.PICKUP, player);
            client.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId, targetMenuSlot, 0, ClickType.PICKUP, player);
            if (!player.inventoryMenu.getCarried().isEmpty()) {
                client.gameMode.handleInventoryMouseClick(
                        player.inventoryMenu.containerId, sourceMenuSlot, 0, ClickType.PICKUP, player);
            }
            return;
        }
    }

    /** 玩家背包（Inventory）槽位号 → 背包界面（InventoryMenu）槽位号。 */
    private static int toMenuSlot(int inventorySlot) {
        if (inventorySlot == OFFHAND_INVENTORY_SLOT) {
            return OFFHAND_MENU_SLOT;
        }
        if (inventorySlot < 0 || inventorySlot > 35) {
            return -1;
        }
        // Inventory 0-8 是快捷栏；InventoryMenu 中快捷栏对应 36-44
        return inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
    }

    private static long currentTick(net.minecraft.world.entity.player.Player player) {
        return player.level() == null ? 0L : player.level().getGameTime();
    }
}
