package me.aleksilassila.litematica.printer.enums;

import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.config.ConfigOptionListEntry;
import me.aleksilassila.litematica.printer.printer.PrinterBox;

public enum IterationOrderType implements ConfigOptionListEntry<IterationOrderType> {
    XYZ(I18n.of("iterationOrder.xyz"), Axis.X, Axis.Y, Axis.Z),
    XZY(I18n.of("iterationOrder.xzy"), Axis.X, Axis.Z, Axis.Y),
    YXZ(I18n.of("iterationOrder.yxz"), Axis.Y, Axis.X, Axis.Z),
    YZX(I18n.of("iterationOrder.yzx"), Axis.Y, Axis.Z, Axis.X),
    ZXY(I18n.of("iterationOrder.zxy"), Axis.Z, Axis.X, Axis.Y),
    ZYX(I18n.of("iterationOrder.zyx"), Axis.Z, Axis.Y, Axis.X);

    private final I18n i18n;
    public final Axis[] axis;

    IterationOrderType(I18n i18n, Axis... axis) {
        this.i18n = i18n;
        this.axis = axis;
    }

    @Override
    public I18n getI18n() {
        return i18n;
    }

    /**
     * 返回一个把 primary 放到最外层轴、其余两轴保持原相对顺序的迭代顺序。
     * 运动感知扫描用它把"玩家正在逼近的轴"提到最前先扫。
     */
    public static IterationOrderType primaryFirst(IterationOrderType base, Axis primary) {
        Axis[] target = reorder(base.axis, primary);
        for (IterationOrderType t : values()) {
            if (t.axis[0] == target[0] && t.axis[1] == target[1] && t.axis[2] == target[2]) {
                return t;
            }
        }
        return base;
    }

    private static Axis[] reorder(Axis[] base, Axis primary) {
        Axis[] result = new Axis[3];
        result[0] = primary;
        int idx = 1;
        for (Axis a : base) {
            if (a != primary) {
                result[idx++] = a;
            }
        }
        return result;
    }

    // 封装轴的所有行为逻辑
    public enum Axis {
        X {
            @Override
            public int getCoord(PrinterBox box, int x, int y, int z) {
                return x;
            }

            @Override
            public int increment(PrinterBox box, int current) {
                return current + (box.xIncrement ? 1 : -1);
            }

            @Override
            public boolean isOverflow(PrinterBox box, int value) {
                return box.xIncrement ? value > box.maxX : value < box.minX;
            }

            @Override
            public int reset(PrinterBox box) {
                return box.xIncrement ? box.minX : box.maxX;
            }
        },
        Y {
            @Override
            public int getCoord(PrinterBox box, int x, int y, int z) {
                return y;
            }

            @Override
            public int increment(PrinterBox box, int current) {
                return current + (box.yIncrement ? 1 : -1);
            }

            @Override
            public boolean isOverflow(PrinterBox box, int value) {
                return box.yIncrement ? value > box.maxY : value < box.minY;
            }

            @Override
            public int reset(PrinterBox box) {
                return box.yIncrement ? box.minY : box.maxY;
            }
        },
        Z {
            @Override
            public int getCoord(PrinterBox box, int x, int y, int z) {
                return z;
            }

            @Override
            public int increment(PrinterBox box, int current) {
                return current + (box.zIncrement ? 1 : -1);
            }

            @Override
            public boolean isOverflow(PrinterBox box, int value) {
                return box.zIncrement ? value > box.maxZ : value < box.minZ;
            }

            @Override
            public int reset(PrinterBox box) {
                return box.zIncrement ? box.minZ : box.maxZ;
            }
        };

        // 获取当前轴的坐标值（x/y/z）
        public abstract int getCoord(PrinterBox box, int x, int y, int z);

        // 对当前轴执行增量操作
        public abstract int increment(PrinterBox box, int current);

        // 检查当前轴是否超出边界
        public abstract boolean isOverflow(PrinterBox box, int value);

        // 重置当前轴到起始边界值
        public abstract int reset(PrinterBox box);
    }
}