package me.aleksilassila.litematica.printer.handler;

import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.Setter;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.*;
import me.aleksilassila.litematica.printer.go.GhastFlyer;
import me.aleksilassila.litematica.printer.go.GoManager;
import me.aleksilassila.litematica.printer.guide.guides.ShulkerPlacementGuard;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PrintTaskController;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.BreakUtils;
import me.aleksilassila.litematica.printer.utils.LitematicaUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

public class ClientPlayerTickManager {
    public static final Minecraft mc = Minecraft.getInstance();

    public static final GuiHandler GUI = new GuiHandler();
    public static final PrintHandler PRINT = new PrintHandler();
    public static final FillHandler FILL = new FillHandler();
    public static final MineHandler MINE = new MineHandler();
    public static final FluidHandler FLUID = new FluidHandler();
    public static final BedrockHandler BEDROCK = new BedrockHandler();

    @Getter
    @Setter
    private static int packetTick;
    @Getter
    private static long currentHandlerTime;

    /** 上一次客户端 tick 所在维度：检测"同一连接内换维度"（不触发 Connection.disconnect） */
    private static ResourceKey<Level> lastDimension;

    /**
     * 换维度（或断线重进）时复位全部按坐标记账的运行时状态。
     * MC 换维度不触发 Connection.disconnect，破坏队列/发包簿记/寻路状态若不清理，
     * 会带着旧维度的坐标到新维度执行（把新维度同坐标的地形当原理图破坏）。
     * 须在 LocalPlayer.tick 顶部调用（打印循环可能被旧破坏队列挂起，检测不能放在 tick() 内部）。
     */
    public static void checkDimensionChange(@Nullable ClientLevel level) {
        ResourceKey<Level> dim = level == null ? null : level.dimension();
        if (dim == null ? lastDimension == null : dim.equals(lastDimension)) {
            return;
        }
        lastDimension = dim;
        resetRuntimeState();
    }

    /** 统一复位：破坏队列 / 打印状态机 / 快速重试与确认簿记 / 潜影盒守卫 / 寻路与乐魂飞行 / 动作队列 */
    public static void resetRuntimeState() {
        PrintTaskController.INSTANCE.reset();
        BreakUtils.INSTANCE.resetRuntime();
        ActionManager.INSTANCE.resetRuntime();
        PRINT.resetFastRetry();
        ShulkerPlacementGuard.INSTANCE.reset();
        GhastFlyer.resetRuntime();
        GoManager.INSTANCE.stop(null);
    }

    public static final ImmutableList<ClientPlayerTickHandler> VALUES = ImmutableList.of(
            GUI, PRINT, FILL, FLUID, MINE, BEDROCK
    );

    public static void tick() {
        // 自动寻路独立于打印处理器：即使打印繁忙/界面打开也照常驱动（输入覆写内部有屏幕判断）
        me.aleksilassila.litematica.printer.go.GoManager.INSTANCE.tick();
        // 扫描自动寻路：派发/监控自动目标（依赖 GoManager 的驱动状态，须在其后）
        me.aleksilassila.litematica.printer.go.AutoWalkScanner.INSTANCE.tick();

        // 暴饮暴食进食中：打印/挖掘等 handler 全部让路（switchItem 仍照常驱动快捷潜影盒取食）
        if (InventoryUtils.isOpenHandler || InventoryUtils.switchItem() || BreakUtils.INSTANCE.isNeedHandle()
                || me.aleksilassila.litematica.printer.utils.EatUtils.isBusy()) {
            return;
        }
        
        // 检查是否需要等待视角修改
        if (ActionManager.INSTANCE.sendQueue(mc.player).isWaiting()) {
            return;
        }

        // 延迟检查
        if (Configs.Core.LAG_CHECK.getBooleanValue()) {
            if (packetTick > Configs.Core.LAG_CHECK_MAX.getIntegerValue()) {
                return;
            }
            packetTick++;
        }

        // 遍历所有处理器执行tick逻辑
        for (ClientPlayerTickHandler handler : VALUES) {
            // 非GUI处理器需要进行二次迭代检查，避免资源抢占问题
            if (!(handler instanceof GuiHandler)) {
                if (InventoryUtils.isOpenHandler || InventoryUtils.switchItem() || BreakUtils.INSTANCE.isNeedHandle()) {
                    return;
                }
                // 有任务需要修改视角时强制退出
                if (ActionManager.INSTANCE.needWaitModifyLook) {
                    return;
                }
            }
            handler.tick();
        }
    }

    public static void updateTickHandlerTime() {
        currentHandlerTime++;
    }
}
