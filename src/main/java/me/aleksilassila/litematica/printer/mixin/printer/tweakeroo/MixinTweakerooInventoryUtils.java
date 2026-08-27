package me.aleksilassila.litematica.printer.mixin.printer.tweakeroo;

import me.aleksilassila.litematica.printer.utils.HandRestockShulkerCompat;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 监听 Tweakeroo 自动补货（hand restock）的取货请求：
 * 在其查找槽位前先检查背包潜影盒中是否有目标物品，
 * 有则触发快捷潜影盒取出（见 HandRestockShulkerCompat）。
 */
@Pseudo
@Mixin(targets = "fi.dy.masa.tweakeroo.util.InventoryUtils", remap = false)
public abstract class MixinTweakerooInventoryUtils {
    @Inject(
            method = "restockNewStackToHand",
            at = @At("HEAD"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void litematica_printer$restockFromShulker(
            Player player,
            InteractionHand hand,
            ItemStack stackReference,
            boolean allowHotbar,
            CallbackInfo ci
    ) {
        HandRestockShulkerCompat.onTweakerooRestockRequest(player, stackReference);
    }
}
