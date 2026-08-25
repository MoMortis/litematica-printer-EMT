package me.aleksilassila.litematica.printer.mixin.printer.malilib;

import fi.dy.masa.malilib.config.IConfigStringList;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.widgets.WidgetConfigOptionBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptionsBase;
import fi.dy.masa.malilib.gui.widgets.WidgetListStringListEdit;
import fi.dy.masa.malilib.gui.widgets.WidgetStringListEditEntry;
import me.aleksilassila.litematica.printer.gui.BlockNbtRuleScreen;
import me.aleksilassila.litematica.printer.utils.BlockNbtRule;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = WidgetStringListEditEntry.class, remap = false)
public abstract class MixinWidgetStringListEditEntry extends WidgetConfigOptionBase<String> {
    @Shadow protected int listIndex;
    @Shadow protected WidgetListStringListEdit parent;
    private BlockNbtRule litematica_printer$pendingRule;

    protected MixinWidgetStringListEditEntry(int x, int y, int width, int height,
                                              WidgetListConfigOptionsBase<?, ?> parent, String entry, int index) {
        super(x, y, width, height, parent, entry, index);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void litematica_printer$addNbtButton(int x, int y, int width, int height, int index, boolean odd,
                                                   String entry, String defaultValue, WidgetListStringListEdit parent,
                                                   CallbackInfo ci) {
        IConfigStringList config = parent.getConfig();
        if (index < 0 || !(config.getName().endsWith("breakWhitelist")
                || config.getName().endsWith("breakBlacklist")
                || config.getName().endsWith("excavateWhitelist")
                || config.getName().endsWith("excavateBlacklist"))) return;
        BlockNbtRule storedRule = BlockNbtRule.parse(entry);
        if (!storedRule.conditions().isEmpty() && this.textField != null) {
            this.textField.textField().setTextWrapper(storedRule.blockMatcher());
            WidgetConfigOptionBaseAccessor accessor = (WidgetConfigOptionBaseAccessor) (Object) this;
            accessor.litematica_printer$setInitialStringValue(storedRule.blockMatcher());
            accessor.litematica_printer$setLastAppliedValue(storedRule.blockMatcher());
        }
        // Malilib places the text field at rowX + rowWidth - 138.
        ButtonGeneric button = new ButtonGeneric(this.x + this.width - 158, this.y + 4,
                MaLiLibIcons.ARROW_DOWN, "NBT");
        button.setHoverStrings("NBT");
        addButton(button, (ignored, mouseButton) -> Minecraft.getInstance().setScreen(
                new BlockNbtRuleScreen(config, index,
                        index < config.getStrings().size() ? config.getStrings().get(index) : entry,
                        Minecraft.getInstance().screen)));
    }

    @Inject(method = "applyNewValueToConfig", at = @At("HEAD"))
    private void litematica_printer$captureRule(CallbackInfo ci) {
        this.litematica_printer$pendingRule = null;
        if (this.listIndex < 0 || this.textField == null) return;
        IConfigStringList config = this.parent.getConfig();
        if (!(config.getName().endsWith("breakWhitelist")
                || config.getName().endsWith("breakBlacklist")
                || config.getName().endsWith("excavateWhitelist")
                || config.getName().endsWith("excavateBlacklist"))) return;
        if (this.listIndex < config.getStrings().size()) {
            this.litematica_printer$pendingRule = BlockNbtRule.parse(config.getStrings().get(this.listIndex));
        }
    }

    @Inject(method = "applyNewValueToConfig", at = @At("RETURN"))
    private void litematica_printer$restoreEncodedRule(CallbackInfo ci) {
        BlockNbtRule rule = this.litematica_printer$pendingRule;
        this.litematica_printer$pendingRule = null;
        if (rule == null || rule.conditions().isEmpty() || this.listIndex < 0 || this.textField == null) return;
        IConfigStringList config = this.parent.getConfig();
        if (!(config.getName().endsWith("breakWhitelist")
                || config.getName().endsWith("breakBlacklist")
                || config.getName().endsWith("excavateWhitelist")
                || config.getName().endsWith("excavateBlacklist"))) return;
        if (this.listIndex < config.getStrings().size()) {
            String blockMatcher = this.textField.textField().getTextWrapper();
            BlockNbtRule displayedRule = BlockNbtRule.parse(blockMatcher);
            if (blockMatcher.startsWith("@nbt:")) {
                blockMatcher = displayedRule.blockMatcher();
            }
            String encoded = new BlockNbtRule(blockMatcher, rule.conditions()).encode();
            config.getStrings().set(this.listIndex, encoded);
            ((WidgetConfigOptionBaseAccessor) (Object) this)
                    .litematica_printer$setLastAppliedValue(blockMatcher);
            config.markDirty();
            config.setModified();
        }
    }
}
