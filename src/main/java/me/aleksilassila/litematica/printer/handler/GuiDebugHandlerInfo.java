package me.aleksilassila.litematica.printer.handler;

/**
 * 调试面板用的 Handler 信息载体。
 * 注意：不能作为嵌套类放在 mixin 包内 —— mixin 包中不允许存在非 Mixin 类，
 * 否则部分 Mixin 版本（如 Fabric Loader 0.19.x 自带的）会直接拒绝加载并崩溃。
 */
public final class GuiDebugHandlerInfo {
    public final ClientPlayerTickHandler handler;
    public final GuiBlockInfo guiInfo;

    public GuiDebugHandlerInfo(ClientPlayerTickHandler handler, GuiBlockInfo guiInfo) {
        this.handler = handler;
        this.guiInfo = guiInfo;
    }
}
