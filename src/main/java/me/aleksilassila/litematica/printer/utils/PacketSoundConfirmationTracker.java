package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

public final class PacketSoundConfirmationTracker {
    private static final long TIMEOUT_TICKS = 40L;
    private static final Map<BlockPos, PendingSound> PENDING_SOUNDS = new HashMap<>();

    private PacketSoundConfirmationTracker() {
    }

    public static void trackPlacement(BlockPos pos, BlockState expectedState) {
        if (pos == null || expectedState == null || !Configs.Print.PRINT_SOUND.getBooleanValue()) {
            return;
        }
        track(pos, expectedState, SoundType.PLACEMENT);
    }

    public static void trackBreak(BlockPos pos, BlockState brokenState) {
        if (pos == null || brokenState == null || !Configs.Mine.BREAK_SOUND.getBooleanValue()) {
            return;
        }
        track(pos, brokenState, SoundType.BREAK);
    }

    private static void track(BlockPos pos, BlockState state, SoundType type) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        prune(level.getGameTime());
        PENDING_SOUNDS.put(pos.immutable(), new PendingSound(state, type, level.getGameTime() + TIMEOUT_TICKS));
    }

    public static void confirmServerBlockUpdate(BlockPos pos, BlockState updatedState) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || pos == null || updatedState == null) {
            return;
        }
        prune(level.getGameTime());
        PendingSound pending = PENDING_SOUNDS.get(pos);
        if (pending == null) {
            return;
        }
        boolean confirmed = pending.type == SoundType.PLACEMENT
                ? updatedState.getBlock() == pending.state.getBlock()
                : updatedState.isAir();
        if (!confirmed) {
            return;
        }
        PENDING_SOUNDS.remove(pos);
        if (pending.type == SoundType.PLACEMENT) {
            level.playLocalSound(pos, pending.state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 1.0F, 0.8F, false);
        } else {
            level.playLocalSound(pos, pending.state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 1.0F, 0.8F, false);
        }
    }

    private static void prune(long currentTick) {
        PENDING_SOUNDS.entrySet().removeIf(entry -> entry.getValue().expiresAtTick < currentTick);
    }

    private enum SoundType {
        PLACEMENT,
        BREAK
    }

    private record PendingSound(BlockState state, SoundType type, long expiresAtTick) {
    }
}
