package com.qateralong.rollback;

import com.qateralong.rollback.api.RollbackEvents;
import com.qateralong.rollback.store.Restorer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public final class Countdown {
    private static Pending pending;
    private static boolean restarting;

    private record Pending(long target, String reason, boolean overload, long endsAt, int[] shown) {
    }

    private Countdown() {
    }

    public static boolean active() {
        return pending != null || restarting;
    }

    public static boolean restarting() {
        return restarting;
    }

    public static long target() {
        return pending == null ? -1L : pending.target();
    }

    public static boolean start(MinecraftServer server, long target, String reason, boolean overload, int seconds) {
        if (active() || !RollbackEvents.ALLOW_ROLLBACK.invoker().allow(Instant.ofEpochSecond(target), reason)) {
            return false;
        }
        pending = new Pending(target, reason, overload, System.currentTimeMillis() + Math.max(0, seconds) * 1000L, new int[]{-1});
        Freeze.start(server);
        Rollback.LOGGER.info("Rollback to {} scheduled in {} s: {}", Times.format(target), seconds, reason);
        Notify.alert(server, "Откат до " + Times.format(target) + " через " + seconds + " с (" + reason + "). Отмена: /backup c");
        Component hint = Component.literal("§7Откат до §f" + Times.format(target) + "§7 — отмена: §f/backup c");
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.hasPermissions(RollbackConfig.get().permissionLevel)) {
                player.sendSystemMessage(hint);
            }
        }
        tick(server);
        return true;
    }

    public static boolean cancel(MinecraftServer server, String by) {
        if (pending == null || restarting) {
            return false;
        }
        if (pending.overload()) {
            Overload.quiet();
        }
        pending = null;
        Freeze.stop(server);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 30, 10));
            player.connection.send(new ClientboundSetTitleTextPacket(Component.empty()));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal("§aОткат отменён")));
        }
        Rollback.LOGGER.info("Rollback cancelled by {}", by);
        Notify.info(server, "Откат отменён: " + by);
        return true;
    }

    public static void titles(Iterable<ServerPlayer> players, boolean overload, long target, int seconds) {
        Component title = Component.literal(overload ? "§cСервер перегружен" : "§6Откат");
        Component subtitle = Component.literal(overload ? "§fоткат через §c" + seconds : "§fдо " + Times.shortFormat(target) + " через §c" + seconds);
        Component actionbar = overload ? Component.literal("§eОткат всего сервера на " + minutes(target) + " в связи с перегрузкой") : null;
        for (ServerPlayer player : players) {
            player.connection.send(new ClientboundSetTitlesAnimationPacket(0, 30, 5));
            player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
            player.connection.send(new ClientboundSetTitleTextPacket(title));
            if (overload) {
                player.connection.send(new ClientboundSetActionBarTextPacket(actionbar));
                player.playNotifySound(SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.MASTER, 1.0F, 1.0F);
            }
        }
    }

    private static String minutes(long target) {
        long minutes = Math.max(1L, Math.round((System.currentTimeMillis() / 1000.0 - target) / 60.0));
        long tens = minutes % 100;
        long last = minutes % 10;
        String word = tens >= 11 && tens <= 14 ? "минут" : last == 1 ? "минуту" : last >= 2 && last <= 4 ? "минуты" : "минут";
        return minutes + " " + word;
    }

    public static void tick(MinecraftServer server) {
        Pending current = pending;
        if (current == null || restarting) {
            return;
        }
        long left = current.endsAt() - System.currentTimeMillis();
        int seconds = (int) Math.max(0, (left + 999) / 1000);
        if (left > 0 && seconds != current.shown()[0]) {
            current.shown()[0] = seconds;
            titles(new ArrayList<>(server.getPlayerList().getPlayers()), current.overload(), current.target(), seconds);
        }
        if (left <= 0) {
            execute(server, current);
        }
    }

    private static void execute(MinecraftServer server, Pending current) {
        restarting = true;
        try {
            Restorer.writePending(Recorder.world(), current.target(), current.reason());
        } catch (Exception e) {
            restarting = false;
            pending = null;
            Freeze.stop(server);
            Rollback.LOGGER.error("Could not schedule rollback", e);
            Notify.alert(server, "Не удалось запланировать откат: " + e.getMessage());
            return;
        }
        if (current.overload()) {
            AutoLimit.record(Recorder.world());
        }
        RollbackEvents.BEFORE_RESTART.invoker().beforeRestart(Instant.ofEpochSecond(current.target()), current.reason());
        Rollback.LOGGER.info("Restarting to roll back to {} ({})", Times.format(current.target()), current.reason());
        Notify.alert(server, "Сервер перезапускается: откат до " + Times.format(current.target()) + " (" + current.reason() + ")");
        Notify.flush(3000L);
        Component message = Component.literal(RollbackConfig.get().kickMessage.replace("%time%", Times.format(current.target())));
        List<ServerPlayer> players = new ArrayList<>(server.getPlayerList().getPlayers());
        for (ServerPlayer player : players) {
            player.connection.disconnect(message);
        }
        server.halt(false);
    }
}
