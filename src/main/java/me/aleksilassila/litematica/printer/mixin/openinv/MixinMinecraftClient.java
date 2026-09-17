//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package me.aleksilassila.litematica.printer.mixin.openinv;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//#if MC <= 12103
//$$ import net.minecraft.world.entity.player.Inventory;
//$$ import net.minecraft.world.item.ItemStack;
//#endif

@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public abstract class MixinMinecraftClient {
    @Shadow
    public LocalPlayer player;

    @Shadow
    @Nullable
    public ClientLevel level;

    @Inject(method = {"setScreen"}, at = {@At(value = "HEAD")}, cancellable = true)
    public void setScreen(@Nullable Screen screen, CallbackInfo ci) {
        if (InventoryUtils.shouldPreserveAutomatedQuickShulkerScreenOnClose(screen)) {
            ci.cancel();
            return;
        }
        if(ModUtils.closeScreen > 0 && /*screen != null &&*/ screen instanceof AbstractContainerScreen<?>){
            ModUtils.closeScreen--;
            ci.cancel();
        }
    }

    //鼠标中键从打印机库存或通过快捷濳影盒 取出对应物品
    //#if MC >= 260100
    //$$ @WrapOperation(method = "pickBlockOrEntity",at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handlePickItemFromBlock(Lnet/minecraft/core/BlockPos;Z)V"))
    //#elseif MC > 12103
    @WrapOperation(method = "pickBlock",at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handlePickItemFromBlock(Lnet/minecraft/core/BlockPos;Z)V"))
    //#else
    //$$ @WrapOperation(method = "pickBlock",at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;findSlotMatchingItem(Lnet/minecraft/world/item/ItemStack;)I" ))
    //#endif

    //#if MC > 12103
    private void doItemPick(MultiPlayerGameMode instance, BlockPos pos, boolean b, Operation<Void> original) {
        if(level == null) {
            original.call(instance, pos, b);
            return;
        }
        Item item;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic != null && LitematicaUtils.isSchematicBlock(pos)) {
            // 位置在原理图内 → 取原理图预期方块（即使原理图该位置为空气，也按原理图计）
            BlockState schematicState = LitematicaUtils.getSchematicBlockState(pos);
            item = (schematicState == null || schematicState.isAir())
                    ? net.minecraft.world.item.Items.AIR
                    : schematicState.getBlock().asItem();
        } else {
            // 不在原理图内 → 取世界方块
            item = level.getBlockState(pos).getBlock().asItem();
        }
        boolean forceCloudStore = Configs.Special.PRINT_CLOUD_STORE_MIDDLE_CLICK_FORCE.getBooleanValue();
        // 潜影盒物品要求身上是"空盒"才算已拥有；有物品的潜影盒不算。
        // 判定A（inMain）：主背包主栏直接持有该物品（不含潜影盒内容）——快捷潜影盒用。
        // 判定B（inShulkers）：主栏 + 所有背包潜影盒内容 —— 云仓库手动补货用。
        boolean itemIsShulker = me.aleksilassila.litematica.printer.utils.InventoryUtils.isShulkerItem(item);
        boolean inMain;
        if (itemIsShulker) {
            inMain = player.inventoryMenu.slots.stream().anyMatch(slot -> {
                net.minecraft.world.item.ItemStack stack = slot.getItem();
                return stack.getItem().equals(item)
                        && me.aleksilassila.litematica.printer.utils.InventoryUtils.isEmptyShulker(stack);
            });
        } else {
            inMain = me.aleksilassila.litematica.printer.utils.InventoryUtils.countMatchingMainInventory(
                    player, stack -> stack.is(item)) > 0;
        }
        boolean inShulkers = me.aleksilassila.litematica.printer.utils.InventoryUtils.countAvailableIncludingShulkers(player, item) > 0;
        if (!player.getAbilities().instabuild) {
            // 快捷潜影盒：主栏没有但潜影盒里有 → 从背包潜影盒取
            if (!inMain && inShulkers
                    && Configs.Core.QUICK_SHULKER.getBooleanValue()) {
                InventoryUtils.addQuickShulkerDemand(item);
                InventoryUtils.switchItem();
                return;
            }
            // 云仓库-手动补货：主栏与潜影盒都没有才下单（与快捷潜影盒互斥）
            if (!inShulkers
                    && (forceCloudStore || Configs.Special.PRINT_CLOUD_STORE_MANUAL_REFILL.getBooleanValue())
                    && item != net.minecraft.world.item.Items.AIR
                    && ModUtils.isCloudStoreLoaded()) {
                me.aleksilassila.litematica.printer.utils.CloudStoreUtils.tryRequestRefillImmediate(
                        player,
                        item,
                        Configs.Special.PRINT_CLOUD_STORE_REFILL_AMOUNT.getIntegerValue()
                );
            }
        }
        original.call(instance, pos, b);
    }
    //#else
    //$$ private int doItemPick(Inventory instance, ItemStack stack, Operation<Integer> original) {
    //$$     int slotWithStack = original.call(instance, stack);
    //$$ if(!player.getAbilities().instabuild && Configs.Core.QUICK_SHULKER.getBooleanValue() && slotWithStack == -1){
    //$$         Item item = stack.getItem();
    //$$         InventoryUtils.addQuickShulkerDemand(item);
    //$$         InventoryUtils.switchItem();
    //$$         return -1;
    //$$     }
    //$$     return slotWithStack;
    //$$ }
    //#endif

}