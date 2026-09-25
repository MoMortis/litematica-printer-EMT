package me.aleksilassila.litematica.printer.config;

import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import me.aleksilassila.litematica.printer.gui.ConfigUi;
import me.aleksilassila.litematica.printer.printer.zxy.utils.ZxyUtils;
import me.aleksilassila.litematica.printer.utils.CloudStoreUtils;
import net.minecraft.client.Minecraft;


//监听按键
public class HotkeysCallback {
    private static final Minecraft client = Minecraft.getInstance();

    public static boolean onKeyAction(KeyAction action, IKeybind key) {
        if (client.player == null || client.level == null) {
            return false;
        }
        if (key == Configs.Hotkeys.OPEN_SCREEN.getKeybind()) {
            //#if MC >= 260200
            //$$ client.setScreenAndShow(new ConfigUi());
            //#else
            client.setScreen(new ConfigUi());
            //#endif
            return true;
        }

        if (key == Configs.Hotkeys.SYNC_INVENTORY.getKeybind()) {
            ZxyUtils.startOrOffSyncInventory();
            return true;
        }

        if (key == Configs.Hotkeys.REFILL_AMOUNT_ADJUST.getKeybind()) {
            CloudStoreUtils.showRefillAmountOverlay();
            return true;
        }

        return false;
    }
}