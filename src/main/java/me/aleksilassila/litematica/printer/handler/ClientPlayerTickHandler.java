package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import lombok.Getter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import me.aleksilassila.litematica.printer.printer.*;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 打印机客户端玩家Tick抽象处理器
 */
public abstract class ClientPlayerTickHandler extends ConfigUtils {
    // 交互盒引用：存储迭代范围，null表示不使用迭代功能
    @Getter
    @Nullable
    public final AtomicReference<PrinterBox> boxRef;

    @Getter
    private final String id;

    @Getter
    @Nullable
    private final PrintModeType printMode;

    @Getter
    @Nullable
    private final ConfigBoolean enableConfig;

    @Getter
    @Nullable
    private final ConfigOptionList selectionType;

    // 跳过迭代标志
    private final AtomicReference<Boolean> skipIteration = new AtomicReference<>(false);

    // GUI信息队列（用于渲染）
    private final Queue<GuiBlockInfo> guiQueue = new ConcurrentLinkedQueue<>();

    // 迭代状态缓存（性能优化关键）
    private Iterator<BlockPos> cachedIterator = null;
    // 方案二：记录当前正在扫描的Y层，时间预算仅在层边界截断（保证整层Y扫完）
    private int lastSweptY = Integer.MIN_VALUE;
    private int expandRange = -1;

    // 方案六：空闲退避。完整扫完一轮且零待处理格位时按 1→2→4→…→MAX 递增扫描间隔；
    // 任何失效信号（缓存修订号变化 / 盒子重建）立即恢复逐 tick 扫描
    private static final int MAX_IDLE_BACKOFF_TICKS = 10;
    private int idleBackoffTicks = 1;
    private long nextScanAllowedAt = -1L;
    private long lastSeenCacheRevision = -1L;

    // 命名冷却的类型字符串缓存（避免每次 id + "_" + name 分配）
    private final Map<String, String> cooldownTypeCache = new HashMap<>();

    protected Minecraft mc;
    protected ClientLevel level;
    protected LocalPlayer player;
    protected ClientPacketListener connection;
    protected MultiPlayerGameMode gameMode;
    protected GameType gameType;
    @Nullable
    protected HitResult hitResult;
    @Nullable
    protected BlockHitResult blockHitResult;
    @Nullable
    private PrinterBox lastBox;
    @Nullable
    private BlockPos lastPos;

    // 运动感知：上一观测的玩家方块位置，用于推算主导移动轴
    @Nullable
    private BlockPos prevPlayerBlockPos = null;

    private long lastTickTime = -1L;

    @Getter
    private int renderIndex = 0;

    private int guiCacheTicks;

    protected ClientPlayerTickHandler(String id, @Nullable PrintModeType printMode, @Nullable ConfigBoolean enableConfig, @Nullable ConfigOptionList selectionType, boolean useBox) {
        this.id = id;
        this.printMode = printMode;
        this.enableConfig = enableConfig;
        this.selectionType = selectionType;
        this.boxRef = useBox ? new AtomicReference<>() : null;
        updateVariables();
    }

    protected void updateVariables() {
        mc = Minecraft.getInstance();
        level = mc.level;
        player = mc.player;
        connection = mc.getConnection();
        gameMode = mc.gameMode;
        gameType = gameMode == null ? null : gameMode.getPlayerMode();
        hitResult = mc.hitResult;
        blockHitResult = (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK)
                ? (BlockHitResult) hitResult : null;
    }

    /**
     * 核心Tick方法：处理GUI缓存、间隔控制、迭代范围更新和方块迭代
     */
    public void tick() {
        // GUI缓存倒计时
        if (guiCacheTicks > 0) {
            guiCacheTicks--;
        } else {
            guiQueue.clear();
            renderIndex = 0;
        }

        // 执行间隔控制
        int tickInterval = getTickInterval();
        if (tickInterval > 0) {
            long currentTickTime = ClientPlayerTickManager.getCurrentHandlerTime();
            if (lastTickTime != -1L && currentTickTime - lastTickTime < tickInterval) {
                return;
            }
            lastTickTime = currentTickTime;
        }

        // 基础检查
        if (!isPrinterEnable()) {
            lastPos = null;
            return;
        }

        if (!isConfigAllowed()) {
            lastPos = null;
            return;
        }

        updateVariables();
        if (mc == null || level == null || player == null || connection == null || gameMode == null || gameType == null) {
            lastPos = null;
            return;
        }

        updateBox();

        // 方案六：空闲退避门槛（盒子重建已在 updateBox 内重置退避）
        if (idleBackoffEnabled() && shouldSkipForIdleBackoff()) {
            return;
        }

        // 例如填充和拍流体等需要额外方块的模式，需要提前处理好转换
        preprocess();

        // 执行方块迭代
        if (!iterateBlocks()) {
            lastPos = null;
        }
    }

