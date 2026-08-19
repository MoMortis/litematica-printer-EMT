package me.aleksilassila.litematica.printer.mixin.printer.mc;

import me.aleksilassila.litematica.printer.utils.SchematicVerifierChunkUpdater;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class MixinSchematicVerifierChunkReload {
    @Inject(method = "handleLevelChunkWithLight", at = @At("RETURN"))
    private void printer$enqueue(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
        SchematicVerifierChunkUpdater.enqueue(packet.getX(), packet.getZ());
    }
}
