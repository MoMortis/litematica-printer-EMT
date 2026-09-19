package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Optional;

/**
 * 炼药锅
 */
public class CauldronGuide extends Guide {

    public CauldronGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        if (!requiredState.is(Blocks.WATER_CAULDRON)) {
            return Result.SKIP;
        }
        Optional<Integer> currentLevel = getProperty(currentState, LayeredCauldronBlock.LEVEL);
        Optional<Integer> requiredLevel = getProperty(requiredState, LayeredCauldronBlock.LEVEL);

        if (currentLevel.isEmpty() || requiredLevel.isEmpty()) {
            return Result.SKIP;
        }

        if (currentLevel.get() > requiredLevel.get()) {
            if (InventoryUtils.playerHasAccessToItem(client.player, Items.GLASS_BOTTLE)) {
                return Result.success(new ClickAction().setItem(Items.GLASS_BOTTLE));
            }
        }
        if (currentLevel.get() < requiredLevel.get()) {
            return this.buildWaterFillAction(requiredLevel.get());
        }
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        // 既有水逻辑：空锅缺水 → 填水；水锅多余 → 玻璃瓶舀水
        if (requiredState.is(Blocks.WATER_CAULDRON) && currentState.is(Blocks.CAULDRON)) {
            int requiredLevel = getProperty(requiredState, LayeredCauldronBlock.LEVEL).orElse(1);
            return this.buildWaterFillAction(requiredLevel);
        }
        if (requiredState.is(Blocks.CAULDRON)
                && currentState.is(Blocks.WATER_CAULDRON)
                && InventoryUtils.playerHasAccessToItem(client.player, Items.GLASS_BOTTLE)) {
            return Result.success(new ClickAction().setItem(Items.GLASS_BOTTLE));
        }

        // 装填炼药锅：用桶类物品填充/舀出（如 空锅→熔岩桶填熔岩、熔岩锅→空桶舀出）
        if (Configs.Print.FILL_CAULDRON.getBooleanValue()) {
            Result fillResult = this.buildFillOrScoopAction();
            if (fillResult != null) {
                return fillResult;
            }
        }

        // 炼药锅（含装水/熔岩/细雪）不会被打印机破坏：
        // 内容不符时等上面的填充/舀出跨 tick 处理，或保持现状
        if (isCauldronFamily(currentState)) {
            return Result.SKIP;
        }

        // 现实方块不是炼药锅（如石头占了锅位）→ 正常破坏流程
        if (Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                && BreakUtils.canBreakBlock(blockPos)) {
            BreakUtils.INSTANCE.add(context);
        }
        return Result.SKIP;
    }

    /** 内容物不符时的填充/舀出动作；无可用物品或无对应处理返回 null */
    private Result buildFillOrScoopAction() {
        if (requiredState.is(Blocks.LAVA_CAULDRON)) {
            if (currentState.is(Blocks.CAULDRON)
                    && InventoryUtils.playerHasAccessToItem(client.player, Items.LAVA_BUCKET)) {
                return Result.success(new ClickAction().setItem(Items.LAVA_BUCKET));
            }
            return this.buildScoopAction();
        }
        if (requiredState.is(Blocks.POWDER_SNOW_CAULDRON)) {
            if (currentState.is(Blocks.CAULDRON)
                    && InventoryUtils.playerHasAccessToItem(client.player, Items.POWDER_SNOW_BUCKET)) {
                return Result.success(new ClickAction().setItem(Items.POWDER_SNOW_BUCKET));
            }
            return this.buildScoopAction();
        }
        // required 空锅/水锅，但现实装了熔岩/细雪/水 → 先舀出现有内容物
        if (requiredState.is(Blocks.CAULDRON) || requiredState.is(Blocks.WATER_CAULDRON)) {
            return this.buildScoopAction();
        }
        return null;
    }

    /** 舀出现有内容物：水用玻璃瓶逐层舀，熔岩/细雪用空桶整锅舀出 */
    private Result buildScoopAction() {
        if (currentState.is(Blocks.WATER_CAULDRON)
                && InventoryUtils.playerHasAccessToItem(client.player, Items.GLASS_BOTTLE)) {
            return Result.success(new ClickAction().setItem(Items.GLASS_BOTTLE));
        }
        if ((currentState.is(Blocks.LAVA_CAULDRON) || currentState.is(Blocks.POWDER_SNOW_CAULDRON))
                && InventoryUtils.playerHasAccessToItem(client.player, Items.BUCKET)) {
            return Result.success(new ClickAction().setItem(Items.BUCKET));
        }
        return null;
    }

    private static boolean isCauldronFamily(BlockState state) {
        return state.getBlock() instanceof AbstractCauldronBlock;
    }

    private Result buildWaterFillAction(int requiredLevel) {
        if (requiredLevel == LayeredCauldronBlock.MAX_FILL_LEVEL
                && InventoryUtils.playerHasItemInInventory(client.player, Items.WATER_BUCKET)) {
            return Result.success(new ClickAction().setItem(Items.WATER_BUCKET));
        }
        ItemStack waterPotion = InventoryUtils.createWaterPotionStack();
        if (InventoryUtils.playerHasAccessToMatchingStack(
                client.player,
                waterPotion,
                InventoryUtils::isWaterPotion
        )) {
            return Result.success(new ClickAction()
                    .setItem(Items.POTION)
                    .setRequiredStackPredicate(InventoryUtils::isWaterPotion)
                    .setRequiredCreativeStack(waterPotion));
        }
        return Result.SKIP;
    }
}
