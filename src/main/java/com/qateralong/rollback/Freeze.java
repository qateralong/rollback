package com.qateralong.rollback;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class Freeze {
    private static final Map<UUID, double[]> HELD = new ConcurrentHashMap<>();
    private static volatile boolean active;
    private static boolean wasFrozen;

    private Freeze() {
    }

    public static boolean active() {
        return active;
    }

    public static boolean blocks(Player player) {
        return active && player instanceof ServerPlayer;
    }

    public static void start(MinecraftServer server) {
        if (active) {
            return;
        }
        active = true;
        wasFrozen = server.tickRateManager().isFrozen();
        server.tickRateManager().setFrozen(true);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            hold(player);
        }
    }

    public static void stop(MinecraftServer server) {
        if (!active) {
            return;
        }
        active = false;
        HELD.clear();
        if (!wasFrozen) {
            server.tickRateManager().setFrozen(false);
        }
    }

    private static double[] hold(ServerPlayer player) {
        return HELD.computeIfAbsent(player.getUUID(), id -> new double[]{player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(), 0});
    }

    public static void holdPosition(ServerPlayer player) {
        double[] held = hold(player);
        long now = System.currentTimeMillis();
        if (now - (long) held[5] > 250L) {
            held[5] = now;
            player.connection.teleport(held[0], held[1], held[2], (float) held[3], (float) held[4]);
        }
    }
}
