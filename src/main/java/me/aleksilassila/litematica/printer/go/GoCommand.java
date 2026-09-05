package me.aleksilassila.litematica.printer.go;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /go 自动寻路指令：
 * <ul>
 *   <li>/go x y z —— 走到目标方块（支持 ~ 相对坐标）</li>
 *   <li>/go 玩家名 —— 跟随附近的其他玩家</li>
 *   <li>/go stop —— 停止寻路</li>
 * </ul>
 */
public final class GoCommand {
    private GoCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("go")
                        .then(ClientCommandManager.literal("stop")
                                .executes(ctx -> {
                                    GoManager.INSTANCE.stop("已手动停止寻路");
                                    return Command.SINGLE_SUCCESS;
                                }))
                        .then(ClientCommandManager.argument("target", StringArgumentType.greedyString())
                                .suggests(GoCommand::suggestTargets)
                                .executes(ctx -> {
                                    handle(StringArgumentType.getString(ctx, "target"));
                                    return Command.SINGLE_SUCCESS;
                                }))));
    }

    /**
     * 目标补全：客户端已加载（可见）的其他玩家名，按距离由近到远；
     * 输入形如坐标（数字/~ 开头）时不干扰坐标输入。
     */
    private static CompletableFuture<Suggestions> suggestTargets(
            CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder builder) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (mc.level == null || self == null) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemaining().trim().toLowerCase();
        if (!remaining.isEmpty() && (remaining.charAt(0) == '~'
                || (remaining.charAt(0) >= '0' && remaining.charAt(0) <= '9'))) {
            return builder.buildFuture(); // 看起来是坐标，不补玩家名
        }
        List<AbstractClientPlayer> players = new ArrayList<>(mc.level.players());
        players.removeIf(p -> p.getUUID().equals(self.getUUID()));
        players.sort(Comparator.comparingDouble(p -> p.distanceToSqr(self)));
        for (AbstractClientPlayer p : players) {
            String name = p.getName().getString();
            if (name.toLowerCase().startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private static void handle(String arg) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            chat("§c[寻路] 未进入世界");
            return;
        }
        String[] parts = arg.trim().split("\\s+");
        if (parts.length == 3) {
            Integer x = parseCoord(parts[0], player.getX());
            Integer y = parseCoord(parts[1], player.getY());
            Integer z = parseCoord(parts[2], player.getZ());
            if (x == null || y == null || z == null) {
                chat("§c[寻路] 坐标格式无效: " + arg);
                return;
            }
            GoManager.INSTANCE.go(new BlockPos(x, y, z));
            return;
        }
        if (parts.length == 1 && !parts[0].isEmpty()) {
            AbstractClientPlayer target = GoManager.INSTANCE.findPlayerByName(parts[0]);
            if (target == null) {
                chat("§c[寻路] 找不到（或无法唯一确定）玩家: " + parts[0]);
                return;
            }
            GoManager.INSTANCE.go(target);
            return;
        }
        chat("§e[寻路] 用法: /go <x y z> 或 /go <玩家名> 或 /go stop");
    }

    @Nullable
    private static Integer parseCoord(String token, double relativeTo) {
        token = token.trim();
        if (token.equals("~")) {
            return (int) Math.floor(relativeTo);
        }
        if (token.startsWith("~")) {
            try {
                return (int) Math.floor(relativeTo + Double.parseDouble(token.substring(1)));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void chat(String text) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            //#if MC >= 260100
            //$$ p.sendSystemMessage(Component.literal(text));
            //#else
            p.displayClientMessage(Component.literal(text), false);
            //#endif
        }
    }
}
