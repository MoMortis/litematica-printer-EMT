package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.PrintPriorityType;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 打印放置顺序控制器：优先列表 → 普通方块 → 后置列表 → 潜影盒 → 破冰放水。
 *
 * 优先/后置列表按条目先后顺序推进；潜影盒与水/含水方块不属于"普通方块"，
 * 只有显式写进列表时才提前到优先/后置阶段。
 * 策略：OFF=不启用；INTERACTION_RANGE=仅玩家交互范围内；GLOBAL=投影渲染层内。
 * 每 tick 缓存当前阶段，避免重复扫描。
 */
public class PrintOrderController {
    public static final PrintOrderController INSTANCE = new PrintOrderController();

    private enum Stage {
        PRIORITY,   // 优先列表（记录 index）
        NORMAL,     // 普通方块
        POSTPONED,  // 后置列表（记录 index）
        SHULKER,    // 潜影盒
        ICE_WATER,  // 水/含水方块
        DONE        // 全部完成（放行）
    }

    /** 阶段扫描缓存（每 tick 一次） */
    private long stageCacheTick = -1L;
    private Stage cachedStage = Stage.DONE;
    private int cachedIndex = -1;

    private PrintOrderController() {
    }

    /**
     * 该方块是否允许在当前阶段放置。由 PrintHandler.canProcessPos 开头咨询。
     */
    public boolean shouldAllow(SchematicBlockContext ctx, BlockPos pos) {
        // 方块放置优先级策略关闭 → 不启用顺序控制，全部放行（保持原行为）
        if (Configs.Print.PRINT_ORDER_STRATEGY.getOptionListValue() == PrintPriorityType.OFF) {
            return true;
        }
        refreshStage();
        Stage stage = cachedStage;
        if (stage == Stage.DONE) {
            return true;
        }
        BlockState required = ctx.requiredState;
        switch (stage) {
            case PRIORITY:
                return matchesListEntry(required, Configs.Print.PRINT_PRIORITY_LIST.getStrings(), cachedIndex);
            case POSTPONED:
                return matchesListEntry(required, Configs.Print.PRINT_POSTPONED_LIST.getStrings(), cachedIndex);
            case SHULKER:
                return required.getBlock() instanceof ShulkerBoxBlock;
            case ICE_WATER:
                return BlockStateUtils.isWaterBlock(required);
            case NORMAL:
            default:
                return isOrdinary(required);
        }
    }

    /**
     * 每 tick 刷新当前阶段。规则：
     * 1. 优先策略≠OFF 且优先列表存在待放置条目 → PRIORITY(该条目索引)
     * 2. 存在待放置普通方块 → NORMAL
     * 3. 后置策略≠OFF 且后置列表存在待放置条目 → POSTPONED(该条目索引)
     * 4. 存在待放置潜影盒（且未跳过潜影盒）→ SHULKER
     * 5. 存在待放置水/含水 → ICE_WATER
     * 6. 否则 → DONE
     */
    private void refreshStage() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        long tick = minecraft.level.getGameTime();
        if (tick == stageCacheTick) {
            return;
        }
        stageCacheTick = tick;

