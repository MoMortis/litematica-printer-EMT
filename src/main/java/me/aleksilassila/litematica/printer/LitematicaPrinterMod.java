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
        me.aleksilassila.litematica.printer.go.GoCommand.register();
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
