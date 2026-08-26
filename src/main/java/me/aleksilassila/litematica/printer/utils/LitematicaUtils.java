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
                    return container.get(local.getX(), local.getY(), local.getZ());
                }
            }
        }
        return null;
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