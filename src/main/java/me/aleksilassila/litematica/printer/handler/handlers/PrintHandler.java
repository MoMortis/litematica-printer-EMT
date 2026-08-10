package me.aleksilassila.litematica.printer.handler.handlers;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.guide.guides.ShulkerPlacementGuard;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

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
        // 破冰放水：水源/含水方块缺水时由任务控制器优先接管（放冰），避免普通 Guide 先放"干方块"
        Action waterTask = PrintTaskController.INSTANCE.handle(ctx);
        if (waterTask != null) {
            this.action = waterTask;
            return true;
        }
        // 等待水源出现：跳过本位置（保留状态，不进入 buildAction），水出现后自动转正常流程
        if (PrintTaskController.INSTANCE.isWaitingWater(blockPos)) {
            return false;
        }
        // 破冰阶段：位置是冰，入破坏队列由 tweakeroo 决定工具破掉
        if (PrintTaskController.INSTANCE.isBreaking(blockPos)) {
            this.action = new Action();
            return true;
        }
        Action action = Guides.INSTANCE.buildAction(ctx).orElse(null);
        if (action == null) return false;
        this.action = action;
        return true;
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        // 破冰放水：破冰阶段直接把冰入破坏队列，工具切换交给 tweakeroo
        if (PrintTaskController.INSTANCE.isBreaking(blockPos)) {
            BreakUtils.INSTANCE.add(blockPos);
            setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
            return;
        }
        // 潜影盒放置守卫：只打印空盒时，后置放置 + 关容器 + 5gt 确认空盒，避免误放打开中的盒子
        if (Configs.Print.PRINT_ONLY_EMPTY_SHULKER.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
            ShulkerPlacementGuard.GuardResult guardResult = ShulkerPlacementGuard.INSTANCE.evaluate(ctx);
            switch (guardResult) {
                case WAIT_OTHER_BLOCKS:
                case WAIT_CLOSE:
                case WAIT_CONFIRM:
                    setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                    return;
                case NO_EMPTY:
                    requestCloudStoreRefill(new Item[]{ctx.requiredState.getBlock().asItem()});
                    setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
                    return;
                case READY:
                    // 守卫已切换主手为空盒，直接走放置
                    break;
            }
        }
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
        Item[] reqItems = action.getRequiredItems(ctx.requiredState.getBlock());
        // 潜影盒守卫 READY 时主手已切好，跳过 switchToItems
        boolean shulkerReady = Configs.Print.PRINT_ONLY_EMPTY_SHULKER.getBooleanValue()
                && ctx.requiredState.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock
                && ShulkerPlacementGuard.INSTANCE.isReady(blockPos);
        // 换挡类交互（ClickAction 未指定物品，如中继器/比较器/活板门/红石线等右键切换状态）：
        // 保持当前手持任意物品直接右键，不再要求切空手
        if (shulkerReady) {
            // 主手已由守卫设置
        } else if (isFreeHandClick(action, reqItems)) {
            // 不切换物品，保持当前手持任意物品非潜行右键目标方块
        } else if (!InventoryUtils.switchToItems(player, reqItems)) {
            requestCloudStoreRefill(reqItems);
            return;
        }
        Direction side = action.getValidSide(level, blockPos);
        if (side == null) return;
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
        // 破冰放水：放冰动作已发出，标记冰已放置（下一 tick 位置变为冰后进入破冰阶段）
        PrintTaskController.INSTANCE.onIcePlaceSent(blockPos);
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
     * 是否为"空手右键换挡"类交互（ClickAction 且未指定实际物品）。
     * 这类交互（中继器/比较器/活板门/红石线/拉杆等右键切换状态）不再要求空手，
     * 保持当前手持任意物品直接右键即可。
     */
    private static boolean isFreeHandClick(Action action, Item[] reqItems) {
        if (!(action instanceof ClickAction)) {
            return false;
        }
        if (reqItems == null) {
            return true;
        }
        for (Item item : reqItems) {
            if (item != null && item != Items.AIR) {
                return false;
            }
        }
        return true;
    }

    /**
     * 统计工作范围内所有需要打印、但背包中数量为 0 的材料种类。
     *
     * 注意：这里使用只读判定（isRequiredForPlacement），不调用 canProcessPos / Guides.buildAction。
     * 之前的实现会经 Guides.buildAction 触发 DefaultGuide.onBuildActionWrongBlock，把多余/错误方块
     * 全部入队破坏，造成"开启云仓库补货时挖到原理图之外"的副作用。
     */
    private Set<Item> collectMissingMaterials() {
        Set<Item> missing = new HashSet<>();
        PrinterBox box = this.boxRef == null ? null : this.boxRef.get();
        if (box == null) return missing;
        int scanned = 0;
        for (BlockPos pos : box) {
            if (++scanned > 20000) break;
            if (!PlayerUtils.canInteracted(pos)) continue;
            if (!LitematicaUtils.isSchematicBlock(pos)) continue;
            if (getSelectionType() != null
                    && !PlayerUtils.isPositionInSelectionRange(player, pos, getSelectionType())) continue;
            Item[] reqItems = getRequiredItemsFor(pos);
            if (reqItems == null) continue;
            for (Item reqItem : reqItems) {
                if (reqItem == null || reqItem == net.minecraft.world.item.Items.AIR) continue;
                // 主背包 + 潜影盒内容都没有才视为缺货（且潜影盒取货流程未在进行）
                if (InventoryUtils.countAvailableIncludingShulkers(player, reqItem) == 0
                        && !InventoryUtils.hasRecentlyOpenedShulker(player)) {
                    missing.add(reqItem);
                }
            }
        }
        return missing;
    }

    /**
     * 只读判定该位置是否需要打印（世界缺失目标方块或存在错误方块需替换），纯比较、不产生任何构建动作副作用。
     * 返回需要的物品 []；不需要或不可打印时返回 null。
     */
    @Nullable
    private Item[] getRequiredItemsFor(BlockPos pos) {
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return null;
        SchematicBlockContext context = new SchematicBlockContext(client, level, schematic, pos);
        if (Configs.Print.PRINT_SKIP.getBooleanValue()) {
            Set<String> skipSet = new HashSet<>(Configs.Print.PRINT_SKIP_LIST.getStrings());
            if (skipSet.stream().anyMatch(s -> PinYinSearchUtils.matchName(s, context.requiredState))) {
                return null;
            }
        }
        BlockState required = context.requiredState;
        if (required.isAir() || required.getBlock() instanceof LiquidBlock) {
            return null;
        }
        BlockMatchResult match = BlockMatchResult.compare(context);
        // 空气/可替换（MISSING）需要放方块；错误方块（WRONG_BLOCK）需要先破坏再放，
        // 两者都会消耗目标方块材料，都应纳入云仓库缺货统计。
        if (match != BlockMatchResult.MISSING && match != BlockMatchResult.WRONG_BLOCK) {
            return null;
        }
        return new Item[]{required.getBlock().asItem()};
    }

    /**
     * 背包缺货时发起云仓库补货订单。
     * 空集兜底：只读扫描结果为空时，仍把当前缺货的 reqItems 加入订单，避免静默失效（无任何提示）。
     */
    private void requestCloudStoreRefill(Item[] reqItems) {
        if (!Configs.Placement.PRINT_CLOUD_STORE_REFILL.getBooleanValue()
                || CloudStoreUtils.isRefillInCooldown()) {
            return;
        }
        Set<Item> missing = collectMissingMaterials();
        if (reqItems != null) {
            for (Item reqItem : reqItems) {
                if (reqItem == null || reqItem == net.minecraft.world.item.Items.AIR) continue;
                if (InventoryUtils.countAvailableIncludingShulkers(player, reqItem) == 0
                        && !InventoryUtils.hasRecentlyOpenedShulker(player)) {
                    missing.add(reqItem);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        CloudStoreUtils.tryRequestRefillMany(
                player,
                missing,
                Configs.Placement.PRINT_CLOUD_STORE_REFILL_AMOUNT.getIntegerValue()
        );
    }
}