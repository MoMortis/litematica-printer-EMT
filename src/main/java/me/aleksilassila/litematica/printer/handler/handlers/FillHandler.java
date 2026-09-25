package me.aleksilassila.litematica.printer.handler.handlers;

import lombok.Getter;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.FillBlockModeType;
import me.aleksilassila.litematica.printer.enums.PrintModeType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickHandler;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class FillHandler extends ClientPlayerTickHandler {
    public final static String NAME = "fill";

    private List<String> fillCacheBlocklist = new ArrayList<>();
    @Getter
    private Item[] fillModeItemList = new Item[0];

    public FillHandler() {
        super(NAME, PrintModeType.FILL, Configs.Core.FILL, Configs.Fill.FILL_SELECTION_TYPE, true);
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
        FillBlockModeType fillMode = (FillBlockModeType) Configs.Fill.FILL_BLOCK_MODE.getOptionListValue();
        switch (fillMode) {
            case BLOCKLIST:
                // 每次去MC注册表中获取会造成大量卡顿, 所以仅在玩家修改了填充列表, 再去读取以便注册表
                List<String> strings = Configs.Fill.FILL_BLOCK_LIST.getStrings();
                if (!strings.equals(fillCacheBlocklist)) {
                    fillCacheBlocklist = new ArrayList<>(strings);
                    // 列表被清空时同步清空已解析缓存，避免旧配置残留继续参与填充
                    fillModeItemList = new Item[0];
                    if (strings.isEmpty()) {
                        return;
                    }
                    List<Item> items = new ArrayList<>();
                    for (String itemName : fillCacheBlocklist) {
                        items.addAll(BuiltInRegistries.ITEM
                                .stream()
                                .filter(item -> PinYinSearchUtils.matchName(itemName, new ItemStack(item)))
                                .toList()
                        );
                    }
                    fillModeItemList = items.toArray(new Item[0]);
                }
                break;
            case HANDHELD:  // 手持物品
                if (Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
                    ItemStack heldStack = player.getMainHandItem(); // 获取主手物品
                    if (!heldStack.isEmpty() && heldStack.getCount() > 0) {
                        fillModeItemList = new Item[]{player.getMainHandItem().getItem()};
                    } else {
                        fillModeItemList = new Item[0];
                    }
                }
                break;
        }
    }

    @Override
    protected boolean canIterate() {
        return fillModeItemList.length > 0;
    }

    /**
     * 位置级过滤：非填充目标（已正确的实心方块等）直接跳过，不消耗「每刻放置方块数」额度。
     * 目标判定若留在 executeIteration 里，范围内的实心方块也会把每刻额度吃光，
     * 扫描推进被压到每刻几格，填充会异常缓慢。
     */
    @Override
    public boolean canProcessPos(BlockPos blockPos) {
        if (Configs.Fill.FILL_BLOCK_MODE.getOptionListValue() == FillBlockModeType.HANDHELD) {
            ItemStack heldStack = player.getMainHandItem(); // 获取主手物品
            if (heldStack.isEmpty() || heldStack.getCount() <= 0) {
                return false;
            }
        }
        return isFillTarget(level.getBlockState(blockPos));
    }

    /** 该格位是否属于填充目标：空气 / 液体 / 「可替换方块列表」内的方块。 */
    private boolean isFillTarget(BlockState state) {
        return state.isAir()
                || (state.getBlock() instanceof LiquidBlock)
                || Configs.Print.REPLACEABLE_LIST.getStrings().stream().anyMatch(s -> PinYinSearchUtils.matchName(s, state));
    }

    @Override
    protected void executeIteration(BlockPos blockPos, AtomicReference<Boolean> skipIteration) {
        // 目标判定已在 canProcessPos 完成：不通过的格位不会走到这里，也不消耗放置额度
        if (!InventoryUtils.switchToItems(player, this.fillModeItemList)) {
            return;
        }
        if (Configs.Print.FALLING_CHECK.getBooleanValue() &&
            player.getMainHandItem().getItem() instanceof BlockItem item &&
            item.getBlock() instanceof FallingBlock block &&
            FallingBlock.isFree(level.getBlockState(blockPos.below()))
        ) {
            MessageUtils.setOverlayMessage(I18n.BLOCK_NO_SUPPORT.getName(block.getName().getString()));
            return;
        }

        Action action;
        if (ConfigUtils.getFillModeFacing() != null) {
            action = new Action()
                    .setActionSource(ActionManager.ActionSource.FILL)
                    .setLookDirection(ConfigUtils.getFillModeFacing().getOpposite())
                    .queueAction(blockPos, ConfigUtils.getFillModeFacing(), false, player);
        } else {
            action = new Action()
                    .setActionSource(ActionManager.ActionSource.FILL)
                    .queueAction(blockPos, getPlayerPlacementDirection(), false, player);
        }
        ActionManager.INSTANCE.setLook(action.getPlayerLook());
        ActionManager.INSTANCE.setNeedWaitModifyLookFromAction(action.getNeedWaitModifyLook());
        // 固定方向填充：目标视角已通过 look 包同步给服务端，无需等待客户端镜头实际转向，
        // 否则每次发送都会因 WAITING_FOR_LOOK 停轮，每刻只能放置 1 个
        ActionManager.INSTANCE.setWaitForHorizontalLook(false);
        if (ActionManager.INSTANCE.sendQueue(player).isWaiting()){
            skipIteration.set(true);
        } else {
            this.setCooldown(blockPos, ConfigUtils.getPlaceCooldown());
        }
    }

}