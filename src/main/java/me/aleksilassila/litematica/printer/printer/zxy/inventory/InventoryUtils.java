package me.aleksilassila.litematica.printer.printer.zxy.inventory;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.mixin.printer.litematica.InventoryUtilsAccessor;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

//#if MC > 11904 
import me.aleksilassila.litematica.printer.printer.zxy.chesttracker.MemoryUtils;
import me.aleksilassila.litematica.printer.printer.zxy.chesttracker.SearchItem;
//#elseif MC <= 11904
//$$ import net.minecraft.core.Registry;
//$$ import net.minecraft.resources.ResourceLocation;
//$$ import net.minecraft.resources.ResourceKey;
//$$ import me.aleksilassila.litematica.printer.printer.zxy.memory.Memory;
//$$ import me.aleksilassila.litematica.printer.printer.zxy.memory.MemoryDatabase;
//$$ import me.aleksilassila.litematica.printer.printer.zxy.memory.MemoryUtils;
    //#if MC > 11902
    //$$ import net.minecraft.core.registries.Registries;
    //#endif
//#endif

//#if MC >= 12001
//$$ import red.jackf.chesttracker.api.providers.InteractionTracker;
//#endif

import java.util.HashSet;
import static me.aleksilassila.litematica.printer.printer.zxy.inventory.OpenInventoryPacket.openIng;

public class InventoryUtils {
    private static final int AUTOMATED_QUICK_SHULKER_SCREEN_PROTECTION_TIMEOUT_TICKS = 40;
    private static final long QUICK_SHULKER_SEARCH_TIMEOUT_NANOS = 1_000_000_000L;

    private static int shulkerCooldown = 0;
    private static long quickShulkerSearchDeadlineNanos;
    private static int automatedQuickShulkerScreenProtectionTimeout;
    private static boolean automatedQuickShulkerScreenProtection;
    private static boolean automatedQuickShulkerOpened;
    private static boolean preserveAutomatedQuickShulkerScreenOnClose;
    private static Screen protectedScreen;

    private static final Minecraft client = Minecraft.getInstance();

    public static boolean isInventory(Level world, BlockPos pos) {
        return fi.dy.masa.malilib.util.InventoryUtils.getInventory(world, pos) != null;
    }

    public static boolean canOpenInv(BlockPos pos) {
        if (client.level != null) {
            BlockState blockState = client.level.getBlockState(pos);
            BlockEntity blockEntity = client.level.getBlockEntity(pos);
            boolean isInventory = InventoryUtils.isInventory(client.level, pos);
            try {
                if ((isInventory && blockState.getMenuProvider(client.level, pos) == null) ||
                        (blockEntity instanceof ShulkerBoxBlockEntity entity &&
                                //#if MC > 12103
                                !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F, pos.getBottomCenter()).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12103 && MC > 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(1.0F, blockState.getValue(BlockStateProperties.FACING), 0.0F, 0.5F).move(pos).deflate(1.0E-6)) &&
                                //#elseif MC <= 12004
                                //$$ !client.level.noCollision(Shulker.getProgressDeltaAabb(blockState.getValue(BlockStateProperties.FACING), 0.0f, 0.5f).move(pos).deflate(1.0E-6)) &&
                                //#endif
                                entity.getAnimationStatus() == ShulkerBoxBlockEntity.AnimationStatus.CLOSED)) {
                    return false;
                } else if (!isInventory) {
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
            return true;
        } else {
            return false;
        }
    }

    public static HashSet<Item> lastNeedItemList = new HashSet<>();
    public static boolean isOpenHandler = false;

    public static void addQuickShulkerDemand(Item item) {
        if (lastNeedItemList.isEmpty() && Configs.Core.QUICK_SHULKER.getBooleanValue()) {
            quickShulkerSearchDeadlineNanos = System.nanoTime() + QUICK_SHULKER_SEARCH_TIMEOUT_NANOS;
        }
        lastNeedItemList.add(item);
    }

