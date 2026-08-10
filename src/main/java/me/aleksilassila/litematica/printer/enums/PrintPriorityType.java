package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

/**
 * 优先/后置放置策略：
 * OFF            关闭（不使用该列表，相关方块按普通顺序放置）
 * INTERACTION_RANGE 仅玩家交互范围内的方块参与该列表排序
 * GLOBAL         投影渲染层内所有方块参与该列表排序
 */
public enum PrintPriorityType implements ConfigOptionListEntry<PrintPriorityType> {
    OFF("printPriorityType.off"),
    INTERACTION_RANGE("printPriorityType.interactionRange"),
    GLOBAL("printPriorityType.global");

    private final I18n i18n;

    PrintPriorityType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
