package me.aleksilassila.litematica.printer.utils;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModUtils {
    // 阻止 UI 显示 如果此时已经在 UI 中 请设置为 2 因为关闭 UI 也会调用一次
    public static int closeScreen = 0;

    // 内置视频（"千万别点"B 按钮）释放到 config 目录下的这个子文件夹，长期保留、不再删除
    private static final String VIDEO_DIR_NAME = "litematica_printer_EMT";
    private static final String VIDEO_FILE_NAME = "video.mp4";
    // 旧方案（释放到系统临时目录）的临时文件前缀，仅用于清理历史残留
    private static final String VIDEO_TEMP_PREFIX = "litematica_printer_video_";
    // 视频只存于 versionpack 外壳 jar 一份（findPath 路径相对 mod 根，不带前导斜杠），三个内层版本共用
    private static final String VIDEO_RESOURCE = "assets/litematica-printer/video.mp4";
    // 流式读取用的固定缓冲区大小（校验与释放都只占这么多内存）
    private static final int COPY_BUFFER_SIZE = 8192;

    /**
     * 查找 jar 内置视频：遍历所有已加载 mod 的资源（视频存于 versionpack 外壳，避免三个内层版本重复打包 21MB）。
     * @return 视频路径；找不到返回 null
     */
    private static Path findBundledVideo() {
        for (var container : FabricLoader.getInstance().getAllMods()) {
            var path = container.findPath(VIDEO_RESOURCE);
            if (path.isPresent()) {
                return path.get();
            }
        }
        return null;
    }

    /**
     * A 按钮：用系统浏览器打开整蛊网页，成功打开后立刻让游戏崩溃。
     * 供配置 GUI 按钮（ConfigUi.DangerWidget）与配置加载兜底监听器（Configs.Danger）共用。
     */
    public static void openTrollVideoUrl() {
        net.minecraft.util.Util.getPlatform().openUri("https://www.bilibili.com/video/BV1UT42167xb");
        crashNow();
    }

    /**
     * B 按钮：把 jar 内置视频释放到 config/litematica_printer_EMT/video.mp4（长期保留）后，
     * 交给系统默认播放器打开，并立刻让游戏崩溃（播放继续由外部播放器进程完成，与游戏进程无关）。
     * 释放前先校验落地文件与内置视频是否一致，文件缺失或被用户替换时重新释放，
     * 保证播放的始终是 mod 内置的那份视频。
     */
    public static void playBundledVideo() {
        Path source = findBundledVideo();
        if (source == null) {
            return;
        }
        try {
            Path dir = FabricLoader.getInstance().getConfigDir().resolve(VIDEO_DIR_NAME);
            Path target = dir.resolve(VIDEO_FILE_NAME);
            if (!hasSameContent(target, source)) {
                Files.createDirectories(dir);
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
            net.minecraft.util.Util.getPlatform().openUri(target.toUri());
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        crashNow();
    }

    /**
     * 内置视频的“指纹”：未压缩长度 + CRC32，足以唯一确定内容。
     */
    private record Fingerprint(long size, long crc) {
    }

    /**
     * 取内置视频的指纹：视频在外壳 jar 里，直接读 zip 中央目录记录的长度与 CRC32——
     * 不解压、不读字节，开销可忽略（7MB 视频在外壳 jar 里是 deflate 存储的，完整解压一次要十几毫秒）。
     * 视频不在真实 jar 文件里时（开发环境 classpath 目录）返回 null，调用方退回流式读取。
     */
    private static Fingerprint fingerprintOfBundledVideo() {
        for (var container : FabricLoader.getInstance().getAllMods()) {
            if (container.findPath(VIDEO_RESOURCE).isEmpty()) {
                continue;
            }
            for (Path origin : container.getOrigin().getPaths()) {
                if (!Files.isRegularFile(origin)) {
                    continue;
                }
                try (ZipFile zip = new ZipFile(origin.toFile())) {
                    ZipEntry entry = zip.getEntry(VIDEO_RESOURCE);
                    if (entry != null) {
                        return new Fingerprint(entry.getSize(), entry.getCrc());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /**
     * 校验落地视频与内置视频是否同一份内容（先比长度，再比 CRC32），全程流式读取，不整份加载。
     * @return 文件不存在、不可读或内容不符时返回 false（调用方据此重新释放）
     */
    private static boolean hasSameContent(Path target, Path source) {
        try {
            if (!Files.isRegularFile(target)) {
                return false;
            }
            Fingerprint expected = fingerprintOfBundledVideo();
            if (expected != null) {
                return Files.size(target) == expected.size() && crc32(target) == expected.crc();
            }
            return Files.size(target) == Files.size(source) && crc32(target) == crc32(source);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 流式计算文件（含 jar 内条目）的 CRC32，只用固定大小的缓冲区。
     */
    private static long crc32(Path file) throws Exception {
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    /**
     * 整蛊收尾：主动调用原版 Minecraft.emergencySaveAndCrash(CrashReport) 走官方崩溃流程——
     * 生成崩溃报告文件并显示崩溃界面，最终退出游戏进程。
     * 不依赖异常冒泡：26.x 新输入系统的事件分发会吞掉界面回调里的异常（只记日志），
     * 点击回调里单纯 throw 无法触发崩溃。
     */
    public static void crashNow() {
        net.minecraft.client.Minecraft.getInstance()
                .emergencySaveAndCrash(new net.minecraft.CrashReport("You were told NOT to click that! (千万别点！！！)",
                        new RuntimeException("千万别点！！！")));
        // 正常情况下 emergencySaveAndCrash() 不会返回；万一返回（被上层拦截），再抛一次兜底
        throw new RuntimeException("You were told NOT to click that! (千万别点！！！)");
    }

    /**
     * 清理旧方案（释放到系统临时目录）遗留的临时视频文件，避免历史残留长期占空间。
     * 现方案把视频释放到 config 目录且长期保留，不产生临时文件。
     */
    public static void cleanBundledVideoTemp() {
        try {
            File tmp = new File(System.getProperty("java.io.tmpdir"));
            File[] leftovers = tmp.listFiles((dir, name) ->
                    name.startsWith(VIDEO_TEMP_PREFIX) && name.endsWith(".mp4"));
            if (leftovers != null) {
                for (File f : leftovers) {
                    try {
                        Files.deleteIfExists(f.toPath());
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public static boolean isLoadMod(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static boolean isCloudStoreLoaded() {
        return isLoadMod("cloudstore");
    }

    public static boolean isBedrockMinerLoaded() {
        //#if MC >= 11900
        return isLoadMod("bedrockminer");
        //#else
        //$$ return false;
        //#endif
    }

    public static boolean isBlockMinerLoaded() {
        //#if MC >= 11605
        return isLoadMod("blockminer");
        //#else
        //$$ return false;
        //#endif
    }

    public static boolean isTweakerooLoaded() {
        return isLoadMod("tweakeroo");
    }

    private static @Nullable Object tweakToolSwitchEnum;
    private static @Nullable Object tweakSwapAlmostBrokenToolsEnum;
    private static @Nullable Object disableBlockBreakCooldownConfig;
    private static @Nullable Object itemSwapDurabilityThresholdConfig;
    private static @Nullable Method trySwitchToEffectiveToolMethod;
    private static @Nullable Method trySwapCurrentToolIfNearlyBrokenMethod;
    private static @Nullable Method getBooleanValueMethod;
    private static @Nullable Method getIntegerValueMethod;

    static {
        if (FabricLoader.getInstance().isModLoaded("tweakeroo")) {
            try {
                Class<?> featureToggleClass = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle");
                tweakToolSwitchEnum = featureToggleClass.getField("TWEAK_TOOL_SWITCH").get(null);
                tweakSwapAlmostBrokenToolsEnum = featureToggleClass.getField("TWEAK_SWAP_ALMOST_BROKEN_TOOLS").get(null);

                Class<?> disableConfigsClass = Class.forName("fi.dy.masa.tweakeroo.config.Configs$Disable");
                disableBlockBreakCooldownConfig = disableConfigsClass.getField("DISABLE_BLOCK_BREAK_COOLDOWN").get(null);

                Class<?> genericConfigsClass = Class.forName("fi.dy.masa.tweakeroo.config.Configs$Generic");
                itemSwapDurabilityThresholdConfig = genericConfigsClass.getField("ITEM_SWAP_DURABILITY_THRESHOLD").get(null);

                Class<?> iConfigBooleanClass = Class.forName("fi.dy.masa.malilib.config.IConfigBoolean");
                getBooleanValueMethod = iConfigBooleanClass.getDeclaredMethod("getBooleanValue");
                getIntegerValueMethod = itemSwapDurabilityThresholdConfig.getClass().getMethod("getIntegerValue");

                Class<?> inventoryUtilsClass = Class.forName("fi.dy.masa.tweakeroo.util.InventoryUtils");
                trySwitchToEffectiveToolMethod = inventoryUtilsClass.getDeclaredMethod("trySwitchToEffectiveTool", BlockPos.class);
                trySwapCurrentToolIfNearlyBrokenMethod = inventoryUtilsClass.getDeclaredMethod("trySwapCurrentToolIfNearlyBroken");

            } catch (Exception e) {
                tweakToolSwitchEnum = null;
                tweakSwapAlmostBrokenToolsEnum = null;
                disableBlockBreakCooldownConfig = null;
                itemSwapDurabilityThresholdConfig = null;
                trySwitchToEffectiveToolMethod = null;
                trySwapCurrentToolIfNearlyBrokenMethod = null;
                getBooleanValueMethod = null;
                getIntegerValueMethod = null;
                e.printStackTrace();
            }
        }
    }

    /**
     * 检查 Tweakeroo 的 TWEAK_TOOL_SWITCH 选项是否启用。
     * @return 如果 Tweakeroo 存在且选项启用，则返回 true，否则返回 false。
     */
    public static boolean isToolSwitchEnabled() {
        if (getBooleanValueMethod == null || tweakToolSwitchEnum == null) {
            return false;
        }
        try {
            return (boolean) getBooleanValueMethod.invoke(tweakToolSwitchEnum);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isDisableBlockBreakCooldownEnabled() {
        if (getBooleanValueMethod == null || disableBlockBreakCooldownConfig == null) {
            return false;
        }
        try {
            return (boolean) getBooleanValueMethod.invoke(disableBlockBreakCooldownConfig);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean isSwapAlmostBrokenToolsEnabled() {
        if (getBooleanValueMethod == null || tweakSwapAlmostBrokenToolsEnum == null) {
            return false;
        }
        try {
            return (boolean) getBooleanValueMethod.invoke(tweakSwapAlmostBrokenToolsEnum);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 调用 Tweakeroo 的 InventoryUtils.trySwitchToEffectiveTool(BlockPos pos) 静态方法。
     * 只有在 Tweakeroo 存在且方法被成功加载时才执行。
     * @param pos 要挖掘的方块位置
     */
    public static void trySwitchToEffectiveTool(BlockPos pos) {
        if (trySwitchToEffectiveToolMethod == null) {
            return;
        }
        try {
            trySwitchToEffectiveToolMethod.invoke(null, pos);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void trySwapCurrentToolIfNearlyBroken() {
        if (trySwapCurrentToolIfNearlyBrokenMethod == null) {
            return;
        }
        try {
            trySwapCurrentToolIfNearlyBrokenMethod.invoke(null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isToolTooDamagedForBreaking(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || !isSwapAlmostBrokenToolsEnabled()) {
            return false;
        }
        int remainingDurability = stack.getMaxDamage() - stack.getDamageValue();
        return remainingDurability <= getMinDurability(stack);
    }

    public static int getSafeBreakBudget(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.isDamageableItem() || !isSwapAlmostBrokenToolsEnabled()) {
            return Integer.MAX_VALUE;
        }
        int remainingDurability = stack.getMaxDamage() - stack.getDamageValue();
        return Math.max(0, remainingDurability - getMinDurability(stack));
    }

    private static int getMinDurability(ItemStack stack) {
        int threshold = getItemSwapDurabilityThreshold();
        int maxDamage = stack.getMaxDamage();
        if (maxDamage <= 100 && threshold <= 20 && (double) threshold / (double) maxDamage > 0.08D) {
            threshold = (int) Math.ceil((double) maxDamage * 0.08D);
        }
        return threshold;
    }

    private static int getItemSwapDurabilityThreshold() {
        if (getIntegerValueMethod == null || itemSwapDurabilityThresholdConfig == null) {
            return 5;
        }
        try {
            return (int) getIntegerValueMethod.invoke(itemSwapDurabilityThresholdConfig);
        } catch (Exception e) {
            e.printStackTrace();
            return 5;
        }
    }

}
