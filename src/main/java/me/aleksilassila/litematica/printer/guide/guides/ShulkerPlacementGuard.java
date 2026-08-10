package me.aleksilassila.litematica.printer.guide.guides;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 潜影盒放置守卫（只打印空盒时生效）：
 * 1. 放置顺序靠后：目标所在层还有其它非潜影盒普通方块待放置时，等待；
 * 2. 放置前停下其他操作并关闭打开的容器；
 * 3. 锁定选中的空盒槽位，等待 5gt 复查确认确实是空盒后再放置，
 *    避免 QuickShulker 打开瞬间（本地 CONTAINER 短暂变空盒）把有内容的潜影盒误放出去。
 */
public class ShulkerPlacementGuard {
    public static final ShulkerPlacementGuard INSTANCE = new ShulkerPlacementGuard();

    /** 空盒确认窗口（游戏刻） */
    private static final int CONFIRM_TICKS = 5;

    private static class ConfirmEntry {
        final int slot;
        final long tick;

        ConfirmEntry(int slot, long tick) {
            this.slot = slot;
            this.tick = tick;
        }
    }

    /** key = 目标位置 pos.asLong()，value = 锁定的槽位与登记刻 */
    private final Map<Long, ConfirmEntry> pending = new HashMap<>();

    /** 已确认 READY 的目标位置 -> 确认时游戏刻（仅当刻有效，用于跳过 switchToItems） */
    private final Map<Long, Long> readyMap = new HashMap<>();
    private long readyMapTick = -1L;

    private ShulkerPlacementGuard() {
    }

    /** 该位置本 tick 已被守卫确认为空盒（主手已切换），跳过 switchToItems */
    public boolean isReady(BlockPos pos) {
        return pos != null && readyMap.containsKey(pos.asLong());
    }

    /** 仅保留当前游戏刻的 READY 标记（防止跨 tick 残留） */
    private void refreshReadyTick(long tick) {
        if (readyMapTick != tick) {
            readyMapTick = tick;
            readyMap.clear();
        }
    }

    public enum GuardResult {
        WAIT_OTHER_BLOCKS, // 该层还有其他非潜影盒普通方块待放（后置）
        WAIT_CLOSE,        // 已关闭容器，等待
        WAIT_CONFIRM,      // 等待 5gt 空盒确认
        READY,             // 已确认空盒，主手已设置，可放置
        NO_EMPTY           // 无可用空盒
    }

    /**
     * 评估潜影盒放置是否就绪。
     *
     * @return READY 时主手已切换为该空盒槽位；其余状态表示需等待/跳过。
     */
    public GuardResult evaluate(SchematicBlockContext ctx) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = ctx.level;
        long key = ctx.blockPos.asLong();
        if (player == null || level == null) {
            return GuardResult.NO_EMPTY;
        }
        long tick = level.getGameTime();
        refreshReadyTick(tick);

        // 1. 放置顺序靠后：目标所在层还有其他非潜影盒普通方块待放置 → 等待
        if (hasPendingOtherLayerBlock(ctx)) {
            return GuardResult.WAIT_OTHER_BLOCKS;
        }

        // 2. 停下其他操作：关闭打开的容器（如 QuickShulker 界面）
        if (!player.containerMenu.equals(player.inventoryMenu)) {
            player.closeContainer();
            return GuardResult.WAIT_CLOSE;
        }

        // 3. 5gt 空盒确认：锁定同一个槽位复查
        ConfirmEntry entry = pending.get(key);
        if (entry == null) {
            int slot = findEmptyShulkerSlot(player);
            if (slot == -1) {
                return GuardResult.NO_EMPTY;
            }
            pending.put(key, new ConfirmEntry(slot, tick));
            return GuardResult.WAIT_CONFIRM;
        }
        if (tick - entry.tick < CONFIRM_TICKS) {
            return GuardResult.WAIT_CONFIRM;
        }
        // 满 5gt：复查该槽位是否仍是空盒
        pending.remove(key);
        ItemStack stack = player.getInventory().getItem(entry.slot);
        if (stack.isEmpty() || !InventoryUtils.isEmptyShulker(stack)) {
            // 误判（打开中的盒子实际有内容）→ 放弃该槽位，重新找下一个
            int slot = findEmptyShulkerSlot(player);
            if (slot == -1) {
                return GuardResult.NO_EMPTY;
            }
            pending.put(key, new ConfirmEntry(slot, tick));
            return GuardResult.WAIT_CONFIRM;
        }
        if (!InventoryUtils.setPickedItemToHand(entry.slot, stack, minecraft)) {
            return GuardResult.NO_EMPTY;
        }
        readyMap.put(key, tick);
        return GuardResult.READY;
    }

    /**
     * 目标所在层（Y 轴）内是否还有其他"非潜影盒、非水"的待放置普通方块。
     */
    private boolean hasPendingOtherLayerBlock(SchematicBlockContext ctx) {
        ClientLevel level = ctx.level;
        WorldSchematic schematic = ctx.schematic;
        AtomicReference<PrinterBox> boxRef = ClientPlayerTickManager.PRINT.getBoxRef();
        if (boxRef == null) {
            return false;
        }
        PrinterBox box = boxRef.get();
        if (box == null) {
            return false;
        }
        int targetY = ctx.blockPos.getY();
        for (BlockPos pos : box) {
            if (pos.getY() != targetY) {
                continue;
            }
            if (pos.equals(ctx.blockPos)) {
                continue;
            }
            if (!PlayerUtils.canInteracted(pos)) {
                continue;
            }
            if (!LitematicaUtils.isSchematicBlock(pos)) {
                continue;
            }
            BlockState required = schematic.getBlockState(pos);
            if (required.isAir()) {
                continue;
            }
            // 液体/含水由破冰放水或流体流程处理，不算普通方块
            if (required.getBlock() instanceof LiquidBlock || BlockStateUtils.isWaterBlock(required)) {
                continue;
            }
            // 其他潜影盒不算（它们也属于后置放置）
            if (required.getBlock() instanceof ShulkerBoxBlock) {
                continue;
            }
            if (BlockStateUtils.statesEqualIgnoreProperties(level.getBlockState(pos), required)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 在背包中找一个空盒潜影盒槽位（跳过刚打开的槽位） */
    private int findEmptyShulkerSlot(LocalPlayer player) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack itemStack = inventory.getItem(i);
            if (itemStack.isEmpty()) {
                continue;
            }
            if (!InventoryUtils.isShulkerItem(itemStack.getItem())) {
                continue;
            }
            if (BlockUtils.isShulkerRecentlyOpened(i)) {
                continue;
            }
            if (InventoryUtils.isEmptyShulker(itemStack)) {
                return i;
            }
        }
        return -1;
    }
}
