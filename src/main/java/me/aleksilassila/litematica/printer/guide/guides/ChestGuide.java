package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 箱子
 */
public class ChestGuide extends Guide {

    /**
     * 双箱子放置状态：记录组内位置首次以"第一半"身份放置的时间戳（毫秒）。
     * 放置速度过快时服务器可能尚未把第一个箱子同步回客户端，
     * 用于等待确认后再放第二半，避免两个半箱都按第一半逻辑放置导致不合并。
     */
    private static final Map<String, Long> DOUBLE_CHEST_PENDING = new HashMap<>();
    private static final long PARTNER_CONFIRM_TIMEOUT_MS = 1000;

    public ChestGuide(SchematicBlockContext context) {
        super(context);
    }

    @Override
    protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        Direction facing = getProperty(requiredState, ChestBlock.FACING).orElseThrow();

        Direction facingOpposite = facing.getOpposite();
        ChestType chestType = getProperty(requiredState, BlockStateProperties.CHEST_TYPE).orElse(ChestType.SINGLE);

        // 收集所有不与其他箱子相邻的面
        Map<Direction, Vec3> noChestSides = new HashMap<>();
        for (Direction side : Direction.values()) {
            if (level.getBlockState(blockPos.relative(side)).getBlock() instanceof ChestBlock) {
                continue;
            }
            noChestSides.put(side, Vec3.ZERO);
        }

        if (chestType == ChestType.SINGLE) {
            // 单箱子：强制潜行放置，避免与已有箱子意外合并
            return Result.success(new Action()
                    .setSides(noChestSides)
                    .setLookDirection(facingOpposite)
                    .setWaitForHorizontalLook(false)
                    .setShift(true));
        }

        // 双箱子：先潜行放置一半（对准非箱面），再对着已放好的一半潜行放置另一半
        // （Java 版中潜行放置但对准箱子时，两个箱子仍会合并）
        Direction partnerDir = chestType == ChestType.LEFT
                ? facing.getClockWise()
                : facing.getCounterClockWise();
        BlockPos partnerPos = blockPos.relative(partnerDir);

        // 伙伴已确认放置 → 放第二半：只对准伙伴点击并潜行，触发自动合并
        if (level.getBlockState(partnerPos).getBlock() instanceof ChestBlock) {
            clearPending(blockPos);
            clearPending(partnerPos);
            return Result.success(new Action()
                    .setSides(partnerDir)
                    .setLookDirection(facingOpposite)
                    .setWaitForHorizontalLook(false)
                    .setShift(true));
        }

        // 伙伴未放置：把自己当第一半。放置后等待服务器确认（超时阈值内），
        // 期间如果伙伴位置被确认则上方分支会放第二半；超时未确认（丢包/延迟）则重试第一半。
        long now = System.currentTimeMillis();
        Long firstTry = DOUBLE_CHEST_PENDING.get(pendingKey(blockPos));
        if (firstTry == null || now - firstTry >= PARTNER_CONFIRM_TIMEOUT_MS) {
            DOUBLE_CHEST_PENDING.put(pendingKey(blockPos), now);
            DOUBLE_CHEST_PENDING.put(pendingKey(partnerPos), now);
            return Result.success(new Action()
                    .setSides(noChestSides)
                    .setLookDirection(facingOpposite)
                    .setWaitForHorizontalLook(false)
                    .setShift(true));
        }

        // 仍在等待确认：本 tick 跳过
        return Result.SKIP;
    }

    @Override
    protected Result onBuildActionWrongState(BlockMatchResult state) {
        // 朝向放错的箱子无法通过交互修正，破坏后重放；
        // ChestType（单箱/双箱半边）差异由 missing 分支的合并流程处理，不能破坏，保持跳过
        Direction requiredFacing = getProperty(requiredState, ChestBlock.FACING).orElse(null);
        Direction currentFacing = getProperty(currentState, ChestBlock.FACING).orElse(null);
        if (requiredFacing != null && currentFacing != requiredFacing
                && Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue()
                && Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue()
                && BreakUtils.canBreakBlock(blockPos)
                && BreakUtils.breakRestriction(level, blockPos, currentState)) {
            BreakUtils.INSTANCE.add(context);
        }
        return Result.SKIP;
    }

    private String pendingKey(BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.asLong();
    }

    private void clearPending(BlockPos pos) {
        DOUBLE_CHEST_PENDING.remove(pendingKey(pos));
    }
}
