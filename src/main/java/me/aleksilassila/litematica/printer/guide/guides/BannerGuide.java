package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BlockUtils;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallTorchBlock;

/**
 * 旗帜。
 */
public class BannerGuide extends Guide {

    public BannerGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, WallTorchBlock.FACING).orElse(null);

        if (requiredBlock instanceof BannerBlock) {
            int rotation = getProperty(requiredState, BannerBlock.ROTATION).orElseThrow();
            // 原版 BannerBlock 放置公式为 rotation = round(yaw/22.5)&15（无 +180），
            // 而 rotationToPlayerYaw 会加 180，必须取反向补偿，否则立式旗帜恒差 180°（同 SkullGuide）
            return Result.success(new Action()
                    .setSides(Direction.DOWN)
                    .setLookRotation(BlockUtils.getOppositeRotation(rotation))
                    .setRequiresSupport());
        }
        if (requiredBlock instanceof WallBannerBlock && facing != null) {
            return Result.success(new Action()
                    .setSides(facing.getOpposite())
                    .setLookDirection(facing.getOpposite())
                    .setRequiresSupport());
        }
        return Result.SKIP;
    }
}
