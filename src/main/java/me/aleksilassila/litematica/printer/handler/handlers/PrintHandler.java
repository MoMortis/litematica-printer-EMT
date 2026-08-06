package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.interfaces.Implementation;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class PrintHandler extends ClientPlayerTickHandler {
    public final static String NAME = "print";

    @Getter
    @Setter
    private boolean pistonNeedFix;

    @Getter
    @Setter
    private boolean printerMemorySync;

    private Action action;

    private SchematicBlockContext ctx;

    public PrintHandler() {
        super(NAME, PrintModeType.PRINTER, Configs.Core.PRINT, Configs.Print.PRINT_SELECTION_TYPE, true);
    }

    public SchematicBlockContext getContext() {
        return ctx;
    }

    @Override
    protected int getTickInterval() {
        return Configs.Placement.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Placement.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected boolean isSchematicHandler() {
        return true;
    }

    @Override
    public boolean canProcessPos(BlockPos blockPos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return false;
        this.ctx = new SchematicBlockContext(client, level, schematic, blockPos);
        if (Configs.Print.PRINT_SKIP.getBooleanValue()) {
            Set<String> skipSet = new HashSet<>(Configs.Print.PRINT_SKIP_LIST.getStrings()); // 转换为 HashSet
            if (skipSet.stream().anyMatch(s -> PinYinSearchUtils.matchName(s, ctx.requiredState))) {
                return false;
            }
        }
        Action action = Guides.INSTANCE.buildAction(ctx).orElse(null);
        if (action == null) return false;
        this.action = action;
        return true;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (Configs.Placement.FALLING_CHECK.getBooleanValue() && ctx.requiredState.getBlock() instanceof FallingBlock) {
            BlockPos downPos = blockPos.below();

            if (FallingBlock.isFree(level.getBlockState(downPos))) {
                MessageUtils.setOverlayMessage(I18n.BLOCK_NO_SUPPORT.getName(ctx.getRequiredBlockName().getString()));
                return;
            } else if (level.getBlockState(downPos) != ctx.schematic.getBlockState(downPos)) {
                    MessageUtils.setOverlayMessage(I18n.BLOCK_MISMATCH.getName(ctx.getRequiredBlockName().getString()));
                    return;
                }

        }
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) return;
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        if (!InventoryUtils.switchToItems(player, reqItems)) {
            // 缺货：统计工作范围内所有需要打印但背包为空的材料，一次性提交云仓库取货订单
            if (Configs.Placement.PRINT_CLOUD_STORE_REFILL.getBooleanValue()
                    && !CloudStoreUtils.isRefillInCooldown()) {
                CloudStoreUtils.tryRequestRefillMany(
                        player,
                        collectMissingMaterials(),
                        Configs.Placement.PRINT_CLOUD_STORE_REFILL_AMOUNT.getIntegerValue()
                );
            }
            return;
        }
        boolean useShift;
        if (action.getShift() == null) {
            useShift = (Implementation.isInteractive(level.getBlockState(blockPos.relative(side)).getBlock()) && !(action instanceof ClickAction))
                    || Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
        } else {
            useShift = action.getShift();
        }
        action.setActionSource(ActionManager.ActionSource.PRINT);
        action.queueAction(blockPos, side, useShift, player, reqItems);
        Vec3 hitModifier = LitematicaUtils.usePrecisionPlacement(blockPos, ctx.requiredState);
        if (hitModifier != null) {
            ActionManager.INSTANCE.hitModifier = hitModifier;
            ActionManager.INSTANCE.useProtocol = true;
        }
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        ActionManager.INSTANCE.setWaitForHorizontalLook(action.isWaitForHorizontalLook());
        ActionManager.SendResult sendResult = ActionManager.INSTANCE.sendQueue(player);
        if (sendResult.isWaiting() || sendResult == ActionManager.SendResult.RESERVE_LIMIT) {
            skipIteration.set(true);
        }
        if (action.getCooldownTicksOverride() >= 0) {
            setCooldown(blockPos, action.getCooldownTicksOverride());
        } else {
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
        }
    }

    /**
     * 统计工作范围内所有需要打印、但背包中数量为 0 的材料种类。
     * 扫描会修改 this.action / this.ctx，完成后恢复原值。
     */
    private Set<Item> collectMissingMaterials() {
        Set<Item> missing = new HashSet<>();
        PrinterBox box = this.boxRef == null ? null : this.boxRef.get();
        if (box == null) return missing;
        Action savedAction = this.action;
        SchematicBlockContext savedCtx = this.ctx;
        int scanned = 0;
        try {
            for (BlockPos pos : box) {
                if (++scanned > 20000) break;
                if (!canProcessPos(pos)) continue;
                Item[] reqItems = this.action.getRequiredItems(this.ctx.requiredState.getBlock());
                if (reqItems == null) continue;
                for (Item reqItem : reqItems) {
                    if (reqItem == null || reqItem == net.minecraft.world.item.Items.AIR) continue;
                    if (InventoryUtils.countMatchingMainInventory(player, stack -> stack.is(reqItem)) == 0) {
                        missing.add(reqItem);
                    }
                }
            }
        } finally {
            this.action = savedAction;
            this.ctx = savedCtx;
        }
        return missing;
    }
}