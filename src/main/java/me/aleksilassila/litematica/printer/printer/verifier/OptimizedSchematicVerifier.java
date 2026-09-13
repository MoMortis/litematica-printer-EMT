package me.aleksilassila.litematica.printer.printer.verifier;

import com.google.common.collect.HashMultimap;
import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import me.aleksilassila.litematica.printer.Reference;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.BlockInfoListType;
import fi.dy.masa.litematica.util.IgnoreBlockRegistry;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.litematica.util.PositionUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.LayerRange;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.UUID;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 「验证器优化」开启时的投影验证器实现（继承原版 {@link SchematicVerifier}，
 * 由 {@code MixinSchematicPlacement} 在放置首次取验证器时替换，重进服务器后生效）。
 *
 * <h2>与原版的差异</h2>
 * <ul>
 *   <li><b>按子区块分桶存储</b>：错误不再存全局 multimap，而是
 *       {@code 区块 → 不匹配条目 → IntOpenHashSet(区块内打包坐标)}（打包 x4|z4|(y+64)9 bit），
 *       BlockPos 仅在渲染选点/遍历回调时按需物化。每个错误 ~10B（原版 ~80B），
 *       1000x64x1000 全量缺失时 ~600MB（原版需数 GB 直接堆溢出）。</li>
 *   <li><b>后台线程扫描</b>：主线程只做区块快照（拷贝客户端/原理图区块的
 *       LevelChunkSection 副本，预算 2~4ms/tick），分类在单个守护线程上对快照执行，
 *       全程零 World/DataManager 访问；主线程改动区块与 worker 无竞争。</li>
 *   <li><b>全路径时长预算</b>：快照采集与增量重校验各有独立预算（原版重校验无上限，
 *       且删除走 multimap 值列表线性扫描；本实现为集合 O(1) 删除）。</li>
 *   <li><b>增量完整性</b>：所有方块变化无条件入队（原版只复查"原本就有错误"的坐标，
 *       在正确位置上新产生的错误永远漏检），扫描完成后先消化积压变化再宣告完成，
 *       消除扫描期间快照过期。</li>
 *   <li><b>选点近似</b>：最近 N 个渲染点按"玩家区块由近到远"遍历分桶凑样后局部排序，
 *       替代原版全类别 O(n log n) 全量排序；角落情形与原版可能有数格级出入。</li>
 * </ul>
 *
 * <h2>兼容性</h2>
 * GUI（GuiSchematicVerifier）、世界内线框渲染（OverlayRenderer）、HUD、
 * 悬停信息（RayTraceUtils）与扫描自动寻路/扫描白名单消费方接口全部保持原版语义；
 * 忽略机制按"状态对"生效（与原版一致），采用惰性清除（存储中已忽略条目的位置
 * 不再参与计数/遍历/悬停，坐标在对应位置复查时物理移除）。
 */
public class OptimizedSchematicVerifier extends SchematicVerifier implements VerifierDataView {
    /** 主线程单 tick 快照采集预算（纳秒） */
    private static final long SNAPSHOT_BUDGET_NS = 3_000_000L;
    /** 主线程单 tick 增量重校验预算（纳秒） */
    private static final long RECHECK_BUDGET_NS = 1_500_000L;
    /** 快照队列容量（区块数）：有界形成背压，内存上界 ~32 x 数百 KB */
    private static final int JOB_QUEUE_CAPACITY = 32;
    /** 最近选点：候选收集量 = maxEntries x 该余量后排序截断 */
    private static final int NEAREST_OVERSAMPLE = 2;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    // ==================== 共享存储（读写锁保护） ====================

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 不匹配条目注册表：一个条目 = 类型 + 状态对（下标从 1 开始，0 作映射表哨兵） */
    private final List<Entry> entries = new ArrayList<>();
    private final Map<Entry, Integer> entryIndex = new HashMap<>();
    /** 条目下标（1 基） -> 物理位置数 */
    private final Int2IntOpenHashMap entryCounts = new Int2IntOpenHashMap();
    /** 被忽略的条目（下标 1 基）：物理位置保留但不参与计数/遍历/悬停 */
    private final IntOpenHashSet ignoredEntries = new IntOpenHashSet();
    /** 被忽略的状态对：分类时直接跳过（与原版一致） */
    private final HashSet<Pair<BlockState, BlockState>> ignoredPairs = new HashSet<>();
    /** 每类有效错误数（物理数 - 被忽略条目数） */
    private final int[] typeEffective = new int[MismatchType.values().length];
    /** 区块坐标(ChunkPos.asLong) -> 分桶数据 */
    private final Long2ObjectOpenHashMap<ChunkData> byChunk = new Long2ObjectOpenHashMap<>();
    /** 世界坐标打包值(BlockPos.asLong) -> 条目下标（1 基，0 = 无错误） */
    private final Long2IntOpenHashMap mismatchByPos = new Long2IntOpenHashMap();
    private final Object2IntOpenHashMap<BlockState> correctStateCounts = new Object2IntOpenHashMap<>();
    private int correctStatesCount;
    private int schematicBlocks;
    private int clientBlocks;

    // ==================== 高亮选择（与原版同构，主线程访问） ====================

    private final Set<MismatchType> selectedCategories = new HashSet<>();
    private final HashMultimap<MismatchType, BlockMismatch> selectedEntries = HashMultimap.create();
    private volatile List<MismatchRenderPos> mismatchPositionsForRender = new ArrayList<>();
    private volatile List<BlockPos> mismatchBlockPositionsForRender = new ArrayList<>();
    private final List<BlockPos>[] closestByType = new List[MismatchType.values().length];

    // ==================== 快照管线 ====================

    private final ArrayBlockingQueue<ChunkJob> jobQueue = new ArrayBlockingQueue<>(JOB_QUEUE_CAPACITY);
    /** 队列满时溢出的作业（无界），每 tick 先于新快照补投回队列 */
    private final java.util.concurrent.ConcurrentLinkedQueue<ChunkJob> overflowJobs = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final LongOpenHashSet pendingChunks = new LongOpenHashSet();
    private int totalChunks;
    private volatile boolean scanStarted;
    private volatile boolean scanActive;
    private volatile boolean scanDone;
    private volatile boolean allDispatched;
    private volatile boolean workerRunning;
    /** 当前 worker 线程（所有权守卫）：旧线程退出时不得改写新一轮扫描的共享标志 */
    private volatile Thread scanWorker;

    // ==================== 待验证区块渲染快照（渲染线程只读） ====================
    // pendingChunks 本身主线程读写、非线程安全；渲染线程每帧需要遍历，
    // 故以写时复制数组暴露快照，结构变更（启动/派发/重置）时整体重建
    private volatile long[] pendingSnapshot = new long[0];
    /** 渲染框 Y 范围 = 各启用子区域盒的 Y 包络（启动时计算） */
    private volatile int renderMinY;
    private volatile int renderMaxY;

    // ==================== 世界引用（仅主线程使用） ====================

    @Nullable
    private ClientLevel worldClient;
    @Nullable
    private WorldSchematic worldSchematic;
    @Nullable
    private SchematicPlacement schematicPlacement;
    @Nullable
    private IgnoreBlockRegistry ignoreRegistry;
    /** 所属放置的稳定标识（跨维度 JSON 往返不变），用于验证器注册表 */
    @Nullable
    private UUID ownerHashId;
    /** 所属维度的 ResourceKey：维度守卫用维度键比较而非世界实例比较
     *（原版每次维度切换都会重建 ClientLevel 实例，实例恒不等） */
    @Nullable
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> boundDimension;
    /** 重挂接后需在主线程重算盒/区域/渲染范围（放置对象已更换） */
    private volatile boolean geometryDirty;

