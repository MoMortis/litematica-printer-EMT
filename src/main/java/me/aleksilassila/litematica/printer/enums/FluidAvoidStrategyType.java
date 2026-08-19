package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

public enum FluidAvoidStrategyType implements ConfigOptionListEntry<FluidAvoidStrategyType> {
    FIVE_FACES("breakFluidStrategy.fiveFaces"),
    SIX_FACES("breakFluidStrategy.sixFaces");

    private final I18n i18n;

    FluidAvoidStrategyType(String translateKey) {
        this.i18n = I18n.of(translateKey);
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }
}
