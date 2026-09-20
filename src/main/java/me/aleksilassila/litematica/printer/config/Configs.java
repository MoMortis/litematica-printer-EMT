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
        optionSet.addAll(Go.OPTIONS);             // 寻路
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
                QUICK_SHULKER_COOLDOWN
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

        // 扫描白名单：开启且列表非空时，打印只扫描/放置列表内方块（只管打印；
        // 扫描自动寻路的目标过滤用「寻路」目录的寻路扫描白名单）
        public static final ConfigBoolean PRINT_SCAN_WHITELIST = bool("printScanWhitelist")
                .defaultValue(false)
                .build();

        // 扫描白名单列表（匹配格式同跳过放置名单）
        public static final ConfigStringList PRINT_SCAN_WHITELIST_LIST = stringList("printScanWhitelistList")
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

        // 装填炼药锅：开启后用空桶/水桶/熔岩桶/细雪桶右键处理炼药锅（填充与舀出）；
        // 炼药锅（含装水/熔岩/细雪）任何时候都不会被打印机当作错误方块破坏
        public static final ConfigBoolean FILL_CAULDRON = bool("printFillCauldron")
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

        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                // 发包与音效
                PRINT_USE_PACKET,
                PRINT_SOUND,

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
                PRINT_REPLACE,
                REPLACEABLE_LIST,
                PRINT_ICE_FOR_WATER,
                FILL_CAULDRON,
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
                PLACE_INTERVAL,
                PLACE_BLOCKS_PER_TICK,
                PLACE_SAME_ITEM_FIRST,
                ITEM_SWITCH_INTERVAL,
                PLACE_COOLDOWN,
                FALLING_CHECK
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

        // 暴饮！暴食！触发模式：关闭 / 仅打印机工作时（总开关开启）/ 任何时候
        // 取食链：副手 → 快捷栏 → 背包 →（开启快捷潜影盒时）快捷潜影盒
        public static final ConfigOptionList EAT = optionList("eat")
                .defaultValue(EatMode.OFF)
                .build();

        // 饥饿度阈值：饥饿值 ≤ 该值时开吃
        public static final ConfigInteger EAT_HUNGER_THRESHOLD = integer("eatHungerThreshold")
                .defaultValue(14)
                .range(1, 19)
                .build();

        // 进食黑名单：严格匹配注册路径 / 完整ID / 精确译名，列表内的食物永远不会被自动吃掉
        public static final ConfigStringList EAT_BLACKLIST = stringList("eatBlacklist")
                .defaultValue(
                        "minecraft:rotten_flesh",
                        "minecraft:golden_apple",
                        "minecraft:enchanted_golden_apple",
                        "minecraft:beef",
                        "minecraft:porkchop",
                        "minecraft:chicken",
                        "minecraft:mutton",
                        "minecraft:rabbit",
                        "minecraft:cod",
                        "minecraft:salmon",
                        "minecraft:tropical_fish",
                        "minecraft:potato",
                        "minecraft:pufferfish",
                        "minecraft:suspicious_stew",
                        "minecraft:chorus_fruit",
                        "minecraft:poisonous_potato",
                        "minecraft:spider_eye"
                )
                .build();

        // 受伤打断-进食冷却（秒）：进食中受伤立即停止并在该秒数内不再触发；0 = 受伤不打断
        public static final ConfigInteger EAT_HURT_CANCEL_COOLDOWN = integer("eatHurtCancelCooldown")
                .defaultValue(7)
                .range(0, 60)
                .build();

        // ===== 容器同步 =====

        // 容器同步 - 开关：开启后同步前先校验玩家背包材料是否齐全，不足则暂不同步
        public static final ConfigBoolean SYNC_INVENTORY_CHECK = bool("syncInventoryCheck")
                .defaultValue(false)
                .build();

        // 容器同步 - 同步合成器：同步时一并同步合成器的槽位禁用状态（先禁用位后物品）；
        // 关闭后只同步物品，不读写禁用位
        public static final ConfigBoolean SYNC_INVENTORY_CRAFTER = bool("syncInventoryCrafter")
                .defaultValue(true)
                .build();

        // 容器同步 - 高亮颜色
        public static final ConfigColor SYNC_INVENTORY_COLOR = color("syncInventoryColor")
                .defaultValue("#4CFF4CE6")
                .build();

        // 容器同步 - 高亮渲染距离（0 为不限制）
        public static final ConfigInteger SYNC_HIGHLIGHT_RENDER_DISTANCE = integer("syncHighlightRenderDistance")
                .defaultValue(64)
                .range(0, 256)
                .build();

        // 特殊配置项列表（按功能分组排序：原版限制放宽 → 原理图渲染 → 云仓库补货 → 容器同步 → 暴饮暴食）
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                // 原版/他模组限制放宽
                UNLOCK_BEACON_EFFECTS,
                TWEAKEROO_ANGEL_BLOCK_MAY_BUILD,

                // 原理图渲染过滤
                RENDER_ONLY_BLOCKS,
                RENDER_ONLY_BLOCK_LIST,

                // 云仓库补货（需云仓库 Mod）
                PRINT_CLOUD_STORE_REFILL,
                PRINT_CLOUD_STORE_MANUAL_REFILL,
                PRINT_CLOUD_STORE_MIDDLE_CLICK_FORCE,
                PRINT_CLOUD_STORE_REFILL_COOLDOWN,
                PRINT_CLOUD_STORE_REFILL_AMOUNT,
                REFILL_SCROLL_REVERSE,

                // 容器同步
                SYNC_INVENTORY_CHECK,
                SYNC_INVENTORY_CRAFTER,
                SYNC_INVENTORY_COLOR,
                SYNC_HIGHLIGHT_RENDER_DISTANCE,

                // 暴饮暴食（自动进食）
                EAT,
                EAT_HUNGER_THRESHOLD,
                EAT_BLACKLIST,
                EAT_HURT_CANCEL_COOLDOWN
        );
    }

    /** 「寻路」目录：/go 自动寻路与扫描自动寻路的全部配置 + 寻路扫描白名单 */
    public static class Go {
        // 扫描自动寻路：打印工作时自动寻找待放方块并用寻路载玩家过去
        //（寻路扫描白名单未开启时全量扫描，开启且列表非空时只找列表内/验证器高亮的方块）
        public static final ConfigBoolean PRINT_SCAN_AUTOWALK = bool("printScanAutoWalk")
                .defaultValue(false)
                .build();

        // 乐魂寻路：开启后不再走路移动，改为骑乘快乐恶魂做三维飞行移动
        //（需骑乘"可操控"的乐魂：本人为第一乘客、已装备挽具、非静默态；
        //  飞行空间须完全落在原理图预测的空气格内，详见设计方案）
        public static final ConfigBoolean GHAST_PATHFIND = bool("ghastPathfind")
                .defaultValue(false)
                .build();

        // 最短路径优先：派发时对全部候选做一次多目标寻路，选路径成本最短的目标
        //（关闭 = 维持直线距离最近 + 单目标寻路的旧行为）
        public static final ConfigBoolean PATH_NEAREST_TARGET = bool("pathNearestTarget")
                .defaultValue(true)
                .build();

        // 寻路多余方块：扫描自动寻路把"多余方块"（原理图此处为空气、现实却有方块）
        // 作为优先目标——存在多余方块候选时优先前往并等打印机破坏，多个之间按路径最短竞争。
        // 前置：开启「破坏多余方块」（否则打印机不会破坏，寻路过去只会干等）。
        // 注意「扫描白名单」（打印机目录）开启时空气格不在白名单内，多余方块不会被打印机破坏
        public static final ConfigBoolean GO_SCAN_EXTRA_BLOCKS = bool("goScanExtraBlocks")
                .defaultValue(false)
                .build();

        // 多目标候选上限：进入目标集合的候选数上限，超限按直线距离预截（直线距离只作预筛）
        public static final ConfigInteger PATH_TARGET_CANDIDATE_LIMIT = integer("pathTargetCandidateLimit")
                .defaultValue(256)
                .range(16, 1024)
                .build();

        // 寻路扫描白名单：开启且列表非空时，扫描自动寻路只寻找列表内/验证器高亮的待放方块
        // （与打印的"扫描白名单"完全独立，互不影响）
        public static final ConfigBoolean WALK_SCAN_WHITELIST = bool("walkScanWhitelist")
                .defaultValue(false)
                .build();

        // 寻路扫描白名单列表（匹配格式同跳过放置名单；判定为"列表命中 ∪ 验证器高亮的缺失方块"）
        public static final ConfigStringList WALK_SCAN_WHITELIST_LIST = stringList("walkScanWhitelistList")
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

        // 自动寻路：寻路成本上限倍数（0 = 不设上限）。以「起点到最近目标的距离下界」为基准，
        // 当搜索中最小 f（已走代价 + 剩余距离下界）超过「下界 × 倍数」时立即判定不可达并返回，
        // 不再把时长预算烧在"目标根本到不了"的探索上（乐魂飞行与走路共用）。
        public static final ConfigInteger GO_COST_LIMIT_FACTOR = integer("goCostLimitFactor")
                .defaultValue(8)
                .range(0, 32)
                .build();

        // 自动寻路：启发值权重（1.0 = 标准 A*，保证配置成本模型下的最短路径；>1 = 加权 A*，
        // 按 f = g + w×h 排序，更贪心——同样预算内更快锁定可用目标，预算被掐断时结果最多约 w 倍）。
        // 只改"先搜哪条路"的排序：可达性/成本上限/多目标收工比较仍按未加权的距离下界判定，
        // 剪枝也只剪"确实不可能更便宜"的节点，不会把走得通的目标误判为无解。
        public static final ConfigDouble GO_HEURISTIC_WEIGHT = doubleValue("goHeuristicWeight")
                .defaultValue(1.0D)
                .range(1.0D, 10.0D)
                .build();

        // ===== 乐魂寻路（飞行）代价 =====
        // 逐条对应「移动方式 → 路程代价」，单位＝几何格；寻路开始时快照一次。
        // 与下方的「行走寻路代价」完全独立，互不影响。

        // 乐魂寻路 - 正交代价：上下／前后／左右移动 1 格（默认 1，即一格）
        public static final ConfigDouble GO_GHAST_COST_ORTHO = doubleValue("goGhastCostOrtho")
                .defaultValue(1.0D)
                .range(0.1D, 10.0D)
                .build();

        // 乐魂寻路 - 面对角代价：同时在两个轴上各移动 1 格（默认 √2＝1.41421356）
        public static final ConfigDouble GO_GHAST_COST_DIAG2 = doubleValue("goGhastCostDiag2")
                .defaultValue(1.41421356D)
                .range(0.1D, 10.0D)
                .build();

        // 乐魂寻路 - 体对角代价：三个轴同时各移动 1 格（默认 √3＝1.7320508）
        public static final ConfigDouble GO_GHAST_COST_DIAG3 = doubleValue("goGhastCostDiag3")
                .defaultValue(1.7320508D)
                .range(0.1D, 10.0D)
                .build();

        // 乐魂寻路 - 上升倍率：凡含上升的步，在上述三档代价上乘该倍率（整数，默认 2：空格上升
        // 推力只有水平的一半；调高更愿意平飞，1 ＝ 上升与平飞同价）。
        // 下限取 1 是为了让启发值（几何路程下界，不含倍率）始终可采纳，A* 的最优性才成立。
        public static final ConfigInteger GO_GHAST_ASCEND_MULT = integer("goGhastAscendMult")
                .defaultValue(2)
                .range(1, 32)
                .build();

        // 乐魂寻路 - 下降倍率：凡含下降的步，在上述三档代价上乘该倍率（整数，默认 2，与上升同价）。
        // 下降要先飞到位再低头（见 GhastFlyer.drive），并不比上升划算，故默认不便宜；
        // 调高更愿意绕开下降段，1 ＝ 下降与平飞同价
        public static final ConfigInteger GO_GHAST_DESCEND_MULT = integer("goGhastDescendMult")
                .defaultValue(2)
                .range(1, 32)
                .build();

        // 乐魂寻路 - 贴墙惩罚：紧贴方块（三维切比雪夫 1 格）的格单步加价，隔开一整格以上不加价
        //（默认 6，0 = 不惩罚）
        public static final ConfigDouble GO_GHAST_WALL_PENALTY = doubleValue("goGhastWallPenalty")
                .defaultValue(6.0D)
                .range(0.0D, 64.0D)
                .build();

        // 乐魂寻路 - 末端升降权重：每格垂直移动再按"该步离目标的水平距离（封顶 16 格）"加价，
        // 使路线呈"长距离平飞 + 末端集中升降"（默认 0.2，0 = 关闭该机制，高度可在任意位置改变）
        public static final ConfigDouble GO_GHAST_VERT_LATE_WEIGHT = doubleValue("goGhastVertLateWeight")
                .defaultValue(0.2D)
                .range(0.0D, 2.0D)
                .build();

        // 乐魂寻路 - 节点超时：从当前路径节点前往下一节点的限时＝两节点距离 × 此倍率（tick/格）。
        // 超时仍未推进到下一节点（或一直进不了终点的"贴近即到达"圈）→ 放弃当前路线：
        // 自动寻路换目标重新派发，手动寻路从当前位置重算。0 = 不限制
        public static final ConfigDouble GO_GHAST_WAYPOINT_TIMEOUT = doubleValue("goGhastWaypointTimeout")
                .defaultValue(30.0D)
                .range(0.0D, 600.0D)
                .build();

        // 乐魂寻路 - 转向惩罚：路径每折向一次（当前步方向与前一步不同）叠加的软性代价。
        // 恶魂转向要身体朝向平滑收敛（先转后飞），每次折向都有真实的减速与弧线成本；
        // 调高使路线更趋直线、减少折返（默认 0，0 = 不惩罚）
        public static final ConfigDouble GO_GHAST_TURN_PENALTY = doubleValue("goGhastTurnPenalty")
                .defaultValue(0.0D)
                .range(0.0D, 10.0D)
                .build();

        // ===== 行走寻路代价 =====
        // 逐条对应「移动方式 → 代价」，单位＝tick（按原版实测速度折算）；寻路开始时快照一次。
        // 与上方的「乐魂寻路代价」完全独立，互不影响。

        // 行走寻路 - 步行代价：平移 1 格（默认 20/4.317 ≈ 4.633）。
        // 落地（走下悬崖）＝该值＋按 MC 重力模拟出的下落 tick；同层爬出攀爬列＝该值；
        // 攀爬列下移 1 格＝该值＋下落 1 格的 tick
        public static final ConfigDouble GO_WALK_COST = doubleValue("goWalkCost")
                .defaultValue(4.633D)
                .range(0.1D, 60.0D)
                .build();

        // 行走寻路 - 面对角代价：同层斜走 1 格（默认 4.633×√2 ≈ 6.552）
        public static final ConfigDouble GO_WALK_DIAGONAL_COST = doubleValue("goWalkDiagonalCost")
                .defaultValue(6.552D)
                .range(0.1D, 60.0D)
                .build();

        // 行走寻路 - 跳上一格代价（默认 4.633＋5 ＝ 9.633）；跳入攀爬列亦按此价
        public static final ConfigDouble GO_JUMP_UP_COST = doubleValue("goJumpUpCost")
                .defaultValue(9.633D)
                .range(0.1D, 60.0D)
                .build();

        // 行走寻路 - 涉水代价：涉水 1 格（默认 20/2.2 ≈ 9.091）；涉水对角＝该值×√2
        public static final ConfigDouble GO_WATER_COST = doubleValue("goWaterCost")
                .defaultValue(9.091D)
                .range(0.1D, 60.0D)
                .build();

        // 行走寻路 - 攀爬代价：梯子／藤蔓沿列 1 格（默认 20/2.35 ≈ 8.511）
        public static final ConfigDouble GO_LADDER_COST = doubleValue("goLadderCost")
                .defaultValue(8.511D)
                .range(0.1D, 60.0D)
                .build();

        // 行走寻路 - 翻出梯顶代价：从梯顶翻出（跳＋侧移，默认 8.511＋4 ＝ 12.511）
        public static final ConfigDouble GO_LADDER_EXIT_COST = doubleValue("goLadderExitCost")
                .defaultValue(12.511D)
                .range(0.1D, 60.0D)
                .build();

        // 行走寻路 - 跑酷跳代价：疾跑跳过缺口（腾空约 12 tick，可覆盖 2~4 格；默认 12）。
        // 调高会让寻路更不愿意跳缺口
        public static final ConfigDouble GO_PARKOUR_COST = doubleValue("goParkourCost")
                .defaultValue(12.0D)
                .range(0.0D, 60.0D)
                .build();

        // 行走寻路 - 疾跑代价：仅作启发值下界与路径时长换算，不参与任何移动方式的边成本
        //（默认 20/5.612 ≈ 3.564）。开启「强制疾跑」时启发值按该值估算
        public static final ConfigDouble GO_SPRINT_COST = doubleValue("goSprintCost")
                .defaultValue(3.564D)
                .range(0.1D, 60.0D)
                .build();

        // 自动寻路 - 最大速度：寻路移动的速度上限（格/秒），超过疾跑全速的值等效不限速
        public static final ConfigDouble GO_MAX_SPEED = doubleValue("goMaxSpeed")
                .defaultValue(5.7D)
                .range(0.5D, 10.0D)
                .build();

        // 自动寻路 - 强制疾跑：始终请求疾跑（等效一直按住 Ctrl），能否真正冲刺由原版条件决定；
        // 关闭时寻路不生成跑酷跳边（完全绕开缺口）
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

        // 寻路配置项列表（按功能分组排序：总开关与移动方式 → 目标选择 → 扫描白名单 → 子区块扫描顺序 →
        // 通用参数 → 乐魂寻路代价 → 行走寻路代价 → 行走控制）
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                // 总开关与移动方式
                PRINT_SCAN_AUTOWALK,          // 扫描自动寻路（总开关置顶）
                GHAST_PATHFIND,               // 乐魂寻路（改为三维飞行）

                // 目标选择
                PATH_NEAREST_TARGET,
                GO_SCAN_EXTRA_BLOCKS,
                PATH_TARGET_CANDIDATE_LIMIT,

                // 扫描白名单
                WALK_SCAN_WHITELIST,
                WALK_SCAN_WHITELIST_LIST,

                // 子区块扫描顺序
                PRINT_SCAN_SECTION_ORDER,
                PRINT_SCAN_X_REVERSE,
                PRINT_SCAN_Y_REVERSE,
                PRINT_SCAN_Z_REVERSE,

                // 通用寻路参数
                GO_TIME_LIMIT,
                GO_COST_LIMIT_FACTOR,
                GO_HEURISTIC_WEIGHT,
                GO_MAX_FALL,

                // 乐魂寻路（飞行）代价
                GO_GHAST_COST_ORTHO,
                GO_GHAST_COST_DIAG2,
                GO_GHAST_COST_DIAG3,
                GO_GHAST_ASCEND_MULT,
                GO_GHAST_DESCEND_MULT,
                GO_GHAST_WALL_PENALTY,
                GO_GHAST_VERT_LATE_WEIGHT,
                GO_GHAST_WAYPOINT_TIMEOUT,
                GO_GHAST_TURN_PENALTY,

                // 行走寻路代价
                GO_WALK_COST,
                GO_WALK_DIAGONAL_COST,
                GO_JUMP_UP_COST,
                GO_WATER_COST,
                GO_LADDER_COST,
                GO_LADDER_EXIT_COST,
                GO_PARKOUR_COST,
                GO_SPRINT_COST,

                // 行走控制与判停
                GO_MAX_SPEED,
                GO_FORCE_SPRINT,
                GO_TAKEOVER_VIEW,
                GO_VIEW_OFFSET,
                GO_DEVIATION_STOP,
                GO_DEVIATION_DISTANCE
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
                // 发包与音效
                BREAK_USE_PACKET,
                BREAK_SOUND,

                MINE_SELECTION_TYPE,          // 挖掘 - 选区类型
                EXCAVATE_LIMITER,             // 挖掘 - 挖掘模式限制器
                EXCAVATE_LIMIT,               // 挖掘 - 挖掘模式限制
                EXCAVATE_WHITELIST,           // 挖掘 - 挖掘白名单
                EXCAVATE_BLACKLIST,            // 挖掘 - 挖掘黑名单
                // 原「破坏方块」目录
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

        // 填充配置项列表（按功能分组排序：选区 → 填充模式 → 方块名单 → 朝向）
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

        // 排流体配置项列表（按功能分组排序：选区 → 填充行为 → 方块名单 → 液体名单）
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                FLUID_SELECTION_TYPE,         // 排流体 - 选区类型
                FILL_FLOWING_FLUID,           // 排流体 - 填充流动液体
                FLUID_REPLACE_BLOCK_LIST,     // 排流体 - 方块名单
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

        // 扫描自动寻路快捷键：切换「寻路-扫描自动寻路」
        public static final ConfigHotkey SCAN_AUTOWALK_HOTKEY = hotkey("scanAutoWalkHotkey")
                .keybindCallback((action, key) -> toggleWithNotify(Go.PRINT_SCAN_AUTOWALK))
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

        // 快捷键列表（按功能分组排序：基础操作 → 多模开关 → 打印相关 → 容器同步 → 云仓库）
        public static final ImmutableList<IConfigBase> OPTIONS = ImmutableList.of(
                // 基础操作
                OPEN_SCREEN,                  // 打开设置菜单
                WORK_SWITCH_HOTKEY,           // 工作开关快捷键
                CLOSE_ALL_MODE,               // 关闭全部模式
                SWITCH_PRINTER_MODE,          // 切换模式

                // 多模式各功能开关
                PRINT_HOTKEY,                 // 打印快捷键
                MINE_HOTKEY,                  // 挖掘快捷键
                FILL_HOTKEY,                  // 填充快捷键
                FLUID_HOTKEY,                 // 排流体快捷键
                BEDROCK,                      // 破基岩

                // 打印相关
                PRINT_ICE_FOR_WATER_HOTKEY,   // 破冰放水快捷键
                SCAN_AUTOWALK_HOTKEY,         // 扫描自动寻路快捷键

                // 容器同步
                SYNC_INVENTORY,               // 同步容器热键

                // 云仓库（需云仓库 Mod）
                REFILL_AMOUNT_ADJUST          // 取货数量调整（按住+滚轮）
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
