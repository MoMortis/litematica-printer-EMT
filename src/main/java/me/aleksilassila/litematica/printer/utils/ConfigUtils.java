package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.*;
import fi.dy.masa.malilib.config.options.ConfigOptionList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.NotNull;

public class ConfigUtils {
    @NotNull
    public static final Minecraft client = Minecraft.getInstance();

    /** 进入服务器自启动：进入服务器后重试开启打印机，共 60 次、每次间隔 1 秒（20 tick）。 */
    private static final int AUTO_ENABLE_MAX_ATTEMPTS = 60;
    private static final int AUTO_ENABLE_INTERVAL_TICKS = 20;
    private static int autoEnableAttemptsLeft = 0;
    private static int autoEnableTickCounter = 0;
    private static boolean autoEnableSessionActive = false;

    /**
     * 进入服务器时调用：若开关开启则启动"重试开启打印机"会话。
     * 仅在未处于会话中时启动，死亡重生不重复启动会话。
     */
    public static void startAutoEnableSession() {
        if (Configs.Core.AUTO_ENABLE_PRINTER.getBooleanValue() && !autoEnableSessionActive) {
            autoEnableSessionActive = true;
            autoEnableAttemptsLeft = AUTO_ENABLE_MAX_ATTEMPTS;
            autoEnableTickCounter = 0;
            tryAutoEnableNow();
        }
    }

    /**
     * 每 tick 调用：尝试开启打印机，成功后结束会话；否则按间隔重试至次数耗尽。
     */
    public static void tickAutoEnable() {
        if (!autoEnableSessionActive) {
            return;
        }
        if (ConfigUtils.isPrinterEnable()) {
            // 已开启，结束会话
            resetAutoEnableSession();
            return;
        }
        if (autoEnableAttemptsLeft <= 0) {
            // 尝试次数耗尽，结束会话
            resetAutoEnableSession();
            return;
        }
        autoEnableTickCounter++;
        if (autoEnableTickCounter >= AUTO_ENABLE_INTERVAL_TICKS) {
            autoEnableTickCounter = 0;
            tryAutoEnableNow();
        }
    }

    /** 立即尝试开启打印机并消耗一次尝试 */
    private static void tryAutoEnableNow() {
        if (autoEnableAttemptsLeft > 0) {
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            autoEnableAttemptsLeft--;
        }
    }

    /** 退出服务器时重置会话，使下次进服再次自启动 */
    public static void resetAutoEnableSession() {
        autoEnableSessionActive = false;
        autoEnableAttemptsLeft = 0;
        autoEnableTickCounter = 0;
    }

    public static boolean isPrinterEnable() {
        return Configs.Core.WORK_SWITCH.getBooleanValue();
    }

    public static boolean isMultiMode() {
        return Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI);
    }

    public static boolean isSingleMode() {
        return Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.SINGLE);
    }

    public static boolean isPrintMode() {
        return (Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI) && Configs.Core.PRINT.getBooleanValue())
                || Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.PRINTER;
    }

    public static boolean isMineMode() {
        return (Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI) && Configs.Core.MINE.getBooleanValue())
                || Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.MINE;
    }

    public static boolean isFillMode() {
        return (Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI) && Configs.Core.FILL.getBooleanValue())
                || Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.FILL;
    }

    public static boolean isFluidMode() {
        return (Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI) && Configs.Core.FLUID.getBooleanValue())
                || Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.FLUID;
    }

    public static boolean isBedrockMode() {
        return (Configs.Core.WORK_MODE.getOptionListValue().equals(WorkingModeType.MULTI) && Configs.Hotkeys.BEDROCK.getBooleanValue())
                || Configs.Core.WORK_MODE_TYPE.getOptionListValue() == PrintModeType.BEDROCK;
    }

    public static PrintModeType getPrintModeType() {
        return (PrintModeType) Configs.Core.WORK_MODE_TYPE.getOptionListValue();
    }

    public static int getPlaceCooldown() {
        return Configs.Placement.PLACE_COOLDOWN.getIntegerValue();
    }

    public static int getBreakCooldown() {
        return Configs.Break.BREAK_COOLDOWN.getIntegerValue();
    }

    public static float getBreakProgressThreshold() {
        int value = Configs.Break.BREAK_PROGRESS_THRESHOLD.getIntegerValue();
        if (value < 70) {
            value = 70;
        } else if (value > 100) {
            value = 100;
        }
        return (float) value / 100;
    }

    public static int getWorkRange() {
        return Configs.Core.WORK_RANGE.getIntegerValue();
    }

    public static Direction getFillModeFacing() {
        if (Configs.Fill.FILL_BLOCK_FACING.getOptionListValue() instanceof FillModeFacingType fillModeFacingType) {
            return switch (fillModeFacingType) {
                case DOWN -> Direction.DOWN;
                case UP -> Direction.UP;
                case WEST -> Direction.WEST;
                case EAST -> Direction.EAST;
                case NORTH -> Direction.NORTH;
                case SOUTH -> Direction.SOUTH;
                default -> null;
            };
        }
        return null;
    }

    public static boolean isPositionInSelectionRange(Player player, @NotNull BlockPos pos, ConfigOptionList selectionTypeConfig) {
        if (player == null || selectionTypeConfig == null) {
            return false;
        }
        if (!(selectionTypeConfig.getOptionListValue() instanceof SelectionType selectionType)) {
            return false;
        }
        return switch (selectionType) {
            case LITEMATICA_RENDER_LAYER -> LitematicaUtils.isPositionWithinRange(pos);
            case LITEMATICA_SELECTION_BELOW_PLAYER -> pos.getY() <= Math.floor(player.getY());
            case LITEMATICA_SELECTION_ABOVE_PLAYER -> pos.getY() >= Math.ceil(player.getY());
            default -> true;
        };
    }
}