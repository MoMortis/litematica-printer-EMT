package me.aleksilassila.litematica.printer.mixin.printer.mc;

import com.mojang.authlib.GameProfile;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.BlockPosCooldownManager;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.CloudStoreUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

//#if MC >= 12001 
import me.aleksilassila.litematica.printer.utils.ModUtils;
//#endif

@Mixin(LocalPlayer.class)
public class MixinLocalPlayer extends AbstractClientPlayer {
    @Final
    @Shadow
    public ClientPacketListener connection;

    @Final
    @Shadow
    protected Minecraft minecraft;

    @Unique
    private boolean updateChecked;

    //#if MC == 11902
    //$$ public MixinLocalPlayer(ClientLevel world, GameProfile profile, @Nullable PlayerPublicKey publicKey) {
    //$$    super(world, profile, publicKey);
    //$$ }
    //#else
    public MixinLocalPlayer(ClientLevel world, GameProfile profile) {
        super(world, profile);
    }
    //#endif

    @Inject(at = @At("HEAD"), method = "resetPos")
    public void init(CallbackInfo ci) {
        if (Configs.Core.UPDATE_CHECK.getBooleanValue() && !updateChecked) {
            CompletableFuture.runAsync(ModUtils::checkForUpdates);
        }
        updateChecked = true;
        // 进入服务器自启动：启动"重试开启打印机"会话（死亡重生不重复启动会话）
        me.aleksilassila.litematica.printer.utils.ConfigUtils.startAutoEnableSession();
    }

    @Inject(at = @At("HEAD"), method = "tick")
    public void tick(CallbackInfo ci) {
        ClientPlayerTickManager.updateTickHandlerTime();
        me.aleksilassila.litematica.printer.utils.ConfigUtils.tickAutoEnable();
        // 仅渲染方块：配置变化后全量重建原理图渲染网格（须在早退逻辑之前，保证必定执行）
        me.aleksilassila.litematica.printer.printer.RenderOnlyBlockCache.tickPendingReload();
        BlockPosCooldownManager.INSTANCE.tick();
        // 暴饮暴食：自动进食（须在取货/容器同步 tick 之前更新自身状态，它们的让路判断才准确）
        me.aleksilassila.litematica.printer.utils.EatUtils.tick(minecraft);
        InventoryUtils.tick();
        ZxyUtils.tick();
        CloudStoreUtils.tickArrivalCheck(minecraft.player);
        BreakUtils.INSTANCE.preprocess();
        // 进食期间挖掘也让路（与打印同等待遇），吃完自动继续
        if (BreakUtils.INSTANCE.isNeedHandle() && !me.aleksilassila.litematica.printer.utils.EatUtils.isBusy()) {
            BreakUtils.INSTANCE.onTick();
        } else {
            ClientPlayerTickManager.tick();
        }
    }

    @Inject(method = "openTextEdit", at = @At("HEAD"), cancellable = true)
    //#if MC > 11904
    public void openTextEdit(SignBlockEntity sign, boolean front, CallbackInfo ci) {
        openEditSignScreen(sign, front, ci);
    }
    //#else
    //$$ public void openTextEdit(SignBlockEntity sign, CallbackInfo ci) {
    //$$    openEditSignScreen(sign, false, ci);
    //$$ }
    //#endif

    public void openEditSignScreen(SignBlockEntity sign, boolean front, CallbackInfo ci) {
        getTargetSignEntity(sign).ifPresent(signBlockEntity ->
        {
            //#if MC > 11904
            String line1 = signBlockEntity.getText(front).getMessage(0, false).getString();
            String line2 = signBlockEntity.getText(front).getMessage(1, false).getString();
            String line3 = signBlockEntity.getText(front).getMessage(2, false).getString();
            String line4 = signBlockEntity.getText(front).getMessage(3, false).getString();
            //#else
            //$$ String line1 = signBlockEntity.getMessage(0, false).getString();
            //$$ String line2 = signBlockEntity.getMessage(1, false).getString();
            //$$ String line3 = signBlockEntity.getMessage(2, false).getString();
            //$$ String line4 = signBlockEntity.getMessage(3, false).getString();
            //#endif
            ServerboundSignUpdatePacket packet = new ServerboundSignUpdatePacket(sign.getBlockPos(),
                    //#if MC > 11904
                    front,
                    //#endif
                    line1,
                    line2,
                    line3,
                    line4
            );
            this.connection.send(packet);
            ci.cancel();
        });
    }

    @Unique
    private Optional<SignBlockEntity> getTargetSignEntity(SignBlockEntity sign) {
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        if (sign.getLevel() == null || worldSchematic == null) {
            return Optional.empty();
        }
        BlockEntity targetBlockEntity = worldSchematic.getBlockEntity(sign.getBlockPos());
        if (targetBlockEntity instanceof SignBlockEntity targetSignEntity) {
            return Optional.of(targetSignEntity);
        }
        return Optional.empty();
    }

    // ===== 快捷潜影盒-自动补货：本地丢弃标记（抑制 Ctrl+Q 误触发） =====
    @Inject(method = "drop", at = @At("HEAD"))
    private void litematica_printer$markLocalDrop(boolean dropAll,
                                                  org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        me.aleksilassila.litematica.printer.utils.HandRestockShulkerCompat.markLocalDrop();
    }
}
