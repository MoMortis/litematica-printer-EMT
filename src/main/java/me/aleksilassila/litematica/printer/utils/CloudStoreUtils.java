package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 云仓库 (cloud-store) 软集成。通过反射调用 com.cloudstore.client.CloudStoreClient.api：
 * connect() -> inventory(ownerUuid) -> withdrawMany(ownerUuid, requests, targetUsername)。
 * 全程后台线程执行，完成后回到主线程提示。
 */
public class CloudStoreUtils {
    private static final String CLOUD_STORE_CLASS = "com.cloudstore.client.CloudStoreClient";
    private static final String REQUEST_CLASS = "com.cloudstore.client.api.CloudStoreModels$WithdrawRequest";
    private static final String ITEM_CLASS = "com.cloudstore.client.api.CloudStoreModels$Item";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "cloud-store-refill");
        thread.setDaemon(true);
        return thread;
    });

    // 全局补货冷却截止时间（毫秒）。一次订单覆盖所有缺货种类，冷却为全局。
    private static long pendingUntilMillis = 0L;

    // 当前自动订单中的材料（用于"拿到目标物品即结束冷却"检测）
    private static final Set<Item> orderedItems = new HashSet<>();

    // 最近一次自动订单提交时间戳（用于判断在途订单是否已陈旧、可被清理）
    private static long autoOrderSubmittedAt = 0L;

    // 自动订单序号：每次提交自增，用于让过期订单的收尾逻辑不再覆盖新订单的冷却
    private static long orderToken = 0L;

    // 手动（鼠标中键）取货在途材料：与冷却计时器无关，到包或失败后移除
    private static final Set<Item> manualOrdered = new HashSet<>();

    // 补货订单失败后的重试冷却
    private static final long REFILL_COOLDOWN_MS = 30_000L;

    // 云仓库单个 HTTP 请求最大等待时间：防止网络卡死占用唯一后台线程
    private static final long REQUEST_TIMEOUT_MS = 10_000L;

    private CloudStoreUtils() {
    }

    public static synchronized boolean isRefillInCooldown() {
        return System.currentTimeMillis() < pendingUntilMillis;
    }

    /**
     * 按住"云仓库取货数量调整"快捷键时捕获滚轮，调整 PRINT_CLOUD_STORE_REFILL_AMOUNT。
     * 返回 true 表示已消费本次滚轮事件（调用方应取消原事件）。
     */
    public static boolean handleScrollAmount(double yOffset) {
        if (yOffset == 0) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.screen != null || client.player == null) {
            return false;
        }
        if (!ModUtils.isCloudStoreLoaded()) {
            return false;
        }
        if (!Configs.Hotkeys.REFILL_AMOUNT_ADJUST.getKeybind().isKeybindHeld()) {
            return false;
        }
        int delta = yOffset > 0 ? 1 : -1;
        if (Configs.Special.REFILL_SCROLL_REVERSE.getBooleanValue()) {
            delta = -delta;
        }
        fi.dy.masa.malilib.config.options.ConfigInteger config =
                Configs.Special.PRINT_CLOUD_STORE_REFILL_AMOUNT;
        int value = Math.max(config.getMinIntegerValue(),
                Math.min(config.getMaxIntegerValue(), config.getIntegerValue() + delta));
        config.setIntegerValue(value);
        MessageUtils.setOverlayMessage("[打印机] 云仓库单次取货数量: " + value);
        return true;
    }

    /**
     * 按下"云仓库取货数量调整"快捷键时显示当前数量。
     */
    public static void showRefillAmountOverlay() {
        MessageUtils.setOverlayMessage("[打印机] 云仓库单次取货数量: "
                + Configs.Special.PRINT_CLOUD_STORE_REFILL_AMOUNT.getIntegerValue());
    }

    /**
     * 为一批缺货材料发起云仓库取货订单。调用方应放在游戏主线程。
     * 冷却期间直接拒绝；提交成功后按配置进入冷却。
     */
    public static boolean tryRequestRefillMany(LocalPlayer player, Collection<Item> missingItems, int amount) {
        if (player == null || missingItems == null || missingItems.isEmpty() || amount <= 0) {
            return false;
        }
        List<Item> snapshot;
        final long token;
        synchronized (CloudStoreUtils.class) {
            if (isRefillInCooldown()) {
                return false;
            }
            snapshot = new ArrayList<>(missingItems);
            orderedItems.addAll(snapshot);
            autoOrderSubmittedAt = System.currentTimeMillis();
            token = ++orderToken;
            // 先按失败短冷却占位，成功提交后再替换为配置长冷却
            pendingUntilMillis = System.currentTimeMillis() + REFILL_COOLDOWN_MS;
        }
        String playerName = player.getName().getString();
        EXECUTOR.execute(() -> runRefill(snapshot, playerName, amount, false, token));
        // 立即反馈：后台 HTTP 请求链（登录/查仓/取货）完成前先提示已提交
        MessageUtils.setOverlayMessage("[打印机] 已提交补货请求：" + snapshot.size() + " 种材料，每种 x" + amount);
        return true;
    }

    /**
     * 鼠标中键取货：不受冷却计时器限制，立即下单点击方块的材料（只取这一格的材料，不下批量单），
     * 也不启动/重置冷却计时器。冷却计时器只影响打印机自动补货。
     * 手动订单到货后仍会强制结束冷却（tickArrivalCheck），让自动补货尽快恢复。
     */
    public static boolean tryRequestRefillImmediate(LocalPlayer player, Item item, int amount) {
        if (player == null || item == null || item == Items.AIR || amount <= 0) {
            return false;
        }
        // 同一种材料可同时多次取货，不做去重限制
        synchronized (CloudStoreUtils.class) {
            manualOrdered.add(item);
        }
        List<Item> snapshot = new ArrayList<>();
        snapshot.add(item);
        String playerName = player.getName().getString();
        EXECUTOR.execute(() -> runRefill(snapshot, playerName, amount, true, 0L));
        // 立即反馈：后台 HTTP 请求链（登录/查仓/取货）完成前先提示已提交
        MessageUtils.setOverlayMessage("[打印机] 已提交取货请求：" + item.getName(net.minecraft.world.item.ItemStack.EMPTY).getString() + " x" + amount);
        return true;
    }

    /**
     * 每游戏刻在主线程调用：已到背包的目标材料被移除。
     * 自动订单的材料到包后立即结束冷却；手动订单的材料到包后也强制结束冷却，
     * 让打印机的自动补货能继续工作。
     */
    public static synchronized void tickArrivalCheck(@Nullable LocalPlayer player) {
        if (player == null) {
            return;
        }
        boolean arrived = false;
        // 手动订单在途材料：到包即移除（与冷却无关）
        Iterator<Item> manualIterator = manualOrdered.iterator();
        while (manualIterator.hasNext()) {
            Item item = manualIterator.next();
            if (InventoryUtils.countMatchingMainInventory(player, stack -> stack.is(item)) > 0) {
                manualIterator.remove();
                arrived = true;
            }
        }
        // 自动订单在途材料：到包即移除（先于任何清理判断，保证到货能被检测到）
        Iterator<Item> iterator = orderedItems.iterator();
        while (iterator.hasNext()) {
            Item item = iterator.next();
            if (InventoryUtils.countMatchingMainInventory(player, stack -> stack.is(item)) > 0) {
                iterator.remove();
                arrived = true;
            }
        }
        // 只在订单确实陈旧（提交时间超过冷却+缓冲）而无果时才清理，避免后台订单未完成
        // （登录/查仓/取货 HTTP 链 > 短冷却）时提前清空导致到货检测永久失效。
        if (!orderedItems.isEmpty()
                && autoOrderSubmittedAt > 0L
                && System.currentTimeMillis() - autoOrderSubmittedAt > getOrderStaleThresholdMs()) {
            orderedItems.clear();
        }
        if (!arrived) {
            return;
        }
        if (isRefillInCooldown()) {
            pendingUntilMillis = 0L;
        }
        if (orderedItems.isEmpty()) {
            MessageUtils.setOverlayMessage("[打印机] 已收到云仓库材料，补货冷却结束");
        } else {
            MessageUtils.setOverlayMessage("[打印机] 已收到部分云仓库材料");
        }
    }

    /**
     * 自动订单在途视为陈旧的时间阈值：短冷却与配置长冷却取大者，再加 60 秒缓冲。
     */
    private static long getOrderStaleThresholdMs() {
        long threshold = REFILL_COOLDOWN_MS;
        try {
            threshold = Math.max(threshold,
                    Configs.Special.PRINT_CLOUD_STORE_REFILL_COOLDOWN.getIntegerValue() * 1000L);
        } catch (Throwable ignored) {
        }
        return threshold + 60_000L;
    }

    private static void runRefill(List<Item> missingItems, String playerName, int amount, boolean manual, long token) {
        String message = null;
        boolean success = false;
        try {
            Object api = getApi();
            if (api == null) {
                message = "云仓库未加载，无法自动补充材料";
                return;
            }
            // 已登录（会话 Cookie 非空）时优先从本地缓存取 ownerUuid，免去 connect() 请求；
            // 未登录才调用 connect()（登录 + 获取仓库）。
            String ownerUuid = null;
            String sessionCookie = readPrivateField(api, "sessionCookie");
            if (sessionCookie != null && !sessionCookie.isBlank()) {
                Object cachedMe = callCacheMethod("me", getCacheEndpoint());
                if (cachedMe != null) {
                    ownerUuid = readField(cachedMe, "defaultOwnerUuid");
                }
            }
            if (ownerUuid == null || ownerUuid.isBlank()) {
                Object me = await(api, "connect");
                ownerUuid = readField(me, "defaultOwnerUuid");
            }
            if (ownerUuid == null || ownerUuid.isBlank()) {
                message = "云仓库未登录或没有可用仓库";
                return;
            }

            // 库存只读云仓库 mod 的本地缓存，不做实时查询
            Object cachedInventory = callCacheMethod("inventory", getCacheEndpoint(), ownerUuid);
            List<?> cachedItems = cachedInventory == null ? null : readList(cachedInventory, "items");
            if (cachedItems == null) {
                message = "云仓库库存缓存为空，请先打开云仓库界面刷新缓存";
                return;
            }
            List<Object> requests = new ArrayList<>();
            List<String> missingIds = new ArrayList<>();
            for (Item missing : missingItems) {
                String missingId = BuiltInRegistries.ITEM.getKey(missing).toString();
                missingIds.add(missingId);
                Object matched = findItem(cachedItems, missingId);
                if (matched != null) {
                    requests.add(newWithdrawRequest(matched, amount));
                }
            }

            if (requests.isEmpty()) {
                message = "云仓库中没有这些材料：" + String.join("、", missingIds);
                return;
            }
            if (requests.size() < missingItems.size()) {
                message = "部分材料云仓库缺货，仅提交有货的 " + requests.size() + " 种";
            }
            Object response = withdrawMany(api, ownerUuid, requests, playerName);
            Boolean ok = readBoolean(response, "ok");
            if (Boolean.TRUE.equals(ok)) {
                message = (message == null
                        ? "已从云仓库申请取货 " + requests.size() + " 种材料，每种 x" + amount
                        : message + "，每种 x" + amount);
                success = true;
            } else {
                message = (message == null ? "云仓库取货失败" : message + "失败")
                        + "：" + readField(response, "error");
            }
        } catch (Throwable error) {
            message = "云仓库取货失败：" + safeMessage(error);
        } finally {
            final String finalMessage = message;
            if (finalMessage != null) {
                Minecraft.getInstance().execute(() ->
                        MessageUtils.setOverlayMessage("[打印机] " + finalMessage)
                );
            }
            if (manual) {
                // 手动订单不启动/重置冷却计时器；失败则解除在途标记，允许再次中键取货
                if (!success) {
                    synchronized (CloudStoreUtils.class) {
                        manualOrdered.removeAll(missingItems);
                    }
                }
                return;
            }
            long cooldown;
            if (success) {
                cooldown = Configs.Special.PRINT_CLOUD_STORE_REFILL_COOLDOWN.getIntegerValue() * 1000L;
            } else {
                cooldown = REFILL_COOLDOWN_MS;
            }
            synchronized (CloudStoreUtils.class) {
                // 仅当仍是当前订单（未被更新的订单取代）时才写入冷却，防止过期订单的收尾
                // 覆盖新订单的冷却；且订单材料已全部到包（tickArrivalCheck 已清空 orderedItems
                // 并清零冷却）时不再重新拉长冷却，保证"到货即结束冷却"不被后台线程撤销。
                if (orderToken == token && !(success && orderedItems.isEmpty())) {
                    pendingUntilMillis = System.currentTimeMillis() + cooldown;
                }
            }
        }
    }

    @Nullable
    private static Object findItem(List<?> items, String itemId) {
        if (items == null) {
            return null;
        }
        // 顶层条目：散装 amount 或潜影盒内 boxedAmount 有货都算有
        for (Object entry : items) {
            if (entry == null) continue;
            if (itemId.equals(readField(entry, "itemId"))
                    && readLong(entry, "amount") + readLong(entry, "boxedAmount") > 0L
                    && matchesShulkerExpectation(entry, itemId)) {
                return entry;
            }
        }
        // 仅在潜影盒内（无顶层条目）的物品：搜索各条目的 shulkerContents
        for (Object entry : items) {
            if (entry == null) continue;
            Object inner = findItem(readList(entry, "shulkerContents"), itemId);
            if (inner != null) {
                return inner;
            }
        }
        return null;
    }

    /**
     * 目标是潜影盒时，只匹配空盒条目（无盒内容物、无盒内数量），避免取到装有物品的盒子。
     * 非潜影盒物品不受影响。
     */
    private static boolean matchesShulkerExpectation(Object entry, String itemId) {
        if (!isShulkerId(itemId)) {
            return true;
        }
        List<?> contents = readList(entry, "shulkerContents");
        return readLong(entry, "boxedAmount") == 0L && (contents == null || contents.isEmpty());
    }

    private static boolean isShulkerId(String itemId) {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
            return item != null && item != Items.AIR && Block.byItem(item) instanceof ShulkerBoxBlock;
        } catch (Throwable error) {
            return false;
        }
    }

    private static Object newWithdrawRequest(Object matchedItem, int amount) throws Exception {
        Class<?> itemClass = Class.forName(ITEM_CLASS);
        Class<?> requestClass = Class.forName(REQUEST_CLASS);
        Constructor<?> constructor = requestClass.getConstructor(itemClass, long.class);
        return constructor.newInstance(matchedItem, (long) amount);
    }

    private static Object withdrawMany(Object api, String ownerUuid, List<?> requests, String playerName)
            throws Exception {
        Method method = null;
        for (Method candidate : api.getClass().getMethods()) {
            if (candidate.getName().equals("withdrawMany") && candidate.getParameterCount() == 3) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            throw new IllegalStateException("cloud-store 方法 withdrawMany 不存在");
        }
        Object future = method.invoke(api, ownerUuid, requests, playerName);
        if (future instanceof CompletableFuture<?> completableFuture) {
            return completableFuture.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        throw new IllegalStateException("cloud-store 方法 withdrawMany 未返回异步结果");
    }

    @Nullable
    private static Object getApi() throws ReflectiveOperationException {
        Class<?> clazz = Class.forName(CLOUD_STORE_CLASS);
        Field field = clazz.getField("api");
        return field.get(null);
    }

    /**
     * 与 CloudStoreConfig.normalizeUrl 相同的归一化规则，用于命中 mod 的本地缓存。
     */
    private static String getCacheEndpoint() {
        try {
            Class<?> clazz = Class.forName(CLOUD_STORE_CLASS);
            Object config = clazz.getField("config").get(null);
            String value = readField(config, "apiBaseUrl");
            if (value == null || value.isBlank()) {
                return "http://127.0.0.1:8787";
            }
            String result = value.trim();
            if (!result.startsWith("http://") && !result.startsWith("https://")) {
                result = "http://" + result;
            }
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        } catch (Throwable error) {
            return "http://127.0.0.1:8787";
        }
    }

    /**
     * 调用 CloudStoreClient.cache 的公开方法（me / inventory），失败或未命中返回 null。
     */
    @Nullable
    private static Object callCacheMethod(String methodName, Object... args) {
        try {
            Class<?> clazz = Class.forName(CLOUD_STORE_CLASS);
            Object cache = clazz.getField("cache").get(null);
            if (cache == null) {
                return null;
            }
            Method method = null;
            for (Method candidate : cache.getClass().getMethods()) {
                if (candidate.getName().equals(methodName) && candidate.getParameterCount() == args.length) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) {
                return null;
            }
            Object result = method.invoke(cache, args);
            if (result instanceof List<?> list) {
                return list;
            }
            return result;
        } catch (Throwable error) {
            return null;
        }
    }

    @Nullable
    private static String readPrivateField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static Object await(Object api, String methodName, Object... args) throws Exception {
        Method method = null;
        for (Method candidate : api.getClass().getMethods()) {
            if (candidate.getName().equals(methodName) && candidate.getParameterCount() == args.length) {
                method = candidate;
                break;
            }
        }
        if (method == null) {
            throw new IllegalStateException("cloud-store 方法 " + methodName + " 不存在");
        }
        Object future = method.invoke(api, args);
        if (future instanceof CompletableFuture<?> completableFuture) {
            return completableFuture.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        throw new IllegalStateException("cloud-store 方法 " + methodName + " 未返回异步结果");
    }

    @Nullable
    private static List<?> readList(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            Object value = field.get(target);
            return value instanceof List<?> list ? list : null;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    @Nullable
    private static String readField(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            Object value = field.get(target);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static long readLong(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            Object value = field.get(target);
            return value instanceof Number number ? number.longValue() : 0L;
        } catch (ReflectiveOperationException error) {
            return 0L;
        }
    }

    @Nullable
    private static Boolean readBoolean(Object target, String name) {
        try {
            Field field = target.getClass().getField(name);
            Object value = field.get(target);
            return value instanceof Boolean bool ? bool : null;
        } catch (ReflectiveOperationException error) {
            return null;
        }
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}