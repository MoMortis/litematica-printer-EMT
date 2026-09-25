package me.aleksilassila.litematica.printer;

import fi.dy.masa.malilib.event.InitializationHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

public class LitematicaPrinterMod implements ModInitializer, ClientModInitializer {
    @Override
    public void onInitialize() {
    }

    // 👉 仅客户端逻辑
    @Override
    public void onInitializeClient() {
        // 清理旧方案释放到系统临时目录的视频残留（现方案改为释放到 config 目录并长期保留）
        me.aleksilassila.litematica.printer.utils.ModUtils.cleanBundledVideoTemp();
        me.aleksilassila.litematica.printer.go.GoCommand.register();
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
