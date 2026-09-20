package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

/**
 * 「暴饮！暴食！」触发模式：
 * <ul>
 *     <li>{@link #OFF}：关闭，永不自动进食</li>
 *     <li>{@link #PRINTER_ONLY}：仅打印机工作时（总开关开启）自动进食</li>
 *     <li>{@link #ANYTIME}：任何时候，饥饿值到阈值就吃，与总开关无关</li>
 * </ul>
 * 三种模式都保留"打印机正忙（动作在飞）时先让路"的等待语义。
 */
public enum EatMode implements ConfigOptionListEntry<EatMode> {
    OFF("eatMode.off"),
    PRINTER_ONLY("eatMode.printerOnly"),
    ANYTIME("eatMode.anytime");

    private final I18n i18n;

    EatMode(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}