    public static boolean shouldSuppressContainerScreen() {
        LocalPlayer player = client.player;
        return automatedQuickShulkerScreenProtection
                && player != null
                && !player.containerMenu.equals(player.inventoryMenu)
                && (isOpenHandler || SwitchItem.reSwitchItem != null);
    }

    public static void beginAutomatedQuickShulkerScreenProtection() {
        automatedQuickShulkerScreenProtection = true;
        automatedQuickShulkerOpened = false;
        automatedQuickShulkerScreenProtectionTimeout = AUTOMATED_QUICK_SHULKER_SCREEN_PROTECTION_TIMEOUT_TICKS;
        preserveAutomatedQuickShulkerScreenOnClose = false;
        protectedScreen = client.screen;
    }

    public static boolean shouldPreserveAutomatedQuickShulkerScreenOnClose(Screen screen) {
        if (!automatedQuickShulkerScreenProtection
                || !preserveAutomatedQuickShulkerScreenOnClose
                || screen != null) {
            return false;
        }
        preserveAutomatedQuickShulkerScreenOnClose = false;
        automatedQuickShulkerScreenProtection = false;
        automatedQuickShulkerScreenProtectionTimeout = 0;
        protectedScreen = null;
        return true;
    }

    public static void closeAutomatedQuickShulkerContainer(LocalPlayer player) {
        if (!automatedQuickShulkerOpened) {
            clearAutomatedQuickShulkerScreenProtection();
            return;
        }
        if (!automatedQuickShulkerScreenProtection) {
            player.closeContainer();
            automatedQuickShulkerOpened = false;
            return;
        }
        preserveAutomatedQuickShulkerScreenOnClose = protectedScreen != null;
        player.closeContainer();
        automatedQuickShulkerScreenProtection = false;
        automatedQuickShulkerOpened = false;
        automatedQuickShulkerScreenProtectionTimeout = 0;
        preserveAutomatedQuickShulkerScreenOnClose = false;
        protectedScreen = null;
    }

    public static void clearAutomatedQuickShulkerScreenProtection() {
        automatedQuickShulkerScreenProtection = false;
        automatedQuickShulkerOpened = false;
        automatedQuickShulkerScreenProtectionTimeout = 0;
        preserveAutomatedQuickShulkerScreenOnClose = false;
        protectedScreen = null;
    }

    public static boolean switchItem() {
        if (!lastNeedItemList.isEmpty() && !isOpenHandler && !openIng && OpenInventoryPacket.key == null) {
            LocalPlayer player = client.player;
            AbstractContainerMenu sc = player.containerMenu;
            if (!player.containerMenu.equals(player.inventoryMenu)) return false;
            //排除合成栏 装备栏 副手
            if (Configs.Placement.STORE_ORDERLY.getBooleanValue() && sc.slots.stream().skip(9).limit(sc.slots.size() - 10).noneMatch(slot -> slot.getItem().isEmpty())
                    && (Configs.Core.QUICK_SHULKER.getBooleanValue() || Configs.Core.CLOUD_INVENTORY.getBooleanValue())) {
                SwitchItem.checkItems();
                return true;
            }

            if (Configs.Core.QUICK_SHULKER.getBooleanValue() && openShulker(lastNeedItemList)) {
                return true;
            } else if (Configs.Core.CLOUD_INVENTORY.getBooleanValue()) {
                for (Item item : lastNeedItemList) {
                    //#if MC >= 12001
                    MemoryUtils.currentMemoryKey = client.level.dimension().identifier();
                    MemoryUtils.itemStack = new ItemStack(item);
                    if (SearchItem.search(true)) {
                        ModUtils.closeScreen++;
                        isOpenHandler = true;
                        ClientPlayerTickManager.PRINT.setPrinterMemorySync(true);
                        return true;
                    }
                    //#elseif MC < 12001
                    //$$
                    //$$    MemoryDatabase database = MemoryDatabase.getCurrent();
                    //$$    if (database != null) {
                    //$$        for (ResourceLocation dimension : database.getDimensions()) {
                    //$$            for (Memory memory : database.findItems(item.getDefaultInstance(), dimension)) {
                    //$$                MemoryUtils.setLatestPos(memory.getPosition());
                        //#if MC < 11904
                        //$$ OpenInventoryPacket.sendOpenInventory(memory.getPosition(), ResourceKey.create(Registry.DIMENSION_REGISTRY, dimension));
                        //#else
                        //$$ OpenInventoryPacket.sendOpenInventory(memory.getPosition(), ResourceKey.create(Registries.DIMENSION, dimension));
                        //#endif
                    //$$                if(ModUtils.closeScreen == 0) ModUtils.closeScreen++;
                    //$$                me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager.PRINT.setPrinterMemorySync(true);
                    //$$                isOpenHandler = true;
                    //$$                return true;
                    //$$            }
                    //$$        }
                    //$$    }
                    //#endif
                }
                lastNeedItemList = new HashSet<>();
                quickShulkerSearchDeadlineNanos = 0L;
                isOpenHandler = false;
            }
        }
        return false;
    }

