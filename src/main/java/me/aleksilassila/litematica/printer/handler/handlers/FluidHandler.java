package me.aleksilassila.litematica.printer.handler.handlers;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class FluidHandler extends ClientPlayerTickHandler {
    public final static String NAME = "fluid";

    private List<String> fillBlocks = new ArrayList<>();
    private List<Item> fillItems = new ArrayList<>();

    private List<String> fluidBlocks = new ArrayList<>();
    private List<Fluid> fluids = List.of(new Fluid[0]);

    public FluidHandler() {
        super(NAME, PrintModeType.FLUID, Configs.Core.FLUID, Configs.Fluid.FLUID_SELECTION_TYPE, true);
    }

    @Override
    protected int getTickInterval() {
        return Configs.Print.PLACE_INTERVAL.getIntegerValue();
    }

    @Override
    protected int getMaxExecutions() {
        return Configs.Print.PLACE_BLOCKS_PER_TICK.getIntegerValue();
    }

    @Override
    protected void preprocess() {
        // 填充方块
        List<String> fileBlocks = Configs.Fluid.FLUID_REPLACE_BLOCK_LIST.getStrings();
        if (!fileBlocks.equals(fillBlocks)) {
            fillBlocks = new ArrayList<>(fileBlocks);
            // 列表被清空时同步清空已解析缓存，避免旧配置残留继续参与排流体
            fillItems = new ArrayList<>();
            for (String itemName : fillBlocks) {
                List<Item> list = BuiltInRegistries.ITEM.stream().filter(item -> PinYinSearchUtils.matchName(itemName, new ItemStack(item))).toList();
                fillItems.addAll(list);
            }
        }
        // 流体方块
        List<String> fluidBlocks = Configs.Fluid.FLUID_LIST.getStrings();
        if (!fluidBlocks.equals(this.fluidBlocks)) {
            this.fluidBlocks = new ArrayList<>(fluidBlocks);
            // 同上：清空流体列表时同步清空已解析缓存
            fluids = new ArrayList<>();
            for (String itemName : this.fluidBlocks) {
                List<Fluid> list = BuiltInRegistries.FLUID.stream().filter(item -> PinYinSearchUtils.matchName(itemName, item.defaultFluidState().createLegacyBlock())).toList();
                fluids.addAll(list);
            }
        }
    }

    @Override
    protected boolean canIterate() {
        return !fillItems.isEmpty() && !fluidBlocks.isEmpty();
    }

    /**
     * 位置级过滤：只有确实存在「流体列表」内流体的格位才算工作目标。
     * 判定必须放在 canProcessPos 里 —— 基类在执行前先问此方法，
     * 不通过的格位直接跳过、不消耗「每刻放置方块数」额度。
     * 若留在 executeIteration 里判定，范围内的空气/实心方块也会把每刻额度吃光，
     * 扫描推进被压到每刻几格，表现为排水极慢甚至几乎不动。
     */
    @Override
    public boolean canProcessPos(BlockPos blockPos) {
        FluidState fluidState = level.getBlockState(blockPos).getFluidState();
        if (!fluids.contains(fluidState.getType())) {
            return false;
        }
        return Configs.Fluid.FILL_FLOWING_FLUID.getBooleanValue() || fluidState.isSource();
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        if (!InventoryUtils.switchToItems(player, fillItems.toArray(new Item[0]))) {
            return;
        }
        Action action = new Action()
                .setActionSource(ActionManager.ActionSource.FLUID)
                .queueAction(blockPos, Direction.UP, false, player);
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        if (ActionManager.INSTANCE.sendQueue(player).isWaiting()) {
            skipIteration.set(true);
        } else {
            setCooldown(blockPos, Fluids.WATER.getTickDelay(level) * 2);
        }
    }
}