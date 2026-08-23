package me.aleksilassila.litematica.printer.utils;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** A block-name rule with optional all-of NBT path/value constraints. */
public record BlockNbtRule(String blockMatcher, List<Condition> conditions) {
    private static final String PREFIX = "@nbt:";
    private static final Gson GSON = new Gson();

    public BlockNbtRule {
        blockMatcher = blockMatcher == null ? "" : blockMatcher;
        conditions = List.copyOf((conditions == null ? List.<Condition>of() : conditions).stream()
                .filter(condition -> condition != null && condition.path() != null
                        && condition.path().startsWith("state.") && !condition.path().isBlank())
                .toList());
    }

    public static BlockNbtRule parse(String value) {
        if (value == null || !value.startsWith(PREFIX)) return new BlockNbtRule(value, List.of());
        try {
            String json = new String(Base64.getUrlDecoder().decode(value.substring(PREFIX.length())), StandardCharsets.UTF_8);
            Stored stored = GSON.fromJson(json, Stored.class);
            List<Condition> conditions = new ArrayList<>();
            if (stored != null && stored.conditions != null) {
                for (Condition condition : stored.conditions) {
                    if (condition != null && condition.path != null && !condition.path.isBlank()) conditions.add(condition);
                }
            }
            return new BlockNbtRule(stored == null ? "" : stored.block, conditions);
        } catch (IllegalArgumentException | JsonSyntaxException ignored) {
            return new BlockNbtRule(value, List.of());
        }
    }

    public String encode() {
        if (conditions.isEmpty()) return blockMatcher;
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
                GSON.toJson(new Stored(blockMatcher, conditions)).getBytes(StandardCharsets.UTF_8));
    }

    public boolean matchesState(BlockState state) {
        for (Condition condition : conditions) {
            if (!condition.path().startsWith("state.")) continue;
            String propertyName = condition.path().substring("state.".length());
            Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
            if (property == null || !matchesPropertyValue(state, property, condition.value())) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> boolean matchesPropertyValue(BlockState state, Property<T> property, String expected) {
        String actual = property.getName(state.getValue(property));
        return actual.equals(expected == null ? "" : expected.trim());
    }

    public record Condition(String path, String value) { }
    private record Stored(String block, List<Condition> conditions) { }
}
