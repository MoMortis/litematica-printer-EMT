package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;
import net.minecraft.core.Direction;

public enum DefaultPlaceDirectionType implements ConfigOptionListEntry<DefaultPlaceDirectionType> {
    NONE("placeDefaultDirection.none", null),
    DOWN("placeDefaultDirection.down", Direction.DOWN),
    UP("placeDefaultDirection.up", Direction.UP),
    NORTH("placeDefaultDirection.north", Direction.NORTH),
    SOUTH("placeDefaultDirection.south", Direction.SOUTH),
    EAST("placeDefaultDirection.east", Direction.EAST),
    WEST("placeDefaultDirection.west", Direction.WEST);

    private final I18n i18n;
    private final Direction direction;

    DefaultPlaceDirectionType(String translateKey, Direction direction) {
        this.i18n = I18n.of(translateKey);
        this.direction = direction;
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }

    public Direction toDirection() {
        return direction;
    }
}