    /** 增量复查队列：世界坐标打包值，主线程写入与消费 */
    private final LongOpenHashSet recheckQueue = new LongOpenHashSet();
    /**
     * 暂缓复查队列：原理图/客户端区块尚未加载的坐标。与主队列分离的原因：
     * 若滞留在主队列，固定迭代顺序下它们会占据预算，排在后面的可复查坐标
     * 被饿死（处理不到 → 错误数据与高亮都不更新）
     */
    private final LongOpenHashSet deferredRechecks = new LongOpenHashSet();
    private static final long DEFERRED_RETRY_BUDGET_NS = 500_000L;
    private long lastDeferredRetryGameTime = 0L;
    /** 复查改动过错误数据、需要刷新高亮选点（主线程标志） */
    private boolean overlayRefreshPending;
    /** 上次高亮选点刷新的 gameTime（节流：滞留条目存在时也要周期性刷新）。
     *  注意初值必须为 0 且比较用 now >= last + interval 形式：
     *  Long.MIN_VALUE 初值会让 now - last 溢出为负、节流永假（高亮冻结） */
    private long lastOverlayRefreshGameTime = 0L;

    // ==================== 图标缓存补填（worker 登记，主线程预算内消费） ====================
    // 原版扫描时对每个位置调用 ItemUtils.setItemForBlock 预热方块->图标缓存；
    // 该调用会访问世界（方块实体），worker 不能做，改为 worker 只登记状态、
    // 主线程按 tick 预算补填，保证验证器 GUI 的物品图标不缺失。

    private final Set<BlockState> iconStatesSchematic = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<BlockState> iconStatesClient = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // 原理图作用区域（触碰区块包围盒外扩 1 区块）：区域外的方块变化与验证结果无关，
    // 不入复查队列，否则 WorldSchematic.hasChunk 门控会让队列无界泄漏
    private int areaMinX = Integer.MIN_VALUE;
    private int areaMaxX = Integer.MAX_VALUE;
    private int areaMinZ = Integer.MIN_VALUE;
    private int areaMaxZ = Integer.MAX_VALUE;
    /**
     * 启用子区域盒快照（扁平 6 int/盒：minX,minY,minZ,maxX,maxY,maxZ，主线程写、渲染/复查线程读）。
     * 复查坐标必须落在某盒子内才参与分类：原理图区块在子区域体积外全为空气，
     * 无此门控时区域内的任何方块变化都会被误判为"多余方块"（幻影条目）
     */
    private volatile int[][] schematicBoxes = new int[0][];

    public OptimizedSchematicVerifier() {
        super();
        for (int i = 0; i < closestByType.length; i++) {
            closestByType[i] = new ArrayList<>();
        }
    }

    // ==================== 生命周期 ====================

    @Override
    public void startVerification(ClientLevel worldClient, WorldSchematic worldSchematic,
                                  SchematicPlacement schematicPlacement, fi.dy.masa.malilib.interfaces.ICompletionListener completionListener) {
        UUID hashId = schematicPlacement.getHashId();
        this.ownerHashId = hashId;
        super.startVerification(worldClient, worldSchematic, schematicPlacement, completionListener);
        // super 内部先调用 this.reset()（清我们的数据、停 worker、移除注册表旧条目），
        // 随后注册任务/HUD/活跃表
        this.worldClient = worldClient;
        this.boundDimension = worldClient.dimension();
        this.worldSchematic = worldSchematic;
        this.schematicPlacement = schematicPlacement;
        this.ignoreRegistry = new IgnoreBlockRegistry();
        // 预热 malilib 标签缓存（避免 worker 首次访问时惰性初始化竞争）
        BlockUtilsWarmup.warmup();
        lock.writeLock().lock();
        try {
            this.pendingChunks.clear();
            for (ChunkPos pos : schematicPlacement.getTouchedChunks(SubRegionPlacement.RequiredEnabled.ANY)) {
                this.pendingChunks.add(chunkPosToLong(pos));
            }
            this.totalChunks = this.pendingChunks.size();
        } finally {
            lock.writeLock().unlock();
        }
        this.recomputeAreaBounds();
        this.recomputeSchematicBoxes();
        this.recomputeRenderExtent();
        this.rebuildPendingSnapshot();
        this.scanDone = false;
        this.allDispatched = false;
        this.scanStarted = true;
        this.scanActive = true;
        this.startWorker();
        this.updateRequiredChunksStringList();
        // reset()（由 super.startVerification 内部触发）会把 ownerHashId 清空，
        // 这里必须先恢复再注册，否则注册表键为 null、跨维度保留失效
        this.ownerHashId = hashId;
        VerifierRegistry.put(this.ownerHashId, this);
    }

    @Override
    public void resume() {
        if (this.scanStarted && !this.finished) {
            this.scanActive = true;
            if (this.scanWorker == null) {
                this.startWorker(); // 暂停时 worker 已退出：重启以继续消化剩余队列
            }
        }
    }

    @Override
    public void stopVerification() {
        this.scanActive = false;
        this.stopWorker();
    }

    @Override
    public void reset() {
        this.stopWorker();
        VerifierRegistry.remove(this.ownerHashId);
        this.ownerHashId = null;
        super.reset();
        lock.writeLock().lock();
        try {
            this.entries.clear();
            this.entryIndex.clear();
            this.entryCounts.clear();
            this.ignoredEntries.clear();
            // 注意：ignoredPairs（用户忽略设置）与原版 clearData 行为一致，跨重验保留，
            // 仅 resetIgnoredStateMismatches()（GUI"重置忽略"按钮）负责清除。
            // 条目注册表已清空，ignoredEntries 的下标语义随之失效，必须一并清空。
            java.util.Arrays.fill(this.typeEffective, 0);
            this.byChunk.clear();
            this.mismatchByPos.clear();
            this.correctStateCounts.clear();
            this.recheckQueue.clear();
            this.deferredRechecks.clear();
            this.jobQueue.clear();
            this.overflowJobs.clear();
            this.iconStatesSchematic.clear();
            this.iconStatesClient.clear();
            this.pendingChunks.clear();
            this.pendingSnapshot = new long[0];
            this.schematicBoxes = new int[0][];
            this.selectedCategories.clear();
            this.selectedEntries.clear();
            this.mismatchPositionsForRender = new ArrayList<>();
            this.mismatchBlockPositionsForRender = new ArrayList<>();
            for (List<BlockPos> list : this.closestByType) {
                list.clear();
            }
            this.infoHudLines.clear();
        } finally {
            lock.writeLock().unlock();
        }
        this.correctStatesCount = 0;
        this.schematicBlocks = 0;
        this.clientBlocks = 0;
        this.totalChunks = 0;
        this.worldClient = null;
        this.worldSchematic = null;
        this.schematicPlacement = null;
        this.ignoreRegistry = null;
        this.boundDimension = null;
        this.scanStarted = false;
        this.scanActive = false;
        this.scanDone = false;
        this.allDispatched = false;
    }

    /** 依据待验证区块集合计算原理图作用区域包围盒（外扩 1 区块容错）；主线程调用 */
    private void recomputeAreaBounds() {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (LongIterator it = this.pendingChunks.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            minX = Math.min(minX, chunkX(key));
            maxX = Math.max(maxX, chunkX(key));
            minZ = Math.min(minZ, chunkZ(key));
            maxZ = Math.max(maxZ, chunkZ(key));
        }
        if (minX > maxX) {
            this.areaMinX = Integer.MIN_VALUE;
            this.areaMaxX = Integer.MAX_VALUE;
            this.areaMinZ = Integer.MIN_VALUE;
            this.areaMaxZ = Integer.MAX_VALUE;
            return;
        }
        this.areaMinX = (minX << 4) - 16;
        this.areaMaxX = (maxX << 4) + 31;
        this.areaMinZ = (minZ << 4) - 16;
        this.areaMaxZ = (maxZ << 4) + 31;
    }

