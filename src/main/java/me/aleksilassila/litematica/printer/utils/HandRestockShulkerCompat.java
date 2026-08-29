package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 快捷潜影盒 - 自动补货。
 *
 * 自主检测物品的消耗（Mixin 捕获 MultiPlayerGameMode#useItem /
 * useItemOn 前后的手部物品快照、LivingEntity 释放消耗、tick 全背包被动检测，
 * 覆盖弓/弩箭矢、不死图腾等无手部扣减场景）：
 * 消耗后若主背包已没有该物品、但背包内潜影盒里还有，
 * 则交给"快捷潜影盒"流程取出物品放入背包；并在同一 tick、
 * 关闭潜影盒容器之前，把物品放回该物品消耗前所在的手部槽位（主手/副手）。
 */
public final class HandRestockShulkerCompat {
    private HandRestockShulkerCompat() {
    }

    /** 副手在玩家背包（Inventory）中的槽位号。 */
    private static final int OFFHAND_INVENTORY_SLOT = 40;

    private static Item pendingItem;
    private static int pendingTargetInventorySlot = -1;

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
     * 补货入口：主背包（含副手，含预测消耗量）已没有该物品、但潜影盒里有时，
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
        int targetSlot = hand == InteractionHand.OFF_HAND
                ? OFFHAND_INVENTORY_SLOT
                : localPlayer.getInventory().getSelectedSlot();
        tryRestockFromShulker(localPlayer, item, predictedDepletion, targetSlot);
    }

    private static void tryRestockFromShulker(LocalPlayer localPlayer,
                                              Item item,
                                              int predictedDepletion,
                                              int targetSlot) {
        if (!Configs.Core.HAND_RESTOCK_SHULKER_COMPAT.getBooleanValue()
                || !Configs.Core.QUICK_SHULKER.getBooleanValue()) {
            return;
        }
        if (me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler
                || InventoryUtils.hasRecentlyOpenedShulker(localPlayer)) {
            return;
        }
        if (targetSlot < 0) {
            return;
        }
        if (countMainInventoryIncludingOffhand(localPlayer, item) - predictedDepletion <= 0
                && InventoryUtils.countAvailableIncludingShulkers(localPlayer, item) > 0) {
            recordPendingReturn(targetSlot, item);
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

    /** 记录"取货完成后把物品放回消耗前槽位"的待办。 */
    private static void recordPendingReturn(int inventorySlot, Item item) {
        pendingItem = item;
        pendingTargetInventorySlot = inventorySlot;
    }

    /**
     * 由快捷潜影盒取货流程在取货结束时调用（zxy InventoryUtils#finishQuickShulkerTransfer）。
     * 此时潜影盒容器尚未关闭，在取出物品进背包的同一 tick 内，
     * 直接通过当前容器菜单把物品放回消耗前的手部槽位。
     */
    public static void onQuickShulkerTransferFinished(net.minecraft.world.entity.player.Player player) {
        Item item = pendingItem;
        int targetInventorySlot = pendingTargetInventorySlot;
        clearPendingReturn();
        if (item == null || targetInventorySlot < 0 || !(player instanceof LocalPlayer localPlayer)) {
            return;
        }
        // 放回目标槽位已被占用则不移动
        if (!player.getInventory().getItem(targetInventorySlot).isEmpty()) {
            return;
        }
        moveStackToInventorySlot(localPlayer, item, targetInventorySlot);
    }

    // ===== 被动消耗检测：覆盖无手部 use 事件的消耗（弓/弩箭矢、不死图腾等） =====
    private static final ItemStack[] prevSlots = new ItemStack[OFFHAND_INVENTORY_SLOT + 1];
    private static final int DROP_SUPPRESS_TICKS = 3;
    private static long lastLocalDropTick = Long.MIN_VALUE;

    /** 本地发生丢弃（Q/Ctrl+Q/背包界面扔出）时调用，抑制随后数 tick 内的被动补货判定。 */
    public static void markLocalDrop() {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            lastLocalDropTick = currentTick(client.player);
        }
    }

    /** 每客户端 tick 调用（zxy InventoryUtils#tick），执行被动消耗检测。 */
    public static void clientTick(LocalPlayer player) {
        detectPassiveConsumption(player);
    }

    /**
     * 仅检测"槽位从非空变空"：同一物品全背包总量确实减少（排除背包内移动/换位）、
     * 且近期无本地丢弃，即判定该物品被消耗（箭矢射出、图腾弹出等），
     * 按补货流程处理并回置到该槽位。
     */
    private static void detectPassiveConsumption(LocalPlayer player) {
        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        // 仅在自身背包界面、光标空闲、玩家存活时判定，避免把容器操作误判为消耗
        boolean quiet = player.isAlive()
                && player.containerMenu.equals(player.inventoryMenu)
                && player.inventoryMenu.getCarried().isEmpty();
        if (quiet) {
            long tick = currentTick(player);
            boolean dropRecent = tick - lastLocalDropTick <= DROP_SUPPRESS_TICKS;
            for (int slot = 0; slot <= OFFHAND_INVENTORY_SLOT && !dropRecent; slot++) {
                if (slot > 35 && slot != OFFHAND_INVENTORY_SLOT) {
                    continue;
                }
                ItemStack before = prevSlots[slot];
                if (before == null || before.isEmpty() || !inventory.getItem(slot).isEmpty()) {
                    continue;
                }
                Item item = before.getItem();
                if (countItem(prevSlots, item) > countItem(inventory, item)) {
                    tryRestockFromShulker(player, item, 0, slot);
                    break;
                }
            }
        }
        for (int slot = 0; slot <= OFFHAND_INVENTORY_SLOT; slot++) {
            if (slot > 35 && slot != OFFHAND_INVENTORY_SLOT) {
                continue;
            }
            prevSlots[slot] = inventory.getItem(slot).copy();
        }
    }

    private static int countItem(ItemStack[] slots, Item item) {
        int count = 0;
        for (int slot = 0; slot <= OFFHAND_INVENTORY_SLOT; slot++) {
            if (slot > 35 && slot != OFFHAND_INVENTORY_SLOT) {
                continue;
            }
            ItemStack stack = slots[slot];
            if (stack != null && !stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countItem(net.minecraft.world.entity.player.Inventory inventory, Item item) {
        int count = 0;
        for (int slot = 0; slot <= OFFHAND_INVENTORY_SLOT; slot++) {
            if (slot > 35 && slot != OFFHAND_INVENTORY_SLOT) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    public static void clearPendingReturn() {
        pendingItem = null;
        pendingTargetInventorySlot = -1;
    }

    /**
     * 把玩家背包中的一件目标物品移动到指定背包槽位（调用方保证目标槽位为空）。
     * 通过当前打开的容器菜单（潜影盒）点击完成；副手不在容器菜单中，
     * 用 SWAP(按钮 40) 与源槽位交换。若拾取后目标槽位被服务器侧数据占用
     * 导致交换，则把光标物品放回源槽位并放弃。
     */
    private static void moveStackToInventorySlot(LocalPlayer player, Item item, int targetInventorySlot) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;

        net.minecraft.world.entity.player.Inventory inventory = player.getInventory();
        int sourceInventorySlot = -1;
        int size = Math.min(36, inventory.getContainerSize());
        for (int slot = 0; slot < size; slot++) {
            if (slot == targetInventorySlot) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                sourceInventorySlot = slot;
                break;
            }
        }
        // 主背包中没找到时，副手可作为来源
        if (sourceInventorySlot < 0
                && targetInventorySlot != OFFHAND_INVENTORY_SLOT
                && !inventory.getItem(OFFHAND_INVENTORY_SLOT).isEmpty()
                && inventory.getItem(OFFHAND_INVENTORY_SLOT).is(item)) {
            sourceInventorySlot = OFFHAND_INVENTORY_SLOT;
        }
        if (sourceInventorySlot < 0) {
            return;
        }
        int sourceMenuSlot = findMenuSlotForInventorySlot(menu, player, sourceInventorySlot);
        if (sourceMenuSlot < 0) {
            return;
        }

        if (targetInventorySlot == OFFHAND_INVENTORY_SLOT) {
            // 副手不在任何容器菜单中：SWAP 按钮 40 = 与副手交换
            client.gameMode.handleInventoryMouseClick(
                    menu.containerId, sourceMenuSlot, 40, ClickType.SWAP, player);
            return;
        }
        int targetMenuSlot = findMenuSlotForInventorySlot(menu, player, targetInventorySlot);
        if (targetMenuSlot < 0) {
            return;
        }
        client.gameMode.handleInventoryMouseClick(
                menu.containerId, sourceMenuSlot, 0, ClickType.PICKUP, player);
        client.gameMode.handleInventoryMouseClick(
                menu.containerId, targetMenuSlot, 0, ClickType.PICKUP, player);
        if (!menu.getCarried().isEmpty()) {
            client.gameMode.handleInventoryMouseClick(
                    menu.containerId, sourceMenuSlot, 0, ClickType.PICKUP, player);
        }
    }

    /** 在当前容器菜单中查找"玩家背包指定槽位"对应的菜单槽位号。 */
    private static int findMenuSlotForInventorySlot(AbstractContainerMenu menu,
                                                    LocalPlayer player,
                                                    int inventorySlot) {
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == player.getInventory()
                    && slot.getContainerSlot() == inventorySlot) {
                return i;
            }
        }
        return -1;
    }

    private static long currentTick(net.minecraft.world.entity.player.Player player) {
        return player.level() == null ? 0L : player.level().getGameTime();
    }
}
