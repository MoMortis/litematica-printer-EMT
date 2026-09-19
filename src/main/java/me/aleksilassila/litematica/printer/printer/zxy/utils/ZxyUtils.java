package me.aleksilassila.litematica.printer.printer.zxy.utils;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

//#if MC >= 12102
import net.minecraft.world.level.chunk.status.ChunkStatus;
//#else
//$$ import net.minecraft.world.level.chunk.ChunkStatus;
//#endif

import static net.minecraft.world.level.block.ShulkerBoxBlock.FACING;

public class ZxyUtils {
    private static final Minecraft client = Minecraft.getInstance();

    public static String syncInventoryId = "syncInventory";

    public static LinkedHashSet<BlockPos> syncPosList = new LinkedHashSet<>();
    public static ArrayList<ItemStack> targetBlockInv;
    /** 源为合成器时记录其禁用槽位（9 格，isSlotDisabled 快照）；非合成器为 null */
    public static boolean[] targetDisabledSlots;
    /**
     * case 1/3 挂起标记：两者都在「容器内容包」到达时被触发，而菜单的 dataSlots
     * （合成器禁用位）随同一批包到达且排在内容包之后——立即读会拿到全 0 的禁用位。
     * 挂起一 tick 由 {@link #tick()} 补跑，届时 dataSlots 已全部生效。
     */
    static boolean syncDeferred = false;
    public static int num = 0;
    static BlockPos blockPos = null;
    //同步失败的容器计数，连续失败达到上限后放弃该容器
    static final Map<BlockPos, Integer> syncFailCount = new HashMap<>();
    //num==3 等待容器内容超时计数（游戏刻）
    static int syncFailNumTime = 0;
    static Set<BlockPos> highlightPosList = new LinkedHashSet<>();
    static Map<ItemStack, Integer> targetItemsCount = new HashMap<>();
    static Map<ItemStack, Integer> playerItemsCount = new HashMap<>();

    private static void getReadyColor() {
        HighlightBlockRenderer.createHighlightBlockList(syncInventoryId, Configs.Special.SYNC_INVENTORY_COLOR);
        highlightPosList = HighlightBlockRenderer.getHighlightBlockPosList(syncInventoryId);
    }

