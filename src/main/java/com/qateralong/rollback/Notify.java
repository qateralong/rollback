package com.qateralong.rollback;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class Notify {
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static Method send;
    private static Method alert;
    private static Method flush;
    private static boolean looked;

    private Notify() {
    }

    public static void info(MinecraftServer server, String text) {
        notify(server, text, false, null, 0L);
    }

    public static void alert(MinecraftServer server, String text) {
        notify(server, text, true, null, 0L);
    }

    public static void throttled(MinecraftServer server, String key, long everyMillis, boolean loud, String text) {
        notify(server, text, loud, key, everyMillis);
    }

    public static void flush(long timeoutMillis) {
        Method method = method("flush");
        if (method != null) {
            try {
                method.invoke(null, timeoutMillis);
            } catch (Exception ignored) {
            }
        }
    }

    private static void notify(MinecraftServer server, String text, boolean loud, String key, long everyMillis) {
        if (key != null) {
            long now = System.currentTimeMillis();
            Long last = LAST.get(key);
            if (last != null && now - last < everyMillis) {
                return;
            }
            LAST.put(key, now);
        }
        RollbackConfig config = RollbackConfig.get();
        if (config.notifyAdmins && server != null) {
            Component line = Component.literal("[Откат] ").withStyle(loud ? ChatFormatting.RED : ChatFormatting.GOLD)
                    .append(Component.literal(text).withStyle(ChatFormatting.GRAY));
            Runnable task = () -> {
                for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
                    if (player.hasPermissions(config.permissionLevel)) {
                        player.sendSystemMessage(line);
                    }
                }
            };
            if (server.isSameThread()) {
                task.run();
            } else {
                server.execute(task);
            }
        }
        if (config.notifyTelegram) {
            Method method = method(loud ? "alert" : "send");
            if (method != null) {
                try {
                    method.invoke(null, "🔄 Откат: " + text);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static synchronized Method method(String name) {
        if (!looked) {
            looked = true;
            if (FabricLoader.getInstance().isModLoaded("tgbridge")) {
                try {
                    Class<?> api = Class.forName("com.qateralong.tgbridge.api.TelegramBridge");
                    send = api.getMethod("send", String.class);
                    alert = api.getMethod("alert", String.class);
                    flush = api.getMethod("flush", long.class);
                } catch (Exception e) {
                    Rollback.LOGGER.warn("Telegram Bridge is installed but its API was not found");
                }
            }
        }
        return switch (name) {
            case "send" -> send;
            case "alert" -> alert;
            default -> flush;
        };
    }
}
