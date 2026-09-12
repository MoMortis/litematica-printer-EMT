package me.aleksilassila.litematica.printer.printer;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.litematica.selection.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原理图状态与格位判定缓存（扫描性能优化核心）。
 *
 * <p>解决两类每格重复开销：
 * <ol>
 *   <li><b>原理图状态点查</b>：旧实现 {@code LitematicaUtils.getSchematicBlockState} 每次调用都
 *       遍历所有 placement × 所有 subregion 盒并重算镜像/旋转变换。本类将 subregion 盒索引
 *       与变换参数预计算缓存，并按位置缓存点查结果。</li>
 *   <li><b>格位判定</b>：缓存 {@link BlockMatchResult} 结论，"已正确"的格位在迭代时被直接跳过。
 *       结论由三重信号作废，保证不漏扫：
 *       <ul>
 *         <li>现实世界方块变化（{@code ClientLevel.setBlock} mixin 通知，覆盖服务端回包、
 *             本地放置预测、流体/活塞等一切客户端世界写入）；</li>
 *         <li>原理图变化（placement 增删/旋转/镜像/启停/移动原点/子区域原点等指纹变化）；
 *             </li>
 *         <li>保底 TTL（40 tick ≈ 2 秒，兜住指纹未覆盖的罕见变化）。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>线程模型：全部方法仅客户端主线程调用（setBlock mixin 亦在主线程触发）。
 */
public final class SchematicStateCache {
    public static final SchematicStateCache INSTANCE = new SchematicStateCache();

    /** 缓存条目保底重验间隔（tick）。仅在所有失效信号都未覆盖的罕见场景下产生最多 2 秒的滞后 */
    private static final int TTL_TICKS = 40;

    private static final class Entry {
        @Nullable BlockState schematicState;
        long stateTick = Long.MIN_VALUE;
        @Nullable BlockMatchResult verdict;
        long verdictTick = Long.MIN_VALUE;
    }

    /** 现实世界坐标 -> 缓存条目 */
    private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();

    /** 当前绑定的维度（换维度即整体失效） */
    @Nullable
    private ClientLevel boundLevel;

    /** 原理图指纹：per-tick 记忆化，变化即整体失效 */
    private long stampTick = Long.MIN_VALUE;
    private int stamp = Integer.MIN_VALUE;

    /** 修订号：任何实际失效都递增，供空闲退避立即恢复逐 tick 扫描 */
    private long revision = 0L;

    /** subregion 盒索引（预计算盒与放置变换），stamp 变化时重建 */
    private final ArrayList<RegionEntry> regionIndex = new ArrayList<>();
    private boolean regionIndexDirty = true;

    /** 过期清扫间隔（tick）：周期性移除长期未访问的条目，限制稳态表规模 */
    private static final int SWEEP_INTERVAL_TICKS = 100;
    /** 条目保留窗口（tick）：状态与判定均超过该时长未被访问即移除 */
    private static final long ENTRY_RETAIN_TICKS = 200;
    private long lastSweepTick = Long.MIN_VALUE;

    /** 待办清单记忆化 TTL（tick）：主循环内多个候选格的重复调用为 O(1) 命中 */
    private static final int PENDING_LIST_TTL_TICKS = 10;

    /** 物品 -> 待办清单记忆化（物品为注册表单例，identity 语义；revision 变化即失效） */
    private final HashMap<Item, PendingList> pendingLists = new HashMap<>();

    private static final class PendingList {
        final ArrayList<BlockPos> list;
        final long builtTick;
        final long builtRevision;

        PendingList(ArrayList<BlockPos> list, long builtTick, long builtRevision) {
            this.list = list;
            this.builtTick = builtTick;
            this.builtRevision = builtRevision;
        }
    }

    private SchematicStateCache() {
    }

    /** 修订号：任何实际失效（世界方块变化命中缓存 / 原理图指纹变化 / 换维度）都递增 */
    public long getRevision() {
        return revision;
    }

