package me.aleksilassila.litematica.printer.gui;

import fi.dy.masa.malilib.config.IConfigStringList;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiScrollBar;
import fi.dy.masa.malilib.gui.GuiTextFieldGeneric;
import fi.dy.masa.malilib.gui.interfaces.ITextFieldListener;
import me.aleksilassila.litematica.printer.utils.BlockNbtRule;
import me.aleksilassila.litematica.printer.utils.MessageUtils;
import me.aleksilassila.litematica.printer.utils.PinYinSearchUtils;
import me.aleksilassila.litematica.printer.utils.PlayerUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import fi.dy.masa.malilib.render.GuiContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BlockNbtRuleScreen extends GuiBase {
    private static final int ROW_HEIGHT = 24;
    private static final int FIRST_ROW_Y = 45;
    private static final int VISIBLE_ROWS = 8;
    private static final int FIELD_X = 300;
    private static final int FIELD_WIDTH = 210;
    private final IConfigStringList config;
    private final int index;
    private final Screen parent;
    private final List<GuiTextFieldGeneric> values = new ArrayList<>();
    private final List<String> statePaths;
    private final String blockMatcher;
    private final GuiScrollBar scrollBar = new GuiScrollBar();
    private int scrollOffset;

    public BlockNbtRuleScreen(IConfigStringList config, int index, String currentEntry, Screen parent) {
        this.config = config;
        this.index = index;
        this.parent = parent;
        BlockNbtRule rule = BlockNbtRule.parse(currentEntry);
        this.blockMatcher = rule.blockMatcher();
        this.statePaths = findStatePaths(rule);
        this.title = MessageUtils.translatable("litematica_printer.gui.nbt.title", blockMatcher).getString();
        Map<String, String> oldValues = new LinkedHashMap<>();
        for (BlockNbtRule.Condition condition : rule.conditions()) {
            oldValues.put(condition.path(), condition.value());
        }
        for (String path : statePaths) {
            GuiTextFieldGeneric value = new GuiTextFieldGeneric(FIELD_X, FIRST_ROW_Y, FIELD_WIDTH, 20, this.font);
            value.setMaxLengthWrapper(512);
            value.setTextWrapper(oldValues.getOrDefault(path, ""));
            values.add(value);
        }
        setParent(parent);
        scrollBar.setMaxValue(Math.max(0, statePaths.size() - VISIBLE_ROWS));
    }

    private List<String> findStatePaths(BlockNbtRule rule) {
        BlockState target = null;
        ClientLevel level = Minecraft.getInstance().level;
        LocalPlayer player = Minecraft.getInstance().player;
        if (level != null && player != null && Minecraft.getInstance().hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                && PlayerUtils.isWithinBlockInteractionRange(player, hit.getBlockPos(), 0)) {
            BlockState state = level.getBlockState(hit.getBlockPos());
            if (PinYinSearchUtils.matchBlockName(rule.blockMatcher(), state)) target = state;
        }
        if (target == null && level != null && player != null) {
            int range = (int) Math.ceil(PlayerUtils.getInteractionRange(5));
            BlockPos center = player.blockPosition();
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-range, -range, -range), center.offset(range, range, range))) {
                if (!PlayerUtils.isWithinBlockInteractionRange(player, pos, 0)) continue;
                BlockState state = level.getBlockState(pos);
                if (PinYinSearchUtils.matchBlockName(rule.blockMatcher(), state)) {
                    target = state;
                    break;
                }
            }
        }
        List<String> paths = new ArrayList<>();
        if (target != null) {
            for (Property<?> property : target.getProperties()) {
                paths.add("state." + property.getName());
            }
        }
        if (paths.isEmpty()) {
            rule.conditions().stream().map(BlockNbtRule.Condition::path)
                    .filter(path -> path.startsWith("state."))
                    .filter(path -> !paths.contains(path))
                    .forEach(paths::add);
        }
        return paths;
    }

    @Override
    public void initGui() {
        super.initGui();
        clearElements();
        addLabel(80, 25, 210, 20, 0xFFFFFFFF, MessageUtils.translatable("litematica_printer.gui.nbt.path").getString());
        addLabel(FIELD_X, 25, FIELD_WIDTH, 20, 0xFFFFFFFF, MessageUtils.translatable("litematica_printer.gui.nbt.value").getString());
        for (GuiTextFieldGeneric value : values) addTextField(value, new ChangeListener());
    }

    @Override
    protected void drawContents(GuiContext context, int mouseX, int mouseY, float partialTicks) {
        scrollOffset = scrollBar.getValue();
        for (int i = 0; i < values.size(); i++) {
            int y = FIRST_ROW_Y + (i - scrollOffset) * ROW_HEIGHT;
            values.get(i).setYWrapper(y);
            if (i >= scrollOffset && i < scrollOffset + VISIBLE_ROWS) {
                values.get(i).setXWrapper(FIELD_X);
                drawString(context, statePaths.get(i), 80, y + 6, 0xFFFFFFFF);
            } else {
                values.get(i).setXWrapper(-FIELD_WIDTH - 20);
            }
        }
        if (statePaths.size() > VISIBLE_ROWS) {
            scrollBar.render(context, 520, FIRST_ROW_Y, 14, VISIBLE_ROWS * ROW_HEIGHT, 0, 0, 0, 0);
        }
    }

    @Override
    public boolean onMouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (statePaths.size() > VISIBLE_ROWS && mouseX >= 70 && mouseX <= 540
                && mouseY >= FIRST_ROW_Y && mouseY < FIRST_ROW_Y + VISIBLE_ROWS * ROW_HEIGHT) {
            scrollBar.offsetValue(verticalAmount < 0 ? 1 : -1);
            return true;
        }
        return super.onMouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    private void updateConfig() {
        List<BlockNbtRule.Condition> conditions = new ArrayList<>();
        for (int i = 0; i < statePaths.size(); i++) {
            String value = values.get(i).getTextWrapper();
            if (!value.isBlank()) conditions.add(new BlockNbtRule.Condition(statePaths.get(i), value));
        }
        if (index >= 0 && index < config.getStrings().size()) {
            config.getStrings().set(index, new BlockNbtRule(blockMatcher, conditions).encode());
            config.markDirty();
            config.setModified();
        }
    }

    private class ChangeListener implements ITextFieldListener<GuiTextFieldGeneric> {
        @Override public boolean onTextChange(GuiTextFieldGeneric textField) {
            updateConfig();
            return true;
        }
    }
}
