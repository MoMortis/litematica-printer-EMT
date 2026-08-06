package me.aleksilassila.litematica.printer.mixin.printer.mc;

import fi.dy.masa.litematica.util.PlacementHandler;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BlockItem.class, priority = 1020)
public abstract class MixinBlockItem extends Item {
    private MixinBlockItem(Item.Properties builder) {
        super(builder);
    }

    @Shadow
    protected abstract boolean canPlace(BlockPlaceContext context, BlockState state);

    @Shadow
    public abstract Block getBlock();

    @Inject(method = "getPlacementState", at = @At("HEAD"), cancellable = true)
    private void modifyPlacementState(BlockPlaceContext ctx, CallbackInfoReturnable<BlockState> cir) {
        boolean usePrinterProtocol = Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue()
                && ActionManager.INSTANCE.isEasyPlaceProtocolActive();
        //#if MC > 12100
        if (!ActionManager.INSTANCE.isPrinterInteractionActive()) {
            return;
        }
        //#else
        //$$ if (!usePrinterProtocol) {
        //$$     return;
        //$$ }
        //#endif

        BlockState state = this.getBlock().getStateForPlacement(ctx);
        if (state == null || !this.canPlace(ctx, state)) {
            cir.setReturnValue(null);
            return;
        }
        if (usePrinterProtocol) {
            PlacementHandler.UseContext context = PlacementHandler.UseContext.from(ctx, ctx.getHand());
            state = PlacementHandler.applyPlacementProtocolToPlacementState(state, context);
        }
        cir.setReturnValue(state);
    }
}
