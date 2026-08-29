package me.aleksilassila.litematica.printer.utils;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.SchematicUtils;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.selection.SelectionMode;
import fi.dy.masa.litematica.util.EasyPlaceProtocol;
import fi.dy.masa.litematica.util.PlacementHandler;
import fi.dy.masa.litematica.util.WorldUtils;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

//#if MC < 11900
//$$ import fi.dy.masa.malilib.util.SubChunkPos;
//#endif

@SuppressWarnings({"BooleanMethodIsAlwaysInverted", "BooleanMethodIsAlwaysInverted"})
@Environment(EnvType.CLIENT)
public class LitematicaUtils {
    public static final Minecraft client = Minecraft.getInstance();
    public static final LitematicaUtils INSTANCE = new LitematicaUtils();

    private LitematicaUtils() {
    }

    public static boolean isPositionWithinRange(BlockPos pos) {
        return DataManager.getRenderLayerRange().isPositionWithinRange(pos);
    }

    public static Vec3 usePrecisionPlacement(BlockPos pos, BlockState stateSchematic) {
        if (Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue()) {
            EasyPlaceProtocol protocol = PlacementHandler.getEffectiveProtocolVersion();
            Vec3 hitPos = Vec3.atLowerCornerOf(pos);
            if (protocol == EasyPlaceProtocol.V3) {
                return WorldUtils.applyPlacementProtocolV3(pos, stateSchematic, hitPos);
            } else if (protocol == EasyPlaceProtocol.V2) {
                // Carpet Accurate Block placements protocol support, plus slab support
                return WorldUtils.applyCarpetProtocolHitVec(pos, stateSchematic, hitPos);
            }
        }
        return null;
    }
    /**
     * 判断位置是否位于当前加载的投影范围内。
     *
     * <p>与 Litematica 原版默认范围不同，这里按「原理图子区域内容盒」判定
     * （即原理图实际包含方块的范围），而不是完整的放置盒。放置盒会覆盖大量
     * 空气/空白区域，若按放置盒判定，"多余方块"会把投影盒覆盖的地形也破坏。
     *
     * @param pos 要检测的方块位置
     * @return 如果位置属于图纸结构的一部分，则返回 true，否则返回 false
     */
    public static boolean isSchematicBlock(BlockPos pos) {
        return getSchematicBlockState(pos) != null;
    }

    /** Reads the target state directly from schematic data, independent of render chunks. */
    public static BlockState getSchematicBlockState(BlockPos pos) {
        if (pos == null) {
            return null;
        }
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            for (Map.Entry<String, Box> entry : placement.getSubRegionBoxes(
                    SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED).entrySet()) {
                String regionName = entry.getKey();
                if (!new PrinterBox(entry.getValue().getPos1(), entry.getValue().getPos2()).contains(pos)) {
                    continue;
                }
                SubRegionPlacement region = placement.getRelativeSubRegionPlacement(regionName);
                if (region == null || !region.matchesRequirement(
                        SubRegionPlacement.RequiredEnabled.PLACEMENT_ENABLED)) {
                    continue;
                }
                LitematicaSchematic schematic = placement.getSchematic();
                LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);
                if (container == null) {
                    continue;
                }
                BlockPos local = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                        pos, schematic, regionName, placement, region, container);
                if (local != null) {
                    BlockState state = container.get(local.getX(), local.getY(), local.getZ());
                    // 容器内保存的是未应用放置变换的原始状态（litematica 只在渲染的
                    // schematic 世界里应用旋转/镜像）。这里正向应用与渲染一致的变换，
                    // 否则旋转/镜像过的放置中所有方向性方块的朝向都会偏移同一角度。
                    return transformPlacementState(state, placement, region);
                }
            }
        }
        return null;
    }

    /**
     * 对从 schematic 容器直读的方块状态应用放置变换（镜像/旋转），
     * 与 SchematicUtils.getUntransformedBlockState 互为逆操作，
     * 得到与 schematic 渲染世界一致的状态。
     */
    private static BlockState transformPlacementState(
            BlockState state, SchematicPlacement placement, SubRegionPlacement region) {
        if (state == null) {
            return null;
        }
        Mirror mirrorMain = placement.getMirror();
        Mirror mirrorSub = region.getMirror();
        Rotation mainRotation = placement.getRotation();
        // 主放置旋转为 90/270 度时，子区域镜像需左右互换（与 litematica 反变换逻辑对称）
        if (mirrorSub != Mirror.NONE
                && (mainRotation == Rotation.CLOCKWISE_90 || mainRotation == Rotation.COUNTERCLOCKWISE_90)) {
            mirrorSub = mirrorSub == Mirror.LEFT_RIGHT ? Mirror.FRONT_BACK : Mirror.LEFT_RIGHT;
        }
        if (mirrorMain != Mirror.NONE) {
            state = state.mirror(mirrorMain);
        }
        if (mirrorSub != Mirror.NONE) {
            state = state.mirror(mirrorSub);
        }
        Rotation combinedRotation = mainRotation.getRotated(region.getRotation());
        if (combinedRotation != Rotation.NONE) {
            state = state.rotate(combinedRotation);
        }
        return state;
    }

    public static boolean isWithinSelection1ModeRange(BlockPos pos) {
        AreaSelection selection = DataManager.getSelectionManager().getCurrentSelection();
        if (selection == null) return false;
        if (DataManager.getSelectionManager().getSelectionMode() == SelectionMode.NORMAL) {
            List<Box> arr = selection.getAllSubRegionBoxes();
            for (Box box : arr) {
                if (comparePos(box, pos)) {
                    return true;
                }
            }
            return false;
        } else {
            Box box = selection.getSubRegionBox(DataManager.getSimpleArea().getName());
            return comparePos(box, pos);
        }
    }

    static boolean comparePos(Box box, BlockPos pos) {
        if (box == null || box.getPos1() == null || box.getPos2() == null || pos == null) return false;
        PrinterBox printerBox = new PrinterBox(box.getPos1(), box.getPos2());
        return printerBox.contains(pos);
    }

}