    /**
     * 更新交互盒：根据玩家位置和配置动态调整迭代范围
     */
    private void updateBox() {
        if (boxRef == null) return;

        BlockPos eyePos = new BlockPos(new Vec3i((int) Math.round(player.getX()), (int) Math.round(player.getEyeY()), (int) Math.round(player.getZ())));
        PrinterBox box = boxRef.get();

        int currentRange = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue()
                ? (int) PlayerUtils.getInteractionRange(5)
                : getWorkRange();

        // 运动感知：按玩家移动主导轴优先扫描新进入的层，减少高速移动漏扫
        // 该配置仅作用于并行破坏（MINE）模式，不影响放置等其他模式
        boolean fastDirectionalPrint = Configs.Print.PRINT_FAST_DIRECTIONAL_PLACEMENT.getBooleanValue()
                && getPrintMode() == PrintModeType.PRINTER;
        boolean adaptive = (Configs.Core.MOVE_ADAPTIVE_ITERATION.getBooleanValue()
                && getPrintMode() == PrintModeType.MINE) || fastDirectionalPrint;
        if (fastDirectionalPrint && player.getDeltaMovement().length() * 20.0D < 14.0D) {
            adaptive = false;
        }
        int dominantAxis = -1;
        int dominantSign = 1;
        if (adaptive && this.prevPlayerBlockPos != null) {
            int dx = eyePos.getX() - this.prevPlayerBlockPos.getX();
            int dy = eyePos.getY() - this.prevPlayerBlockPos.getY();
            int dz = eyePos.getZ() - this.prevPlayerBlockPos.getZ();
            int adx = Math.abs(dx);
            int ady = Math.abs(dy);
            int adz = Math.abs(dz);
            if (adx >= ady && adx >= adz) {
                dominantAxis = 0;
                dominantSign = dx >= 0 ? 1 : -1;
            } else if (ady >= adz) {
                dominantAxis = 1;
                dominantSign = dy >= 0 ? 1 : -1;
            } else {
                dominantAxis = 2;
                dominantSign = dz >= 0 ? 1 : -1;
            }
            if (adx == 0 && ady == 0 && adz == 0) {
                dominantAxis = -1;
            }
        }
        if (fastDirectionalPrint && adaptive && dominantAxis < 0) {
            double dxMotion = player.getDeltaMovement().x;
            double dyMotion = player.getDeltaMovement().y;
            double dzMotion = player.getDeltaMovement().z;
            double axMotion = Math.abs(dxMotion);
            double ayMotion = Math.abs(dyMotion);
            double azMotion = Math.abs(dzMotion);
            if (axMotion >= ayMotion && axMotion >= azMotion) {
                dominantAxis = 0;
                dominantSign = dxMotion >= 0.0D ? 1 : -1;
            } else if (ayMotion >= azMotion) {
                dominantAxis = 1;
                dominantSign = dyMotion >= 0.0D ? 1 : -1;
            } else {
                dominantAxis = 2;
                dominantSign = dzMotion >= 0.0D ? 1 : -1;
            }
        }
        this.prevPlayerBlockPos = eyePos;

        // 检查是否需要重建交互盒
        boolean needRebuild = box == null
                || !box.equals(lastBox)
                || lastPos == null
                || expandRange != currentRange
                || !lastPos.closerThan(eyePos, getWorkRange() * 0.4);
        if (adaptive && dominantAxis >= 0 && lastPos != null) {
            // 主导轴移动超过半个范围时也提前重建，让盒子跟上运动方向
            int moved = dominantAxis == 0 ? eyePos.getX() - lastPos.getX()
                    : dominantAxis == 1 ? eyePos.getY() - lastPos.getY()
                    : eyePos.getZ() - lastPos.getZ();
            if (Math.abs(moved) > currentRange * 0.5) {
                needRebuild = true;
            }
        }

        if (needRebuild) {
            lastPos = eyePos;
            expandRange = currentRange;

            if (adaptive && dominantAxis >= 0 && Configs.Core.MOTION_AHEAD.getIntegerValue() > 0) {
                box = buildMotionBox(eyePos, expandRange, dominantAxis, dominantSign, Configs.Core.MOTION_AHEAD.getIntegerValue());
            } else {
                box = new PrinterBox(eyePos).expand(expandRange, expandRange, expandRange);
            }
            lastBox = box;
            boxRef.set(box);

            box.iterationMode = (IterationOrderType) Configs.Core.ITERATION_ORDER.getOptionListValue();
            // 先全部按用户反转配置设置，再覆盖主导轴
            box.xIncrement = !Configs.Core.X_REVERSE.getBooleanValue();
            box.yIncrement = !Configs.Core.Y_REVERSE.getBooleanValue();
            box.zIncrement = !Configs.Core.Z_REVERSE.getBooleanValue();
            if (adaptive && dominantAxis >= 0) {
                // 把移动主导轴放到最外层，优先扫描运动前方的新层
                IterationOrderType.Axis primary = dominantAxis == 0 ? IterationOrderType.Axis.X
                        : dominantAxis == 1 ? IterationOrderType.Axis.Y : IterationOrderType.Axis.Z;
                box.iterationMode = IterationOrderType.primaryFirst(box.iterationMode, primary);
                // 主导轴从运动方向一侧开始扫（运动前方即 buildMotionBox 延伸的"前方新层"），
                // 其余两轴沿用用户反转配置。
                boolean fromFront = !(dominantSign > 0);
                if (dominantAxis == 0) {
                    box.xIncrement = fromFront;
                } else if (dominantAxis == 1) {
                    box.yIncrement = fromFront;
                } else {
                    box.zIncrement = fromFront;
                }
            }

            cachedIterator = null;

            // 新盒子可能覆盖未扫过的区域，立即恢复逐 tick 扫描
            idleBackoffTicks = 1;
            nextScanAllowedAt = -1L;
        }
    }

