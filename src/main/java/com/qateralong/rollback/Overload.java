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

    private static volatile String state = "ещё не запущен";
    private static volatile int lastTps = -1;
    private static volatile int streak;
    private static long lastSkipLog;

    public static String describe() {
        return "TPS " + (lastTps < 0 ? "?" : lastTps) + ", низкий TPS подряд " + streak + " с — " + state;
    }

    private static void loop(MinecraftServer server) {
        int lastCount = tickCount;
        long lowSince = 0L;
        while (running) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
            try {
                Diagnostics.check(server, lastTickWall);
                RollbackConfig.Overload config = RollbackConfig.get().overload;
                long now = System.currentTimeMillis();
                int count = tickCount;
                int ticks = count - lastCount;
                lastCount = count;
                lastTps = ticks;
                String skip = !config.enabled ? "выключен в конфиге"
                        : now - startedAt < config.startupGraceSeconds * 1000L ? "запуск сервера, ждёт " + config.startupGraceSeconds + " с"
                        : now < quietUntil ? "пауза после отмены"
                        : Countdown.active() ? "идёт отсчёт отката" : null;
                if (skip != null) {
                    streak = 0;
                    state = skip;
                    continue;
                }
                if (!Recorder.saving() && now - lastTickWall > config.hangSeconds * 1000L) {
                    state = "сервер завис";
                    hang(server, lastTickWall);
                    streak = 0;
                    lastCount = tickCount;
                    continue;
                }
                double expected = Math.max(1.0, tickRate);
                if (ticks < config.tpsBelow * expected / 20.0) {
                    if (streak++ == 0) {
                        lowSince = now - 1000L;
                        Rollback.LOGGER.info("TPS dropped to {}, watching for {} s", ticks, config.lowTpsSeconds);
                    }
                    state = "низкий TPS";
                    if (streak >= config.lowTpsSeconds) {
                        long since = lowSince;
                        streak = 0;
                        Rollback.LOGGER.warn("Overload: TPS below {} for {} s", config.tpsBelow, config.lowTpsSeconds);
                        server.execute(() -> trigger(server, since, RollbackConfig.get().countdownSeconds, "низкий TPS"));
                    }
                } else {
                    streak = 0;
                    state = "норма";
                }
            } catch (Throwable e) {
                if (System.currentTimeMillis() - lastSkipLog > 60_000L) {
                    lastSkipLog = System.currentTimeMillis();
                    Rollback.LOGGER.error("Overload watchdog error", e);
                }
            }
        }
    }

    private static long target(long overloadStartMillis) {
        long wanted = checkpointAtOrBefore(overloadStartMillis / 1000L - RollbackConfig.get().overload.rollbackMinutes * 60L);
        if (wanted >= 0) {
            return wanted;
        }
        long earliest = checkpointAtOrBefore(overloadStartMillis / 1000L);
        if (earliest >= 0) {
            Rollback.LOGGER.warn("No checkpoint {} min before the overload, using the earliest one", RollbackConfig.get().overload.rollbackMinutes);
            for (long id : Journal.ids(Recorder.world())) {
                return id;
            }
        }
        return -1L;
    }

    private static void trigger(MinecraftServer server, long overloadStart, int seconds, String cause) {
        if (Countdown.active()) {
            return;
        }
        long target = target(overloadStart);
        if (target < 0) {
            state = "перегрузка, но нет точки отката";
            Rollback.LOGGER.warn("Server overloaded ({}), but there is no checkpoint to roll back to", cause);
            return;
        }
        if (!AutoLimit.allowed(Recorder.world())) {
            state = "перегрузка, но лимит автооткатов исчерпан";
            Rollback.LOGGER.warn("Server overloaded ({}), automatic rollback limit per hour reached", cause);
            return;
        }
        Rollback.LOGGER.warn("Server overloaded ({}), rolling back to {}", cause, Times.format(target));
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
