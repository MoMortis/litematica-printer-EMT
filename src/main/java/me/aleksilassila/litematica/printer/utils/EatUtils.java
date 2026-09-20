package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.EatMode;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * 「暴饮！暴食！」自动进食：饥饿值降到阈值以下时自动选食物吃掉。触发模式见
 * {@link me.aleksilassila.litematica.printer.enums.EatMode}（关闭 / 仅打印机工作时 / 任何时候）。
 * <p>取食链逐级回退：<b>副手 → 快捷栏 → 背包（SWAP 换到快捷栏末位）→ 快捷潜影盒</b>
 * （副手只读不改：直接对副手使用，不切槽、不换位；快捷潜影盒仅开启时，借用
 * {@link me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils}
 * 的开盒取物机制把食物取到背包，复用其 isOpenHandler 单飞互斥语义），每次只吃一件，
 * 吃完若仍饿由触发门禁自然开始下一件。选择标准：营养值最高（平手比饱和度），
 * 黑名单（注册路径 / 完整ID / 精确译名）内的食物永远不碰。
 * <p><b>建立使用状态</b>：直接调 {@code gameMode.useItem}（原版"对空气使用物品"的公开入口，
 * 主手/副手按食物所在处指定）绕过准星——对着门/箱子/实体也不会误开，不需要探测准星占用；
 * 随后 {@code keyUse.setDown(true)} 保持（vanilla 会在按键未按下时释放使用状态），
 * vanilla 自己的 startUseItem 因 isUsingItem 早退，无副作用。
 * <p><b>互斥</b>（与快捷潜影盒取货同款让路模式）：进食期间打印/挖掘/取货/容器同步全部
 * 暂缓（ClientPlayerTickManager 与 InventoryUtils/ZxyUtils 入口检查 {@link #isBusy()}）；
 * 反向门禁保证它们忙时不启动进食。玩家手动操作（攻击/切槽/开界面）永远优先，
 * 检测到即打断进食。
 * <p>打断：受伤（生命+吸收下降，冷却见 {@code EAT_HURT_CANCEL_COOLDOWN}，0=不打断）、
 * 玩家攻击/切槽/开界面（短冷却）。tick 入口在 {@code MixinLocalPlayer.tick}。
 */
public final class EatUtils {
    /** 进食换入用的快捷栏槽位（末位，吃完换回） */
    private static final int EAT_HOTBAR_SLOT = 8;
    /** 快捷潜影盒取食的尝试间隔（tick）：避免找不到时频繁开盒 */
    private static final long SHULKER_RETRY_TICKS = 100;
    /** 全部没食物时的扫描节流（tick） */
    private static final long SCAN_RETRY_TICKS = 10;
    /** 使用状态建立失败的重试上限 */
    private static final int START_MAX_RETRIES = 5;

    private enum State { IDLE, FETCH, EATING, RESTORE }

    private static final Minecraft client = Minecraft.getInstance();

    private static State state = State.IDLE;
    /** 各类冷却截止（ClientPlayerTickManager 的全局 tick 号） */
    private static long nextActionTick;
    private static long nextScanTick;
    private static long shulkerRetryTick;
    private static long fetchDeadlineTick;
    // ===== EATING/RESTORE 会话字段 =====
    /** 本次进食使用的手：副手食物直接对副手使用，不涉及快捷栏/背包槽位 */
    private static InteractionHand eatHand = InteractionHand.MAIN_HAND;
    /** 进食前的手持槽（结束后恢复；副手进食为 -1） */
    private static int originalSelectedSlot = -1;
    /** 食物所在快捷栏槽（进食期间选中它；副手进食为 -1） */
    private static int foodHotbarSlot = -1;
    /** 食物从背包槽换入 8 号位时记录原背包槽（inv 索引 9..35），吃完换回；-1=本就在快捷栏 */
    private static int swappedInvSlot = -1;
    private static int preEatStackCount;
    private static int preEatHunger;
    private static float lastHealth;
    private static float lastAbsorption;
    /** 使用状态已建立（true 后 !isUsingItem 即"吃完"） */
    private static boolean usingStarted;
    private static boolean keyUseWasDown;
    private static int startRetries;

    private EatUtils() {
    }

    /** 是否正在进食/取食/恢复：打印、挖掘、取货、容器同步入口据此让路 */
    public static boolean isBusy() {
        return state != State.IDLE;
    }

    public static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gameMode == null) {
            if (state != State.IDLE) {
                hardReset();
            }
            return;
        }
        switch (state) {
            case IDLE -> tryStart(mc, player);
            case FETCH -> tickFetch(player);
            case EATING -> tickEating(mc, player);
            case RESTORE -> restore(player);
        }
    }

    // ==================== 触发 ====================

    private static void tryStart(Minecraft mc, LocalPlayer player) {
        EatMode eatMode = (EatMode) Configs.Special.EAT.getOptionListValue();
        if (eatMode == EatMode.OFF) {
            return;
        }
        // 仅打印机工作时：总开关未开就不吃（任何时候模式无视总开关）
        if (eatMode == EatMode.PRINTER_ONLY && !ConfigUtils.isPrinterEnable()) {
            return;
        }
        if (mc.screen != null || !player.isAlive() || !player.containerMenu.equals(player.inventoryMenu)) {
            return;
        }
        GameType mode = mc.gameMode.getPlayerMode();
        if (mode != GameType.SURVIVAL && mode != GameType.ADVENTURE) {
            return;
        }
        long now = ClientPlayerTickManager.getCurrentHandlerTime();
        if (now < nextActionTick || now < nextScanTick) {
            return;
        }
        if (player.getFoodData().getFoodLevel() > Configs.Special.EAT_HUNGER_THRESHOLD.getIntegerValue()) {
            return;
        }
        // 玩家手动操作优先：正在用物品/攻击/按住右键时不抢
        if (player.isUsingItem() || mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) {
            return;
        }
        // 打印机体系忙（取货在飞/有需求在排队/挖掘在处理/容器同步中）时不启动
        if (me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler
                || !me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList.isEmpty()
                || BreakUtils.INSTANCE.isNeedHandle()
                || ZxyUtils.num != 0) {
            return;
        }

        // 取食链 ①：副手（只读不改：直接对副手使用，不切槽、不换位，吃完无需恢复）
        if (foodScore(InventoryUtils.getOffhandStack(player)) > 0) {
            beginEating(player, -1, -1, InteractionHand.OFF_HAND);
            return;
        }
        // 取食链 ②：快捷栏
        int hotbar = findBestFood(player, 0, 9);
        if (hotbar >= 0) {
            beginEating(player, hotbar, -1, InteractionHand.MAIN_HAND);
            return;
        }
        // 取食链 ③：背包 → SWAP 到快捷栏末位
        int backpack = findBestFood(player, 9, 36);
        if (backpack >= 0) {
            swapWithHotbarEnd(player, backpack);
            beginEating(player, EAT_HOTBAR_SLOT, backpack, InteractionHand.MAIN_HAND);
            return;
        }
        // 取食链 ④：快捷潜影盒（客户端直读盒内容选食物，登记需求借用其开盒取物机制）
        if (now >= shulkerRetryTick) {
            shulkerRetryTick = now + SHULKER_RETRY_TICKS;
            if (Configs.Core.QUICK_SHULKER.getBooleanValue()) {
                Item food = findBestFoodInShulkers(player);
                if (food != null) {
                    me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.addQuickShulkerDemand(food);
                    fetchDeadlineTick = now + SHULKER_RETRY_TICKS;
                    state = State.FETCH;
                    MessageUtils.setOverlayMessage(Component.literal("§e从快捷潜影盒取食物…"));
                    return;
                }
            }
        }
        nextScanTick = now + SCAN_RETRY_TICKS; // 全都没有：节流，不必每 tick 重扫
    }

    /** FETCH：快捷潜影盒取食在飞（机制自身驱动：switchItem 开盒 → 容器内容包 → switchInv 取物） */
    private static void tickFetch(LocalPlayer player) {
        boolean finished = !me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.isOpenHandler
                && me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils.lastNeedItemList.isEmpty();
        if (finished || ClientPlayerTickManager.getCurrentHandlerTime() >= fetchDeadlineTick) {
            state = State.IDLE; // 食物可能已到背包：下一 tick 重新走取食链
        }
    }

    // ==================== 进食 ====================

    /**
     * 开始进食。主手食物需先选中其所在快捷栏槽（{@code hotbarSlot}）；
     * 副手食物只读不改：{@code hotbarSlot}/{@code swappedFrom} 传 -1，不切槽、不换位、不恢复槽位。
     */
    private static void beginEating(LocalPlayer player, int hotbarSlot, int swappedFrom, InteractionHand hand) {
        eatHand = hand;
        originalSelectedSlot = hand == InteractionHand.MAIN_HAND
                ? InventoryUtils.getSelectedSlot(player.getInventory()) : -1;
        foodHotbarSlot = hand == InteractionHand.MAIN_HAND ? hotbarSlot : -1;
        swappedInvSlot = hand == InteractionHand.MAIN_HAND ? swappedFrom : -1;
        preEatStackCount = player.getItemInHand(hand).getCount();
        preEatHunger = player.getFoodData().getFoodLevel();
        lastHealth = player.getHealth();
        lastAbsorption = player.getAbsorptionAmount();
        keyUseWasDown = client.options.keyUse.isDown();
        usingStarted = false;
        startRetries = 0;
        if (hand == InteractionHand.MAIN_HAND) {
            InventoryUtils.setHotbarSlot(hotbarSlot, player.getInventory());
        }
        state = State.EATING;
        MessageUtils.setOverlayMessage(Component.literal("§f正在吃: §a"
                + player.getItemInHand(hand).getHoverName().getString()));
    }

    private static void tickEating(Minecraft mc, LocalPlayer player) {
        // 打断检测：受伤（生命+吸收下降）
        long hurtCooldownSeconds = Configs.Special.EAT_HURT_CANCEL_COOLDOWN.getIntegerValue();
        boolean hurt = hurtCooldownSeconds > 0
                && player.getHealth() + player.getAbsorptionAmount() < lastHealth + lastAbsorption - 1.0E-5F;
        lastHealth = player.getHealth();
        lastAbsorption = player.getAbsorptionAmount();
        // 打断检测：玩家手动操作（开界面/攻击/滚轮切槽/出现容器界面）
        // 副手进食不吃选中槽依赖，切槽不影响进食，故不视为打断
        boolean playerActed = mc.screen != null
                || mc.options.keyAttack.isDown()
                || !player.containerMenu.equals(player.inventoryMenu)
                || (eatHand == InteractionHand.MAIN_HAND
                && InventoryUtils.getSelectedSlot(player.getInventory()) != foodHotbarSlot);
        if (hurt || playerActed) {
            cancel(player, hurt ? hurtCooldownSeconds * 20L : 20L);
            if (hurt) {
                MessageUtils.setOverlayMessage(Component.literal("§c受伤打断，"
                        + hurtCooldownSeconds + " 秒冷却"));
            }
            return;
        }
        if (!player.isUsingItem()) {
            if (!usingStarted) {
                // 建立使用状态：原版"对空气使用物品"公开入口，绕过准星方块/实体
                client.gameMode.useItem(player, eatHand);
                if (player.isUsingItem()) {
                    usingStarted = true;
                    client.options.keyUse.setDown(true); // vanilla 在按键未按下时会释放使用状态
                } else if (++startRetries >= START_MAX_RETRIES) {
                    cancel(player, 60L);
                }
            } else {
                // 使用结束：吃完（食物已消耗或饥饿值回升）
                restore(player);
            }
        } else {
            usingStarted = true;
            client.options.keyUse.setDown(true); // 持续按住（保险，防 vanilla 释放检查）
        }
    }

    // ==================== 恢复/取消 ====================

    private static void cancel(LocalPlayer player, long cooldownTicks) {
        client.options.keyUse.setDown(keyUseWasDown);
        if (player.isUsingItem()) {
            player.releaseUsingItem();
        }
        nextActionTick = ClientPlayerTickManager.getCurrentHandlerTime() + cooldownTicks;
        state = State.RESTORE;
    }

    private static void restore(LocalPlayer player) {
        // 吃完瞬间 vanilla 可能因 keyUse 仍按下而重新起嘴（食物 canEat）：先松键再释放
        client.options.keyUse.setDown(keyUseWasDown);
        if (player.isUsingItem()) {
            player.releaseUsingItem();
        }
        if (swappedInvSlot >= 0) {
            // 8 号位剩余（或已空）换回背包原槽，原物品回到 8 号位
            swapWithHotbarEnd(player, swappedInvSlot);
            swappedInvSlot = -1;
        }
        if (originalSelectedSlot >= 0) {
            InventoryUtils.setHotbarSlot(originalSelectedSlot, player.getInventory());
        }
        originalSelectedSlot = -1;
        foodHotbarSlot = -1;
        eatHand = InteractionHand.MAIN_HAND;
        state = State.IDLE;
    }

    /** 玩家/世界失效时的兜底清理（无法也无需恢复按键与槽位） */
    private static void hardReset() {
        state = State.IDLE;
        client.options.keyUse.setDown(false);
        originalSelectedSlot = -1;
        foodHotbarSlot = -1;
        eatHand = InteractionHand.MAIN_HAND;
        swappedInvSlot = -1;
        usingStarted = false;
        startRetries = 0;
    }

    // ==================== 选食物 ====================

    /**
     * [from, to) 槽位区间里营养最高的合法食物（营养优先，平手比饱和度）。
     * 快捷栏传 [0,9)，背包传 [9,36)。
     */
    private static int findBestFood(LocalPlayer player, int from, int to) {
        int best = -1;
        double bestScore = -1;
        for (int i = from; i < to; i++) {
            double score = foodScore(player.getInventory().getItem(i));
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** 背包内潜影盒里营养最高的合法食物（客户端直读盒内容，不发包）；无则 null */
    private static Item findBestFoodInShulkers(LocalPlayer player) {
        Item best = null;
        double bestScore = -1;
        for (int i = 9; i < 36; i++) {
            ItemStack box = player.getInventory().getItem(i);
            if (box.isEmpty() || box.getCount() != 1
                    || !BuiltInRegistries.ITEM.getKey(box.getItem()).toString().contains("shulker_box")) {
                continue;
            }
            for (ItemStack stack : fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(box, -1)) {
                double score = foodScore(stack);
                if (score > bestScore) {
                    bestScore = score;
                    best = stack.getItem();
                }
            }
        }
        return best;
    }

    /** 食物评分：非食物/黑名单返回 -1；营养 ×1000 + 饱和度 */
    private static double foodScore(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }
        FoodProperties food = stack.get(DataComponents.FOOD);
        if (food == null || isBlacklisted(stack)) {
            return -1;
        }
        return food.nutrition() * 1000.0 + food.saturation();
    }

    /** 黑名单匹配：注册路径 / 完整ID（忽略大小写）/ 精确译名 */
    private static boolean isBlacklisted(ItemStack stack) {
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String path = key.getPath();
        String id = key.toString();
        String name = stack.getHoverName().getString();
        for (String entry : Configs.Special.EAT_BLACKLIST.getStrings()) {
            String e = entry.trim();
            if (e.isEmpty()) {
                continue;
            }
            if (e.equals(path) || e.equalsIgnoreCase(id) || e.equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 背包操作 ====================

    /**
     * 背包槽（inv 索引 9..35，玩家 inventoryMenu 菜单槽同号）与快捷栏末位 SWAP：
     * 本地预测 + 发包一次完成（玩家背包无需打开界面即可 SWAP）。
     */
    private static void swapWithHotbarEnd(LocalPlayer player, int invSlot) {
        if (client.gameMode != null) {
            client.gameMode.handleInventoryMouseClick(
                    player.inventoryMenu.containerId, invSlot, EAT_HOTBAR_SLOT, ClickType.SWAP, player);
        }
    }
}