    /**
     * 构建带运动方向预扫的交互盒：主导轴沿运动方向多延伸 ahead 格
     */
    private PrinterBox buildMotionBox(BlockPos eye, int range, int dominantAxis, int sign, int ahead) {
        int xe = eye.getX();
        int ye = eye.getY();
        int ze = eye.getZ();
        int minX = xe - range;
        int maxX = xe + range;
        int minY = ye - range;
        int maxY = ye + range;
        int minZ = ze - range;
        int maxZ = ze + range;
        if (dominantAxis == 0) {
            if (sign > 0) maxX += ahead; else minX -= ahead;
        } else if (dominantAxis == 1) {
            if (sign > 0) maxY += ahead; else minY -= ahead;
        } else {
            if (sign > 0) maxZ += ahead; else minZ -= ahead;
        }
        return new PrinterBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /**
     * 执行方块迭代
     * @return 是否被中断
     */
    private boolean iterateBlocks() {
        if (boxRef == null || !canExecute()) return false;
    
        PrinterBox box = boxRef.get();
        if (box == null || !canIterate()) return false;
    
        if (cachedIterator == null) {
            cachedIterator = box.iterator();
            // 新盒：重置Y层切片追踪
            lastSweptY = Integer.MIN_VALUE;
        }
    
        int maxExecs = getMaxExecutions();
        int timeLimit = getIterationTimeLimit();
        int execCount = 0;
        int workCandidates = 0;
    
        boolean debugMode = Configs.Core.DEBUG_OUTPUT.getBooleanValue();
        boolean needRangeCheck = needsRangeCheck();
        boolean isSchematic = isSchematicHandler();
    
        long startTime = timeLimit > 0 ? System.nanoTime() : 0;
        long timeLimitNanos = timeLimit * 1_000_000L;
        int checkInterval = 10;
        int iterCount = 0;
    
        skipIteration.set(false);
        guiQueue.clear();
        renderIndex = 0;
    
while (cachedIterator.hasNext()) {
            if (skipIteration.get() || ActionManager.INSTANCE.needWaitModifyLook) {
                stopIteration(true);
                return true;
            }

            BlockPos pos = cachedIterator.next();
            if (pos == null) continue;

            // 方案二（分层续扫）：仅在进入新的一层Y时检查时间预算，
            // 保证一整层Y（Y平面）被扫完才可能截断，避免高速移动时中间层被半截丢弃。
            if (timeLimit > 0 && pos.getY() != lastSweptY && lastSweptY != Integer.MIN_VALUE
                    && System.nanoTime() - startTime >= timeLimitNanos) {
                stopIteration(true);
                return true;
            }
            lastSweptY = pos.getY();

            // 兜底：防止单层过大导致预算无限拖长（仅截断同一层，不影响Y层完整性）
            if (timeLimit > 0 && ++iterCount % checkInterval == 0
                    && System.nanoTime() - startTime >= timeLimitNanos) {
                stopIteration(true);
                return true;
            }

            if (!PlayerUtils.canInteracted(pos)) continue;
    
            if (needRangeCheck) {
                if (isSchematic ? !LitematicaUtils.isSchematicBlock(pos)
                        : !LitematicaUtils.isWithinSelection1ModeRange(pos)) {
                    continue;
                }
    
                if (selectionType != null && !PlayerUtils.isPositionInSelectionRange(player, pos, selectionType)) {
                    continue;
                }
            }
    
            if (debugMode) {
                GuiBlockInfo gui = isSchematic
                        ? new GuiBlockInfo(level, SchematicWorldHandler.getSchematicWorld(), pos)
                        : new GuiBlockInfo(level, null, pos);
                gui.interacted = true;
                gui.posInSelectionRange = true;
                gui.execute = !isOnCooldown(pos) && canProcessPos(pos);
                addGuiInfo(gui);
            }

            // 方案一：判定缓存命中 CORRECT 的格位直接跳过整条昂贵链路（冷却/上下文/指南构建）
            if (isVerifiedNoWork(pos)) {
                continue;
            }
            workCandidates++;

            if (!isOnCooldown(pos) && canProcessPos(pos)) {
                executeIteration(pos, skipIteration);

                if (skipIteration.get() || (maxExecs > 0 && ++execCount >= maxExecs)) {
                    stopIteration(true);
                    return true;
                }
            }
        }

        cachedIterator = null;
        stopIteration(false);

        // 方案六：完整一轮结束——零待处理则指数退避，有工作则恢复逐 tick
        if (idleBackoffEnabled()) {
            idleBackoffTicks = workCandidates == 0
                    ? Math.min(idleBackoffTicks * 2, MAX_IDLE_BACKOFF_TICKS)
                    : 1;
            nextScanAllowedAt = ClientPlayerTickManager.getCurrentHandlerTime() + idleBackoffTicks;
        }
        return false;
    }

    protected void stopIteration(boolean interrupt) {
        // 如果被打断，保留迭代器状态以便下次继续
        // 如果完成迭代，cachedIterator 会在 iterateBlocks 中被置 null
    }

    protected boolean isSchematicHandler() {
        return false;
    }

    /**
     * 方案一：判定缓存是否已确认该格位无需处理（CORRECT）。
     * 默认不启用；打印处理器覆写后 consult {@code SchematicStateCache}。
     */
    protected boolean isVerifiedNoWork(BlockPos pos) {
        return false;
    }

    /**
     * 方案六：是否启用空闲退避（完整空轮后拉长扫描间隔，失效信号立即恢复）。
     */
    protected boolean idleBackoffEnabled() {
        return false;
    }

    private boolean shouldSkipForIdleBackoff() {
        long revision = SchematicStateCache.INSTANCE.getRevision();
        if (revision != lastSeenCacheRevision) {
            // 有任何失效信号（世界方块变化/原理图变化/换维度）→ 立即恢复逐 tick 扫描
            lastSeenCacheRevision = revision;
            idleBackoffTicks = 1;
            nextScanAllowedAt = -1L;
            return false;
        }
        return nextScanAllowedAt > ClientPlayerTickManager.getCurrentHandlerTime();
    }

    /**
     * 添加GUI信息到队列
     */
    private void addGuiInfo(GuiBlockInfo info) {
        if (info != null) {
            guiQueue.add(info);
            guiCacheTicks = 20;
        }
    }

    /**
     * 获取下一个GUI信息（渲染阶段调用）
     */
    @Nullable
    public GuiBlockInfo nextGuiInfo() {
        if (guiQueue.isEmpty()) return null;

        GuiBlockInfo[] arr = guiQueue.toArray(new GuiBlockInfo[0]);
        if (renderIndex >= arr.length) {
            renderIndex = 0;
            return arr[arr.length - 1];
        }
        return arr[renderIndex++];
    }

    /**
     * 获取最后一个GUI信息
     */
    @Nullable
    public GuiBlockInfo getLastGuiInfo() {
        if (guiQueue.isEmpty()) return null;
        GuiBlockInfo[] arr = guiQueue.toArray(new GuiBlockInfo[0]);
        return arr[arr.length - 1];
    }

    public void setGuiInfo(@Nullable GuiBlockInfo info) {
        addGuiInfo(info);
    }

    public int getGuiQueueSize() {
        return guiQueue.size();
    }

    /**
     * 配置层面的执行权限校验
     */
    private boolean isConfigAllowed() {
        if (!ConfigUtils.isPrinterEnable()) return false;

        if (printMode != null && enableConfig != null) {
            WorkingModeType mode = (WorkingModeType) Configs.Core.WORK_MODE.getOptionListValue();
            return switch (mode) {
                case SINGLE -> Configs.Core.WORK_MODE_TYPE.getOptionListValue().equals(printMode);
                case MULTI -> enableConfig.getBooleanValue();
            };
        }

        return enableConfig == null || enableConfig.getBooleanValue();
    }

    protected int getTickInterval() {
        return -1;
    }

    protected int getMaxExecutions() {
        return -1;
    }

    /**
     * 获取迭代时间限制（毫秒），0表示禁用
     */
    protected int getIterationTimeLimit() {
        return Configs.Core.ITERATION_TIME_LIMIT.getIntegerValue();
    }

    protected void preprocess() {
    }

    protected boolean canExecute() {
        return true;
    }

    protected boolean canIterate() {
        return true;
    }

    public boolean canProcessPos(BlockPos pos) {
        return true;
    }

    /**
     * 单次方块迭代的核心执行方法，子类重写实现具体逻辑
     */
    protected void executeIteration(BlockPos pos, AtomicReference<Boolean> skipIteration) {
    }

    /**
     * 判断方块是否处于冷却中
     */
    public boolean isOnCooldown(@Nullable BlockPos pos) {
        if (level == null || pos == null) return true;
        return BlockPosCooldownManager.INSTANCE.isOnCooldown(level, id, pos);
    }

    public boolean isOnCooldown(String name, @Nullable BlockPos pos) {
        if (level == null || pos == null) return true;
        return BlockPosCooldownManager.INSTANCE.isOnCooldown(level, cachedCooldownType(name), pos);
    }

    /**
     * 设置方块冷却时间
     */
    public void setCooldown(@Nullable BlockPos pos, int ticks) {
        if (level == null || pos == null || ticks < 1) return;
        BlockPosCooldownManager.INSTANCE.setCooldown(level, id, pos, ticks);
    }

    public void setCooldown(String name, @Nullable BlockPos pos, int ticks) {
        if (level == null || pos == null || ticks < 1) return;
        BlockPosCooldownManager.INSTANCE.setCooldown(level, cachedCooldownType(name), pos, ticks);
    }

    /** 命名冷却类型字符串缓存：避免热路径上反复 id + "_" + name 拼接分配 */
    private String cachedCooldownType(String name) {
        return cooldownTypeCache.computeIfAbsent(name, n -> id + "_" + n);
    }


    protected Direction[] getPlayerOrderedByNearest() {
        return Direction.orderedByNearest(player);
    }

    protected Direction getPlayerPlacementDirection() {
        return Direction.orderedByNearest(player)[0].getOpposite();
    }

    protected boolean needsRangeCheck() {
        return true;
    }
}