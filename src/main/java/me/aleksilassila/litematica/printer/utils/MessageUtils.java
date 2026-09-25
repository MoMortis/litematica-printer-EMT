package me.aleksilassila.litematica.printer.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.StringJoiner;

public class MessageUtils {

    public static final Minecraft client = Minecraft.getInstance();

    public static void setOverlayMessage(Component message, boolean bl) {
        //#if MC >= 260200
        //$$ client.gui.hud.setOverlayMessage(message, bl);
        //#else
        client.gui.setOverlayMessage(message, bl);
        //#endif
    }

    public static void addMessage(Component message) {
        //#if MC >= 260200
        //$$ client.gui.hud.getChat().addClientSystemMessage(message);
        //#elseif MC >= 260100
        //$$ client.gui.getChat().addClientSystemMessage(message);
        //#else
        client.gui.getChat().addMessage(message);
        //#endif
    }

    public static void setOverlayMessage(Component message) {
        setOverlayMessage(message, false);
    }

    // 扩展方法，普通字符串形式, 但并不建议使用, 因为没有做I18n
    public static void setOverlayMessage(String message) {
        setOverlayMessage(MessageUtils.literal(message));
    }

    // 扩展方法，普通字符串形式, 但并不建议使用, 因为没有做I18n
    public static void addMessage(String message) {
        addMessage(MessageUtils.literal(message));
    }

    public final static MutableComponent EMPTY = literal("");

    public static MutableComponent translatable(String key) {
        //#if MC > 11802
        return Component.translatable(key);
        //#else
        //$$ return new net.minecraft.network.chat.TranslatableComponent(key);
        //#endif
    }

    public static MutableComponent translatable(String key, Object... objects) {
        //#if MC > 11802
        return Component.translatable(key, objects);
        //#else
        //$$ return new net.minecraft.network.chat.TranslatableComponent(key, objects);
        //#endif
    }

    public static MutableComponent literal(String text) {
        //#if MC > 11802
        return Component.literal(text);
        //#else
        //$$ return new net.minecraft.network.chat.TextComponent(text);
        //#endif
    }

    public static MutableComponent nullToEmpty(@Nullable String string) {
        return string != null ? literal(string) : EMPTY;
    }

    public static String mergeComments(String delimiter, Component... customComments) {
        StringJoiner joiner = new StringJoiner(delimiter);
        for (Component comment : customComments) {
            if (comment != null) {
                joiner.add(comment.getString());
            }
        }
        return joiner.toString();
    }
}
