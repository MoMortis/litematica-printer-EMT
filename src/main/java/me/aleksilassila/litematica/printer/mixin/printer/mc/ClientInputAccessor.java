package me.aleksilassila.litematica.printer.mixin.printer.mc;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 ClientInput.moveVector（protected），供寻路执行器写入模拟移动向量。
 * 两版本（1.21.11 / 26.1.2）字段一致。
 */
@Mixin(ClientInput.class)
public interface ClientInputAccessor {
    @Accessor("moveVector")
    void printer$setMoveVector(Vec2 moveVector);
}
