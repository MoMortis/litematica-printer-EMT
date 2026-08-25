package me.aleksilassila.litematica.printer.mixin.printer.malilib;

import fi.dy.masa.malilib.gui.widgets.WidgetConfigOptionBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = WidgetConfigOptionBase.class, remap = false)
public interface WidgetConfigOptionBaseAccessor {
    @Accessor("initialStringValue")
    String litematica_printer$getInitialStringValue();

    @Mutable
    @Accessor("initialStringValue")
    void litematica_printer$setInitialStringValue(String value);

    @Accessor("lastAppliedValue")
    String litematica_printer$getLastAppliedValue();

    @Mutable
    @Accessor("lastAppliedValue")
    void litematica_printer$setLastAppliedValue(String value);
}