    /**
     * 现实世界方块变化回调（由 ClientLevel.setBlock mixin 调用）。
     * 仅作废该位置的判定结论；原理图状态缓存不受现实世界变化影响，保留。
     */
    public void onWorldBlockChanged(BlockPos pos) {
        Entry e = entries.get(pos.asLong());
        if (e != null && e.verdict != null) {
            e.verdict = null;
            e.verdictTick = Long.MIN_VALUE;
            revision++;
        }
    }

    /**
     * 查询位置对应的原理图方块状态（带缓存）。
     * 返回 null 表示该位置不属于任何启用的 subregion（与旧实现语义一致）。
     */
    @Nullable
    public BlockState getSchematicState(BlockPos pos) {
        long now = ensureFresh();
        long key = pos.asLong();
        Entry e = entries.get(key);
        if (e != null && now - e.stateTick < TTL_TICKS) {
            return e.schematicState;
        }
        BlockState state = querySchematicState(pos);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        e.schematicState = state;
        e.stateTick = now;
        return state;
    }

    /**
     * 该位置是否已有"CORRECT"判定且未被作废（无需任何打印工作）。
     * 调用方在迭代循环中据此直接跳过整条昂贵链路。
     *
     * <p>判定只在目标区块已加载时计算并缓存：区块未加载时不落缓存，
     * 保持与旧实现逐 tick 重算完全一致的行为。
     */
    public boolean isVerifiedNoWork(BlockPos pos, ClientLevel level) {
        long now = ensureFresh();
        if (level != boundLevel) {
            return false;
        }
        long key = pos.asLong();
        Entry e = entries.get(key);
        if (e != null && e.verdict == BlockMatchResult.CORRECT && now - e.verdictTick < TTL_TICKS) {
            return true;
        }
        // 区块未加载：世界状态不可信（读到空气），不缓存判定，交由原流程逐 tick 处理
        if (!level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
            return false;
        }
        BlockState required = getSchematicState(pos);
        if (required == null) {
            // 非原理图方块：不缓存（与旧行为一致，由外层范围检查处理）
            return false;
        }
        BlockState current = level.getBlockState(pos);
        BlockMatchResult verdict = BlockMatchResult.compare(required, current);
        if (e == null) {
            e = new Entry();
            entries.put(key, e);
        }
        e.verdict = verdict;
        e.verdictTick = now;
        return verdict == BlockMatchResult.CORRECT;
    }

    /**
     * 收集缓存中"需要工作且目标物品为 item"的格位（供"优先同种方块"快速路径）。
     * 结果按物品记忆化（短 TTL + 修订号失效）：主循环内多个候选格的重复调用为 O(1) 命中；
     * 记忆化过期或任何失效信号（revision 变化）后重建，重建成本受过期清扫限制的表规模约束。
     * 仅返回已判定过的格位；调用方必须保留原有的全盒权威扫描作为兜底，避免漏判。
     * 返回的列表为缓存共享实例，调用方不得修改。
     */
    public List<BlockPos> getPendingPositions(Item item) {
        long now = ensureFresh();
        PendingList pl = pendingLists.get(item);
        if (pl != null && pl.builtRevision == revision && now - pl.builtTick < PENDING_LIST_TTL_TICKS) {
            return pl.list;
        }
        ArrayList<BlockPos> out = new ArrayList<>();
        for (Long2ObjectMap.Entry<Entry> le : entries.long2ObjectEntrySet()) {
            Entry e = le.getValue();
            if (e.verdict == null || e.verdict == BlockMatchResult.CORRECT) continue;
            if (e.schematicState == null || e.schematicState.getBlock().asItem() != item) continue;
            out.add(BlockPos.of(le.getLongKey()));
        }
        pendingLists.put(item, new PendingList(out, now, revision));
        return out;
    }

    // ==================== 内部实现 ====================

