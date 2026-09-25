package me.aleksilassila.litematica.printer.mixin;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.EatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraftManualUse {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void preserveManualAnvilScreens(CallbackInfo ci) {
        // 暴饮暴食：进食期间 keyUse 是模拟按下的，食物被吃掉那一 tick 使用状态消失、
        // 按键仍按下，vanilla 会立刻走 startUseItem（先方块交互再物品使用、主手→副手），
        // 造成"吃完顺手打开瞄准的容器 / 把副手物品用了一次"。进食会话期间整段拦下。
        if (EatUtils.isEating()) {
            ci.cancel();
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level != null
                && client.hitResult instanceof BlockHitResult blockHit
                && client.level.getBlockState(blockHit.getBlockPos()).getBlock() instanceof AnvilBlock) {
            ActionManager.INSTANCE.prioritizeManualAnvilScreen();
        }
    }
}