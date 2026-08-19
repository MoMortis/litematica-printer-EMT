package me.aleksilassila.litematica.printer.utils;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.apache.commons.lang3.tuple.Pair;
import fi.dy.masa.malilib.util.IntBoundingBox;

public interface SchematicVerifierExtension {
    boolean printer$isFinished();
    ClientLevel printer$getWorldClient();
    WorldSchematic printer$getWorldSchematic();
    SchematicPlacement printer$getPlacement();
    Object2ObjectOpenHashMap<BlockPos, SchematicVerifier.BlockMismatch> printer$getMismatches();
    ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getMissing();
    ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getExtra();
    ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getWrongBlock();
    ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getWrongState();
    ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getDiffBlock();
    Object2IntOpenHashMap<BlockState> printer$getCorrectCounts();
    SchematicVerifier.BlockMismatch printer$getMismatch(BlockPos pos);
    void printer$removeMismatch(BlockPos pos, SchematicVerifier.BlockMismatch mismatch);
    void printer$invokeVerifyChunk(ChunkAccess client, ChunkAccess schematic, IntBoundingBox box);
    void printer$adjustCounts(int schematicBlocks, int clientBlocks, int correctStates);
    void printer$adjustCorrectStateCount(BlockState state, int amount);
    void printer$updateOverlays();
    boolean printer$isIgnored(BlockState expected, BlockState found);
    void printer$refreshPosition(BlockPos pos, BlockState expected, BlockState found);
}
