package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Tweakeroo 自动补货 - 快捷潜影盒适配。
 *
 * Tweakeroo 的 hand restock 只搜索玩家背包本身，不会翻找背包内潜影盒的内容。
 * 本适配在其补货请求进入时判定：主背包没有该物品、但潜影盒里有时，
 * 转交给"快捷潜影盒"流程取出物品放入背包；补货本身仍由 Tweakeroo 后续完成。
 */
public final class HandRestockShulkerCompat {
    private HandRestockShulkerCompat() {
    }

    /**
     * 由 Mixin 在 tweakeroo InventoryUtils#restockNewStackToHand 入口调用。
     * 不取消原逻辑：背包里确实找不到物品时 tweakeroo 自身也无事可做。
     */
    public static void onTweakerooRestockRequest(Player player, ItemStack stackReference) {
        if (!(player instanceof LocalPlayer localPlayer)
                || stackReference == null
                || stackReference.isEmpty()) {
            return;
        }
        if (!Configs.Core.HAND_RESTOCK_SHULKER_COMPAT.getBooleanValue()
                || !Configs.Core.QUICK_SHULKER.getBooleanValue()) {
            return;
        }

        Item item = stackReference.getItem();
        if (item == Items.AIR) {
            return;
        }

        // 快捷潜影盒正在开盒取货（此时本地 CONTAINER 数据被临时清空，不可信）
        if (me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler
                || me.aleksilassila.litematica.printer.utils.InventoryUtils.hasRecentlyOpenedShulker(localPlayer)) {
            return;
        }

        // 主背包没有该物品，但潜影盒里有 → 交给快捷潜影盒取出
        if (me.aleksilassila.litematica.printer.utils.InventoryUtils.countMatchingMainInventory(
                localPlayer, s -> s.is(item)) == 0
                && me.aleksilassila.litematica.printer.utils.InventoryUtils
                .countAvailableIncludingShulkers(localPlayer, item) > 0) {
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.addQuickShulkerDemand(item);
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem();
        }
    }
}
