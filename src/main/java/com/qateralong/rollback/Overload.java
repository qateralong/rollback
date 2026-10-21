package com.qateralong.rollback;

import com.qateralong.rollback.store.Journal;
import com.qateralong.rollback.store.Restorer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class Overload {
    private static volatile long lastTickWall;
    private static volatile int tickCount;
    private static volatile float tickRate = 20.0F;
    private static volatile boolean running;
    private static volatile long quietUntil;
    private static long startedAt;
    private static Thread thread;

    private Overload() {
    }

    public static void onTick(MinecraftServer server) {
        lastTickWall = System.currentTimeMillis();
        tickCount++;
        tickRate = server.tickRateManager().tickrate();
    }

    public static void quiet() {
        quietUntil = System.currentTimeMillis() + RollbackConfig.get().overload.cooldownAfterCancelMinutes * 60_000L;
    }

    public static long checkpointAtOrBefore(long epochSeconds) {
        long best = -1L;
        for (long id : Journal.ids(Recorder.world())) {
            if (id <= epochSeconds) {
                best = id;
            }
        }
        return best;
    }

    public static void start(MinecraftServer server) {
        stop();
        running = true;
        startedAt = System.currentTimeMillis();
        lastTickWall = startedAt;
        thread = new Thread(() -> loop(server), "rollback overload watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private static void loop(MinecraftServer server) {
        int lastCount = tickCount;
        int lowSeconds = 0;
        long lowSince = 0L;
        while (running) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
            RollbackConfig.Overload config = RollbackConfig.get().overload;
            long now = System.currentTimeMillis();
            int count = tickCount;
            int ticks = count - lastCount;
            lastCount = count;
            if (!config.enabled || now - startedAt < config.startupGraceSeconds * 1000L || now < quietUntil || Countdown.active() || Recorder.busy()
                    || server.getPlayerList() == null || server.getPlayerCount() == 0) {
                lowSeconds = 0;
                continue;
            }
            if (now - lastTickWall > config.hangSeconds * 1000L) {
                hang(server, lastTickWall);
                lowSeconds = 0;
                lastCount = tickCount;
                continue;
            }
            double expected = Math.max(1.0, tickRate);
            if (ticks < config.tpsBelow * expected / 20.0) {
                if (lowSeconds++ == 0) {
                    lowSince = now - 1000L;
                }
                if (lowSeconds >= config.lowTpsSeconds) {
                    long since = lowSince;
                    lowSeconds = 0;
                    server.execute(() -> trigger(server, since, RollbackConfig.get().countdownSeconds, "низкий TPS"));
                }
            } else {
                lowSeconds = 0;
            }
        }
    }

    private static long target(long overloadStartMillis) {
        return checkpointAtOrBefore(overloadStartMillis / 1000L - RollbackConfig.get().overload.rollbackMinutes * 60L);
    }

    private static void trigger(MinecraftServer server, long overloadStart, int seconds, String cause) {
        if (Countdown.active()) {
            return;
        }
        long target = target(overloadStart);
        if (target < 0) {
            Rollback.LOGGER.warn("Server overloaded ({}), but there is no checkpoint to roll back to", cause);
            return;
        }
        if (!AutoLimit.allowed(Recorder.world())) {
            Rollback.LOGGER.warn("Server overloaded ({}), automatic rollback limit per hour reached", cause);
            return;
        }
        Countdown.start(server, target, "перегрузка: " + cause, true, seconds);
    }

    private static void hang(MinecraftServer server, long since) {
        RollbackConfig config = RollbackConfig.get();
        long target = target(since);
        Rollback.LOGGER.warn("Server thread has not ticked for {} s", (System.currentTimeMillis() - since) / 1000L);
        if (target < 0 || !AutoLimit.allowed(Recorder.world())) {
            Rollback.LOGGER.warn("Hang detected, but automatic rollback is not possible (no checkpoint or limit reached)");
            return;
        }
        int frozenAt = tickCount;
        for (int seconds = config.countdownSeconds; seconds > 0; seconds--) {
            if (tickCount != frozenAt) {
                int left = seconds;
                server.execute(() -> trigger(server, since, left, "зависание"));
                return;
            }
            Countdown.titles(players(server), true, target, seconds);
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
        }
        if (tickCount != frozenAt) {
            server.execute(() -> trigger(server, since, 0, "зависание"));
            return;
        }
        try {
            Restorer.writePending(Recorder.world(), target, "перегрузка: зависание");
            AutoLimit.record(Recorder.world());
        } catch (Exception e) {
            Rollback.LOGGER.error("Could not schedule rollback after hang", e);
            return;
        }
        Rollback.LOGGER.error("Server hung — halting to roll back to {}", Times.format(target));
        Component message = Component.literal(config.kickMessage.replace("%time%", Times.format(target)));
        for (ServerPlayer player : players(server)) {
            try {
                var connection = ((com.qateralong.rollback.mixin.ConnectionAccessor) player.connection).rollback$connection();
                connection.send(new ClientboundDisconnectPacket(message));
                connection.disconnect(message);
            } catch (Exception ignored) {
            }
        }
        try {
            Thread.sleep(500L);
        } catch (InterruptedException ignored) {
        }
        Runtime.getRuntime().halt(config.overload.hangExitCode);
    }

    private static List<ServerPlayer> players(MinecraftServer server) {
        try {
            return new ArrayList<>(server.getPlayerList().getPlayers());
        } catch (Exception e) {
            return List.of();
        }
    }
}
