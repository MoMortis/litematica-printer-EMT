package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BlockStateUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 破冰放水的跨 tick 任务控制器（坐实 Guide 注释里的承诺）。
 *
 * 流程：目标为水源方块/含水方块且位置缺水时，
 * ① 先放置冰（new Action().setItem(Items.ICE)）→ ② 通过破坏队列直接破冰（工具切换交给 tweakeroo）→
 * ③ 本地预测到位置出现水（fluidState 非空）→ ④ 状态完成，交还普通 Guide 立即放置含水方块/水源判定。
 *
 * 放置顺序后置：新发起破冰放水前，必须等玩家交互距离内**目标水源/含水方块所在层（Y 轴）**
 * 的所有"非水"普通方块都放置完毕，否则一直等待（不接管，让该层普通方块先被打印）。
 * 流动水等液体方块不计入，避免误判。
 *
 * 破坏队列非空时打印循环会整体暂停（MixinLocalPlayer.tick），天然充当破冰期间的等待，
 * 无需自建定时器。状态按 BlockPos.asLong() 存于 Map，跨 tick 保持。
 */
public class PrintTaskController {
    public static final PrintTaskController INSTANCE = new PrintTaskController();

    private enum Stage {
        NONE,          // 无任务
        NEED_ICE,      // 需要放冰
        ICE_PLACED,    // 冰已放置（等待进入破冰）
        BREAKING,      // 正在破冰（等待冰消失）
        WAITING_WATER  // 冰已破，等待水出现
    }

    /** 等待水源出现的超时（tick），超时后视为破冰失败，重新放冰 */
    private static final int WAIT_WATER_TIMEOUT_TICKS = 60;

    private final Map<Long, Stage> stages = new HashMap<>();
    private final Map<Long, Long> stageStartTicks = new HashMap<>();

    /** 普通方块扫描缓存（每 tick + 层 Y 一次） */
    private long ordinaryScanTick = -1L;
    private int ordinaryScanY = Integer.MIN_VALUE;
    private boolean hasPendingOrdinaryCache;

    private PrintTaskController() {
    }

    /**
     * 由 PrintHandler.canProcessPos 在 Guides.buildAction 之前咨询。
     *
     * @return 非 null 表示本位置由破冰放水接管（返回的 Action 为放冰动作）；
     *         null 表示不接管（配置关闭/非水目标），或位置已含水/正在破冰/等待水（此时由
     *         {@link #isBreaking} / {@link #isWaitingWater} 区分后续处理）
     */
    @Nullable
    public Action handle(SchematicBlockContext ctx) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return null;
        }
        BlockState required = ctx.requiredState;
        if (!BlockStateUtils.isWaterBlock(required)) {
            return null;
        }

        BlockPos pos = ctx.blockPos;
        long key = pos.asLong();
        BlockState current = ctx.currentState;
        Stage stage = stages.getOrDefault(key, Stage.NONE);

        // 位置已经含水（本地预测）→ 完成，交还正常 Guide 放置含水方块/判定水源
        if (!current.getFluidState().isEmpty()) {
            stages.remove(key);
            stageStartTicks.remove(key);
            return null;
        }

        // 位置已是冰 → 进入破冰阶段
        if (current.is(Blocks.ICE)) {
            stages.put(key, Stage.BREAKING);
            return null;
        }

        // 位置是错误方块（不可替换、非水非冰非空）→ 清状态交还原流程破坏（DefaultGuide），破坏完后再放冰
        if (!current.isAir() && current.getFluidState().isEmpty()
                && !BlockStateUtils.isReplaceable(current)) {
            stages.remove(key);
            stageStartTicks.remove(key);
            return null;
        }

        // 冰已消失但水尚未同步到本地 → 等待水源
        if (stage == Stage.BREAKING) {
            stages.put(key, Stage.WAITING_WATER);
            stageStartTicks.put(key, getClientTick());
            return null;
        }
        if (stage == Stage.WAITING_WATER) {
            long start = stageStartTicks.getOrDefault(key, getClientTick());
            if (getClientTick() - start >= WAIT_WATER_TIMEOUT_TICKS) {
                // 超时（例如破冰被精准采集工具打断，冰掉落而非变水）→ 重新放冰
                stages.put(key, Stage.NEED_ICE);
                return new Action().setItem(Items.ICE);
            }
            return null;
        }

        // 放置顺序后置：目标水源/含水方块所在层（Y 轴）还有待放置的普通方块时，
        // 不发起破冰放水，返回 null 让打印循环先处理该层普通方块。
        if (hasPendingOrdinaryBlock(pos.getY())) {
            return null;
        }

        // 需要放冰：显式 setItem(Items.ICE)，否则 getRequiredItems 会回退成水桶
        stages.put(key, Stage.NEED_ICE);
        return new Action().setItem(Items.ICE);
    }

    /**
     * 目标层（Y 轴）内是否仍有"待放置的普通方块"。
     * 只统计目标水源/含水方块所在的那一层，其他层不影响；
     * 排除所有液体方块（含流动水/岩浆）与含水方块，避免误判。
     * 带每 tick + 层 Y 缓存，避免对同一层多个候选方块重复扫描。
     */
    private boolean hasPendingOrdinaryBlock(int targetY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }
        long tick = minecraft.level.getGameTime();
        if (tick != ordinaryScanTick || targetY != ordinaryScanY) {
            ordinaryScanTick = tick;
            ordinaryScanY = targetY;
            hasPendingOrdinaryCache = scanPendingOrdinaryBlock(targetY);
        }
        return hasPendingOrdinaryCache;
    }

    private boolean scanPendingOrdinaryBlock(int targetY) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return false;
        }
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) {
            return false;
        }
        AtomicReference<PrinterBox> boxRef = ClientPlayerTickManager.PRINT.getBoxRef();
        if (boxRef == null) {
            return false;
        }
        PrinterBox box = boxRef.get();
        if (box == null) {
            return false;
        }
        for (BlockPos pos : box) {
            // 只看目标层
            if (pos.getY() != targetY) {
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
            // 所有液体方块（水源/流动水/岩浆等）与含水方块由破冰放水/流体相关流程处理，不算普通方块
            if (required.getBlock() instanceof LiquidBlock || BlockStateUtils.isWaterBlock(required)) {
                continue;
            }
            if (BlockStateUtils.statesEqualIgnoreProperties(level.getBlockState(pos), required)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** 是否正处于破冰阶段（canProcessPos 应返回 true，executeIteration 里把冰入破坏队列） */
    public boolean isBreaking(BlockPos pos) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return false;
        }
        return stages.getOrDefault(pos.asLong(), Stage.NONE) == Stage.BREAKING;
    }

    /** 是否处于等待水源阶段（canProcessPos 应跳过该位置，保留状态等待水出现） */
    public boolean isWaitingWater(BlockPos pos) {
        if (!Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue()) {
            return false;
        }
        return stages.getOrDefault(pos.asLong(), Stage.NONE) == Stage.WAITING_WATER;
    }

    /** 放冰动作已发出后调用，标记冰已放置（下一 tick 会因位置变为冰而进入 BREAKING） */
    public void onIcePlaceSent(BlockPos pos) {
        long key = pos.asLong();
        if (stages.getOrDefault(key, Stage.NONE) == Stage.NEED_ICE) {
            stages.put(key, Stage.ICE_PLACED);
        }
    }

    private static long getClientTick() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? 0L : minecraft.level.getGameTime();
    }
}
