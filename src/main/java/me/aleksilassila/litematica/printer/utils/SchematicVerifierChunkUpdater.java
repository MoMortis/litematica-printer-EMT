package me.aleksilassila.litematica.printer.utils;

import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.ChunkSchematic;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.util.IntBoundingBox;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public final class SchematicVerifierChunkUpdater {
    private static final Queue<ReloadRequest> QUEUE = new ArrayDeque<>();
    private static final Set<Long> QUEUED = new HashSet<>();
    private static final Map<SchematicVerifier, Map<ScanKey, ScanContribution>> SCANS = new HashMap<>();
    private static final Map<SchematicVerifier, ScanContribution> ACTIVE_SCANS = new HashMap<>();
    private static final Map<SchematicVerifier, Long2ObjectOpenHashMap<PositionSnapshot>> INTERACTIVE_SNAPSHOTS = new HashMap<>();

    private SchematicVerifierChunkUpdater() { }

    public static void enqueue(int x, int z) {
        if (!Configs.Core.SCHEMATIC_VERIFIER_OPTIMIZATION.getBooleanValue()) return;
        SchematicVerifier verifier = getVerifier();
        if (verifier == null) {
            return;
        }
        SchematicVerifierExtension ext = (SchematicVerifierExtension) verifier;
        if (ext.printer$getPlacement().getBoxesWithinChunk(x, z).isEmpty()) {
            return;
        }
        long key = key(x, z);
        if (QUEUED.add(key)) {
            QUEUE.add(new ReloadRequest(x, z));
        }
    }

    public static void tick() {
        if (!Configs.Core.SCHEMATIC_VERIFIER_OPTIMIZATION.getBooleanValue()) {
            clear();
            return;
        }
        SchematicVerifier verifier = getVerifier();
        if (verifier == null) {
            return;
        }
        SchematicVerifierExtension ext = (SchematicVerifierExtension) verifier;
        refreshInteractiveRange(verifier, ext);
        if (QUEUE.isEmpty()) return;
        if (System.nanoTime() - DataManager.getClientTickStartTime() >= 50_000_000L) return;
        ReloadRequest request = QUEUE.peek();
        int x = request.x;
        int z = request.z;
        ClientLevelAccess chunks = getChunks(ext, x, z);
        if (chunks == null) return;
        QUEUE.remove();
        QUEUED.remove(key(x, z));
        removeChunk(verifier, x, z, ext);
        ImmutableMap<String, IntBoundingBox> boxes = ext.printer$getPlacement().getBoxesWithinChunk(x, z);
        for (IntBoundingBox box : boxes.values()) ext.printer$invokeVerifyChunk(chunks.client, chunks.schematic, box);
        ext.printer$updateOverlays();
    }

    public static void beginScan(SchematicVerifier verifier, ChunkAccess client, IntBoundingBox box) {
        if (!Configs.Core.SCHEMATIC_VERIFIER_OPTIMIZATION.getBooleanValue()) return;
        //#if MC < 260102
        ScanContribution scan = new ScanContribution(client.getPos().x, client.getPos().z, box);
        //#else
        //$$ ScanContribution scan = new ScanContribution(client.getPos().x(), client.getPos().z(), box);
        //#endif
        ACTIVE_SCANS.put(verifier, scan);
    }

    public static void recordBlock(SchematicVerifier verifier, int x, int y, int z, BlockState expected, BlockState found) {
        ScanContribution scan = activeScan(verifier);
        if (scan == null) return;
        if (!expected.isAir()) scan.schematicBlocks++;
        if (!found.isAir()) scan.clientBlocks++;
        if (!((SchematicVerifierExtension) verifier).printer$isIgnored(expected, found)
                && (expected == found || expected.isAir() && found.isAir())) {
            scan.correctCounts.addTo(found, 1);
            if (!expected.isAir()) scan.correctStates++;
        }
    }

    public static void recordMismatchPosition(SchematicVerifier verifier, BlockPos pos) {
        ScanContribution scan = activeScan(verifier);
        if (scan != null && ((SchematicVerifierExtension) verifier).printer$getMismatch(pos) != null) {
            scan.errorPositions.add(pos.immutable());
        }
    }

    public static void finishScan(SchematicVerifier verifier) { saveScan(verifier); }

    private static void saveScan(SchematicVerifier verifier) {
        ScanContribution scan = ACTIVE_SCANS.remove(verifier);
        if (scan != null) SCANS.computeIfAbsent(verifier, ignored -> new HashMap<>()).put(scan.key(), scan);
    }

    public static void clearVerifier(SchematicVerifier verifier) { SCANS.remove(verifier); ACTIVE_SCANS.remove(verifier); clearQueue(); }
    public static void clear() { SCANS.clear(); ACTIVE_SCANS.clear(); INTERACTIVE_SNAPSHOTS.clear(); clearQueue(); }

    private static void clearQueue() { QUEUE.clear(); QUEUED.clear(); }

    private static void refreshInteractiveRange(SchematicVerifier verifier, SchematicVerifierExtension ext) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || ext.printer$getWorldClient() == null || ext.printer$getWorldSchematic() == null) return;
        int radius = (int) Math.ceil(PlayerUtils.getInteractionRange(5));
        BlockPos center = minecraft.player.blockPosition();
        int chunkRadius = radius / 16 + 1;
        LongOpenHashSet visited = new LongOpenHashSet();
        Long2ObjectOpenHashMap<PositionSnapshot> snapshots = INTERACTIVE_SNAPSHOTS.computeIfAbsent(verifier, ignored -> new Long2ObjectOpenHashMap<>());
        for (int chunkX = (center.getX() >> 4) - chunkRadius; chunkX <= (center.getX() >> 4) + chunkRadius; chunkX++) {
            for (int chunkZ = (center.getZ() >> 4) - chunkRadius; chunkZ <= (center.getZ() >> 4) + chunkRadius; chunkZ++) {
                ImmutableMap<String, IntBoundingBox> boxes = ext.printer$getPlacement().getBoxesWithinChunk(chunkX, chunkZ);
                for (IntBoundingBox box : boxes.values()) {
                    int minX = Math.max(box.minX(), center.getX() - radius - 1);
                    int maxX = Math.min(box.maxX(), center.getX() + radius + 1);
                    int minY = Math.max(box.minY(), center.getY() - radius - 1);
                    int maxY = Math.min(box.maxY(), center.getY() + radius + 1);
                    int minZ = Math.max(box.minZ(), center.getZ() - radius - 1);
                    int maxZ = Math.min(box.maxZ(), center.getZ() + radius + 1);
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            for (int x = minX; x <= maxX; x++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                long packed = pos.asLong();
                                if (!visited.add(packed) || !PlayerUtils.canInteracted(pos)) continue;
                                BlockState expected = ext.printer$getWorldSchematic().getBlockState(pos);
                                BlockState found = ext.printer$getWorldClient().getBlockState(pos);
                                PositionSnapshot old = snapshots.get(packed);
                                if (old == null) {
                                    old = new PositionSnapshot(pos, expected, found, ext.printer$getMismatch(pos));
                                }
                                removePositionContribution(verifier, ext, old);
                                ext.printer$refreshPosition(pos, expected, found);
                                addBlockTotals(ext, expected, found);
                                snapshots.put(packed, new PositionSnapshot(pos, expected, found, ext.printer$getMismatch(pos)));
                            }
                        }
                    }
                }
            }
        }
        ext.printer$updateOverlays();
    }

    private static void removePositionContribution(SchematicVerifier verifier, SchematicVerifierExtension ext, PositionSnapshot snapshot) {
        if (snapshot.mismatch != null) {
            ext.printer$removeMismatch(snapshot.pos, snapshot.mismatch);
        } else if (!ext.printer$isIgnored(snapshot.expected, snapshot.found)
                && (snapshot.expected == snapshot.found || snapshot.expected.isAir() && snapshot.found.isAir())) {
            ext.printer$adjustCorrectStateCount(snapshot.found, -1);
            ext.printer$adjustCounts(snapshot.expected.isAir() ? 0 : -1, snapshot.found.isAir() ? 0 : -1,
                    snapshot.expected.isAir() ? 0 : -1);
            return;
        }
        ext.printer$adjustCounts(snapshot.expected.isAir() ? 0 : -1, snapshot.found.isAir() ? 0 : -1, 0);
    }

    private static void addBlockTotals(SchematicVerifierExtension ext, BlockState expected, BlockState found) {
        ext.printer$adjustCounts(expected.isAir() ? 0 : 1, found.isAir() ? 0 : 1, 0);
    }

    private static ScanContribution activeScan(SchematicVerifier verifier) {
        return ACTIVE_SCANS.get(verifier);
    }

    private static SchematicVerifier getVerifier() {
        SchematicPlacement placement = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        if (placement == null || !placement.hasVerifier()) return null;
        SchematicVerifier verifier = placement.getSchematicVerifier();
        return ((SchematicVerifierExtension) verifier).printer$isFinished() ? verifier : null;
    }

    private static ClientLevelAccess getChunks(SchematicVerifierExtension ext, int x, int z) {
        if (ext.printer$getWorldClient() == null || ext.printer$getWorldSchematic() == null) return null;
        BlockPos pos = new BlockPos(x << 4, 0, z << 4);
        int loaded = 0;
        for (int cx = x - 1; cx <= x + 1; cx++) {
            for (int cz = z - 1; cz <= z + 1; cz++) {
                if (WorldUtils.isClientChunkLoaded(ext.printer$getWorldClient(), cx, cz)) loaded++;
            }
        }
        if (loaded != 9) {
            return null;
        }
        WorldSchematic schematic = ext.printer$getWorldSchematic();
        if (!schematic.getChunkSource().hasChunk(x, z)) {
            return null;
        }
        ChunkAccess client = ext.printer$getWorldClient().getChunk(x, z, ChunkStatus.FULL, false);
        ChunkSchematic source = schematic.getChunk(x, z);
        if (client == null || source == null) {
            return null;
        }
        return new ClientLevelAccess(client, source);
    }

    private static void removeChunk(SchematicVerifier verifier, int x, int z, SchematicVerifierExtension ext) {
        Map<ScanKey, ScanContribution> map = SCANS.get(verifier);
        if (map == null) return;
        ArrayList<ScanKey> keys = new ArrayList<>();
        for (ScanKey scanKey : map.keySet()) if (scanKey.x == x && scanKey.z == z) keys.add(scanKey);
        for (ScanKey scanKey : keys) {
            ScanContribution scan = map.remove(scanKey);
            for (BlockPos pos : scan.errorPositions) {
                SchematicVerifier.BlockMismatch mismatch = ext.printer$getMismatch(pos);
                if (mismatch != null) ext.printer$removeMismatch(pos, mismatch);
            }
            for (Object2IntOpenHashMap.Entry<BlockState> entry : scan.correctCounts.object2IntEntrySet()) {
                ext.printer$adjustCorrectStateCount(entry.getKey(), -entry.getIntValue());
            }
            ext.printer$adjustCounts(-scan.schematicBlocks, -scan.clientBlocks, -scan.correctStates);
        }
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private record ClientLevelAccess(ChunkAccess client, ChunkSchematic schematic) { }
    private record ReloadRequest(int x, int z) { }
    private record ScanKey(int x, int z, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }
    private record PositionSnapshot(BlockPos pos, BlockState expected, BlockState found, SchematicVerifier.BlockMismatch mismatch) { }
    private static final class ScanContribution {
        private final int x, z, minX, minY, minZ, maxX, maxY, maxZ;
        private final Set<BlockPos> errorPositions = new HashSet<>();
        private final Object2IntOpenHashMap<BlockState> correctCounts = new Object2IntOpenHashMap<>();
        private int schematicBlocks, clientBlocks, correctStates;
        private ScanContribution(int x, int z, IntBoundingBox box) {
            this.x = x; this.z = z; minX = box.minX(); minY = box.minY(); minZ = box.minZ();
            maxX = box.maxX(); maxY = box.maxY(); maxZ = box.maxZ();
        }
        private ScanKey key() { return new ScanKey(x, z, minX, minY, minZ, maxX, maxY, maxZ); }
    }

}