        // 1. 优先列表
        if (isOrderEnabled()) {
            int index = findPendingListIndex(Configs.Print.PRINT_PRIORITY_LIST.getStrings());
            if (index >= 0) {
                cachedStage = Stage.PRIORITY;
                cachedIndex = index;
                return;
            }
        }
        // 2. 普通方块
        if (hasPendingOrdinary()) {
            cachedStage = Stage.NORMAL;
            cachedIndex = -1;
            return;
        }
        // 3. 后置列表
        if (isOrderEnabled()) {
            int index = findPendingListIndex(Configs.Print.PRINT_POSTPONED_LIST.getStrings());
            if (index >= 0) {
                cachedStage = Stage.POSTPONED;
                cachedIndex = index;
                return;
            }
        }
        // 4. 潜影盒
        if (hasPendingShulker()) {
            cachedStage = Stage.SHULKER;
            cachedIndex = -1;
            return;
        }
        // 5. 水/含水
        if (hasPendingWater()) {
            cachedStage = Stage.ICE_WATER;
            cachedIndex = -1;
            return;
        }
        cachedStage = Stage.DONE;
        cachedIndex = -1;
    }

    /** 方块放置优先级策略是否已启用（非关闭） */
    private boolean isOrderEnabled() {
        return Configs.Print.PRINT_ORDER_STRATEGY.getOptionListValue() != PrintPriorityType.OFF;
    }

    /** 列表中存在待放置方块的第一个条目索引；无则 -1 */
    private int findPendingListIndex(List<String> list) {
        if (list == null || list.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < list.size(); i++) {
            if (hasPendingMatching(list.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 扫描范围内是否存在匹配指定列表条目的待放置方块 */
    private boolean hasPendingMatching(String entry) {
        for (BlockPos pos : iterateScope()) {
            if (!isInScope(pos)) {
                continue;
            }
            BlockState required = getRequiredState(pos);
            if (required == null || required.isAir()) {
                continue;
            }
            if (!matchesListEntry(required, List.of(entry), 0)) {
                continue;
            }
            if (isPending(pos, required)) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在待放置的普通方块（非潜影盒、非水/含水、非任一列表匹配） */
    private boolean hasPendingOrdinary() {
        for (BlockPos pos : iterateScope()) {
            if (!isInScope(pos)) {
                continue;
            }
            BlockState required = getRequiredState(pos);
            if (required == null || required.isAir()) {
                continue;
            }
            if (!isOrdinary(required)) {
                continue;
            }
            if (isPending(pos, required)) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在待放置的潜影盒（跳过潜影盒开启时视为已放置） */
    private boolean hasPendingShulker() {
        if (Configs.Print.PRINT_SKIP_SHULKER.getBooleanValue()) {
            return false;
        }
        for (BlockPos pos : iterateScope()) {
            if (!isInScope(pos)) {
                continue;
            }
            BlockState required = getRequiredState(pos);
            if (required == null || !(required.getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }
            if (isPending(pos, required)) {
                return true;
            }
        }
        return false;
    }

    /** 是否存在待放置的水/含水方块 */
    private boolean hasPendingWater() {
        for (BlockPos pos : iterateScope()) {
            if (!isInScope(pos)) {
                continue;
            }
            BlockState required = getRequiredState(pos);
            if (required == null || !BlockStateUtils.isWaterBlock(required)) {
                continue;
            }
            if (isPending(pos, required)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 位置是否在当前策略的作用范围内。
     * "仅交互范围"需玩家实际可交互；全局模式只扫描客户端已加载区块。
     */
    private boolean isInScope(BlockPos pos) {
        if (isAnyGlobal()) {
            ClientLevel level = Minecraft.getInstance().level;
            return level != null && WorldUtils.isClientChunkLoaded(level, pos.getX() >> 4, pos.getZ() >> 4);
        }
        return PlayerUtils.canInteracted(pos);
    }

    /** 是否为普通方块（非潜影盒、非水/含水、非优先/后置列表匹配） */
    private boolean isOrdinary(BlockState required) {
        if (required == null || required.isAir()) {
            return false;
        }
        if (required.getBlock() instanceof ShulkerBoxBlock || BlockStateUtils.isWaterBlock(required)) {
            return false;
        }
        if (matchesAnyList(required, Configs.Print.PRINT_PRIORITY_LIST.getStrings())) {
            return false;
        }
        if (matchesAnyList(required, Configs.Print.PRINT_POSTPONED_LIST.getStrings())) {
            return false;
        }
        return true;
    }

    private boolean matchesAnyList(BlockState required, List<String> list) {
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (matchesListEntry(required, list, i)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesListEntry(BlockState required, List<String> list, int index) {
        if (list == null || index < 0 || index >= list.size()) {
            return false;
        }
        return PinYinSearchUtils.matchName(list.get(index), required);
    }

    /** 位置是否需要放置（与原理图状态不一致） */
    private boolean isPending(BlockPos pos, BlockState required) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        return !BlockStateUtils.statesEqualIgnoreProperties(level.getBlockState(pos), required);
    }

    /** 遍历范围：交互范围用 boxRef，全局用选区 box */
    private Iterable<BlockPos> iterateScope() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return java.util.Collections.emptyList();
        }
        if (isAnyGlobal()) {
            return iterateSelectionBoxes();
        }
        AtomicReference<PrinterBox> boxRef = ClientPlayerTickManager.PRINT.getBoxRef();
        PrinterBox box = boxRef == null ? null : boxRef.get();
        if (box == null) {
            return java.util.Collections.emptyList();
        }
        return box;
    }

    /** 方块放置优先级策略是否为全局模式 */
    private boolean isAnyGlobal() {
        return Configs.Print.PRINT_ORDER_STRATEGY.getOptionListValue() == PrintPriorityType.GLOBAL;
    }

    /** 全局：遍历选区所有子区域 box */
    private Iterable<BlockPos> iterateSelectionBoxes() {
        java.util.List<BlockPos> positions = new java.util.ArrayList<>();
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) {
            return positions;
        }
        for (Box box : selection.getAllSubRegionBoxes()) {
            if (box == null || box.getPos1() == null || box.getPos2() == null) {
                continue;
            }
            PrinterBox printerBox = new PrinterBox(box.getPos1(), box.getPos2());
            for (BlockPos pos : printerBox) {
                positions.add(pos);
            }
        }
        return positions;
    }

    /** 读取投影目标方块状态；不在投影内返回 null */
    private BlockState getRequiredState(BlockPos pos) {
        return LitematicaUtils.getSchematicBlockState(pos);
    }
}
