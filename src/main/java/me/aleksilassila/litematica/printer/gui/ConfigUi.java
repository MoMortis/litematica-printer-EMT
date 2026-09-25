package me.aleksilassila.litematica.printer.gui;

import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.IConfigResettable;
import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.IKeybindConfigGui;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOption;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import me.aleksilassila.litematica.printer.I18n;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.mixin_extension.ConfigExtension;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.utils.ModUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public class ConfigUi extends GuiConfigsBase {
    private static Tab tab = Tab.CORE;

    public ConfigUi(@Nullable Screen parent) {
        super(10, 50, Reference.MOD_ID, parent, Reference.MOD_NAME + " " + I18n.PRINTER_TAGLINE.getName().getString());
    }

    public ConfigUi() {
        //#if MC >= 260200
        //$$ this(Minecraft.getInstance().gui.screen());
        //#else
        this(Minecraft.getInstance().screen);
        //#endif
    }

    public static void refresh() {
        //#if MC >= 260200
        //$$ if (Reference.MINECRAFT.gui.screen() instanceof ConfigUi gui) {
        //#else
        if (Reference.MINECRAFT.screen instanceof ConfigUi gui) {
        //#endif
            gui.initGui();
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        this.clearOptions();
        int x = 10;
        int y = 26;
        for (Tab tab : Tab.values()) {
            x += this.createButton(x, y, -1, tab);
        }
    }

    public void reset() {
        reCreateListWidget();
        Objects.requireNonNull(getListWidget()).resetScrollbarPosition();
        initGui();
    }

    private int createButton(int x, int y, int width, Tab tab) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, tab.getName(), tab.getComment());
        button.setEnabled(ConfigUi.tab != tab);
        this.addButton(button, new ButtonListener(tab, this));
        return button.getWidth() + 2;
    }

    @Override
    protected WidgetListConfigOptions createListWidget(int listX, int listY) {
        // 仅"千万别点"目录使用按钮式列表，其余目录保持 malilib 默认列表（否则全部开关都会变成"不要点啊！"按钮）
        if (tab == Tab.DANGER) {
            return new DangerList(listX, listY, this.getBrowserWidth(), this.getBrowserHeight(),
                    this.getConfigWidth(), 0.0f, this.useKeybindSearch(), this);
        }
        return super.createListWidget(listX, listY);
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        ImmutableList.Builder<ConfigOptionWrapper> builder = ImmutableList.builder();
        for (IConfigBase config : ConfigUi.tab.getConfigs()) {
            if (config instanceof ConfigExtension extension) {
                @Nullable BooleanSupplier visible = extension.litematica_printer$getVisible();
                if (visible != null && visible.getAsBoolean()) {
                    builder.add(new ConfigOptionWrapper(config));
                }
            }
        }
        return builder.build();
    }

    public enum Tab {
        CORE(I18n.of("category.core")),
        HOTKEYS(I18n.of("category.hotkeys")),
        PRINT(I18n.of("category.print")),
        EXCAVATE(I18n.of("category.mine")),
        FILL(I18n.of("category.fill")),
        FLUID(I18n.of("category.fluid")),
        SPECIAL(I18n.of("category.special")),
        GO(I18n.of("category.go")),
        DANGER(I18n.of("category.danger"));

        private final I18n i18n;

        Tab(I18n i18n) {
            this.i18n = i18n;
        }

        public String getName() {
            return i18n.getConfigName().getString();
        }

        public String getComment() {
            return i18n.getConfigDesc().getString();
        }

        public ImmutableList<IConfigBase> getConfigs() {
            return switch (this) {
                case CORE -> Configs.Core.OPTIONS;
                case PRINT -> Configs.Print.OPTIONS;
                case EXCAVATE -> Configs.Mine.OPTIONS;
                case FILL -> Configs.Fill.OPTIONS;
                case FLUID -> Configs.Fluid.OPTIONS;
                case HOTKEYS -> Configs.Hotkeys.OPTIONS;
                case SPECIAL -> Configs.Special.OPTIONS;
                case GO -> Configs.Go.OPTIONS;
                case DANGER -> Configs.Danger.OPTIONS;
            };
        }
    }

    public record ButtonListener(Tab tab, ConfigUi parent) implements IButtonActionListener {
        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            ConfigUi.tab = this.tab;
            this.parent.reset();
        }
    }

    /**
     * "千万别点"目录专用列表：把 DONOTCLICK_A 渲染成纯按钮（无"是/否"开关、无"重置"按钮），
     * 参考 LPCTools「调试-时间测试」的按钮式配置交互。
     */
    public static class DangerList extends WidgetListConfigOptions {
        public DangerList(int x, int y, int width, int height, int configWidth, float zLevel,
                          boolean useKeybindSearch, GuiConfigsBase parent) {
            super(x, y, width, height, configWidth, zLevel, useKeybindSearch, parent);
        }

        @Override
        protected WidgetConfigOption createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
                                                           ConfigOptionWrapper wrapper) {
            return new DangerWidget(x, y, this.browserEntryWidth, this.browserEntryHeight,
                    this.maxLabelWidth, this.configWidth, wrapper, listIndex, this.parent, this);
        }
    }

    public static class DangerWidget extends WidgetConfigOption {
        public DangerWidget(int x, int y, int width, int height, int labelWidth, int configWidth,
                            ConfigOptionWrapper wrapper, int listIndex, IKeybindConfigGui host,
                            WidgetListConfigOptionsBase<?, ?> parent) {
            super(x, y, width, height, labelWidth, configWidth, wrapper, listIndex, host, parent);
        }

        @Override
        protected void addConfigButtonEntry(int xReset, int yReset, IConfigResettable config, ButtonBase optionButton) {
            // 不调用 super：不放"是/否"开关，也不放"重置"按钮，只放一个纯按钮，点击即触发
            // 按钮位置与尺寸复用原"是/否"开关（同宽同高），不附加悬停文字，外观与其他配置按钮保持一致
            // 似了喵：直接崩溃；A：打开网页后崩溃；B：播放视频后崩溃（ModUtils 内部处理）
            Runnable action;
            String textKey;
            if (config == Configs.Danger.SIMIAO) {
                action = ModUtils::crashNow;
                textKey = "simiao.button";
            } else if (config == Configs.Danger.DONOTCLICK_B) {
                action = ModUtils::playBundledVideo;
                textKey = "donotclickA.button";
            } else {
                action = ModUtils::openTrollVideoUrl;
                textKey = "donotclickA.button";
            }
            ButtonGeneric button = new ButtonGeneric(
                    optionButton.getX(), optionButton.getY(), optionButton.getWidth(), optionButton.getHeight(),
                    I18n.of(textKey).getConfigName().getString());
            this.addButton(button, (buttonBase, mouseButton) -> action.run());
        }
    }
}