    /** 依据各启用子区域盒计算渲染框的 Y 包络；主线程调用 */
    private void recomputeRenderExtent() {
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (fi.dy.masa.litematica.selection.Box box : this.schematicPlacement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values()) {
            // Box 的 pos1/pos2 无顺序保证，取两点的 Y 包络
            minY = Math.min(minY, Math.min(box.getPos1().getY(), box.getPos2().getY()));
            maxY = Math.max(maxY, Math.max(box.getPos1().getY(), box.getPos2().getY()));
        }
        if (minY > maxY) {
            this.renderMinY = this.worldClient != null ? this.worldClient.getMinY() : -64;
            this.renderMaxY = this.worldClient != null ? this.worldClient.getMaxY() : 320;
        } else {
            this.renderMinY = minY;
            this.renderMaxY = maxY + 1;
        }
    }

    /** 重建待验证区块渲染快照（写时复制，渲染线程无锁读取） */
    private void rebuildPendingSnapshot() {
        long[] arr = new long[this.pendingChunks.size()];
        int i = 0;
        for (LongIterator it = this.pendingChunks.iterator(); it.hasNext(); ) {
            arr[i++] = it.nextLong();
        }
        this.pendingSnapshot = arr;
    }

    /** 快照各启用子区域盒的世界坐标范围（含 Y），供复查坐标的子区域门控使用 */
    private void recomputeSchematicBoxes() {
        var values = this.schematicPlacement.getSubRegionBoxes(SubRegionPlacement.RequiredEnabled.ANY).values();
        int[][] boxes = new int[values.size()][];
        int i = 0;
        for (fi.dy.masa.litematica.selection.Box box : values) {
            BlockPos p1 = box.getPos1();
            BlockPos p2 = box.getPos2();
            boxes[i++] = new int[] {
                    Math.min(p1.getX(), p2.getX()), Math.min(p1.getY(), p2.getY()), Math.min(p1.getZ(), p2.getZ()),
                    Math.max(p1.getX(), p2.getX()), Math.max(p1.getY(), p2.getY()), Math.max(p1.getZ(), p2.getZ())};
        }
        this.schematicBoxes = boxes;
    }

