package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;

/**
 * 扫描自动寻路的子区块扫描顺序（BFS 相邻扩展的轴优先级）。
 * 每个轴先向正方向、再向负方向展开；默认 XZY 即 x+、x-、z+、z-、y+、y-。
 */
public enum SectionScanOrderType implements ConfigOptionListEntry<SectionScanOrderType> {
    XYZ(I18n.of("sectionScanOrder.xyz"), Axis.X, Axis.Y, Axis.Z),
    XZY(I18n.of("sectionScanOrder.xzy"), Axis.X, Axis.Z, Axis.Y),
    YXZ(I18n.of("sectionScanOrder.yxz"), Axis.Y, Axis.X, Axis.Z),
    YZX(I18n.of("sectionScanOrder.yzx"), Axis.Y, Axis.Z, Axis.X),
    ZXY(I18n.of("sectionScanOrder.zxy"), Axis.Z, Axis.X, Axis.Y),
    ZYX(I18n.of("sectionScanOrder.zyx"), Axis.Z, Axis.Y, Axis.X);

    private final I18n i18n;
    public final Axis[] axis;

    SectionScanOrderType(I18n i18n, Axis... axis) {
        this.i18n = i18n;
        this.axis = axis;
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }

    /** 子区块轴向：携带其在子区块坐标系（16³）上的偏移步长 */
    public enum Axis {
        X(1, 0, 0),
        Y(0, 1, 0),
        Z(0, 0, 1);

        public final int dx;
        public final int dy;
        public final int dz;

        Axis(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }
    }
}
