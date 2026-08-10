package me.aleksilassila.litematica.printer.utils;

import com.google.common.collect.Lists;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.SignedBytes;
import fi.dy.masa.litematica.util.EntityUtils;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.EasyPlaceUtilsAccessor;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.state.BlockState;

//#if MC >= 12105
import net.minecraft.network.HashedStack;
//#endif

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static fi.dy.masa.malilib.util.InventoryUtils.*;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList;

@SuppressWarnings({"DataFlowIssue", "SpellCheckingInspection", "GrazieInspection"})
public class InventoryUtils {
    private static final Minecraft client = Minecraft.getInstance();
    private static final int OFFHAND_SLOT_INDEX = 40;
    private static final long MESSAGE_COOLDOWN_MS = 5000L;
    private static final Map<String, Long> LAST_MESSAGE_SEND_TIME = new ConcurrentHashMap<>();
    @Getter
    @Setter
    private static ItemStack orderlyStoreItem; //有序存放临时存储

    public static int getSelectedSlot(Inventory inventory) {
        //#if MC > 12104
        return inventory.getSelectedSlot();
        //#else
        //$$ return inventory.selected;
        //#endif
    }

    public static void setSelectedSlot(Inventory inventory, int slot) {
        //#if MC > 12101
        inventory.setSelectedSlot(slot);
        //#else
        //$$ inventory.selected = slot;
        //#endif
    }

    public static NonNullList<ItemStack> getMainStacks(Inventory inventory) {
        //#if MC > 12104
        return inventory.getNonEquipmentItems();
        //#else
        //$$ return inventory.items;
        //#endif
    }

    public static boolean playerHasAccessToItem(LocalPlayer playerEntity, Item item) {
        return playerHasAccessToItems(playerEntity, item);
    }

