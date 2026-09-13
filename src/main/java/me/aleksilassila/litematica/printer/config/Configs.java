package me.aleksilassila.litematica.printer.config;

import com.google.common.collect.ImmutableList;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.*;
import fi.dy.masa.malilib.config.options.*;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import fi.dy.masa.malilib.util.restrictions.UsageRestriction;
import fi.dy.masa.malilib.config.ConfigManager;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.Blocks;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BooleanSupplier;

//#if MC >= 12111
import fi.dy.masa.malilib.util.data.json.JsonUtils;
//#else
//$$ import fi.dy.masa.malilib.util.JsonUtils;
//#endif
public class Configs extends ConfigBuilders implements IConfigHandler {
    private static final Configs INSTANCE = new Configs();

    private static final String FILE_PATH = "./config/" + Reference.MOD_ID + ".json";
    private static final File CONFIG_DIR = new File("./config");

    private static final KeybindSettings GUI_NO_ORDER = KeybindSettings.create(KeybindSettings.Context.GUI, KeyAction.PRESS, false, false, false, true);

    // 配置页面是否可视(函数式, 动态获取, 全局统一使用)
    private static final BooleanSupplier isLoadChestTrackerLoaded = ModUtils::isChestTrackerLoaded;
    private static final BooleanSupplier isLoadCloudStoreLoaded = ModUtils::isCloudStoreLoaded;
    private static final BooleanSupplier isSingle = () -> Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.SINGLE);
    private static final BooleanSupplier isMulti = () -> Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI);

    private static final BooleanSupplier isBreakCustom = () -> Mine.BREAK_LIMITER.getOptionListValue().equals(ExcavateListMode.CUSTOM);
    private static final BooleanSupplier isBreakWhitelist = () -> isBreakCustom.getAsBoolean() && Mine.BREAK_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.WHITELIST);
    private static final BooleanSupplier isBreakBlacklist = () -> isBreakCustom.getAsBoolean() && Mine.BREAK_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.BLACKLIST);


    private static final BooleanSupplier isExcavateCustom = () -> Mine.EXCAVATE_LIMITER.getOptionListValue().equals(ExcavateListMode.CUSTOM);
    private static final BooleanSupplier isExcavateWhitelist = () -> isExcavateCustom.getAsBoolean() && Mine.EXCAVATE_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.WHITELIST);
    private static final BooleanSupplier isExcavateBlacklist = () -> isExcavateCustom.getAsBoolean() && Mine.EXCAVATE_LIMIT.getOptionListValue().equals(UsageRestriction.ListType.BLACKLIST);
    private static final BooleanSupplier isBlocklist = () -> Fill.FILL_BLOCK_MODE.getOptionListValue().equals(FillBlockModeType.BLOCKLIST);


    public static final ImmutableList<IConfigBase> OPTIONS;
    public static final ImmutableList<IHotkey> HOTKEYS;


    static {
        LinkedHashSet<IConfigBase> optionSet = new LinkedHashSet<>();
        optionSet.addAll(Core.OPTIONS);           // 核心
        optionSet.addAll(Special.OPTIONS);        // 特殊
        optionSet.addAll(Hotkeys.OPTIONS);        // 热键
        optionSet.addAll(Print.OPTIONS);          // 打印
        optionSet.addAll(Mine.OPTIONS);           // 挖掘
        optionSet.addAll(Fill.OPTIONS);           // 填充
        optionSet.addAll(Fluid.OPTIONS);          // 排流体
        OPTIONS = ImmutableList.copyOf(optionSet);

        List<IHotkey> hotkeys = new ArrayList<>();
        for (IConfigBase option : optionSet) {
            if (option instanceof IHotkey hokey) {
                hotkeys.add(hokey);
            }
        }
        HOTKEYS = ImmutableList.copyOf(hotkeys);
    }

    public static class Core {
        // 打印状态
        public static final ConfigBoolean WORK_SWITCH = bool("workingSwitch")
                .defaultValue(false)
                .build();

        // 核心 - 模式切换
        public static final ConfigOptionList WORK_MODE = optionList("modeSwitch")
                .defaultValue(WorkingModeType.SINGLE)
                .build();

        // 多模 - 打印
        public static final ConfigBoolean PRINT = bool("print")
                .defaultValue(false)
                .setVisible(isMulti) // 仅多模式时显示
                .build();

        // 多模 - 挖掘
        public static final ConfigBoolean MINE = bool("mine")
                .defaultValue(false)
                .setVisible(isMulti) // 仅多模式时显示
                .build();

        // 多模 - 填充
        public static final ConfigBoolean FILL = bool("fill")
                .defaultValue(false)
                .setVisible(isMulti) // 仅多模式时显示
                .build();

        // 多模 - 排流体
        public static final ConfigBoolean FLUID = bool("fluid")
                .defaultValue(false)
                .setVisible(isMulti) // 仅多模式时显示
                .build();

        // 核心 - 单模模式
        public static final ConfigOptionList WORK_MODE_TYPE = optionList("printerMode")
                .defaultValue(PrintModeType.PRINTER)
                .setVisible(isSingle) // 仅单模式时显示
                .build();

        // 核心 - 工作半径
        public static final ConfigInteger WORK_RANGE = integer("workRange")
                .defaultValue(6)
                .range(1, 256)
                .build();

        // 核心 - 迭代占用时长（毫秒）
        public static final ConfigInteger ITERATION_TIME_LIMIT = integer("iterationTimeLimit")
                .defaultValue(8)
                .range(0, 32)
                .build();

        // 验证器优化：开启后投影验证器改用优化版实现（按子区块分桶存储 + 后台线程扫描 +
        // 全路径时长预算），重进服务器后生效
        public static final ConfigBoolean VERIFIER_OPTIMIZED = bool("verifierOptimized")
                .defaultValue(false)
                .build();

        // 核心 - 检查玩家方块交互范围
        public static final ConfigBoolean CHECK_PLAYER_INTERACTION_RANGE = bool("checkPlayerInteractionRange")
                .defaultValue(true)
                .build();

        // 核心 - 延迟检测
        public static final ConfigBoolean LAG_CHECK = bool("printerLagCheck")
                .defaultValue(true)
                .build();

        public static final ConfigInteger LAG_CHECK_MAX = integer("printerLagCheckMax")
                .defaultValue(20)
                .setVisible(LAG_CHECK::getBooleanValue)
                .range(20, 1200)
                .build();

        // 核心 - 迭代区域形状
        public static final ConfigOptionList ITERATOR_SHAPE = optionList("printerIteratorShape")
                .defaultValue(RadiusShapeType.SPHERE)
                .build();

        // 核心 - 遍历顺序
        public static final ConfigOptionList ITERATION_ORDER = optionList("printerIteratorMode")
                .defaultValue(IterationOrderType.XZY)
                .build();

        // 核心 - 迭代X轴反向
        public static final ConfigBoolean X_REVERSE = bool("printerXAxisReverse")
                .defaultValue(false)
                .build();

        // 核心 - 迭代Y轴反向
        public static final ConfigBoolean Y_REVERSE = bool("printerYAxisReverse")
                .defaultValue(false)
                .build();

        // 核心 - 迭代Z轴反向
        public static final ConfigBoolean Z_REVERSE = bool("printerZAxisReverse")
                .defaultValue(false)
                .build();

        // 核心 - 运动感知扫描：高速移动时优先扫描移动方向，避免新进入的层漏扫
        public static final ConfigBoolean MOVE_ADAPTIVE_ITERATION = bool("moveAdaptiveIteration")
                .defaultValue(true)
                .build();

        // 核心 - 运动方向预扫余量（格）
        public static final ConfigInteger MOTION_AHEAD = integer("motionAheadBlocks")
                .defaultValue(3)
                .range(0, 16)
                .build();

        // 核心 - 显示打印机HUD
        public static final ConfigBoolean RENDER_HUD = bool("renderHud")
                .defaultValue(false)
                .build();

        // 核心 - 自动禁用打印机
        public static final ConfigBoolean AUTO_DISABLE_PRINTER = bool("printerAutoDisable")
                .defaultValue(true)
                .build();

        // 核心 - 进入服务器自启动打印机
        public static final ConfigBoolean AUTO_ENABLE_PRINTER = bool("printerAutoEnable")
                .defaultValue(false)
                .build();

        // 核心 - 检查更新
        public static final ConfigBoolean UPDATE_CHECK = bool("updateCheck")
                .defaultValue(true)
                .build();

        // 核心 - 调试输出
        public static final ConfigBoolean DEBUG_OUTPUT = bool("debugOutput")
                .defaultValue(false)
                .build();

        // 核心 - 快捷潜影盒-自动补货
        public static final ConfigBoolean HAND_RESTOCK_SHULKER_COMPAT = bool("handRestockShulkerCompat")
                .defaultValue(false)
                .build();

        // 核心 - 快捷潜影盒开关
        public static final ConfigBoolean QUICK_SHULKER = bool("quickShulker")
                .defaultValue(false)
                .build();

        // 核心 - 快捷潜影盒最大取货数量（物品堆数）
        public static final ConfigInteger QUICK_SHULKER_MAX_STACKS = integer("quickShulkerMaxStacks")
                .defaultValue(1)
                .range(1, 27)
                .build();

        // 核心 - 快捷潜影盒冷却时间
        public static final ConfigInteger QUICK_SHULKER_COOLDOWN = integer("quickShulkerCooldown")
                .defaultValue(10)
                .range(0, 20)
                .build();

        // 远程交互 - 开关
        public static final ConfigBoolean CLOUD_INVENTORY = bool("cloudInventory")
                .defaultValue(false)
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        // 远程交互 - 自动设置远程交互
        public static final ConfigBoolean AUTO_INVENTORY = bool("autoInventory")
                .defaultValue(false)
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        // 远程交互 - 库存白名单
        public static final ConfigStringList INVENTORY_LIST = stringList("inventoryList")
                .defaultValue(Blocks.CHEST)
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        // 容器同步与打印机添加库存高亮颜色
        public static final ConfigColor SYNC_INVENTORY_COLOR = color("syncInventoryColor")
                .defaultValue("#4CFF4CE6")
                .build();

        // 容器同步/库存高亮渲染距离（0 为不限制）
        public static final ConfigInteger SYNC_HIGHLIGHT_RENDER_DISTANCE = integer("syncHighlightRenderDistance")
                .defaultValue(64)
                .range(0, 256)
                .build();

        // 通用配置项列表（按功能分类排序）
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                WORK_SWITCH,
                WORK_MODE,
                WORK_MODE_TYPE,
                PRINT,
                MINE,
                FILL,
                FLUID,
                WORK_RANGE,
                ITERATION_TIME_LIMIT,
                VERIFIER_OPTIMIZED,
                RENDER_HUD,
                LAG_CHECK,
                LAG_CHECK_MAX,
                CHECK_PLAYER_INTERACTION_RANGE,
                ITERATOR_SHAPE,
                ITERATION_ORDER,
                X_REVERSE,
                Y_REVERSE,
                Z_REVERSE,
                MOVE_ADAPTIVE_ITERATION,
                MOTION_AHEAD,
                AUTO_DISABLE_PRINTER,
                AUTO_ENABLE_PRINTER,
                UPDATE_CHECK,
                DEBUG_OUTPUT,
                HAND_RESTOCK_SHULKER_COMPAT,
                QUICK_SHULKER,
                QUICK_SHULKER_MAX_STACKS,
                QUICK_SHULKER_COOLDOWN,
                CLOUD_INVENTORY,
                AUTO_INVENTORY,
                INVENTORY_LIST,
                SYNC_INVENTORY_COLOR,
                SYNC_HIGHLIGHT_RENDER_DISTANCE
        );
    }

    public static class Print {
        // 选区类型
        public static final ConfigOptionList PRINT_SELECTION_TYPE = optionList("printSelectionType")
                .defaultValue(SelectionType.LITEMATICA_RENDER_LAYER)
                .build();

        // 投影轻松放置协议
        public static final ConfigBoolean EASY_PLACE_PROTOCOL = bool("easyPlaceProtocol")
                .defaultValue(false)
                .build();

        // 凭空放置
        public static final ConfigBoolean PLACE_IN_AIR = bool("placeInAir")
                .defaultValue(true)
                .build();

        // 快速方向性方块放置（纯客户端原版交互优化）
        public static final ConfigBoolean PRINT_FAST_DIRECTIONAL_PLACEMENT = bool("printFastDirectionalPlacement")
                .defaultValue(false)
                .build();

        // 只打印空潜影盒
        public static final ConfigBoolean PRINT_ONLY_EMPTY_SHULKER = bool("printOnlyEmptyShulker")
                .defaultValue(false)
                .build();

        // 跳过潜影盒打印：直接跳过所有潜影盒的放置
        public static final ConfigBoolean PRINT_SKIP_SHULKER = bool("printSkipShulker")
                .defaultValue(false)
                .build();

        // 潜影盒后置：交换范围∩渲染层内普通方块（不含水/含水）未放完时跳过潜影盒的放置
        public static final ConfigBoolean PRINT_SHULKER_AFTER_ORDINARY = bool("printShulkerAfterOrdinary")
                .defaultValue(true)
                .build();

        // 强制放置方向（所有放置动作一律使用该方向，无视实际支撑面要求）
        public static final ConfigOptionList PLACE_DEFAULT_DIRECTION = optionList("placeDefaultDirection")
                .defaultValue(DefaultPlaceDirectionType.NONE)
                .build();

        // 铁轨形态修复
        public static final ConfigBoolean REPAIR_RAIL_SHAPE = bool("printRepairRailShape")
                .defaultValue(false)
                .build();

        // 跳过放置
        public static final ConfigBoolean PRINT_SKIP = bool("printSkip")
                .defaultValue(false)
                .build();

        // 跳过放置名单
        public static final ConfigStringList PRINT_SKIP_LIST = stringList("printSkipList")
                .build();

        // 扫描白名单：开启且列表非空时，打印只扫描/放置列表内方块
        public static final ConfigBoolean PRINT_SCAN_WHITELIST = bool("printScanWhitelist")
                .defaultValue(false)
                .build();

        // 扫描白名单列表（匹配格式同跳过放置名单）
        public static final ConfigStringList PRINT_SCAN_WHITELIST_LIST = stringList("printScanWhitelistList")
                .build();

        // 扫描自动寻路：需同时开启"扫描白名单"且列表非空；自动寻找待放白名单方块并用寻路载玩家过去
        public static final ConfigBoolean PRINT_SCAN_AUTOWALK = bool("printScanAutoWalk")
                .defaultValue(false)
                .build();

        // 子区块扫描顺序（BFS 相邻扩展的轴优先级，每轴先 + 后 -）
        public static final ConfigOptionList PRINT_SCAN_SECTION_ORDER = optionList("printScanSectionOrder")
                .defaultValue(SectionScanOrderType.XZY)
                .build();

        // 子区块扫描 X 轴反向：该轴扩展顺序改为先 - 后 +
        public static final ConfigBoolean PRINT_SCAN_X_REVERSE = bool("printScanXReverse")
                .defaultValue(false)
                .build();

        // 子区块扫描 Y 轴反向：该轴扩展顺序改为先 - 后 +
        public static final ConfigBoolean PRINT_SCAN_Y_REVERSE = bool("printScanYReverse")
                .defaultValue(false)
                .build();

        // 子区块扫描 Z 轴反向：该轴扩展顺序改为先 - 后 +
        public static final ConfigBoolean PRINT_SCAN_Z_REVERSE = bool("printScanZReverse")
                .defaultValue(false)
                .build();

        // 子区块扩展扫描算法：广度优先（逐层外扩）或深度优先（一路到底）
        public static final ConfigOptionList PRINT_SCAN_EXPAND_ALGO = optionList("printScanExpandAlgorithm")
                .defaultValue(SectionExpandAlgorithmType.BFS)
                .build();

        // 始终潜行
        public static final ConfigBoolean PRINT_FORCED_SNEAK = bool("printForcedSneak")
                .defaultValue(false)
                .build();

        // 保留打印耗材
        public static final ConfigBoolean PRINT_RESERVE_ITEMS = bool("printReserveItems")
                .defaultValue(false)
                .build();

        // 打印耗材保留数量
        public static final ConfigInteger PRINT_RESERVE_ITEM_COUNT = integer("printReserveItemCount")
                .defaultValue(1)
                .range(1, 64)
                .build();

        // 覆盖打印
        public static final ConfigBoolean PRINT_REPLACE = bool("printReplace")
                .defaultValue(true)
                .build();

        // 覆盖方块列表
        public static final ConfigStringList REPLACEABLE_LIST = stringList("printReplaceableList")
                .defaultValue(Blocks.SNOW, Blocks.LAVA, Blocks.WATER, Blocks.BUBBLE_COLUMN, Blocks.SHORT_GRASS)
                .build();

        // 代替放置：目标方块缺失时允许用"代替列表"中登记的方块顶替放置（优先使用目标本体）
        public static final ConfigBoolean SUBSTITUTE_PLACEMENT = bool("printSubstitutePlacement")
                .defaultValue(false)
                .build();

        // 代替列表：每行一条"代替1,代替2,...:目标"，行内可用 ; 分隔多条规则；
        // 名称严格匹配注册路径/完整ID/精确译名（不做模糊/拼音搜索），
        // "," ";" 与全角 "，" "；" "：" 均可作为分隔符
        public static final ConfigStringList SUBSTITUTE_LIST = stringList("printSubstituteList")
                .defaultValue(
                        "tube_coral:dead_tube_coral",
                        "tube_coral_block:dead_tube_coral_block",
                        "tube_coral_fan:dead_tube_coral_fan",
                        "tube_coral_wall_fan:dead_tube_coral_wall_fan",
                        "brain_coral:dead_brain_coral",
                        "brain_coral_block:dead_brain_coral_block",
                        "brain_coral_fan:dead_brain_coral_fan",
                        "brain_coral_wall_fan:dead_brain_coral_wall_fan",
                        "bubble_coral:dead_bubble_coral",
                        "bubble_coral_block:dead_bubble_coral_block",
                        "bubble_coral_fan:dead_bubble_coral_fan",
                        "bubble_coral_wall_fan:dead_bubble_coral_wall_fan",
                        "fire_coral:dead_fire_coral",
                        "fire_coral_block:dead_fire_coral_block",
                        "fire_coral_fan:dead_fire_coral_fan",
                        "fire_coral_wall_fan:dead_fire_coral_wall_fan",
                        "horn_coral:dead_horn_coral",
                        "horn_coral_block:dead_horn_coral_block",
                        "horn_coral_fan:dead_horn_coral_fan",
                        "horn_coral_wall_fan:dead_horn_coral_wall_fan"
                )
                .build();

        // 破冰放水
        public static final ConfigBoolean PRINT_ICE_FOR_WATER = bool("printIceForWater")
                .defaultValue(false)
                .build();

        // 优化放水逻辑：开启后破冰放水放置顺序后置（先放完交换范围∩渲染层内的普通方块再破冰放水）
        public static final ConfigBoolean PRINT_ICE_FOR_WATER_OPTIMIZED = bool("printIceForWaterOptimized")
                .defaultValue(false)
                .build();

        // 自动去皮
        public static final ConfigBoolean STRIP_LOGS = bool("printAutoStripLogs")
                .defaultValue(false)
                .build();

        // 音符盒自动调音
        public static final ConfigBoolean NOTE_BLOCK_TUNING = bool("printAutoTuning")
                .defaultValue(true)
                .build();

        // 侦测器安全放置
        public static final ConfigBoolean SAFELY_OBSERVER = bool("printSafelyObserver")
                .defaultValue(true)
                .build();

        // 堆肥桶自动填充
        public static final ConfigBoolean FILL_COMPOSTER = bool("printAutoFillComposter")
                .defaultValue(false)
                .build();

        // 堆肥桶白名单
        public static final ConfigStringList FILL_COMPOSTER_WHITELIST = stringList("printAutoFillComposterWhitelist")
                .setVisible(FILL_COMPOSTER::getBooleanValue)
                .build();

        // 农作物催熟
        public static final ConfigBoolean BONEMEAL_CROPS = bool("printBonemealCrops")
                .defaultValue(false)
                .build();

        // 农作物催熟连点次数
        public static final ConfigInteger BONEMEAL_CROPS_CLICKS = integer("printBonemealCropsClicks")
                .defaultValue(10)
                .range(1, 32)
                .setVisible(BONEMEAL_CROPS::getBooleanValue)
                .build();

        // 破坏错误方块
        public static final ConfigBoolean BREAK_WRONG_BLOCK = bool("printBreakWrongBlock")
                .defaultValue(false)
                .build();

        // 破坏多余方块
        public static final ConfigBoolean BREAK_EXTRA_BLOCK = bool("printBreakExtraBlock")
                .defaultValue(false)
                .build();

        // 破坏错误状态方块（实验性）
        public static final ConfigBoolean BREAK_WRONG_STATE_BLOCK = bool("printBreakWrongStateBlock")
                .defaultValue(false)
                .build();

        // 使用数据包打印
        public static final ConfigBoolean PRINT_USE_PACKET = bool("placeUsePacket")
                .defaultValue(false)
                .build();

        // 打印音效
        public static final ConfigBoolean PRINT_SOUND = bool("printSound")
                .defaultValue(true)
                .build();

        // 核心 - 工作间隔
        public static final ConfigInteger PLACE_INTERVAL = integer("placeInterval")
                .defaultValue(1)
                .range(0, 20)
                .build();

        // 每刻放置方块数
        public static final ConfigInteger PLACE_BLOCKS_PER_TICK = integer("placeBlocksPerTick")
                .defaultValue(1)
                .range(0, 256)
                .build();

        public static final ConfigBoolean PLACE_SAME_ITEM_FIRST = bool("placeSameItemFirst")
                .defaultValue(false)
                .build();

        public static final ConfigInteger ITEM_SWITCH_INTERVAL = integer("itemSwitchInterval")
                .defaultValue(0)
                .range(0, 200)
                .build();

        // 放置冷却
        public static final ConfigInteger PLACE_COOLDOWN = integer("placeCooldown")
                .defaultValue(3)
                .range(1, 64)
                .build();

        // 下落方块检查
        public static final ConfigBoolean FALLING_CHECK = bool("printFallingBlockCheck")
            .defaultValue(true)
            .build();

        // 储存管理 - 有序存放
        public static final ConfigBoolean STORE_ORDERLY = bool("storeOrderly")
                .defaultValue(false)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                PRINT_SELECTION_TYPE,
                EASY_PLACE_PROTOCOL,
                PLACE_IN_AIR,
                PRINT_FORCED_SNEAK,
                BREAK_WRONG_BLOCK,
                BREAK_EXTRA_BLOCK,
                BREAK_WRONG_STATE_BLOCK,
                PRINT_SKIP,
                PRINT_SKIP_LIST,
                PRINT_SCAN_WHITELIST,
                PRINT_SCAN_WHITELIST_LIST,
                PRINT_SCAN_AUTOWALK,
                PRINT_SCAN_SECTION_ORDER,
                PRINT_SCAN_X_REVERSE,
                PRINT_SCAN_Y_REVERSE,
                PRINT_SCAN_Z_REVERSE,
                PRINT_SCAN_EXPAND_ALGO,
                PRINT_REPLACE,
                REPLACEABLE_LIST,
                PRINT_ICE_FOR_WATER,
                PRINT_ICE_FOR_WATER_OPTIMIZED,
                SAFELY_OBSERVER,
                STRIP_LOGS,
                NOTE_BLOCK_TUNING,
                SUBSTITUTE_PLACEMENT,
                SUBSTITUTE_LIST,
                FILL_COMPOSTER,
                FILL_COMPOSTER_WHITELIST,
                BONEMEAL_CROPS,
                BONEMEAL_CROPS_CLICKS,
                PRINT_FAST_DIRECTIONAL_PLACEMENT,
                PRINT_ONLY_EMPTY_SHULKER,
                PRINT_SHULKER_AFTER_ORDINARY,
                PRINT_SKIP_SHULKER,
                PLACE_DEFAULT_DIRECTION,
                REPAIR_RAIL_SHAPE,
                PRINT_RESERVE_ITEMS,
                PRINT_RESERVE_ITEM_COUNT,
                // 原「放置方块」目录
                PRINT_USE_PACKET,
                PRINT_SOUND,
                PLACE_INTERVAL,
                PLACE_BLOCKS_PER_TICK,
                PLACE_SAME_ITEM_FIRST,
                ITEM_SWITCH_INTERVAL,
                PLACE_COOLDOWN,
                FALLING_CHECK,
                STORE_ORDERLY
        );
    }

    public static class Special {
        // 信标效果限制绕过
        public static final ConfigBoolean UNLOCK_BEACON_EFFECTS = bool("unlockBeaconEffects")
                .defaultValue(false)
                .build();

        // Tweakeroo - 放宽凭空放置限制
        public static final ConfigBoolean TWEAKEROO_ANGEL_BLOCK_MAY_BUILD = bool("tweakerooAngelBlockMayBuild")
                .defaultValue(false)
                .setVisible(ModUtils::isTweakerooLoaded)
                .build();

        // 自动寻路：单次寻路计算时长预算（毫秒），后台线程执行
        public static final ConfigInteger GO_TIME_LIMIT = integer("goTimeLimit")
                .defaultValue(500)
                .range(100, 2000)
                .build();

        // 自动寻路：允许走下的最大下落高度（格）
        public static final ConfigInteger GO_MAX_FALL = integer("goMaxFall")
                .defaultValue(3)
                .range(1, 10)
                .build();

        // 自动寻路 - 最大速度：寻路移动的速度上限（格/秒），超过疾跑全速的值等效不限速
        public static final ConfigDouble GO_MAX_SPEED = doubleValue("goMaxSpeed")
                .defaultValue(5.7D)
                .range(0.5D, 10.0D)
                .build();

        // 自动寻路 - 强制疾跑：始终请求疾跑（等效一直按住 Ctrl），能否真正冲刺由原版条件决定
        public static final ConfigBoolean GO_FORCE_SPRINT = bool("goForceSprint")
                .defaultValue(false)
                .build();

        // 自动寻路 - 接管视角：寻路期间把客户端偏航角转向路线方向（只改 yaw，不动俯仰）
        public static final ConfigBoolean GO_TAKEOVER_VIEW = bool("goTakeoverView")
                .defaultValue(false)
                .build();

        // 自动寻路 - 角度偏转：接管视角时在路线方向上附加的偏航角偏移（度，整数，0 = 正对路线方向；
        // 跑酷起跳的瞬间会临时归零以保证起跳朝向与疾跑）
        public static final ConfigInteger GO_VIEW_OFFSET = integer("goViewOffset")
                .defaultValue(0)
                .range(-180, 180)
                .build();

        // 自动寻路 - 偏离判停：寻路期间持续检查玩家到剩余路径的距离，超过阈值立即停止任务
        public static final ConfigBoolean GO_DEVIATION_STOP = bool("goDeviationStop")
                .defaultValue(true)
                .build();

        // 自动寻路 - 偏离阈值：触发判停的距离（格，整数）。着地/入水比三维距离；
        // 外力腾空（被击退/冲走/失足）只比水平距离；自主跳跃（跑酷/跳上/出水跳）
        // 的空中阶段放行
        public static final ConfigInteger GO_DEVIATION_DISTANCE = integer("goDeviationDistance")
                .defaultValue(1)
                .range(1, 16)
                .build();

        // 仅渲染方块：开启后投影模组的原理图只渲染"仅渲染方块列表"内的方块，
        // 不在列表内的方块连同其缺失/多余高亮线框完全不显示
        public static final ConfigBoolean RENDER_ONLY_BLOCKS = bool("renderOnlyBlocks")
                .defaultValue(false)
                .build();

        // 仅渲染方块列表：严格匹配注册路径 / 完整ID / 精确译名；开关开启且列表为空时不渲染任何方块
        public static final ConfigStringList RENDER_ONLY_BLOCK_LIST = stringList("renderOnlyBlockList")
                .build();

        // 云仓库-打印机补货：打印时缺货自动向云仓库下单
        public static final ConfigBoolean PRINT_CLOUD_STORE_REFILL = bool("printCloudStoreRefill")
                .defaultValue(false)
                .setVisible(isLoadCloudStoreLoaded) // 仅云仓库 Mod 加载时显示
                .build();

        // 云仓库-手动补货：鼠标中键点击方块时，背包无该物品则向云仓库下单
        public static final ConfigBoolean PRINT_CLOUD_STORE_MANUAL_REFILL = bool("printCloudStoreManualRefill")
                .defaultValue(false)
                .setVisible(isLoadCloudStoreLoaded) // 仅云仓库 Mod 加载时显示
                .build();

        // 云仓库补货冷却时间（秒）
        public static final ConfigInteger PRINT_CLOUD_STORE_REFILL_COOLDOWN = integer("printCloudStoreRefillCooldown")
                .defaultValue(300)
                .range(10, 3600)
                .setVisible(isLoadCloudStoreLoaded)
                .build();

        // 云仓库单次取货数量
        public static final ConfigInteger PRINT_CLOUD_STORE_REFILL_AMOUNT = integer("printCloudStoreRefillAmount")
                .defaultValue(64)
                .range(1, 64)
                .setVisible(isLoadCloudStoreLoaded)
                .build();

        // 云仓库取货数量滚动方向反转
        public static final ConfigBoolean REFILL_SCROLL_REVERSE = bool("refillScrollReverse")
                .defaultValue(false)
                .setVisible(isLoadCloudStoreLoaded) // 仅云仓库 Mod 加载时显示
                .build();

        // 云仓库鼠标中键强制取货：开启后，鼠标中键取货不再检查背包是否有物品，强制向云仓库发送请求
        public static final ConfigBoolean PRINT_CLOUD_STORE_MIDDLE_CLICK_FORCE = bool("printCloudStoreMiddleClickForce")
                .defaultValue(false)
                .setVisible(isLoadCloudStoreLoaded) // 仅云仓库 Mod 加载时显示
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                UNLOCK_BEACON_EFFECTS,
                TWEAKEROO_ANGEL_BLOCK_MAY_BUILD,
                GO_TIME_LIMIT,
                GO_MAX_FALL,
                GO_MAX_SPEED,
                GO_FORCE_SPRINT,
                GO_TAKEOVER_VIEW,
                GO_VIEW_OFFSET,
                GO_DEVIATION_STOP,
                GO_DEVIATION_DISTANCE,
                RENDER_ONLY_BLOCKS,
                RENDER_ONLY_BLOCK_LIST,
                // 云仓库
                PRINT_CLOUD_STORE_REFILL,
                PRINT_CLOUD_STORE_MANUAL_REFILL,
                PRINT_CLOUD_STORE_REFILL_COOLDOWN,
                PRINT_CLOUD_STORE_REFILL_AMOUNT,
                REFILL_SCROLL_REVERSE,
                PRINT_CLOUD_STORE_MIDDLE_CLICK_FORCE
        );
    }

    public static class Mine {
        // 选区类型
        public static final ConfigOptionList MINE_SELECTION_TYPE = optionList("mineSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .build();

        // 挖掘模式限制器
        public static final ConfigOptionList EXCAVATE_LIMITER = optionList("excavateLimiter")
                .defaultValue(ExcavateListMode.CUSTOM)
                .build();

        // 挖掘模式限制
        public static final ConfigOptionList EXCAVATE_LIMIT = optionList("excavateLimit")
                .defaultValue(UsageRestriction.ListType.NONE)
                .setVisible(isExcavateCustom)
                .build();

        // 挖掘白名单
        public static final ConfigStringList EXCAVATE_WHITELIST = stringList("excavateWhitelist")
                .setVisible(isExcavateWhitelist)
                .build();

        // 挖掘黑名单
        public static final ConfigStringList EXCAVATE_BLACKLIST = stringList("excavateBlacklist")
                .setVisible(isExcavateBlacklist)
                .build();

        public static final ConfigBoolean BREAK_USE_PACKET = bool("breakUsePacket")
                .defaultValue(false)
                .build();

        // 挖掘音效
        public static final ConfigBoolean BREAK_SOUND = bool("breakSound")
                .defaultValue(true)
                .build();

        public static final ConfigInteger BREAK_PROGRESS_THRESHOLD = integer("breakProgressThreshold")
                .defaultValue(100)
                .range(70, 100)
                .build();

        public static final ConfigInteger BREAK_INTERVAL = integer("breakInterval")
                .defaultValue(1)
                .range(0, 20)
                .build();

        public static final ConfigInteger BREAK_BLOCKS_PER_TICK = integer("breakBlocksPerTick")
                .defaultValue(1)
                .range(0, 256)
                .build();

        public static final ConfigInteger BREAK_COOLDOWN = integer("breakCooldown")
                .defaultValue(3)
                .range(0, 64)
                .build();

        public static final ConfigBoolean BREAK_CHECK_HARDNESS = bool("breakCheckHardness")
                .defaultValue(true)
                .build();

        // 纱幕-开关：数据包挖掘模式下，列表内的方块同一游戏刻同时发送开始+结束挖掘包（同 tick 秒破）
        public static final ConfigBoolean BREAK_INSTANT_MINE = bool("breakVeilToggle")
                .defaultValue(false)
                .build();

        // 纱幕-列表：处于列表内的方块才触发同 tick 同时发包挖掘开始与结束的逻辑
        public static final ConfigStringList BREAK_INSTANT_MINE_LIST = stringList("breakVeilList")
                .build();

        // 延迟破坏（用于同 tick 秒破）
        public static final ConfigBoolean BREAK_USE_DELAYED_DESTROY = bool("breakUseDelayedDestroy")
                .defaultValue(false)
                .build();

        // 并行破坏（一次扫描后按距离由近到远同时破坏多个方块）
        public static final ConfigBoolean BREAK_PARALLEL = bool("breakParallel")
                .defaultValue(false)
                .build();

        // 防流体挖掘：待挖方块的上、东、西、北、南侧存在目标流体时不挖；六面模式额外检查下侧
        public static final ConfigBoolean BREAK_AVOID_FLUID = bool("breakAvoidFluid")
                .defaultValue(false)
                .build();

        public static final ConfigStringList BREAK_FLUID_LIST = stringList("breakFluidList")
                .defaultValue(Blocks.WATER, Blocks.LAVA)
                .build();

        public static final ConfigOptionList BREAK_FLUID_STRATEGY = optionList("breakFluidStrategy")
                .defaultValue(FluidAvoidStrategyType.FIVE_FACES)
                .build();

        // 防支撑破坏：待挖方块正上方是沙子、沙砾、混凝土粉末、铁砧、龙蛋等重力方块时不挖该方块
        public static final ConfigBoolean BREAK_AVOID_SUPPORT = bool("breakAvoidSupport")
                .defaultValue(false)
                .build();

        // 非阻塞型挖掘：玩家手动挖掘（按住左键）时打印机暂停挖掘并让出破坏状态，玩家结束后自动恢复
        public static final ConfigBoolean BREAK_NON_BLOCKING = bool("breakNonBlocking")
                .defaultValue(false)
                .build();

        // 模式限制器
        public static final ConfigOptionList BREAK_LIMITER = optionList("breakLimiter")
                .defaultValue(ExcavateListMode.CUSTOM)
                .build();

        // 模式限制
        public static final ConfigOptionList BREAK_LIMIT = optionList("breakLimit")
                .defaultValue(UsageRestriction.ListType.NONE)
                .setVisible(isBreakCustom)
                .build();

        // 白名单
        public static final ConfigStringList BREAK_WHITELIST = stringList("breakWhitelist")
                .setVisible(isBreakWhitelist)
                .build();

        // 黑名单
        public static final ConfigStringList BREAK_BLACKLIST = stringList("breakBlacklist")
                .setVisible(isBreakBlacklist)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                MINE_SELECTION_TYPE,          // 挖掘 - 选区类型
                EXCAVATE_LIMITER,             // 挖掘 - 挖掘模式限制器
                EXCAVATE_LIMIT,               // 挖掘 - 挖掘模式限制
                EXCAVATE_WHITELIST,           // 挖掘 - 挖掘白名单
                EXCAVATE_BLACKLIST,            // 挖掘 - 挖掘黑名单
                // 原「破坏方块」目录
                BREAK_USE_PACKET,
                BREAK_SOUND,
                BREAK_CHECK_HARDNESS,
                BREAK_INSTANT_MINE,
                BREAK_INSTANT_MINE_LIST,
                BREAK_AVOID_FLUID,
                BREAK_FLUID_LIST,
                BREAK_FLUID_STRATEGY,
                BREAK_AVOID_SUPPORT,
                BREAK_NON_BLOCKING,
                BREAK_PARALLEL,
                BREAK_USE_DELAYED_DESTROY,
                BREAK_INTERVAL,
                BREAK_BLOCKS_PER_TICK,
                BREAK_COOLDOWN,
                BREAK_PROGRESS_THRESHOLD,
                BREAK_LIMITER,
                BREAK_LIMIT,
                BREAK_WHITELIST,
                BREAK_BLACKLIST
        );
    }

    public static class Fill {
        // 选区类型
        public static final ConfigOptionList FILL_SELECTION_TYPE = optionList("fillSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .build();

        // 填充方块模式
        public static final ConfigOptionList FILL_BLOCK_MODE = optionList("fillBlockMode")
                .defaultValue(FillBlockModeType.BLOCKLIST)
                .build();

        // 填充方块名单
        public static final ConfigStringList FILL_BLOCK_LIST = stringList("fillBlockList")
                .defaultValue(Blocks.COBBLESTONE)
                .setVisible(isBlocklist)
                .build();

        // 模式朝向
        public static final ConfigOptionList FILL_BLOCK_FACING = optionList("fillModeFacing")
                .defaultValue(FillModeFacingType.NONE)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                FILL_SELECTION_TYPE,          // 填充 - 选区类型
                FILL_BLOCK_MODE,              // 填充 - 填充方块模式
                FILL_BLOCK_LIST,              // 填充 - 填充方块名单
                FILL_BLOCK_FACING             // 填充 - 模式朝向
        );
    }

    public static class Fluid {

        // 选区类型
        public static final ConfigOptionList FLUID_SELECTION_TYPE = optionList("fluidSelectionType")
                .defaultValue(SelectionType.LITEMATICA_SELECTION)
                .build();

        // 填充流动液体
        public static final ConfigBoolean FILL_FLOWING_FLUID = bool("fluidModeFillFlowing")
                .defaultValue(true)
                .build();

        // 方块名单
        public static final ConfigStringList FLUID_REPLACE_BLOCK_LIST = stringList("fluidReplaceBlockList")
                .defaultValue(Blocks.SAND)
                .build();

        // 液体名单
        public static final ConfigStringList FLUID_LIST = stringList("fluidList")
                .defaultValue(Blocks.WATER, Blocks.LAVA)
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                FLUID_SELECTION_TYPE,         // 排流体 - 选区类型
                FILL_FLOWING_FLUID,           // 排流体 - 填充流动液体
                FLUID_REPLACE_BLOCK_LIST,             // 排流体 - 方块名单
                FLUID_LIST                    // 排流体 - 液体名单
        );
    }

    public static class Hotkeys {
        // 打开设置菜单
        public static final ConfigHotkey OPEN_SCREEN = hotkey("openScreen")
                .defaultStorageString("Z,Y")
                .build();

        // 关闭全部模式
        public static final ConfigHotkey CLOSE_ALL_MODE = hotkey("closeAllMode")
                .defaultStorageString("LEFT_CONTROL,G")
                .build();

        // 工作开关快捷键：切换「工作开关」
        public static final ConfigHotkey WORK_SWITCH_HOTKEY = hotkey("workingSwitchHotkey")
                .defaultStorageString("CAPS_LOCK")
                .keybindSettings(KeybindSettings.PRESS_ALLOWEXTRA_EMPTY)
                .keybindCallback((action, key) -> toggleWithNotify(Core.WORK_SWITCH))
                .build();

        // 打印开关快捷键
        public static final ConfigHotkey PRINT_HOTKEY = hotkey("printHotkey")
                .setVisible(isMulti) // 仅多模式时显示
                .keybindCallback((action, key) -> toggleWithNotify(Core.PRINT))
                .build();

        // 挖掘开关快捷键
        public static final ConfigHotkey MINE_HOTKEY = hotkey("mineHotkey")
                .setVisible(isMulti) // 仅多模式时显示
                .keybindCallback((action, key) -> toggleWithNotify(Core.MINE))
                .build();

        // 填充开关快捷键
        public static final ConfigHotkey FILL_HOTKEY = hotkey("fillHotkey")
                .setVisible(isMulti) // 仅多模式时显示
                .keybindCallback((action, key) -> toggleWithNotify(Core.FILL))
                .build();

        // 排流体开关快捷键
        public static final ConfigHotkey FLUID_HOTKEY = hotkey("fluidHotkey")
                .setVisible(isMulti) // 仅多模式时显示
                .keybindCallback((action, key) -> toggleWithNotify(Core.FLUID))
                .build();

        // 破冰放水快捷键
        public static final ConfigHotkey PRINT_ICE_FOR_WATER_HOTKEY = hotkey("printIceForWaterHotkey")
                .keybindCallback((action, key) -> toggleWithNotify(Print.PRINT_ICE_FOR_WATER))
                .build();

        // 云仓库取货数量调整（按住 + 滚轮调整）
        public static final ConfigHotkey REFILL_AMOUNT_ADJUST = hotkey("refillAmountAdjust")
                .defaultStorageString("LEFT_SHIFT,B")
                .setVisible(isLoadCloudStoreLoaded) // 仅云仓库 Mod 加载时显示
                .build();

        // 切换模式
        public static final ConfigHotkey SWITCH_PRINTER_MODE = hotkey("switchPrinterMode")
                .bindConfig(Core.WORK_MODE_TYPE)
                .setVisible(isSingle) // 仅单模式时显示
                .build();

        // 破基岩
        public static final ConfigBooleanHotkeyed BEDROCK = booleanHotkey("bedrock")
                .defaultValue(false)
                .setVisible(isMulti) // 仅多模式时显示
                .build();

        // 同步容器热键
        public static final ConfigHotkey SYNC_INVENTORY = hotkey("syncInventory")
                .build();

        // 同步容器开关热键
        public static final ConfigBooleanHotkeyed SYNC_INVENTORY_CHECK = booleanHotkey("syncInventoryCheck")
                .defaultValue(false)
                .build();

        // ========== 远程交互热键 ==========

        // 设置打印机库存热键
        public static final ConfigHotkey PRINTER_INVENTORY = hotkey("printerInventory")
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        // 清空打印机库存热键
        public static final ConfigHotkey REMOVE_PRINT_INVENTORY = hotkey("removePrintInventory")
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        // 上一个箱子
        public static final ConfigHotkey LAST = hotkey("last")
                .keybindSettings(GUI_NO_ORDER)
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        // 下一个箱子
        public static final ConfigHotkey NEXT = hotkey("next")
                .keybindSettings(GUI_NO_ORDER)
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        // 删除当前容器
        public static final ConfigHotkey DELETE = hotkey("delete")
                .keybindSettings(GUI_NO_ORDER)
                .setVisible(isLoadChestTrackerLoaded) // 仅箱子追踪 Mod 加载时显示
                .build();

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                OPEN_SCREEN,                  // 打开设置菜单
                WORK_SWITCH_HOTKEY,           // 工作开关快捷键
                CLOSE_ALL_MODE,               // 关闭全部模式
                SWITCH_PRINTER_MODE,          // 切换模式

                // 多模
                PRINT_HOTKEY,                 // 打印快捷键
                MINE_HOTKEY,                  // 挖掘快捷键
                FILL_HOTKEY,                  // 填充快捷键
                FLUID_HOTKEY,                 // 排流体快捷键
                BEDROCK,                      // 破基岩

                // 远程交互
                SYNC_INVENTORY,               // 同步容器热键
                SYNC_INVENTORY_CHECK,         // 同步容器开关热键

                // 云仓库
                REFILL_AMOUNT_ADJUST,         // 取货数量调整（按住+滚轮）

                // 打印
                PRINT_ICE_FOR_WATER_HOTKEY,   // 破冰放水快捷键
                PRINTER_INVENTORY,            // 设置打印机库存热键
                REMOVE_PRINT_INVENTORY,       // 清空打印机库存热键
                LAST,                         // 上一个箱子
                NEXT,                         // 下一个箱子
                DELETE                        // 删除当前容器
        );
    }

    @Override
    public void load() {
        File settingFile = new File(FILE_PATH);
        if (settingFile.isFile() && settingFile.exists()) {
            //#if MC >= 12111
            JsonElement jsonElement = JsonUtils.parseJsonFile(settingFile.toPath());
            //#else
            //$$ JsonElement jsonElement = JsonUtils.parseJsonFile(settingFile);
            //#endif
            if (jsonElement != null && jsonElement.isJsonObject()) {
                JsonObject obj = jsonElement.getAsJsonObject();
                migrateSplitHotkeyBooleans(obj);
                ConfigUtils.readConfigBase(obj, Reference.MOD_ID, OPTIONS);
            }
        }
    }

    @Override
    public void save() {
        if ((CONFIG_DIR.exists() && CONFIG_DIR.isDirectory()) || CONFIG_DIR.mkdirs()) {
            JsonObject configRoot = new JsonObject();
            ConfigUtils.writeConfigBase(configRoot, Reference.MOD_ID, OPTIONS);
            //#if MC >= 12111
            JsonUtils.writeJsonToFile(configRoot, new File(FILE_PATH).toPath());
            //#else
            //$$ JsonUtils.writeJsonToFile(configRoot, new File(FILE_PATH));
            //#endif
        }
    }

    /**
     * 兼容迁移：旧的「布尔值+快捷键」复合配置存为 {enabled: bool, hotkey: {keys: "..."}}，
     * 拆分后布尔为标量、快捷键独立成 key+"Hotkey" 条目。读配置前把旧结构拆开，
     * 保留用户已有的开关状态与键位绑定。
     */
    private static void migrateSplitHotkeyBooleans(JsonObject obj) {
        if (!(obj.get(Reference.MOD_ID) instanceof JsonObject mod)) {
            return;
        }
        for (String key : List.of("workingSwitch", "print", "mine", "fill", "fluid", "printIceForWater")) {
            if (!(mod.get(key) instanceof JsonObject old) || !old.has("enabled")) {
                continue;
            }
            mod.addProperty(key, old.get("enabled").getAsBoolean());
            if (old.has("hotkey")) {
                mod.add(key + "Hotkey", old.get("hotkey"));
            }
        }
    }

    /** 复合开关拆分后的快捷键回调：切换对应布尔值并显示开关提示（与原复合开关行为一致） */
    private static boolean toggleWithNotify(ConfigBoolean config) {
        config.toggleBooleanValue();
        boolean newValue = config.getBooleanValue();
        String pre = newValue ? GuiBase.TXT_GREEN : GuiBase.TXT_RED;
        I18n statusI18n = newValue ? I18n.MESSAGE_VALUE_ON : I18n.MESSAGE_VALUE_OFF;
        MutableComponent message = I18n.MESSAGE_TOGGLED.getName(
                config.getPrettyName(),
                pre + statusI18n.getName().getString() + GuiBase.TXT_RST
        );
        MessageUtils.setOverlayMessage(message);
        return true;
    }

    public static void init() {
        Configs.INSTANCE.load();
        ConfigManager.getInstance().registerConfigHandler(Reference.MOD_ID, Configs.INSTANCE);
        InputEventHandler.getKeybindManager().registerKeybindProvider(InputHandler.getInstance());
        InputEventHandler.getInputManager().registerKeyboardInputHandler(InputHandler.getInstance());
        //#if MC > 12006
        fi.dy.masa.malilib.registry.Registry.CONFIG_SCREEN.registerConfigScreenFactory(
                new fi.dy.masa.malilib.util.data.ModInfo(Reference.MOD_ID, Reference.MOD_NAME, ConfigUi::new)
        );
        //#endif
    }
}
