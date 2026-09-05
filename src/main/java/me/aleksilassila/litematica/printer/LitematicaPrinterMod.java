package me.aleksilassila.litematica.printer;

import fi.dy.masa.malilib.event.InitializationHandler;
import me.aleksilassila.litematica.printer.printer.zxy.inventory.OpenInventoryPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

public class LitematicaPrinterMod implements ModInitializer, ClientModInitializer {
    // 👉 服务端+客户端通用逻辑（仅放无客户端依赖的代码）
    // 例如：注册网络包、通用配置加载（无GUI）、数据生成等
    @Override
    public void onInitialize() {
        OpenInventoryPacket.init();
        OpenInventoryPacket.registerReceivePacket();
    }

    // 👉 仅客户端逻辑（放心使用客户端API）
    // 比如：注册按键、GUI、渲染钩子、客户端配置界面等
    @Override
    public void onInitializeClient() {
        OpenInventoryPacket.registerClientReceivePacket();
        me.aleksilassila.litematica.printer.go.GoCommand.register();
        InitializationHandler.getInstance().registerInitializationHandler(new InitHandler());
    }
}