    static int shulkerBoxSlot = -1;
    private static int quickShulkerEmptySlots;
    private static boolean quickShulkerHasNonShulkerItem;

    public static void switchInv() {
        LocalPlayer player = Minecraft.getInstance().player;
        AbstractContainerMenu sc = player.containerMenu;
        if (sc.equals(player.inventoryMenu)) {
            return;
        }
        NonNullList<Slot> slots = sc.slots;
        int maxStacks = Configs.Core.QUICK_SHULKER_MAX_STACKS.getIntegerValue();
        int allowedStacks = quickShulkerEmptySlots > 0
                ? Math.min(maxStacks, quickShulkerEmptySlots)
                : (quickShulkerHasNonShulkerItem ? 1 : 0);
        if (allowedStacks == 0) {
            MessageUtils.setOverlayMessage(I18n.INVENTORY_BACKPACK_FULL.getName());
            finishQuickShulkerTransfer(player);
            return;
        }
        int movedStacks = 0;
        for (Item item : lastNeedItemList) {
            for (int y = 0; y < slots.get(0).container.getContainerSize() && movedStacks < allowedStacks; y++) {
                ItemStack source = slots.get(y).getItem();
                if (!source.getItem().equals(item)) {
                    continue;
                }
                try {
                    if (quickShulkerEmptySlots > 0) {
                        client.gameMode.handleInventoryMouseClick(
                                sc.containerId,
                                y,
                                0,
                                ClickType.QUICK_MOVE,
                                player);
                        movedStacks++;
                        continue;
                    }
                    if (InventoryUtilsAccessor.getPICK_BLOCKABLE_SLOTS().isEmpty()) {
                        break;
                    }
                    int c = InventoryUtilsAccessor.getPickBlockTargetSlot(player);
                    if (c == -1) {
                        break;
                    }
                    if (BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(c).getItem()).toString().contains("shulker_box")
                            && Configs.Core.QUICK_SHULKER.getBooleanValue()) {
                        MessageUtils.setOverlayMessage(I18n.INVENTORY_SHULKER_PRESELECT.getName());
                        continue;
                    }
                    if (OpenInventoryPacket.key != null) {
                        SwitchItem.newItem(source, OpenInventoryPacket.pos, OpenInventoryPacket.key, y, -1);
                    } else {
                        SwitchItem.newItem(source, null, null, y, shulkerBoxSlot);
                    }
                    fi.dy.masa.malilib.util.InventoryUtils.swapSlots(sc, y, c);
                    me.aleksilassila.litematica.printer.utils.InventoryUtils.setSelectedSlot(player.getInventory(), c);
                    movedStacks++;
                } catch (Exception e) {
                    System.out.println("切换物品异常");
                }
            }
        }
        finishQuickShulkerTransfer(player);
    }

    private static void finishQuickShulkerTransfer(LocalPlayer player) {
        shulkerBoxSlot = -1;
        quickShulkerEmptySlots = 0;
        quickShulkerHasNonShulkerItem = false;
        lastNeedItemList = new HashSet<>();
        quickShulkerSearchDeadlineNanos = 0L;
        isOpenHandler = false;
        // 通知自动补货适配：趁潜影盒容器未关闭，与取出物品同一 tick 执行"物品放回消耗前槽位"
        me.aleksilassila.litematica.printer.utils.HandRestockShulkerCompat
                .onQuickShulkerTransferFinished(player);
        AbstractContainerMenu currentMenu = player.containerMenu;
        if (!currentMenu.equals(player.inventoryMenu)) {
            closeAutomatedQuickShulkerContainer(player);
        } else {
            clearAutomatedQuickShulkerScreenProtection();
        }
    }

    private static void snapshotQuickShulkerInventory(net.minecraft.world.entity.player.Inventory inventory) {
        quickShulkerEmptySlots = 0;
        quickShulkerHasNonShulkerItem = false;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                quickShulkerEmptySlots++;
            } else if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().contains("shulker_box")) {
                quickShulkerHasNonShulkerItem = true;
            }
        }
    }

    private static boolean openShulker(HashSet<Item> items) {
        if (shulkerCooldown > 0) {
            return false;
        }
        for (Item item : items) {
            AbstractContainerMenu sc = Minecraft.getInstance().player.inventoryMenu;
            for (int i = 9; i < sc.slots.size(); i++) {
                ItemStack stack = sc.slots.get(i).getItem();
                String itemid = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (itemid.contains("shulker_box") && stack.getCount() == 1) {
                    NonNullList<ItemStack> items1 = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack, -1);
                    if (items1.stream().anyMatch(s1 -> s1.getItem().equals(item))) {
                        try {
                            shulkerBoxSlot = i;
                            snapshotQuickShulkerInventory(Minecraft.getInstance().player.getInventory());
                            //#if MC >= 12001 
                            //$$ if (ModUtils.isLoadMod("chesttracker")) InteractionTracker.INSTANCE.clear();
                            //#endif
                            BlockUtils.openShulker(stack, shulkerBoxSlot);
                            automatedQuickShulkerOpened = true;
                            isOpenHandler = true;
                            shulkerCooldown = Configs.Core.QUICK_SHULKER_COOLDOWN.getIntegerValue();
                            return true;
                        } catch (Exception e) {
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void abandonExpiredQuickShulkerSearch() {
        if (quickShulkerSearchDeadlineNanos == 0L || isOpenHandler
                || System.nanoTime() < quickShulkerSearchDeadlineNanos) {
            return;
        }
        LocalPlayer player = client.player;
        if (player != null) {
            finishQuickShulkerTransfer(player);
        } else {
            lastNeedItemList = new HashSet<>();
            quickShulkerSearchDeadlineNanos = 0L;
            shulkerBoxSlot = -1;
            quickShulkerEmptySlots = 0;
            quickShulkerHasNonShulkerItem = false;
            isOpenHandler = false;
            clearAutomatedQuickShulkerScreenProtection();
        }
    }

    public static void tick() {
        if (client.player != null) {
            me.aleksilassila.litematica.printer.utils.HandRestockShulkerCompat.clientTick(client.player);
        }
        abandonExpiredQuickShulkerSearch();
        if (shulkerCooldown > 0) {
            shulkerCooldown--;
        }
        if (automatedQuickShulkerScreenProtectionTimeout > 0
                && --automatedQuickShulkerScreenProtectionTimeout == 0) {
            clearAutomatedQuickShulkerScreenProtection();
        }
    }
}