    /** per-tick 记忆化的指纹检查 + 维度绑定检查 + 周期清扫，返回当前游戏刻 */
    private long ensureFresh() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lvl = mc == null ? null : mc.level;
        long now = lvl == null ? 0L : lvl.getGameTime();
        if (lvl != boundLevel) {
            boundLevel = lvl;
            if (!entries.isEmpty()) {
                revision++;
            }
            entries.clear();
            pendingLists.clear();
        }
        if (now != stampTick) {
            int s = computeStamp();
            if (s != stamp) {
                stamp = s;
                if (!entries.isEmpty()) {
                    revision++;
                }
                entries.clear();
                pendingLists.clear();
                regionIndexDirty = true;
            }
            stampTick = now;
        }
        sweepStale(now);
        return now;
    }

    /**
     * 周期清扫：移除状态与判定均长期（{@link #ENTRY_RETAIN_TICKS}）未被访问的条目。
     * 限制稳态表规模，避免大图纸长时间打印后内存与全表遍历成本无限增长；
     * 被清扫的条目若再次被访问会按 TTL 语义正常重算，不影响任何判定结论。
     */
    private void sweepStale(long now) {
        if (entries.isEmpty()) {
            return;
        }
        if (lastSweepTick != Long.MIN_VALUE && now - lastSweepTick < SWEEP_INTERVAL_TICKS) {
            return;
        }
        lastSweepTick = now;
        long minTick = now - ENTRY_RETAIN_TICKS;
        entries.values().removeIf(e -> e.stateTick < minTick && e.verdictTick < minTick);
    }

    /**
     * 原理图指纹：placement 增删 + 每个 placement 的旋转/镜像/启用状态 + <b>放置原点</b> +
     * 各启用子区域的原点/旋转/镜像/启用状态。
     * 任何影响"世界坐标位置或形状"的变化都必须触发失效：原点不进指纹的话，移动原理图后
     * 世界坐标索引停留在旧位置，打印机会继续在原位置打印、新位置查不到方块。
     */
    private static int computeStamp() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        Collection<SchematicPlacement> placements = manager.getAllSchematicsPlacements();
        int s = placements.size();
        for (SchematicPlacement p : placements) {
            s = 31 * s + System.identityHashCode(p);
            s = 31 * s + p.getRotation().ordinal();
            s = 31 * s + p.getMirror().ordinal();
            s = 31 * s + (p.isEnabled() ? 1 : 0);
            s = 31 * s + Long.hashCode(p.getOrigin().asLong());
            for (SubRegionPlacement sub : p.getEnabledRelativeSubRegionPlacements().values()) {
                s = 31 * s + Long.hashCode(sub.getPos().asLong());
                s = 31 * s + sub.getRotation().ordinal();
                s = 31 * s + sub.getMirror().ordinal();
                s = 31 * s + (sub.isEnabled() ? 1 : 0);
            }
        }
        return s;
    }

    /**
     * 该世界坐标区域是否与任何已启用 subregion 盒相交
     *（用于"扫描自动寻路"快速跳过远离原理图的子区块）。
     */
    public boolean intersectsSchematic(BlockPos min, BlockPos max) {
        ensureRegionIndex();
        for (int i = 0; i < regionIndex.size(); i++) {
            PrinterBox box = regionIndex.get(i).box;
            if (box.minX <= max.getX() && box.maxX >= min.getX()
                    && box.minY <= max.getY() && box.maxY >= min.getY()
                    && box.minZ <= max.getZ() && box.maxZ >= min.getZ()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收集与原理图相交的所有子区块最小角坐标（世界坐标，去重）。
     * 供"扫描自动寻路"DFS 找中心（离玩家最近的未放置方块所在子区块）用：
     * 直接由 subregion 盒展开子区块范围，无需逐格扫描。
     */
    public void collectIntersectingSections(java.util.function.Consumer<BlockPos> out) {
        ensureRegionIndex();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int i = 0; i < regionIndex.size(); i++) {
            PrinterBox box = regionIndex.get(i).box;
            int sx0 = box.minX >> 4;
            int sx1 = box.maxX >> 4;
            int sy0 = box.minY >> 4;
            int sy1 = box.maxY >> 4;
            int sz0 = box.minZ >> 4;
            int sz1 = box.maxZ >> 4;
            for (int sx = sx0; sx <= sx1; sx++) {
                for (int sy = sy0; sy <= sy1; sy++) {
                    for (int sz = sz0; sz <= sz1; sz++) {
                        if (seen.add(BlockPos.asLong(sx, sy, sz))) {
                            out.accept(new BlockPos(sx << 4, sy << 4, sz << 4));
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private BlockState querySchematicState(BlockPos pos) {
        ensureRegionIndex();
        for (int i = 0; i < regionIndex.size(); i++) {
            RegionEntry re = regionIndex.get(i);
            if (!re.box.contains(pos)) {
                continue;
            }
            BlockPos local = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    pos, re.placement.getSchematic(), re.regionName, re.placement, re.region, re.container);
            if (local != null) {
                BlockState state = re.container.get(local.getX(), local.getY(), local.getZ());
                return re.transform(state);
            }
        }
        return null;
    }

    private void ensureRegionIndex() {
        if (!regionIndexDirty) {
            return;
        }
        regionIndex.clear();
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            for (Map.Entry<String, Box> entry : placement.getSubRegionBoxes(
                    SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet()) {
                String regionName = entry.getKey();
                SubRegionPlacement region = placement.getRelativeSubRegionPlacement(regionName);
                if (region == null || !region.matchesRequirement(
                        SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED)) {
                    continue;
                }
                LitematicaSchematic schematic = placement.getSchematic();
                if (schematic == null) {
                    continue;
                }
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                if (container == null) {
                    continue;
                }
                Box box = entry.getValue();
                Rotation mainRotation = placement.getRotation();
                Mirror mirrorMain = placement.getMirror();
                regionIndex.add(new RegionEntry(
                        placement, region, regionName, container,
                        new PrinterBox(box.getPos1(), box.getPos2()),
                        mirrorMain == Mirror.NONE ? null : mirrorMain,
                        effectiveSubMirror(mainRotation, region.getMirror()),
                        combinedRotation(mainRotation, region.getRotation())));
            }
        }
        regionIndexDirty = false;
    }

    /**
     * 子区域有效镜像：主放置旋转为 90/270 度时左右互换
     * （与 {@code LitematicaUtils.transformPlacementState} 旧逻辑一致）。
     */
    @Nullable
    private static Mirror effectiveSubMirror(Rotation mainRotation, Mirror subMirror) {
        if (subMirror == Mirror.NONE) {
            return null;
        }
        if (mainRotation == Rotation.CLOCKWISE_90 || mainRotation == Rotation.COUNTERCLOCKWISE_90) {
            return subMirror == Mirror.LEFT_RIGHT ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT;
        }
        return subMirror;
    }

    @Nullable
    private static Rotation combinedRotation(Rotation mainRotation, Rotation subRotation) {
        Rotation combined = mainRotation.getRotated(subRotation);
        return combined == Rotation.NONE ? null : combined;
    }

    /** 索引条目：subregion 盒 + 预计算的放置变换 */
    private static final class RegionEntry {
        final SchematicPlacement placement;
        final SubRegionPlacement region;
        final String regionName;
        final LitematicaBlockStateContainer container;
        final PrinterBox box;
        @Nullable final Mirror mirrorMain;
        @Nullable final Mirror mirrorSub;
        @Nullable final Rotation rotation;

        private RegionEntry(SchematicPlacement placement, SubRegionPlacement region, String regionName,
                            LitematicaBlockStateContainer container, PrinterBox box,
                            @Nullable Mirror mirrorMain, @Nullable Mirror mirrorSub, @Nullable Rotation rotation) {
            this.placement = placement;
            this.region = region;
            this.regionName = regionName;
            this.container = container;
            this.box = box;
            this.mirrorMain = mirrorMain;
            this.mirrorSub = mirrorSub;
            this.rotation = rotation;
        }

        @Nullable
        BlockState transform(@Nullable BlockState state) {
            if (state == null) {
                return null;
            }
            BlockState s = state;
            if (mirrorMain != null) {
                s = s.mirror(mirrorMain);
            }
            if (mirrorSub != null) {
                s = s.mirror(mirrorSub);
            }
            if (rotation != null) {
                s = s.rotate(rotation);
            }
            return s;
        }
    }
}
