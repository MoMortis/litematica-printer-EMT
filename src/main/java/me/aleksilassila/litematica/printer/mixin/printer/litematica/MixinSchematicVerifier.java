package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import com.google.common.collect.ArrayListMultimap;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import me.aleksilassila.litematica.printer.utils.SchematicVerifierChunkUpdater;
import me.aleksilassila.litematica.printer.utils.SchematicVerifierExtension;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import fi.dy.masa.malilib.util.IntBoundingBox;

@Mixin(SchematicVerifier.class)
public abstract class MixinSchematicVerifier implements SchematicVerifierExtension {
    @Shadow private ClientLevel worldClient;
    @Shadow private WorldSchematic worldSchematic;
    @Shadow private SchematicPlacement schematicPlacement;
    @Shadow private Object2ObjectOpenHashMap<BlockPos, SchematicVerifier.BlockMismatch> blockMismatches;
    @Shadow private Object2IntOpenHashMap<BlockState> correctStateCounts;
    @Shadow private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> missingBlocksPositions;
    @Shadow private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> extraBlocksPositions;
    @Shadow private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongBlocksPositions;
    @Shadow private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongStatesPositions;
    @Shadow private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> diffBlocksPositions;
    @Shadow private int schematicBlocks;
    @Shadow private int clientBlocks;
    @Shadow private int correctStatesCount;
    @Shadow private java.util.HashSet<Pair<BlockState, BlockState>> ignoredMismatches;

    @Invoker("verifyChunk")
    abstract boolean printer$verifyChunk(ChunkAccess client, ChunkAccess schematic, IntBoundingBox box);

    @Invoker("updateMismatchOverlays")
    abstract void printer$updateMismatchOverlays();

    @Invoker("checkBlockStates")
    abstract void printer$checkBlockStates(int x, int y, int z, BlockState expected, BlockState found);

    @Inject(method = "verifyChunk", at = @At("HEAD"))
    private void printer$beginChunk(ChunkAccess client, ChunkAccess schematic, IntBoundingBox box,
                                    CallbackInfoReturnable<Boolean> cir) {
        SchematicVerifierChunkUpdater.beginScan((SchematicVerifier) (Object) this, client, box);
    }

    @Inject(method = "verifyChunk", at = @At("RETURN"))
    private void printer$finishChunk(ChunkAccess client, ChunkAccess schematic, IntBoundingBox box,
                                     CallbackInfoReturnable<Boolean> cir) {
        SchematicVerifierChunkUpdater.finishScan((SchematicVerifier) (Object) this);
    }

    @Inject(method = "checkBlockStates", at = @At("HEAD"))
    private void printer$recordBlock(int x, int y, int z, BlockState expected, BlockState found, CallbackInfo ci) {
        SchematicVerifierChunkUpdater.recordBlock((SchematicVerifier) (Object) this, x, y, z, expected, found);
    }

    @Inject(method = "checkBlockStates", at = @At("RETURN"))
    private void printer$recordMismatchPosition(int x, int y, int z, BlockState expected, BlockState found, CallbackInfo ci) {
        SchematicVerifierChunkUpdater.recordMismatchPosition((SchematicVerifier) (Object) this, new BlockPos(x, y, z));
    }

    @Inject(method = "reset", at = @At("HEAD"))
    private void printer$reset(CallbackInfo ci) {
        SchematicVerifierChunkUpdater.clearVerifier((SchematicVerifier) (Object) this);
    }

    @Inject(method = "startVerification", at = @At("HEAD"))
    private void printer$startVerification(ClientLevel worldClient, WorldSchematic worldSchematic,
                                           SchematicPlacement placement,
                                           fi.dy.masa.malilib.interfaces.ICompletionListener completionListener,
                                           CallbackInfo ci) {
        SchematicVerifierChunkUpdater.clearVerifier((SchematicVerifier) (Object) this);
    }

    @Override public boolean printer$isFinished() { return ((SchematicVerifier) (Object) this).isFinished(); }
    @Override public ClientLevel printer$getWorldClient() { return worldClient; }
    @Override public WorldSchematic printer$getWorldSchematic() { return worldSchematic; }
    @Override public SchematicPlacement printer$getPlacement() { return schematicPlacement; }
    @Override public Object2ObjectOpenHashMap<BlockPos, SchematicVerifier.BlockMismatch> printer$getMismatches() { return blockMismatches; }
    @Override public ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getMissing() { return missingBlocksPositions; }
    @Override public ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getExtra() { return extraBlocksPositions; }
    @Override public ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getWrongBlock() { return wrongBlocksPositions; }
    @Override public ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getWrongState() { return wrongStatesPositions; }
    @Override public ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> printer$getDiffBlock() { return diffBlocksPositions; }
    @Override public Object2IntOpenHashMap<BlockState> printer$getCorrectCounts() { return correctStateCounts; }
    @Override public SchematicVerifier.BlockMismatch printer$getMismatch(BlockPos pos) { return blockMismatches.get(pos); }
    @Override public void printer$removeMismatch(BlockPos pos, SchematicVerifier.BlockMismatch mismatch) {
        blockMismatches.remove(pos);
        Pair<BlockState, BlockState> pair = Pair.of(mismatch.stateExpected, mismatch.stateFound);
        switch (mismatch.mismatchType) {
            case MISSING -> missingBlocksPositions.remove(pair, pos);
            case EXTRA -> extraBlocksPositions.remove(pair, pos);
            case WRONG_BLOCK -> wrongBlocksPositions.remove(pair, pos);
            case WRONG_STATE -> wrongStatesPositions.remove(pair, pos);
            case DIFF_BLOCK -> diffBlocksPositions.remove(pair, pos);
            default -> { }
        }
    }
    @Override public void printer$invokeVerifyChunk(ChunkAccess client, ChunkAccess schematic, IntBoundingBox box) { printer$verifyChunk(client, schematic, box); }
    @Override public void printer$adjustCounts(int schematic, int client, int correct) { schematicBlocks += schematic; clientBlocks += client; correctStatesCount += correct; }
    @Override public void printer$adjustCorrectStateCount(BlockState state, int amount) { correctStateCounts.addTo(state, amount); }
    @Override public void printer$updateOverlays() { printer$updateMismatchOverlays(); }
    @Override public boolean printer$isIgnored(BlockState expected, BlockState found) { return ignoredMismatches.contains(Pair.of(expected, found)); }
    @Override public void printer$refreshPosition(BlockPos pos, BlockState expected, BlockState found) {
        printer$checkBlockStates(pos.getX(), pos.getY(), pos.getZ(), expected, found);
    }
}