    public static boolean playerHasAccessToItems(LocalPlayer playerEntity, Item... items) {
        if (items == null || items.length == 0) return true;
        if (PlayerUtils.getAbilities(playerEntity).mayBuild) return true;
        if (!playerEntity.containerMenu.equals(playerEntity.inventoryMenu)) return false;
        Inventory inventory = playerEntity.getInventory();
        for (Item item : items) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                if (inventory.getItem(i).getItem() == item) {
                    return true;
                }
            }
            me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList.add(item);
        }
        return false;
    }

    public static boolean playerHasItemInInventory(LocalPlayer playerEntity, Item item) {
        if (playerEntity == null || item == null) {
            return false;
        }
        if (PlayerUtils.getAbilities(playerEntity).instabuild) {
            return true;
        }
        Inventory inventory = playerEntity.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    public static boolean playerHasAccessToMatchingStack(
            LocalPlayer playerEntity,
            ItemStack creativeFallback,
            Predicate<ItemStack> predicate
    ) {
        if (playerEntity == null || predicate == null) {
            return false;
        }
        if (PlayerUtils.getAbilities(playerEntity).instabuild) {
            return creativeFallback != null && predicate.test(creativeFallback);
        }
        if (!playerEntity.containerMenu.equals(playerEntity.inventoryMenu)) {
            return false;
        }
        Inventory inventory = playerEntity.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return true;
            }
        }
        return false;
    }

    public static ItemStack createWaterPotionStack() {
        //#if MC >= 12005
        return PotionContents.createItemStack(Items.POTION, Potions.WATER);
        //#else
        //$$ return PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        //#endif
    }

    public static boolean isWaterPotion(ItemStack stack) {
        if (stack == null || !stack.is(Items.POTION)) {
            return false;
        }
        //#if MC >= 12005
        PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
        return contents != null && contents.is(Potions.WATER);
        //#else
        //$$ return PotionUtils.getPotion(stack) == Potions.WATER;
        //#endif
    }

    public static boolean setPickedItemToHand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        int slotNum = mc.player.getInventory().findSlotMatchingItem(stack);
        return setPickedItemToHand(slotNum, stack, mc);
    }

    public static void setHotbarSlot(int slot, Inventory inventory) {
        boolean usePacket = Configs.Placement.PRINT_USE_PACKET.getBooleanValue();
        if (usePacket) {
            client.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
        setSelectedSlot(inventory, slot);
    }

    /**
     * 检查是否有可用的 Pick 槽位
     *
     * @param sourceSlot 源槽位（-1表示自动寻找）
     * @param mc         Minecraft实例
     * @return PickResult 枚举结果
     */
    public static PickResult checkPickSlotAvailable(int sourceSlot, Minecraft mc) {
        // 基础校验失败 → 返回通用FAIL
        if (mc.player == null) return PickResult.FAIL;
        Player player = mc.player;
        Inventory inventory = player.getInventory();
        // 源槽位是快捷栏 → 成功
        if (Inventory.isHotbarSlot(sourceSlot)) return PickResult.SUCCESS;
        // 无配置可拾取槽位 → 精准失败类型
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            return PickResult.FAIL_NO_PICK_SLOTS_CONFIGURED;
        }
        // 寻找可用槽位
        int hotbarSlot = sourceSlot;
        if (sourceSlot == -1 || !Inventory.isHotbarSlot(sourceSlot)) {
            hotbarSlot = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory);
        }
        if (hotbarSlot == -1) {
            hotbarSlot = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
        }
        // 无可用槽位 → 精准失败类型；否则成功
        return hotbarSlot != -1 ? PickResult.SUCCESS : PickResult.FAIL_NO_SUITABLE_SLOT_FOUND;
    }

    public static boolean setPickedItemToHand(int sourceSlot, ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;
        Inventory inventory = player.getInventory();
        // 目标物品在热键栏中
        if (Inventory.isHotbarSlot(sourceSlot)) {
            setHotbarSlot(sourceSlot, inventory);
            return true;
        }
        if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_valid_slots_configured");
            return false;
        }
        int hotbarSlot = sourceSlot;
        // 尝试寻找一个空的可拾取方块的热键栏槽位
        if (sourceSlot == -1 || !Inventory.isHotbarSlot(sourceSlot)) {
            hotbarSlot = InventoryUtilsAccessor.getEmptyPickBlockableHotbarSlot(inventory);
        }
        // 如果没有空槽位，则寻找一个可拾取方块的热键栏槽位
        if (hotbarSlot == -1) {
            hotbarSlot = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
        }
        if (hotbarSlot != -1) {
            setHotbarSlot(hotbarSlot, inventory);
            if (EntityUtils.isCreativeMode(player)) {
                getMainStacks(inventory).set(hotbarSlot, stack.copy());
                client.gameMode.handleCreativeModeItemAdd(client.player.getMainHandItem(), 36 + hotbarSlot);
                return true;
            }
            EasyPlaceUtilsAccessor.callSetEasyPlaceLastPickBlockTime();
            return swapItemToMainHand(stack.copy(), mc);
        } else {
            showMessageWithCooldown(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }
    }

    public static boolean swapItemToMainHand(ItemStack stackReference, Minecraft mc) {
        Player player = mc.player;
        if (player == null) return false;

        //#if MC > 12004
        boolean b = areStacksEqualIgnoreNbt(stackReference, player.getMainHandItem());
        //#else
        //$$ boolean b = areStacksEqual(stackReference, player.getMainHandItem());
        //#endif
        if (b) {
            return false;
        }

        int slot = findSlotWithItem(player.inventoryMenu, stackReference, true);
        if (slot != -1) {
            ClientPacketListener connection = client.getConnection();
            if (connection == null) {
                return false;
            }
            int currentHotbarSlot = getSelectedSlot(player.getInventory());
            if (Configs.Placement.PRINT_USE_PACKET.getBooleanValue()) {
                NonNullList<Slot> slots = player.inventoryMenu.slots;
                int totalSlots = slots.size();
                List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
                for (Slot slotItem : slots) {
                    copies.add(slotItem.getItem().copy());
                }

                //#if MC >= 12105
                Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();
                //#else
                //$$ Int2ObjectMap<ItemStack> snapshot = new Int2ObjectOpenHashMap<>();
                //#endif

                for (int j = 0; j < totalSlots; j++) {
                    ItemStack original = copies.get(j);
                    ItemStack current = slots.get(j).getItem();
                    if (!ItemStack.isSameItem(original, current)) {
                        //#if MC >=12105
                        snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                        //#else
                        //$$ snapshot.put(j, current.copy());
                        //#endif
                    }
                }

                //#if MC >= 12105
                HashedStack hashedStack = HashedStack.create(player.inventoryMenu.getCarried(), connection.decoratedHashOpsGenenerator());
                connection.send(new ServerboundContainerClickPacket(
                        player.inventoryMenu.containerId,
                        player.inventoryMenu.getStateId(),
                        Shorts.checkedCast(slot),
                        SignedBytes.checkedCast(currentHotbarSlot),
                        ClickType.SWAP,
                        snapshot,
                        hashedStack
                ));
                //#else
                //$$  connection.send(new ServerboundContainerClickPacket(
                //$$           player.inventoryMenu.containerId,
                //$$           player.inventoryMenu.getStateId(),
                //$$           slot,
                //$$           currentHotbarSlot,
                //$$           ClickType.SWAP,
                //$$           player.inventoryMenu.getCarried().copy(),
                //$$           snapshot
                //$$   ));
                //#endif

                player.inventoryMenu.clicked(slot, currentHotbarSlot, ClickType.SWAP, player);
            } else {
                if (client.gameMode != null) {
                    client.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, currentHotbarSlot, ClickType.SWAP, player);
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 获取玩家副手的物品栈（全版本通用，极简实现）
     *
     * @param player 玩家实例
     * @return 副手物品栈
     */
    public static ItemStack getOffhandStack(Player player) {
        // 直接通过固定槽位40获取，无版本专属字段依赖
        return player.getInventory().getItem(OFFHAND_SLOT_INDEX);
    }

    /**
     * 将指定物品切换/设置到副手（核心方法，无选中格子逻辑）
     *
     * @param stack 要放到副手的物品栈
     * @param mc    Minecraft实例
     * @return 是否切换成功
     */
    public static boolean setItemToOffhand(ItemStack stack, Minecraft mc) {
        if (mc.player == null) return false;
        Player player = mc.player;

        // 1. 检查副手已有该物品，直接返回成功（避免重复操作）
        boolean isAlreadyInOffhand = areStacksEqual(stack, getOffhandStack(player));
        if (isAlreadyInOffhand) {
            return true;
        }

        // 2. 创造模式：直接设置副手物品（无需交换）
        if (EntityUtils.isCreativeMode(player)) {
            player.getInventory().setItem(OFFHAND_SLOT_INDEX, stack.copy());
            client.gameMode.handleCreativeModeItemAdd(getOffhandStack(player), OFFHAND_SLOT_INDEX);
            return true;
        }

        // 3. 生存模式：找到物品所在槽位，交换到副手
        int sourceSlot = findSlotWithItem(player.inventoryMenu, stack, true);
        if (sourceSlot == -1) {
            InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING, "litematica.message.warn.pickblock.no_suitable_slot_found");
            return false;
        }

        ClientPacketListener connection = client.getConnection();
        if (connection == null) return false;

        // 4. 发送交换数据包（复用原有配置，目标槽位固定为40）
        if (Configs.Placement.PRINT_USE_PACKET.getBooleanValue()) {
            NonNullList<Slot> slots = player.inventoryMenu.slots;
            int totalSlots = slots.size();
            List<ItemStack> copies = Lists.newArrayListWithCapacity(totalSlots);
            for (Slot slotItem : slots) {
                copies.add(slotItem.getItem().copy());
            }

            // 版本兼容的快照对象
            //#if MC >= 12105
            Int2ObjectMap<HashedStack> snapshot = new Int2ObjectOpenHashMap<>();
            //#else
            //$$ Int2ObjectMap<ItemStack> snapshot = new Int2ObjectOpenHashMap<>();
            //#endif

            // 构建库存快照
            for (int j = 0; j < totalSlots; j++) {
                ItemStack original = copies.get(j);
                ItemStack current = slots.get(j).getItem();
                if (!ItemStack.isSameItem(original, current)) {
                    //#if MC >=12105
                    snapshot.put(j, HashedStack.create(current, connection.decoratedHashOpsGenenerator()));
                    //#else
                    //$$ snapshot.put(j, current.copy());
                    //#endif
                }
            }

            // 发送SWAP数据包到副手槽位40
            //#if MC >= 12105
            HashedStack hashedStack = HashedStack.create(player.inventoryMenu.getCarried(), connection.decoratedHashOpsGenenerator());
            connection.send(new ServerboundContainerClickPacket(
                    player.inventoryMenu.containerId,
                    player.inventoryMenu.getStateId(),
                    Shorts.checkedCast(sourceSlot),
                    SignedBytes.checkedCast(OFFHAND_SLOT_INDEX), // 目标：副手槽位40
                    ClickType.SWAP,
                    snapshot,
                    hashedStack
            ));
            //#else
            //$$ connection.send(new ServerboundContainerClickPacket(
            //$$         player.inventoryMenu.containerId,
            //$$         player.inventoryMenu.getStateId(),
            //$$         sourceSlot,
            //$$         OFFHAND_SLOT_INDEX, // 目标：副手槽位40
            //$$         ClickType.SWAP,
            //$$         player.inventoryMenu.getCarried().copy(),
            //$$         snapshot
            //$$ ));
            //#endif

            // 本地同步交换操作
            player.inventoryMenu.clicked(sourceSlot, OFFHAND_SLOT_INDEX, ClickType.SWAP, player);
        } else {
            // 不使用数据包：本地直接交换到副手
            if (client.gameMode != null) {
                client.gameMode.handleInventoryMouseClick(
                        player.inventoryMenu.containerId,
                        sourceSlot,
                        OFFHAND_SLOT_INDEX, // 目标：副手槽位40
                        ClickType.SWAP,
                        player
                );
            }
        }

        return true;
    }


    // ========== 新增：副手核心方法（无选中格子逻辑） ==========

    private static void showMessageWithCooldown(Message.MessageType type, String messageKey) {
        long currentTime = System.currentTimeMillis();
        // 核心修改：通过消息Key获取最后发送时间，而非消息类型
        long lastSendTime = LAST_MESSAGE_SEND_TIME.getOrDefault(messageKey, 0L);

        // 未超过冷却时间，直接返回不发送
        if (currentTime - lastSendTime < MESSAGE_COOLDOWN_MS) {
            return;
        }

        // 超过冷却时间，发送消息并更新【该Key】的最后发送时间
        InfoUtils.showGuiOrInGameMessage(type, messageKey);
        LAST_MESSAGE_SEND_TIME.put(messageKey, currentTime);
    }

    public static boolean switchToItems(LocalPlayer player, Item[] items) {
        if (items == null || items.length == 0) {
            items = new Item[]{Items.AIR};
        }
        Inventory inventory = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        // 创造模式
        if (isCreativeMode) {
            ItemStack stack = new ItemStack(items[0]);
            return InventoryUtils.setPickedItemToHand(stack, client);
        }
        // 找到背包中可用的物品
        for (Item item : items) {
            int slot = -1;
            boolean onlyEmptyShulker = isShulkerItem(item)
                    && Configs.Print.PRINT_ONLY_EMPTY_SHULKER.getBooleanValue();
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                ItemStack itemStack = inventory.getItem(i);
                // 跳过"刚打开的潜影盒"槽位：打开瞬间本地 CONTAINER 会短暂变空盒，
                // 若在此窗口内选为"空盒"会误放置有内容的潜影盒
                if (onlyEmptyShulker && BlockUtils.isShulkerRecentlyOpened(i)) {
                    continue;
                }
                if (itemStack.getItem().equals(item)
                        && (!onlyEmptyShulker || isEmptyShulker(itemStack))) {
                    slot = i;
                    break;
                }
            }
            if (slot != -1) {
                ItemStack itemStack = inventory.getItem(slot);
                orderlyStoreItem = itemStack;
                return InventoryUtils.setPickedItemToHand(slot, itemStack, client);
            }
            lastNeedItemList.add(item);
        }
        return false;
    }

    public static boolean isShulkerItem(Item item) {
        return net.minecraft.world.level.block.Block.byItem(item)
                instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    public static boolean isEmptyShulker(ItemStack stack) {
        //#if MC >= 12109
        net.minecraft.world.item.component.ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) return true;
        return !contents.nonEmptyItems().iterator().hasNext();
        //#elseif MC >= 12005
        //$$ net.minecraft.world.item.component.SizedContainerContents contents = stack.get(DataComponents.CONTAINER);
        //$$ if (contents == null) return true;
        //$$ return !contents.nonEmptyItems().iterator().hasNext();
        //#else
        //$$ net.minecraft.nbt.CompoundTag tag = stack.getTag();
        //$$ if (tag == null) return true;
        //$$ net.minecraft.nbt.CompoundTag blockEntityTag = tag.getCompound("BlockEntityTag");
        //$$ if (blockEntityTag.isEmpty()) return true;
        //$$ return blockEntityTag.getList("Items", 10).isEmpty();
        //#endif
    }

    public static boolean switchToBestTool(LocalPlayer player, BlockState blockState) {
        if (player == null || blockState == null || blockState.isAir()) {
            return false;
        }
        if (PlayerUtils.getAbilities(player).instabuild) {
            return false;
        }

        Inventory inventory = player.getInventory();
        ItemStack currentStack = player.getMainHandItem();
        boolean currentToolAllowed = BreakUtils.isToolAllowedByDurabilityProtection(currentStack);
        float currentProgress = currentToolAllowed
                ? getDestroyProgress(player, blockState, currentStack)
                : 0.0F;
        float bestProgress = currentProgress;
        boolean preferSilkTouch = ToolSelectionUtils.prefersSilkTouchForDrops(blockState);
        boolean bestHasSilkTouch = preferSilkTouch
                && currentToolAllowed
                && ToolSelectionUtils.hasSilkTouch(currentStack);
        int bestSlot = -1;
        ItemStack bestStack = ItemStack.EMPTY;

        NonNullList<ItemStack> stacks = getMainStacks(inventory);
        for (int slot = 0; slot < stacks.size(); slot++) {
            ItemStack stack = stacks.get(slot);
            if (stack.isEmpty() || !BreakUtils.isToolAllowedByDurabilityProtection(stack)) {
                continue;
            }
            float progress = getDestroyProgress(player, blockState, stack);
            boolean stackHasSilkTouch = preferSilkTouch && ToolSelectionUtils.hasSilkTouch(stack);
            if ((stackHasSilkTouch && !bestHasSilkTouch)
                    || stackHasSilkTouch == bestHasSilkTouch && progress > bestProgress) {
                bestProgress = progress;
                bestHasSilkTouch = stackHasSilkTouch;
                bestSlot = slot;
                bestStack = stack;
            }
        }

        if (bestSlot == -1 || bestStack.isEmpty()) {
            return false;
        }
        return setPickedItemToHand(bestSlot, bestStack, client);
    }

    public static boolean hasUsableSilkTouchTool(LocalPlayer player) {
        if (player == null || PlayerUtils.getAbilities(player).instabuild) {
            return false;
        }
        for (ItemStack stack : getMainStacks(player.getInventory())) {
            if (!stack.isEmpty()
                    && BreakUtils.isToolAllowedByDurabilityProtection(stack)
                    && ToolSelectionUtils.hasSilkTouch(stack)) {
                return true;
            }
        }
        return false;
    }

    private static float getDestroyProgress(LocalPlayer player, BlockState state, ItemStack stack) {
        float hardness = state.getBlock().defaultDestroyTime();
        if (hardness < 0.0F) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return 1.0F;
        }
        int divisor = (!state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state)) ? 30 : 100;
        return PlayerUtils.getBlockBreakingSpeed(player, state, stack) / hardness / (float) divisor;
    }

    /**
     * 返回当前物品最多还能安全尝试消耗的次数。
     * {@link Integer#MAX_VALUE} 表示该物品不受保留数量限制。
     */
    public static int getConsumableSurplus(
            LocalPlayer player,
            ItemStack stack,
            @org.jetbrains.annotations.Nullable java.util.function.Predicate<ItemStack> requiredStackPredicate,
            int reserveCount
    ) {
        if (player == null || stack == null) {
            return 0;
        }
        if (reserveCount < 0
                || PlayerUtils.getAbilities(player).instabuild
                || stack.isEmpty()
                || stack.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }

        java.util.function.Predicate<ItemStack> predicate = requiredStackPredicate != null
                ? requiredStackPredicate
                : candidate -> candidate.is(stack.getItem());
        return Math.max(0, countMatchingMainInventory(player, predicate) - reserveCount);
    }

    public static ItemStack findReserveBlockedStack(
            LocalPlayer player,
            Item[] items,
            @org.jetbrains.annotations.Nullable java.util.function.Predicate<ItemStack> requiredStackPredicate,
            int reserveCount
    ) {
        if (player == null
                || reserveCount < 0
                || PlayerUtils.getAbilities(player).instabuild) {
            return ItemStack.EMPTY;
        }

        Inventory inventory = player.getInventory();
        int size = Math.min(36, inventory.getContainerSize());
        if (requiredStackPredicate != null) {
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()
                        && requiredStackPredicate.test(stack)
                        && getConsumableSurplus(player, stack, requiredStackPredicate, reserveCount) <= 0) {
                    return stack.copy();
                }
            }
            return ItemStack.EMPTY;
        }

        Item[] targetItems = items == null || items.length == 0 ? new Item[]{Items.AIR} : items;
        for (Item item : targetItems) {
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()
                        && stack.is(item)
                        && getConsumableSurplus(player, stack, null, reserveCount) <= 0) {
                    return stack.copy();
                }
            }
        }
        return ItemStack.EMPTY;
    }

    public static int countMatchingMainInventory(
            LocalPlayer player,
            java.util.function.Predicate<ItemStack> predicate
    ) {
        Inventory inventory = player.getInventory();
        int size = Math.min(36, inventory.getContainerSize());
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /**
     * 检查是否能切换到目标物品（配合槽位检查，仅判断不执行切换）
     *
     * @param player 本地玩家实例
     * @param items  目标物品数组（null/空则视为AIR）
     * @return PickResult 检查结果
     */
    public PickResult checkCanSwitchToItems(LocalPlayer player, Item[] items) {
        if (player == null) {
            return PickResult.FAIL;
        }
        Item[] targetItems = items;
        if (targetItems == null || targetItems.length == 0) {
            targetItems = new Item[]{Items.AIR};
        }
        Inventory inv = player.getInventory();
        boolean isCreativeMode = PlayerUtils.getAbilities(player).instabuild;
        if (isCreativeMode) {
            return InventoryUtils.checkPickSlotAvailable(-1, client);
        }
        for (Item item : targetItems) {
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack itemStack = inv.getItem(i);
                if (itemStack.getItem().equals(item)) {
                    return InventoryUtils.checkPickSlotAvailable(i, client);
                }
            }
        }
        return PickResult.FAIL;
    }

    public enum PickResult {
        SUCCESS,
        FAIL,
        FAIL_NO_PICK_SLOTS_CONFIGURED,
        FAIL_NO_SUITABLE_SLOT_FOUND;

        // 快捷判断：是否是「未配置可拾取槽位」
        public boolean isNoPickSlotsConfigured() {
            return this == FAIL_NO_PICK_SLOTS_CONFIGURED;
        }

        // 快捷判断：是否是「无可用槽位」
        public boolean isNoSuitableSlotFound() {
            return this == FAIL_NO_SUITABLE_SLOT_FOUND;
        }

        // 快捷方法：是否「无可用槽位」（包含两种精准失败类型）
        public boolean isNoAvailableSlot() {
            return isNoPickSlotsConfigured() || isNoSuitableSlotFound();
        }

        // 快捷方法：是否「有可用槽位」（仅SUCCESS表示有）
        public boolean isAvailable() {
            return this == SUCCESS;
        }
    }
}