    public static void startOrOffSyncInventory() {
        getReadyColor();
        if (client.hitResult != null && client.hitResult.getType() == HitResult.Type.BLOCK && syncPosList.isEmpty()) {
            BlockPos pos = ((BlockHitResult) client.hitResult).getBlockPos();
            BlockState blockState = client.level.getBlockState(pos);
            Block block = null;
            if (client.level != null) {
                block = client.level.getBlockState(pos).getBlock();
                BlockEntity blockEntity = client.level.getBlockEntity(pos);
                boolean isInventory = InventoryUtils.isInventory(client.level, pos);
                try {
                    if ((isInventory && blockState.getMenuProvider(client.level, pos) == null) ||
                            (blockEntity instanceof ShulkerBoxBlockEntity entity &&
                                    //#if MC > 12103
                                    !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(FACING), 0.0F, 0.5F, pos.getBottomCenter()).move(pos).deflate(1.0E-6)) &&
                                    //#elseif MC <= 12103 && MC > 12004
                                    //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(FACING), 0.0F, 0.5F).move(pos).deflate(1.0E-6)) &&
                                    //#elseif MC <= 12004
                                    //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(blockState.getValue(FACING), 0.0f, 0.5f).move(pos).deflate(1.0E-6)) &&
                                    //#endif
                                    entity.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED)) {
                        MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_CONTAINER_CANNOT_OPEN.getName());
                    } else if (!isInventory) {
                        MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_NOT_CONTAINER.getName());
                        return;
                    }
                } catch (Exception e) {
                    MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_NOT_CONTAINER.getName());
                    return;
                }
            }
            String blockName = BuiltInRegistries.BLOCK.getKey(block).toString();
            syncPosList.addAll(filterBlocksByName(blockName));
            if (!syncPosList.isEmpty()) {
                if (client.player == null) return;
                client.player.closeContainer();
                if (!openInv(pos, false)) {
                    syncPosList = new LinkedHashSet<>();
                    return;
                }
                highlightPosList.addAll(syncPosList);
                ModUtils.closeScreen++;
                num = 1;
            }
        } else if (!syncPosList.isEmpty()) {
            syncPosList.forEach(highlightPosList::remove);
            syncPosList = new LinkedHashSet<>();
            if (client.player != null) client.player.clientSideCloseContainer();
            num = 0;
            MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_CANCELLED.getName());
        }
    }

    public static boolean openInv(BlockPos pos, boolean ignoreThePrompt) {
        if (client.player != null && !PlayerUtils.canInteracted(pos)) {
            if (!ignoreThePrompt)
                MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_TOO_FAR.getName());
            return false;
        }
        if (client.gameMode != null) {
            //#if MC < 11904
            //$$ client.gameMode.useItemOn(client.player, client.level, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction. DOWN, pos, false));
            //#else
            client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.DOWN, pos, false));
            //#endif
            return true;
        } else return false;
    }

    public static void itemsCount(Map<ItemStack, Integer> itemsCount, ItemStack itemStack) {
        // 判断是否存在可合并的键
        Optional<Map.Entry<ItemStack, Integer>> entry = itemsCount.entrySet().stream()
                .filter(e -> ItemStack.isSameItemSameComponents(e.getKey(), itemStack))
                .findFirst();

        if (entry.isPresent()) {
            // 更新已有键对应的值
            Integer count = entry.get().getValue();
            count += itemStack.getCount();
            itemsCount.put(entry.get().getKey(), count);
        } else {
            // 添加新键值对
            itemsCount.put(itemStack, itemStack.getCount());
        }
    }

    public static void syncInv() {
        switch (num) {
            case 1 -> {
                if (!syncDeferred) {
                    syncDeferred = true; // 挂起一 tick 等 dataSlots 到齐，见 tick() 补跑
                    return;
                }
                syncDeferred = false;
                //按下热键后记录看向的容器 开始同步容器 只会触发一次
                targetBlockInv = new ArrayList<>();
                targetItemsCount = new HashMap<>();
                targetDisabledSlots = null;
                if (client.player != null && !client.player.containerMenu.equals(client.player.inventoryMenu)) {
                    // 合成器：记录源禁用槽位。原版约束"只有空槽可禁用"→ 禁用槽必为空，目标端可复现；
                    // dataSlots 已随上一批包生效，此刻读取即为服务端当前值。
                    // 「同步合成器」关闭时不读禁用位（目标端也就不会做状态对齐）
                    if (client.player.containerMenu instanceof CrafterMenu crafterMenu
                            && Configs.Special.SYNC_INVENTORY_CRAFTER.getBooleanValue()) {
                        boolean[] disabled = new boolean[9];
                        for (int i = 0; i < disabled.length; i++) {
                            disabled[i] = crafterMenu.isSlotDisabled(i);
                        }
                        targetDisabledSlots = disabled;
                    }
                    for (int i = 0; i < client.player.containerMenu.slots.get(0).container.getContainerSize(); i++) {
                        ItemStack copy = client.player.containerMenu.slots.get(i).getItem().copy();
                        itemsCount(targetItemsCount, copy);
                        targetBlockInv.add(copy);
                    }
                    //上面如果不使用copy()在关闭容器后会使第一个元素号变该物品成总数 非常有趣...
                    client.player.closeContainer();
//                    System.out.println("!!!1 "+targetBlockInv.get(0).getCount());
                    num = 2;
                } else {
                    // 源容器在挂起期间被关闭：放弃本次同步（避免 num 卡在 1）
                    syncPosList.forEach(highlightPosList::remove);
                    syncPosList = new LinkedHashSet<>();
                    num = 0;
                }
            }
            case 2 -> {
                //打开列表中的容器 只要容器同步列表不为空 就会一直执行此处
                if (client.player == null) return;
                playerItemsCount = new HashMap<>();
                MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_REMAINING.getName(syncPosList.size()));
                if (!client.player.containerMenu.equals(client.player.inventoryMenu)) return;
                NonNullList<Slot> slots = client.player.inventoryMenu.slots;
                slots.forEach(slot -> itemsCount(playerItemsCount, slot.getItem()));

                if (Configs.Special.SYNC_INVENTORY_CHECK.getBooleanValue() && !targetItemsCount.entrySet().stream()
                        .allMatch(target -> playerItemsCount.entrySet().stream()
                                .anyMatch(player ->
                                        ItemStack.isSameItemSameComponents(player.getKey(), target.getKey()) && target.getValue() <= player.getValue())))
                    return;

                // 失败重试容器暂存，循环结束后统一加回，避免迭代中修改 syncPosList 触发 CME
                List<BlockPos> retryPositions = new ArrayList<>();
                Iterator<BlockPos> iterator = syncPosList.iterator();
                while (iterator.hasNext()) {
                    BlockPos pos = iterator.next();
                    if (!openInv(pos, true)) {
                        iterator.remove();
                        // 超距容器：无法打开但不应放弃，保留等待玩家靠近（避免误报同步完成）
                        if (client.player != null && !PlayerUtils.canInteracted(pos)) {
                            retryPositions.add(pos);
                            continue;
                        }
                        //打开失败（如距离过远），移到队尾稍后再试，连续失败则放弃该容器
                        int failCount = syncFailCount.getOrDefault(pos, 0) + 1;
                        if (failCount >= 5) {
                            syncFailCount.remove(pos);
                            highlightPosList.remove(pos);
                            MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_CONTAINER_CANNOT_OPEN.getName());
                        } else {
                            syncFailCount.put(pos, failCount);
                            retryPositions.add(pos);
                        }
                        continue;
                    }
                    syncFailCount.remove(pos);
                    ModUtils.closeScreen++;
                    blockPos = pos;
                    num = 3;
                    break;
                }
                syncPosList.addAll(retryPositions);
                if (syncPosList.isEmpty()) {
                    num = 0;
                    MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_COMPLETE.getName());
                }
            }
            case 3 -> {
                if (!syncDeferred) {
                    syncDeferred = true; // 挂起一 tick：目标的合成器禁用位 dataSlots 尚未到齐
                    return;
                }
                syncDeferred = false;
                //开始同步 在打开容器后触发
                AbstractContainerMenu sc = client.player.containerMenu;
                if (sc.equals(client.player.inventoryMenu)) return;
                int size = Math.min(targetBlockInv.size(), sc.slots.get(0).container.getContainerSize());

                // 合成器：先同步禁用槽位状态，再同步物品。原版约束"只有空槽可切换状态"（禁用/启用皆是）：
                // ① 快照要求启用的禁用槽 → 直接解禁（禁用槽必为空，恒合法）；
                // ② 快照要求禁用的启用槽 → 槽内非空则先整组 THROW 清空（同步口径：对不上就扔），再禁用。
                // 状态对齐后物品对齐才不会撞上 CrafterSlot.mayPlace（禁用槽拒绝放置）
                CrafterMenu crafterMenu = sc instanceof CrafterMenu m && targetDisabledSlots != null ? m : null;
                if (crafterMenu != null) {
                    for (int i = 0; i < 9; i++) {
                        boolean wantDisabled = targetDisabledSlots[i];
                        if (!wantDisabled && crafterMenu.isSlotDisabled(i)) {
                            setCrafterSlotState(crafterMenu, i, true); // 解禁（槽必空，恒合法）
                        } else if (wantDisabled && !crafterMenu.isSlotDisabled(i)
                                && !sc.slots.get(i).getItem().isEmpty()) {
                            // 清空待禁用槽（整组扔出，与物品对齐的"不同直接扔出"同口径）
                            client.gameMode.handleInventoryMouseClick(
                                    sc.containerId, i, 1, ClickType.THROW, client.player);
                        }
                    }
                    for (int i = 0; i < 9; i++) {
                        if (targetDisabledSlots[i] && !crafterMenu.isSlotDisabled(i)) {
                            setCrafterSlotState(crafterMenu, i, false); // 禁用（此槽此刻必为空）
                        }
                    }
                }

                int times = 0;
                for (int i = 0; i < size; i++) {
                    ItemStack item1 = sc.slots.get(i).getItem();
                    ItemStack item2 = targetBlockInv.get(i).copy();
                    int currNum = item1.getCount();
                    int tarNum = item2.getCount();
                    boolean same = ItemStack.isSameItemSameComponents(item1, item2.copy()) && !item1.isEmpty();
                    if (ItemStack.isSameItemSameComponents(item1, item2) && currNum == tarNum) continue;
                    //不和背包交互
                    if (same) {
                        //有多
                        while (currNum > tarNum) {
                            client.gameMode.handleInventoryMouseClick(sc.containerId, i, 0, ClickType.THROW, client.player);
                            currNum--;
                        }
                    } else {
                        //不同直接扔出
                        client.gameMode.handleInventoryMouseClick(sc.containerId, i, 1, ClickType.THROW, client.player);
                        times++;
                    }
                    boolean thereAreItems = false;
                    //背包交互
                    for (int i1 = size; i1 < sc.slots.size(); i1++) {
                        ItemStack stack = sc.slots.get(i1).getItem();
                        ItemStack currStack = sc.slots.get(i).getItem();
                        currNum = currStack.getCount();
                        boolean same2 = thereAreItems = ItemStack.isSameItemSameComponents(item2, stack);
                        if (same2 && !stack.isEmpty()) {
                            int i2 = stack.getCount();
                            client.gameMode.handleInventoryMouseClick(sc.containerId, i1, 0, ClickType.PICKUP, client.player);
                            for (; currNum < tarNum && i2 > 0; i2--) {
                                client.gameMode.handleInventoryMouseClick(sc.containerId, i, 1, ClickType.PICKUP, client.player);
                                currNum++;
                            }
                            client.gameMode.handleInventoryMouseClick(sc.containerId, i1, 0, ClickType.PICKUP, client.player);
                        }
                        //这里判断没啥用，因为一个游戏刻操作背包太多次.getStack().getCount()获取的数量不准确 下次一定优化，
                        if (currNum != tarNum) times++;
                    }
                    if (!thereAreItems) times++;
                }
                if (blockPos != null) {
                    if (times == 0) {
                        syncPosList.remove(blockPos);
                        highlightPosList.remove(blockPos);
                        syncFailCount.remove(blockPos);
                    } else {
                        //物品不匹配（如容器已满），移到队尾稍后再试，连续失败则放弃该容器
                        int failCount = syncFailCount.getOrDefault(blockPos, 0) + 1;
                        if (failCount >= 5) {
                            syncFailCount.remove(blockPos);
                            syncPosList.remove(blockPos);
                            highlightPosList.remove(blockPos);
                            MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_CONTAINER_CANNOT_OPEN.getName());
                        } else {
                            syncFailCount.put(blockPos, failCount);
                            syncPosList.remove(blockPos);
                            syncPosList.add(blockPos);
                        }
                    }
                    blockPos = null;
                }
                syncFailNumTime = 0;
                client.player.closeContainer();
                num = 2;
            }
        }
    }

    /**
     * 合成器槽位禁用状态切换：本地预测（{@link CrafterMenu#setSlotState} 写客户端 dataSlots，
     * 使后续 mayPlace/对齐逻辑立即看到新状态）+ 发送 ServerboundContainerSlotStateChangedPacket
     * （服务端校验菜单是 CrafterMenu、方块实体是 CrafterBlockEntity，且仅空槽可禁用）。
     * 注意包构造器参数序为 (slotId, containerId, newState)，slotId 在前——传反会被服务端
     * 「containerId 不匹配当前打开菜单」静默丢弃。
     */
    private static void setCrafterSlotState(CrafterMenu menu, int slot, boolean enabled) {
        if (client.player == null) return;
        menu.setSlotState(slot, enabled);
        client.player.connection.send(new ServerboundContainerSlotStateChangedPacket(
                slot, menu.containerId, enabled));
    }

    public static void tick() {
        if (me.aleksilassila.litematica.printer.utils.EatUtils.isBusy()) {
            return; // 暴饮暴食进食/取食中：容器同步状态机让路
        }
        // case 1/3 的挂起补跑：此刻菜单 dataSlots（合成器禁用位）已全部生效。
        // 注意不能先清 syncDeferred 再调 syncInv——case 体靠它==true 放行，先清会无限重新挂起
        if (syncDeferred) {
            if (num == 1 || num == 3) {
                syncInv(); // case 体检测到 syncDeferred==true 放行并自行清位
            } else {
                syncDeferred = false; // 状态已离开 1/3（如挂起期间取消同步）：丢弃挂起
            }
        }
        if (num == 2) {
            syncInv();
        }
        if (num == 3) {
            //打开容器超时（服务器无响应或未收到内容包），放弃本次打开，稍后再试
            syncFailNumTime++;
            if (syncFailNumTime >= 40) {
                syncFailNumTime = 0;
                if (client.player != null) client.player.closeContainer();
                if (blockPos != null) {
                    int failCount = syncFailCount.getOrDefault(blockPos, 0) + 1;
                    if (failCount >= 5) {
                        syncFailCount.remove(blockPos);
                        syncPosList.remove(blockPos);
                        highlightPosList.remove(blockPos);
                        MessageUtils.setOverlayMessage(I18n.INVENTORY_SYNC_CONTAINER_CANNOT_OPEN.getName());
                    } else {
                        syncFailCount.put(blockPos, failCount);
                        syncPosList.remove(blockPos);
                        syncPosList.add(blockPos);
                    }
                    blockPos = null;
                }
                num = 2;
            }
        }
        //回收泄漏的 closeScreen 计数，避免吞掉玩家手动打开的容器界面
        if (num == 0 && !InventoryUtils.isOpenHandler && ModUtils.closeScreen > 0) {
            ModUtils.closeScreen--;
        }
    }

    public static void switchPlayerInvToHotbarAir(int slot) {
        if (client.player == null) return;
        LocalPlayer player = client.player;
        AbstractContainerMenu sc = player.containerMenu;
        NonNullList<Slot> slots = sc.slots;
        int i = sc.equals(player.inventoryMenu) ? 9 : 0;
        for (; i < slots.size(); i++) {
            if (slots.get(i).getItem().isEmpty() && slots.get(i).container instanceof Inventory) {
                fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, i, slot);
                return;
            }
        }
    }

    /**
     * 从当前选中的区域中筛选出指定名称的方块，并返回这些方块的位置列表。
     *
     * @param blockName 方块的名字，用于匹配要筛选的方块类型
     * @return 返回一个包含所有匹配到的方块位置的集合。如果没有找到匹配项或当前没有选中任何区域，则返回空集合。
     */
    public static Collection<BlockPos> filterBlocksByName(String blockName) {
        Set<BlockPos> blocks = new LinkedHashSet<>();
        if (client.level == null) return blocks;
        AreaSelection i = DataManager.getSelectionManager().getCurrentSelection();
        List<Box> boxes;
        if (i == null) return blocks;
        boxes = i.getAllSubRegionBoxes();
        for (Box box : boxes) {
            if (box.getPos1() == null || box.getPos2() == null) continue;
            PrinterBox printerBox = new PrinterBox(box.getPos1(), box.getPos2());
            //按区块扫描方块实体，避免逐方块 getBlockState 导致的全量扫描卡顿
            for (int cx = printerBox.minX >> 4; cx <= printerBox.maxX >> 4; cx++) {
                for (int cz = printerBox.minZ >> 4; cz <= printerBox.maxZ >> 4; cz++) {
                    LevelChunk chunk = (LevelChunk) client.level.getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) continue;
                    for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                        if (!printerBox.contains(pos)) continue;
                        BlockState state = chunk.getBlockState(pos);
                        if (!PinYinSearchUtils.matchName(blockName, state)) continue;
                        // 双箱只保留左半：打开任意一半都是同一个 54 格容器菜单，右半是重复目标；
                        // 点在右半上时左半不在选区内则列表为空，重新点左半即可
                        if (state.getBlock() instanceof ChestBlock
                                && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) {
                            continue;
                        }
                        blocks.add(pos);
                    }
                }
            }
        }
        return blocks;
    }

// //右键单击
// client.interactionManager.clickSlot(sc.syncId, i, 1, SlotActionType.PICKUP, client.player);
// //左键单击
// client.interactionManager.clickSlot(sc.syncId, i, 0, SlotActionType.PICKUP, client.player);
// //点击背包外
// client.interactionManager.clickSlot(sc.syncId, -999, 0, SlotActionType.PICKUP, client.player);
// //丢弃一个
// client.interactionManager.clickSlot(sc.syncId, i, 0, SlotActionType.THROW, client.player);
// //丢弃全部
// client.interactionManager.clickSlot(sc.syncId, i, 1, SlotActionType.THROW, client.player);
// //开始拖动
// client.interactionManager.clickSlot(sc.syncId, -999, 0, SlotActionType.QUICK_CRAFT, client.player);
// //拖动经过的槽
// client.interactionManager.clickSlot(sc.syncId, i1, 1, SlotActionType.QUICK_CRAFT, client.player);
// //结束拖动
// client.interactionManager.clickSlot(sc.syncId, -999, 2, SlotActionType.QUICK_CRAFT, client.player);
// //副手交换
// client.interactionManager.clickSlot(sc.syncId, i, 40, SlotActionType.SWAP, client.player);

}