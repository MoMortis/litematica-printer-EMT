package me.aleksilassila.litematica.printer.mixin.printer.litematica;


import fi.dy.masa.litematica.util.InventoryUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.CloudStoreUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryUtils.class)
public class MixinInventoryUtils {
    @Inject(at = @At("TAIL"),method = "schematicWorldPickBlock")
    private static void schematicWorldPickBlock(ItemStack stack, BlockPos pos, Level schematicWorld, Minecraft mc, CallbackInfo ci) {
        if (mc.player == null) {
            return;
        }
        Item item = stack.getItem();
        // 判定A（inMain）：主背包主栏直接持有该物品（不含潜影盒内容）——快捷潜影盒用。
        // 判定B（inShulkers）：主栏 + 所有背包潜影盒内容 —— 云仓库手动补货用。
        boolean itemIsShulker = me.aleksilassila.litematica.printer.utils.InventoryUtils.isShulkerItem(item);
        boolean inMain;
        if (itemIsShulker) {
            inMain = mc.player.inventoryMenu.slots.stream().anyMatch(slot -> {
                net.minecraft.world.item.ItemStack slotStack = slot.getItem();
                return slotStack.getItem().equals(item)
                        && me.aleksilassila.litematica.printer.utils.InventoryUtils.isEmptyShulker(slotStack);
            });
        } else {
            inMain = me.aleksilassila.litematica.printer.utils.InventoryUtils.countMatchingMainInventory(
                    mc.player, s -> s.is(item)) > 0;
        }
        boolean inShulkers = me.aleksilassila.litematica.printer.utils.InventoryUtils.countAvailableIncludingShulkers(mc.player, item) > 0;
        // 快捷潜影盒优先：主栏没有但潜影盒里有 → 从背包潜影盒取
        if (!inMain && inShulkers
                && (Configs.Core.CLOUD_INVENTORY.getBooleanValue()
                || Configs.Placement.QUICK_SHULKER.getBooleanValue())) {
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList.add(item);
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.switchItem();
            return;
        }
        // 云仓库-手动补货：主栏与潜影盒都没有才下单（与快捷潜影盒互斥）
        boolean forceCloudStore = Configs.Placement.PRINT_CLOUD_STORE_MIDDLE_CLICK_FORCE.getBooleanValue();
        boolean manualRefill = Configs.Placement.PRINT_CLOUD_STORE_MANUAL_REFILL.getBooleanValue();
        if (!inShulkers
                && (forceCloudStore || manualRefill)
                && item != Items.AIR
                && ModUtils.isCloudStoreLoaded()) {
            CloudStoreUtils.tryRequestRefillImmediate(
                    mc.player,
                    item,
                    Configs.Placement.PRINT_CLOUD_STORE_REFILL_AMOUNT.getIntegerValue()
            );
        }
    }

    /**
     * @author BlinkWhite
     * @reason 去除优先选择目前已选择的槽位
     */
    @Overwrite
    private static int getPickBlockTargetSlot(Player player) {
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            return -1;
        }
        int slotNum;
        if (InventoryUtilsAccessor.getNextPickSlotIndex() >= InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().size()) {
            InventoryUtilsAccessor.setNextPickSlotIndex(0);
        }
        for (int i = 0; i < InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().size(); ++i) {
            slotNum = InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().get(InventoryUtilsAccessor.getNextPickSlotIndex());

            InventoryUtilsAccessor.setNextPickSlotIndex(InventoryUtilsAccessor.getNextPickSlotIndex() + 1);

            if (InventoryUtilsAccessor.getNextPickSlotIndex() >= InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().size()) {
                InventoryUtilsAccessor.setNextPickSlotIndex(0);
            }
            if (InventoryUtilsAccessor.canPickToSlot(player.getInventory(), slotNum)) {
                return slotNum;
            }
        }
        return -1;
    }
}
