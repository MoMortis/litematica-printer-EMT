package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.SubstitutePlacementCache;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 代替放置：目标方块缺失时，允许用"代替列表"中登记的方块顶替放置。
 * 物品候选 = 目标本体（优先）+ 代替方块们；放置上下文套用目标方块的状态
 * （朝向/轴向/上下半等，见 {@link Guide#buildTargetStateContext}）。
 *
 * <p>适配"破坏错误方块"：目标位置上是登记的代替方块时，不进入破坏队列，
 * 避免"放置-破坏"循环；状态错误仍会正常修正。
 */
public class SubstituteGuide extends Guide {

    public SubstituteGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected boolean canExecute() {
        return SubstitutePlacementCache.active()
                && SubstitutePlacementCache.hasSubstitutes(requiredBlock);
    }

    @Override
    protected Result onBuildActionWrongBlock(BlockMatchResult state) {
        // 目标位置上是该目标登记的代替方块（代替放置的结果），视为暂时接受，不破坏
        if (Configs.Print.SUBSTITUTE_PLACEMENT.getBooleanValue()
                && SubstitutePlacementCache.isSubstituteOf(requiredBlock, currentBlock)) {
            return Result.SKIP;
        }
        return Result.PASS;
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        List<Item> items = new ArrayList<>();
        items.add(requiredBlock.asItem());
        for (Block substitute : SubstitutePlacementCache.getSubstitutes(requiredBlock)) {
            Item item = substitute.asItem();
            if (item != net.minecraft.world.item.Items.AIR && !items.contains(item)) {
                items.add(item);
            }
        }

        // 放置上下文套用目标方块状态
        Action action = buildTargetStateContext(new Action());
        action.setItems(items.toArray(new Item[0]));

        // 珊瑚类个体（非完整方块）需要贴面放置（水中支撑），沿用原"代替失活珊瑚"逻辑
        Identifier targetId = BlockUtils.getKey(requiredBlock);
        if (targetId.getPath().contains("coral") && !targetId.getPath().endsWith("_block")) {
            getProperty(requiredState, BlockStateProperties.HORIZONTAL_FACING)
                    .ifPresent(facing -> action.setSides(facing.getOpposite()));
            action.setRequiresSupport();
        }

        return Result.success(action);
    }
}