    private boolean inSchematicBoxes(int x, int y, int z) {
        for (int[] b : this.schematicBoxes) {
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    private void startWorker() {
        this.scanWorker = new Thread(this::workerLoop, "litematica-printer-schematic-verifier");
        this.scanWorker.setDaemon(true);
        this.workerRunning = true;
        this.scanWorker.start();
    }

    private void stopWorker() {
        this.workerRunning = false;
        Thread sw = this.scanWorker;
        this.scanWorker = null;
        if (sw != null) {
            sw.interrupt();
        }
    }

    // ==================== 状态查询 ====================

    @Override
    public boolean isActive() {
        return this.scanActive;
    }

    @Override
    public boolean isPaused() {
        return this.scanStarted && !this.scanActive && !this.finished;
    }

    @Override
    public boolean isFinished() {
        return this.finished;
    }

    @Override
    public int getTotalChunks() {
        return this.totalChunks;
    }

    @Override
    public int getUnseenChunks() {
        return this.pendingChunks.size();
    }

    /** 待验证区块渲染快照（写时复制数组，渲染线程安全读取） */
    public long[] getPendingSnapshot() {
        return this.pendingSnapshot;
    }

    public boolean isScanStarted() {
        return this.scanStarted;
    }

    public int getRenderMinY() {
        return this.renderMinY;
    }

    public int getRenderMaxY() {
        return this.renderMaxY;
    }

    @Override
    public int getSchematicTotalBlocks() {
        lock.readLock().lock();
        try {
            return this.schematicBlocks;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int getRealWorldTotalBlocks() {
        lock.readLock().lock();
        try {
            return this.clientBlocks;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int getCorrectStatesCount() {
        lock.readLock().lock();
        try {
            return this.correctStatesCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public int getMissingBlocks() {
        return effectiveCount(MismatchType.MISSING);
    }

    @Override
    public int getExtraBlocks() {
        return effectiveCount(MismatchType.EXTRA);
    }

    @Override
    public int getMismatchedBlocks() {
        return effectiveCount(MismatchType.WRONG_BLOCK);
    }

    @Override
    public int getMismatchedStates() {
        return effectiveCount(MismatchType.WRONG_STATE);
    }

    @Override
    public int getDiffBlocks() {
        return effectiveCount(MismatchType.DIFF_BLOCK);
    }

    private int effectiveCount(MismatchType type) {
        lock.readLock().lock();
        try {
            return this.typeEffective[type.ordinal()];
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Object2IntOpenHashMap<BlockState> getCorrectStates() {
        // GUI 会遍历该 map；worker 在写锁下持续写入，故返回读锁下的拷贝
        lock.readLock().lock();
        try {
            return new Object2IntOpenHashMap<>(this.correctStateCounts);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 条目注册与记录（写锁内调用） ====================

    private static final class Entry {
        final MismatchType type;
        final Pair<BlockState, BlockState> pair;

        Entry(MismatchType type, Pair<BlockState, BlockState> pair) {
            this.type = type;
            this.pair = pair;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Entry entry)) {
                return false;
            }
            return this.type == entry.type && this.pair.equals(entry.pair);
        }

        @Override
        public int hashCode() {
            int result = this.type.hashCode();
            result = 31 * result + this.pair.hashCode();
            return result;
        }
    }

    /** 区块分桶：条目下标(1 基) -> 区块内打包坐标集合；反向：区块内打包坐标 -> 条目下标 */
    private static final class ChunkData {
        final Int2ObjectOpenHashMap<IntOpenHashSet> byEntry = new Int2ObjectOpenHashMap<>();
        final Int2IntOpenHashMap reverse = new Int2IntOpenHashMap();
    }

    /** 区块内打包坐标：x 4bit | z 4bit | (y+2048) 13bit（覆盖 y -2048..6143，任意常规/自定义世界高度） */
    private static int packLocal(int x, int y, int z) {
        return (((y + 2048) & 0x1FFF) << 8) | ((z & 15) << 4) | (x & 15);
    }

    /**
     * 区块键统一采用原版 ChunkPos 打包布局（x 在低 32 位、z 在高 32 位，与
     * {@code getX(long)}/{@code getZ(long)} 的解码一致），pendingChunks 与
     * 分桶/选点/遍历共用同一布局，杜绝 x/z 转置。
     */
    private static long chunkKey(int x, int z) {
        return packChunkPos(x >> 4, z >> 4);
    }

    private static long packChunkPos(int cx, int cz) {
        return ((long) cz << 32) | (cx & 0xFFFFFFFFL);
    }

    static int chunkX(long key) {
        return (int) key;
    }

    static int chunkZ(long key) {
        return (int) (key >> 32);
    }

    private static long toWorldPacked(long chunkKey, int local) {
        return BlockPos.asLong(
                (chunkX(chunkKey) << 4) + (local & 15),
                (local >> 8) - 2048,
                (chunkZ(chunkKey) << 4) + ((local >> 4) & 15));
    }

    /** 记录一个不匹配位置（调用方持有写锁）。与原版一致：被忽略的状态对不记录。 */
    private void record(MismatchType type, BlockState expected, BlockState found, int x, int y, int z) {
        Pair<BlockState, BlockState> pair = Pair.of(expected, found);
        Entry key = new Entry(type, pair);
        Integer boxed = this.entryIndex.get(key);
        int idx;
        if (boxed == null) {
            this.entries.add(key);
            idx = this.entries.size();
            this.entryIndex.put(key, idx);
            this.entryCounts.put(idx, 0);
        } else {
            idx = boxed;
        }
        this.entryCounts.addTo(idx, 1);
        this.typeEffective[type.ordinal()]++;

        long ck = chunkKey(x, z);
        ChunkData cd = this.byChunk.computeIfAbsent(ck, k -> new ChunkData());
        int local = packLocal(x, y, z);
        cd.byEntry.computeIfAbsent(idx, k -> new IntOpenHashSet()).add(local);
        cd.reverse.put(local, idx);
        this.mismatchByPos.put(BlockPos.asLong(x, y, z), idx);
    }

    /** 移除某坐标的既有记录（调用方持有写锁），返回移除前的条目下标（0 = 无记录） */
    private int unrecord(long worldPacked) {
        int idx = this.mismatchByPos.remove(worldPacked);
        if (idx == 0) {
            return 0;
        }
        Entry entry = this.entries.get(idx - 1);
        int x = BlockPos.getX(worldPacked);
        int y = BlockPos.getY(worldPacked);
        int z = BlockPos.getZ(worldPacked);
        ChunkData cd = this.byChunk.get(chunkKey(x, z));
        if (cd != null) {
            int local = packLocal(x, y, z);
            IntOpenHashSet set = cd.byEntry.get(idx);
            if (set != null) {
                set.rem(local);
                if (set.isEmpty()) {
                    cd.byEntry.remove(idx);
                }
            }
            cd.reverse.remove(local);
        }
        int had = this.entryCounts.get(idx);
        if (had > 0) {
            this.entryCounts.put(idx, had - 1);
            // 已忽略条目的 typeEffective 在 ignoreStateMismatch 时已整体扣除，
            // 此处（复查物理移除）不得再扣，否则有效计数漂移为负
            if (!this.ignoredEntries.contains(idx)) {
                this.typeEffective[entry.type.ordinal()]--;
            }
        }
        return idx;
    }

    // ==================== 分类（与原版 checkBlockStates 判定树一致） ====================

    /**
     * 判定两个状态的错误类型；返回 null 表示"无需记录"（被忽略、或多余方块被
     * 忽略流体/忽略方块配置过滤）。正确状态由调用方另行处理。
     */
    @Nullable
    private MismatchType classify(BlockState schematic, BlockState found) {
        if (schematic.isAir()) {
            if (Configs.Visuals.IGNORE_EXISTING_FLUIDS.getBooleanValue() && found.liquid()) {
                return null;
            }
            if (this.ignoreRegistry != null && this.ignoreRegistry.hasBlock(found.getBlock())) {
                return null;
            }
            return MismatchType.EXTRA;
        }
        if (found.isAir()) {
            return MismatchType.MISSING;
        }
        if (schematic.getBlock() != found.getBlock()) {
            if (Configs.Generic.ENABLE_DIFFERENT_BLOCKS.getBooleanValue()
                    && fi.dy.masa.malilib.util.game.BlockUtils.isInSameGroup(schematic, found)) {
                return fi.dy.masa.malilib.util.game.BlockUtils.matchPropertiesOnly(schematic, found)
                        ? MismatchType.DIFF_BLOCK : MismatchType.WRONG_STATE;
            }
            return MismatchType.WRONG_BLOCK;
        }
        return MismatchType.WRONG_STATE;
    }

    /** 正确/不一致的统一入口（快照分类与复查共用），调用方持有写锁 */
    private void checkStates(BlockPos.MutableBlockPos pos, BlockState schematic, BlockState found) {
        this.iconStatesSchematic.add(schematic);
        this.iconStatesClient.add(found);
        if (found == schematic || (found.isAir() && schematic.isAir())) {
            this.correctStateCounts.addTo(found, 1);
            if (!schematic.isAir()) {
                this.correctStatesCount++;
            }
            return;
        }
        Pair<BlockState, BlockState> pair = Pair.of(schematic, found);
        if (this.ignoredPairs.contains(pair)) {
            return;
        }
        MismatchType type = this.classify(schematic, found);
        if (type != null) {
            this.record(type, schematic, found, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    // ==================== 主线程：快照采集（预算内） ====================

    /** 是否处于验证所属维度（跨维度时暂停一切处理，防止用旧世界引用污染数据）。
     *  比较维度键而非世界实例：原版每次维度切换都会重建 ClientLevel 实例 */
    public boolean isBoundToCurrentDimension() {
        Minecraft mc = Minecraft.getInstance();
        return this.boundDimension != null && mc.level != null
                && mc.level.dimension() == this.boundDimension;
    }

    /** 维度往返后重挂接：绑定新放置对象，几何范围在主线程 execute 中重算 */
    public void rebindPlacement(SchematicPlacement placement) {
        this.schematicPlacement = placement;
        this.geometryDirty = true;
    }

    @Override
    public boolean execute(net.minecraft.util.profiling.ProfilerFiller profiler) {
        if (!this.isBoundToCurrentDimension()) {
            return false; // 其它维度：暂停快照/复查/刷新，防止跨维度污染
        }
        // 维度往返后原版会重建 ClientLevel 实例：重绑为新实例，
        // 否则快照采集/复查会读取已废弃的旧世界（其区块源已清空，扫描永不恢复）
        Minecraft mc = Minecraft.getInstance();
        if (this.worldClient != mc.level) {
            this.worldClient = mc.level;
        }
        if (this.geometryDirty) {
            this.geometryDirty = false;
            this.recomputeAreaBounds();
            this.recomputeSchematicBoxes();
            this.recomputeRenderExtent();
        }
        // 维度往返后 litematica 已重建原理图世界实例，重新绑定（仅所属维度时执行）
        WorldSchematic currentSchematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
        if (currentSchematicWorld != null && this.worldSchematic != currentSchematicWorld) {
            this.worldSchematic = currentSchematicWorld;
        }
        if (this.scanStarted && this.scanActive && !this.scanDone) {
            this.dispatchSnapshots(System.nanoTime() + SNAPSHOT_BUDGET_NS);
        }
        if (this.finished || this.scanDone) {
            this.processRechecks(System.nanoTime() + RECHECK_BUDGET_NS);
        }
        this.drainIconWarmups();
        if (this.scanDone && !this.finished) {
            this.finished = true;
            this.scanStarted = false;
            this.scanActive = false;
            this.notifyListener();
        }
        // 初扫描期间数据持续增长：存在高亮选择时周期性刷新世界内标记，
        // 否则高亮停留在用户点击类别/条目瞬间的快照（大图扫描可持续数分钟）
        if (this.scanStarted && !this.finished
                && (!this.selectedCategories.isEmpty() || !this.selectedEntries.isEmpty())) {
            long now = this.worldClient != null ? this.worldClient.getGameTime() : 0L;
            if (now >= this.lastOverlayRefreshGameTime + 20) {
                this.updateMismatchOverlays();
                this.lastOverlayRefreshGameTime = now;
            }
        }
        return false;
    }

    /**
     * 两版本 ChunkPos 实例打包方法命名不同（1.21.11: {@code toLong()} / 26.1.2: {@code pack()}）；
     * MC 类成员名会被重映射（1.21.11 发布包为 intermediary），无法按名反射查找，
     * 故经项目预处理器分叉（plain 行 = 1.21.11，//$$ 行 = 26.1.2，与 GoCommand 同款写法）。
     * 仅在验证启动时按待验证区块数调用，无热路径开销。
     */
    private static long chunkPosToLong(ChunkPos pos) {
        //#if MC >= 260100
        //$$ return pos.pack();
        //#else
        return pos.toLong();
        //#endif
    }

    private boolean neighborsLoaded(long chunkKey) {
        for (int cx = chunkX(chunkKey) - 1; cx <= chunkX(chunkKey) + 1; cx++) {
            for (int cz = chunkZ(chunkKey) - 1; cz <= chunkZ(chunkKey) + 1; cz++) {
                if (this.worldClient == null || !WorldUtils.isClientChunkLoaded(this.worldClient, cx, cz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 对通过门控的区块采集双世界 section 副本并入队；预算耗尽或队列满即停（区块留在待处理队列） */
    private void dispatchSnapshots(long deadline) {
        // 先补投上一轮溢出的作业（worker 消化出空间后续投）
        while (!this.overflowJobs.isEmpty() && this.jobQueue.offer(this.overflowJobs.peek())) {
            this.overflowJobs.poll();
        }
        LongIterator it = this.pendingChunks.iterator();
        boolean dispatched = false;
        while (it.hasNext()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            long chunkKey = it.nextLong();
            int ccx = chunkX(chunkKey);
            int ccz = chunkZ(chunkKey);
            if (!this.neighborsLoaded(chunkKey)) {
                continue;
            }
            if (this.worldSchematic == null || !this.worldSchematic.getChunkSource().hasChunk(ccx, ccz)) {
                continue;
            }
            List<IntBoundingBox> boxes = new ArrayList<>();
            for (IntBoundingBox box : this.schematicPlacement.getBoxesWithinChunk(ccx, ccz).values()) {
                boxes.add(this.clipToRenderLayers(box));
            }
            SectionSnapshot clientSnap = copySections(this.worldClient.getChunk(ccx, ccz));
            SectionSnapshot schematicSnap = copySections(this.worldSchematic.getChunk(ccx, ccz));
            for (IntBoundingBox box : boxes) {
                ChunkJob job = new ChunkJob(box, clientSnap, schematicSnap);
                if (!this.jobQueue.offer(job)) {
                    this.overflowJobs.add(job); // 队列满：进溢出队列续投，不阻塞该区块完成
                }
            }
            it.remove();
            dispatched = true;
        }
        if (dispatched) {
            this.updateRequiredChunksStringList();
            this.rebuildPendingSnapshot();
        }
        if (this.pendingChunks.isEmpty()) {
            this.allDispatched = true;
        }
    }

    /** RENDER_LAYERS 验证类型时把扫描盒夹到当前渲染层范围（与原版一致；主线程执行） */
    private IntBoundingBox clipToRenderLayers(IntBoundingBox box) {
        if (this.schematicPlacement.getSchematicVerifierType() != BlockInfoListType.RENDER_LAYERS) {
            return box;
        }
        LayerRange range = DataManager.getRenderLayerRange();
        Direction.Axis axis = range.getAxis();
        int minX = axis == Direction.Axis.X ? Math.max(box.minX(), range.getLayerMin()) : box.minX();
        int minY = axis == Direction.Axis.Y ? Math.max(box.minY(), range.getLayerMin()) : box.minY();
        int minZ = axis == Direction.Axis.Z ? Math.max(box.minZ(), range.getLayerMin()) : box.minZ();
        int maxX = axis == Direction.Axis.X ? Math.min(box.maxX(), range.getLayerMax()) : box.maxX();
        int maxY = axis == Direction.Axis.Y ? Math.min(box.maxY(), range.getLayerMax()) : box.maxY();
        int maxZ = axis == Direction.Axis.Z ? Math.min(box.maxZ(), range.getLayerMax()) : box.maxZ();
        return new IntBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static SectionSnapshot copySections(ChunkAccess chunk) {
        LevelChunkSection[] src = chunk.getSections();
        LevelChunkSection[] out = new LevelChunkSection[src.length];
        for (int i = 0; i < src.length; i++) {
            LevelChunkSection section = src[i];
            out[i] = section == null || section.hasOnlyAir() ? null : section.copy();
        }
        return new SectionSnapshot(out, chunk.getMinY());
    }

    // ==================== 工作线程 ====================

    private void workerLoop() {
        while (this.workerRunning) {
            ChunkJob job;
            try {
                job = this.jobQueue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (job == null) {
                if (this.allDispatched && this.overflowJobs.isEmpty()) {
                    break;
                }
                continue;
            }
            try {
                this.verifyJob(job);
            } catch (Throwable t) {
                // 单个作业失败不得让 worker 线程静默死亡（否则 scanDone 永不置位，
                // 验证卡在"验证中"且无任何提示）：记录日志并继续消化剩余作业
                Reference.LOGGER.error("Schematic verifier job failed", t);
            }
        }
        // 所有权守卫：仅当前仍是登记在册的 worker 时才改写共享标志。
        // 否则 reset/重启后，被打断的旧线程跑完手头作业收尾时会把新 worker 关停
        // 或把新一轮 scanDone 误置真（旧线程退出后共享状态交由新 worker 负责）。
        if (this.workerRunning && Thread.currentThread() == this.scanWorker) {
            if (this.allDispatched && this.overflowJobs.isEmpty()) {
                this.scanDone = true;
            }
            this.workerRunning = false;
        }
    }

    private void verifyJob(ChunkJob job) {
        IntBoundingBox box = job.box;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // 按 16 格 y 段分片加写锁：单段最多 16x16x16=4096 格（约 0.1~0.5ms），
        // 避免高纵深作业整段持锁数百毫秒、阻塞主线程/渲染线程的读锁访问
        int y = box.minY();
        while (y <= box.maxY()) {
            // 每段开始前检查：reset/停用后立即中止剩余段，
            // 避免被打断的旧 worker 在新一轮结构上继续写入陈旧数据
            if (!this.workerRunning || Thread.currentThread() != this.scanWorker) {
                return;
            }
            int bandEnd = Math.min(box.maxY(), y | 15);
            lock.writeLock().lock();
            try {
                for (int yy = y; yy <= bandEnd; yy++) {
                    for (int z = box.minZ(); z <= box.maxZ(); z++) {
                        for (int x = box.minX(); x <= box.maxX(); x++) {
                            BlockState stateSchematic = stateAt(job.schematic, x, yy, z);
                            BlockState stateClient = stateAt(job.client, x, yy, z);
                            pos.set(x, yy, z);
                            this.checkStates(pos, stateSchematic, stateClient);
                            if (!stateSchematic.isAir()) {
                                this.schematicBlocks++;
                            }
                            if (!stateClient.isAir()) {
                                this.clientBlocks++;
                            }
                        }
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }
            y = bandEnd + 1;
        }
    }

    private static BlockState stateAt(SectionSnapshot snapshot, int x, int y, int z) {
        int idx = (y - snapshot.minY) >> 4;
        if (idx < 0 || idx >= snapshot.sections.length) {
            return AIR;
        }
        LevelChunkSection section = snapshot.sections[idx];
        return section == null ? AIR : section.getBlockState(x & 15, y & 15, z & 15);
    }

    // ==================== 增量重校验（主线程，预算内） ====================

    @Override
    public void markBlockChanged(BlockPos pos) {
        // 跨维度时忽略：区域门控只有 X/Z，其它维度同 X/Z 范围内的方块变化
        // 若不拦截会被错误计入复查队列
        if (!this.isBoundToCurrentDimension()) {
            return;
        }
        // 与原版不同：区域内无条件入队（含扫描期间），扫描完成后统一消化，
        // 覆盖"原本正确的位置产生新错误"与快照过期两种情形；
        // 区域外直接丢弃（该位置永远无法通过原理图区块门控，入队只会无界泄漏）
        int x = pos.getX();
        int z = pos.getZ();
        if (x < this.areaMinX || x > this.areaMaxX || z < this.areaMinZ || z > this.areaMaxZ) {
            return;
        }
        this.recheckQueue.add(pos.asLong());
    }

    /** 主线程按预算把 worker 登记的状态补进 ItemUtils 图标缓存（代表坐标用玩家位置，方块实体类图标为近似） */
    private void drainIconWarmups() {
        if (this.worldClient == null || this.worldSchematic == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        BlockPos at = mc.player.blockPosition();
        int budget = 512;
        // 先出队再补填：无论成败都不重试（原版对每个状态也只补填一次），
        // 且异常不得逃逸——否则会打断 execute 的完成转换并传导进客户端 tick 循环
        for (Iterator<BlockState> it = this.iconStatesSchematic.iterator(); it.hasNext() && budget > 0; budget--) {
            BlockState state = it.next();
            it.remove();
            try {
                ItemUtils.setItemForBlock(this.worldSchematic, at, state);
            } catch (Throwable t) {
                Reference.LOGGER.error("Schematic verifier icon warmup failed", t);
            }
        }
        for (Iterator<BlockState> it = this.iconStatesClient.iterator(); it.hasNext() && budget > 0; budget--) {
            BlockState state = it.next();
            it.remove();
            try {
                ItemUtils.setItemForBlock(this.worldClient, at, state);
            } catch (Throwable t) {
                Reference.LOGGER.error("Schematic verifier icon warmup failed", t);
            }
        }
    }

    private void processRechecks(long deadline) {
        if ((this.recheckQueue.isEmpty() && this.deferredRechecks.isEmpty())
                || this.worldClient == null || this.worldSchematic == null) {
            return;
        }
        ClientLevel client = this.worldClient;
        WorldSchematic schematic = this.worldSchematic;
        boolean any = false;
        // 主队列：新事件与可立即复查的坐标
        if (this.drainRechecks(this.recheckQueue, client, schematic, deadline, false)) {
            any = true;
        }
        // 暂缓队列：每 20gt 小预算重试（区块加载后即可处理），不影响主队列吞吐
        if (!this.deferredRechecks.isEmpty()) {
            long now = client.getGameTime();
            if (now >= this.lastDeferredRetryGameTime + 20) {
                this.lastDeferredRetryGameTime = now;
                if (this.drainRechecks(this.deferredRechecks, client, schematic,
                        System.nanoTime() + DEFERRED_RETRY_BUDGET_NS, true)) {
                    any = true;
                }
            }
        }
        // 高亮刷新用脏标记 + 节流（10gt）：不要求"整个队列清空"，
        // 暂缓条目长期滞留时已处理部分的最新状态也会周期性上屏
        if (any) {
            this.overlayRefreshPending = true;
        }
        if (this.overlayRefreshPending) {
            long now = client.getGameTime();
            if ((this.recheckQueue.isEmpty() && this.deferredRechecks.isEmpty())
                    || now >= this.lastOverlayRefreshGameTime + 10) {
                this.updateMismatchOverlays();
                this.overlayRefreshPending = false;
                this.lastOverlayRefreshGameTime = now;
            }
        }
    }

    /**
     * 消化一批复查坐标。门控失败（区块未加载）：主队列的坐标移入暂缓队列
     * （避免阻塞后续可复查坐标）；暂缓队列的留在原地等待下次重试。
     * 区域外的坐标（兜底）直接丢弃。
     */
    private boolean drainRechecks(LongOpenHashSet queue, ClientLevel client, WorldSchematic schematic,
                                  long deadline, boolean deferred) {
        boolean any = false;
        LongIterator it = queue.iterator();
        while (it.hasNext()) {
            if (System.nanoTime() >= deadline) {
                break;
            }
            long worldPacked = it.nextLong();
            int x = BlockPos.getX(worldPacked);
            int y = BlockPos.getY(worldPacked);
            int z = BlockPos.getZ(worldPacked);
            if (!this.inSchematicBoxes(x, y, z)) {
                it.remove(); // 子区域体积之外：原理图状态恒为空气，复查只会产生幻影"多余方块"
                continue;
            }
            if (!client.hasChunk(x >> 4, z >> 4) || !schematic.hasChunk(x >> 4, z >> 4)) {
                if (x < this.areaMinX || x > this.areaMaxX || z < this.areaMinZ || z > this.areaMaxZ) {
                    it.remove(); // 区域外兜底丢弃
                } else if (!deferred) {
                    it.remove();
                    this.deferredRechecks.add(worldPacked);
                }
                continue;
            }
            BlockState stateSchematic = schematic.getBlockState(new BlockPos(x, y, z));
            BlockState stateFound = client.getBlockState(new BlockPos(x, y, z));
            lock.writeLock().lock();
            try {
                int oldIdx = this.unrecord(worldPacked);
                if (oldIdx != 0 && !stateFound.isAir()
                        && this.entries.get(oldIdx - 1).pair.getRight().isAir()) {
                    this.clientBlocks++;
                }
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                pos.set(x, y, z);
                this.checkStates(pos, stateSchematic, stateFound);
            } finally {
                lock.writeLock().unlock();
            }
            it.remove();
            any = true;
        }
        return any;
    }

    // ==================== 忽略机制（按状态对，与原版一致） ====================

    @Override
    public void ignoreStateMismatch(BlockMismatch mismatch) {
        this.ignoreStateMismatch(mismatch, true);
    }

    private void ignoreStateMismatch(BlockMismatch mismatch, boolean updateOverlay) {
        Pair<BlockState, BlockState> pair = Pair.of(mismatch.stateExpected, mismatch.stateFound);
        lock.writeLock().lock();
        try {
            if (this.ignoredPairs.add(pair)) {
                Integer boxed = this.entryIndex.get(new Entry(mismatch.mismatchType, pair));
                if (boxed != null) {
                    this.ignoredEntries.add(boxed);
                    this.typeEffective[mismatch.mismatchType.ordinal()] -= this.entryCounts.get(boxed);
                }
                // 与原版一致：忽略条目同时取消其高亮选择
                this.selectedEntries.remove(mismatch.mismatchType, mismatch);
            }
        } finally {
            lock.writeLock().unlock();
        }
        if (updateOverlay) {
            this.updateMismatchOverlays();
        }
    }

    @Override
    public void addIgnoredStateMismatches(java.util.Collection<BlockMismatch> mismatches) {
        for (BlockMismatch mismatch : mismatches) {
            this.ignoreStateMismatch(mismatch, false);
        }
        this.updateMismatchOverlays();
    }

    @Override
    public void resetIgnoredStateMismatches() {
        lock.writeLock().lock();
        try {
            // 回补有效计数：忽略时曾把各条目计数从 typeEffective 整体扣除，
            // 重置忽略后这些条目重新参与计数与结果列表，必须加回；
            // 否则表头计数与列表矛盾，且后续复查的物理移除会走"未忽略"分支重复扣减
            for (IntIterator it = this.ignoredEntries.iterator(); it.hasNext(); ) {
                int idx = it.nextInt();
                Entry entry = this.entries.get(idx - 1);
                this.typeEffective[entry.type.ordinal()] += this.entryCounts.get(idx);
            }
            this.ignoredPairs.clear();
            this.ignoredEntries.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public Set<Pair<BlockState, BlockState>> getIgnoredMismatches() {
        lock.readLock().lock();
        try {
            return new HashSet<>(this.ignoredPairs);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 概览（GUI 列表，按状态对聚合） ====================

    @Override
    public List<BlockMismatch> getMismatchOverviewFor(MismatchType type) {
        ArrayList<BlockMismatch> list = new ArrayList<>();
        if (type == MismatchType.ALL) {
            return this.getMismatchOverviewCombined();
        }
        lock.readLock().lock();
        try {
            this.addCountFor(type, list);
        } finally {
            lock.readLock().unlock();
        }
        return list;
    }

    @Override
    public List<BlockMismatch> getMismatchOverviewCombined() {
        ArrayList<BlockMismatch> list = new ArrayList<>();
        lock.readLock().lock();
        try {
            this.addCountFor(MismatchType.MISSING, list);
            this.addCountFor(MismatchType.EXTRA, list);
            this.addCountFor(MismatchType.WRONG_BLOCK, list);
            this.addCountFor(MismatchType.WRONG_STATE, list);
            this.addCountFor(MismatchType.DIFF_BLOCK, list);
        } finally {
            lock.readLock().unlock();
        }
        list.sort(Comparator.naturalOrder());
        return list;
    }

    private void addCountFor(MismatchType type, List<BlockMismatch> list) {
        for (int i = 0; i < this.entries.size(); i++) {
            Entry entry = this.entries.get(i);
            int count = this.entryCounts.get(i + 1);
            if (entry.type != type || count <= 0 || this.ignoredEntries.contains(i + 1)) {
                continue; // 计数归零的条目（方块已修正）不再出现在结果列表，与原版 multimap 行为一致
            }
            list.add(new BlockMismatch(entry.type, entry.pair.getLeft(), entry.pair.getRight(), count));
        }
    }

    @Override
    public List<Pair<BlockState, BlockState>> getIgnoredStateMismatchPairs(GuiBase gui) {
        ArrayList<Pair<BlockState, BlockState>> list;
        lock.readLock().lock();
        try {
            list = new ArrayList<>(this.ignoredPairs);
        } finally {
            lock.readLock().unlock();
        }
        try {
            list.sort((o1, o2) -> {
                // 与原版一致：按注册名（注册表 id）排序，而非展示名
                String name1 = BuiltInRegistries.BLOCK.getKey(o1.getLeft().getBlock()).toString();
                String name2 = BuiltInRegistries.BLOCK.getKey(o2.getLeft().getBlock()).toString();
                int val = name1.compareTo(name2);
                if (val != 0) {
                    return val;
                }
                name1 = BuiltInRegistries.BLOCK.getKey(o1.getRight().getBlock()).toString();
                name2 = BuiltInRegistries.BLOCK.getKey(o2.getRight().getBlock()).toString();
                return name1.compareTo(name2);
            });
        } catch (Exception e) {
            gui.addMessage(fi.dy.masa.malilib.gui.Message.MessageType.ERROR, "litematica.error.generic.failed_to_sort_list_of_ignored_states");
        }
        return list;
    }

    @Override
    @Nullable
    public BlockMismatch getMismatchForPosition(BlockPos pos) {
        lock.readLock().lock();
        try {
            int idx = this.mismatchByPos.get(pos.asLong());
            if (idx == 0 || this.ignoredEntries.contains(idx)) {
                return null;
            }
            Entry entry = this.entries.get(idx - 1);
            return new BlockMismatch(entry.type, entry.pair.getLeft(), entry.pair.getRight(), 1);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 主动回填的差分判定（读锁）：以本验证器的分类语义计算 pos 处"应有的错误记录"，
     * 与实际记录比对——不一致返回 true（调用方随即 {@link #markBlockChanged} 提交复查）。
     * 被忽略的状态对视为"应无记录"（物理残留待复查移除）。复查时会重读现势状态，
     * 此处仅判定"记录是否与现势一致"，故扫描器侧的陈旧读数至多多提交一次无害复查。
     */
    public boolean probeMismatch(BlockPos pos, BlockState required, BlockState found) {
        lock.readLock().lock();
        try {
            int actual = this.mismatchByPos.get(pos.asLong());
            if (found == required || (found.isAir() && required.isAir())) {
                return actual != 0;
            }
            Pair<BlockState, BlockState> pair = Pair.of(required, found);
            if (this.ignoredPairs.contains(pair)) {
                return actual != 0;
            }
            MismatchType type = this.classify(required, found);
            if (type == null) {
                return actual != 0;
            }
            if (actual == 0) {
                return true;
            }
            Entry entry = this.entries.get(actual - 1);
            return entry.type != type || !entry.pair.equals(pair);
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 高亮选择（与原版语义一致） ====================

    @Override
    public void toggleMismatchCategorySelected(MismatchType type) {
        if (type == MismatchType.CORRECT_STATE) {
            return;
        }
        if (this.selectedCategories.contains(type)) {
            this.selectedCategories.remove(type);
        } else {
            this.selectedCategories.add(type);
            this.selectedEntries.removeAll(type);
        }
        this.updateMismatchOverlays();
    }

    @Override
    public void toggleMismatchEntrySelected(BlockMismatch mismatch) {
        MismatchType type = mismatch.mismatchType;
        if (this.selectedEntries.containsValue(mismatch)) {
            this.selectedEntries.remove(type, mismatch);
        } else {
            this.selectedCategories.remove(type);
            this.selectedEntries.put(type, mismatch);
        }
        this.updateMismatchOverlays();
    }

    @Override
    public boolean isMismatchCategorySelected(MismatchType type) {
        return this.selectedCategories.contains(type);
    }

    @Override
    public boolean isMismatchEntrySelected(BlockMismatch mismatch) {
        return this.selectedEntries.containsValue(mismatch);
    }

    @Override
    public List<MismatchRenderPos> getSelectedMismatchPositionsForRender() {
        return this.mismatchPositionsForRender;
    }

    @Override
    public List<BlockPos> getSelectedMismatchBlockPositionsForRender() {
        return this.mismatchBlockPositionsForRender;
    }

    @Override
    public void updateRequiredChunksStringList() {
        // 两版本 ChunkPos 坐标访问 API 不一致（字段/getter），统一经 packed long 构造
        List<ChunkPos> pending = new ArrayList<>(this.pendingChunks.size());
        for (LongIterator it = this.pendingChunks.iterator(); it.hasNext(); ) {
            long key = it.nextLong();
            pending.add(new ChunkPos(chunkX(key), chunkZ(key)));
        }
        this.updateInfoHudLinesPendingChunks(pending);
    }

    // ==================== 最近选点（按子区块距离分批，替代全量排序） ====================

    private void updateMismatchOverlays() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        int maxEntries = Configs.InfoOverlays.VERIFIER_ERROR_HILIGHT_MAX_POSITIONS.getIntegerValue();
        BlockPos centerPos = player.blockPosition();
        this.updateClosestPositions(centerPos, maxEntries);
        this.combineClosestPositions(centerPos, maxEntries);
        if (this.selectedCategories.size() == 1 && this.selectedEntries.isEmpty()) {
            MismatchType type = this.mismatchPositionsForRender.isEmpty()
                    ? null : this.mismatchPositionsForRender.get(0).type;
            this.updateMismatchPositionStringList(type, this.mismatchPositionsForRender);
        } else {
            this.updateMismatchPositionStringList(null, this.mismatchPositionsForRender);
        }
    }

    private void updateClosestPositions(BlockPos centerPos, int maxEntries) {
        // 分桶列表按区块-玩家距离排序一次，供 5 个类型复用（原为每类型各排一次）
        List<Long2ObjectMap.Entry<ChunkData>> chunks;
        lock.readLock().lock();
        try {
            chunks = new ArrayList<>(this.byChunk.long2ObjectEntrySet());
        } finally {
            lock.readLock().unlock();
        }
        double ccx = centerPos.getX() + 0.5;
        double ccz = centerPos.getZ() + 0.5;
        chunks.sort(Comparator.comparingDouble(e -> {
            double dx = (chunkX(e.getLongKey()) * 16 + 8) - ccx;
            double dz = (chunkZ(e.getLongKey()) * 16 + 8) - ccz;
            return dx * dx + dz * dz;
        }));
        this.selectNearest(MismatchType.DIFF_BLOCK, chunks, centerPos, maxEntries, this.closestByType[MismatchType.DIFF_BLOCK.ordinal()]);
        this.selectNearest(MismatchType.WRONG_BLOCK, chunks, centerPos, maxEntries, this.closestByType[MismatchType.WRONG_BLOCK.ordinal()]);
        this.selectNearest(MismatchType.WRONG_STATE, chunks, centerPos, maxEntries, this.closestByType[MismatchType.WRONG_STATE.ordinal()]);
        this.selectNearest(MismatchType.EXTRA, chunks, centerPos, maxEntries, this.closestByType[MismatchType.EXTRA.ordinal()]);
        this.selectNearest(MismatchType.MISSING, chunks, centerPos, maxEntries, this.closestByType[MismatchType.MISSING.ordinal()]);
    }

    /**
     * 选出某类型离玩家最近的最多 maxEntries 个位置：按区块与玩家的距离由近到远遍历分桶，
     * 凑足 maxEntries x NEAREST_OVERSAMPLE 个候选即停，局部排序截断。
     * （近似选点：与原版全量排序相比，极端角落情形可能有数格级差异。）
     */
    private void selectNearest(MismatchType type, List<Long2ObjectMap.Entry<ChunkData>> chunks,
                               BlockPos centerPos, int maxEntries, List<BlockPos> out) {
        out.clear();
        // 逐条选中时把该类型的选中状态对做成 HashSet，避免对每个条目构造临时 BlockMismatch
        HashSet<Pair<BlockState, BlockState>> selectedPairs = null;
        if (!this.selectedCategories.contains(type)) {
            selectedPairs = new HashSet<>();
            for (BlockMismatch mismatch : this.selectedEntries.get(type)) {
                selectedPairs.add(Pair.of(mismatch.stateExpected, mismatch.stateFound));
            }
        }
        List<BlockPos> candidates = new ArrayList<>(Math.min(maxEntries * NEAREST_OVERSAMPLE + 1, 4096));
        lock.readLock().lock();
        try {
            int need = maxEntries * NEAREST_OVERSAMPLE;
            outer:
            for (Long2ObjectMap.Entry<ChunkData> chunkEntry : chunks) {
                // 快照排序后分区可能已被 reset 清空
                ChunkData cd = this.byChunk.get(chunkEntry.getLongKey());
                if (cd == null) {
                    continue;
                }
                for (Int2ObjectMap.Entry<IntOpenHashSet> setEntry : cd.byEntry.int2ObjectEntrySet()) {
                    Entry entry = this.entries.get(setEntry.getIntKey() - 1);
                    if (entry.type != type || this.ignoredEntries.contains(setEntry.getIntKey())) {
                        continue;
                    }
                    if (selectedPairs != null && !selectedPairs.contains(entry.pair)) {
                        continue;
                    }
                    IntIterator localIt = setEntry.getValue().iterator();
                    while (localIt.hasNext()) {
                        candidates.add(BlockPos.of(toWorldPacked(chunkEntry.getLongKey(), localIt.nextInt())));
                        if (candidates.size() >= need) {
                            break outer;
                        }
                    }
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        PositionUtils.BLOCK_POS_COMPARATOR.setReferencePosition(centerPos);
        PositionUtils.BLOCK_POS_COMPARATOR.setClosestFirst(true);
        candidates.sort(PositionUtils.BLOCK_POS_COMPARATOR);
        int n = Math.min(maxEntries, candidates.size());
        for (int i = 0; i < n; i++) {
            out.add(candidates.get(i));
        }
    }

    private void combineClosestPositions(BlockPos centerPos, int maxEntries) {
        ArrayList<MismatchRenderPos> tempList = new ArrayList<>();
        this.getMismatchRenderPositionFor(MismatchType.WRONG_BLOCK, tempList);
        this.getMismatchRenderPositionFor(MismatchType.DIFF_BLOCK, tempList);
        this.getMismatchRenderPositionFor(MismatchType.WRONG_STATE, tempList);
        this.getMismatchRenderPositionFor(MismatchType.EXTRA, tempList);
        this.getMismatchRenderPositionFor(MismatchType.MISSING, tempList);
        tempList.sort(new RenderPosComparator(centerPos, true));
        int max = Math.min(maxEntries, tempList.size());
        ArrayList<MismatchRenderPos> renderList = new ArrayList<>(max);
        ArrayList<BlockPos> renderBlockList = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            MismatchRenderPos entry = tempList.get(i);
            renderList.add(entry);
            renderBlockList.add(entry.pos);
        }
        // 写时复制 + volatile 写：渲染线程每帧整体换引用，杜绝读到拼接中的列表
        this.mismatchBlockPositionsForRender = renderBlockList;
        this.mismatchPositionsForRender = renderList;
    }

    private void getMismatchRenderPositionFor(MismatchType type, List<MismatchRenderPos> listOut) {
        for (BlockPos pos : this.closestByType[type.ordinal()]) {
            listOut.add(new MismatchRenderPos(type, pos));
        }
    }

    private static final class RenderPosComparator implements Comparator<MismatchRenderPos> {
        private final BlockPos posReference;
        private final boolean closestFirst;

        RenderPosComparator(BlockPos posReference, boolean closestFirst) {
            this.posReference = posReference;
            this.closestFirst = closestFirst;
        }

        @Override
        public int compare(MismatchRenderPos pos1, MismatchRenderPos pos2) {
            double dist1 = pos1.pos.distSqr(this.posReference);
            double dist2 = pos2.pos.distSqr(this.posReference);
            if (dist1 == dist2) {
                return 0;
            }
            return dist1 < dist2 == this.closestFirst ? -1 : 1;
        }
    }

    private void updateMismatchPositionStringList(@Nullable MismatchType mismatchType, List<MismatchRenderPos> positionList) {
        this.infoHudLines.clear();
        if (positionList.isEmpty()) {
            return;
        }
        String rst = GuiBase.TXT_RST;
        if (mismatchType != null) {
            this.infoHudLines.add(String.format("%s%s%s", mismatchType.getFormattingCode(), mismatchType.getDisplayname(), rst));
        } else {
            String title = fi.dy.masa.malilib.util.StringUtils.translate("litematica.gui.title.schematic_verifier_errors");
            this.infoHudLines.add(String.format("%s%s%s", GuiBase.TXT_BOLD, title, rst));
        }
        int count = Math.min(positionList.size(), Configs.InfoOverlays.INFO_HUD_MAX_LINES.getIntegerValue());
        for (int i = 0; i < count; i++) {
            MismatchRenderPos entry = positionList.get(i);
            BlockPos pos = entry.pos;
            String pre = entry.type.getColorCode();
            this.infoHudLines.add(String.format("%sx: %5d, y: %3d, z: %5d%s", pre, pos.getX(), pos.getY(), pos.getZ(), rst));
        }
    }

    // ==================== VerifierDataView（打印机消费方视图） ====================

    @Override
    public boolean forEachMismatch(MismatchType type, MismatchVisitor visitor) {
        lock.readLock().lock();
        try {
            for (Long2ObjectMap.Entry<ChunkData> chunkEntry : this.byChunk.long2ObjectEntrySet()) {
                ChunkData cd = chunkEntry.getValue();
                for (Int2ObjectMap.Entry<IntOpenHashSet> setEntry : cd.byEntry.int2ObjectEntrySet()) {
                    int idx = setEntry.getIntKey();
                    Entry entry = this.entries.get(idx - 1);
                    if (entry.type != type || this.ignoredEntries.contains(idx)) {
                        continue;
                    }
                    IntIterator localIt = setEntry.getValue().iterator();
                    while (localIt.hasNext()) {
                        if (!visitor.accept(entry.pair, toWorldPacked(chunkEntry.getLongKey(), localIt.nextInt()))) {
                            return false;
                        }
                    }
                }
            }
            return true;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Set<MismatchType> getSelectedMismatchTypes() {
        lock.readLock().lock();
        try {
            return new HashSet<>(this.selectedCategories);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public HashMultimap<MismatchType, BlockMismatch> getSelectedMismatchEntries() {
        lock.readLock().lock();
        try {
            HashMultimap<MismatchType, BlockMismatch> copy = HashMultimap.create();
            copy.putAll(this.selectedEntries);
            return copy;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ==================== 快照数据结构 ====================

    /** 双世界 section 副本（不可变，worker 只读） */
    private record SectionSnapshot(@Nullable LevelChunkSection[] sections, int minY) {
    }

    /** 一个扫描作业 = 裁剪后的扫描盒 + 双世界 section 副本 */
    private record ChunkJob(IntBoundingBox box, SectionSnapshot client, SectionSnapshot schematic) {
    }

    /** 预热 malilib 标签缓存（主线程调用，避免 worker 惰性初始化竞争） */
    private static final class BlockUtilsWarmup {
        private static boolean warmed;

        static void warmup() {
            if (!warmed) {
                warmed = true;
                BlockState stone = Blocks.STONE.defaultBlockState();
                fi.dy.masa.malilib.util.game.BlockUtils.isInSameGroup(stone, stone);
                fi.dy.masa.malilib.util.game.BlockUtils.matchPropertiesOnly(stone, stone);
            }
        }
    }
}
