package com.qateralong.rollback;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

public final class Freeze {
    private static final ResourceLocation MODIFIER = ResourceLocation.fromNamespaceAndPath(Rollback.MOD_ID, "freeze");
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
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            release(player);
        }
        HELD.clear();
        if (!wasFrozen) {
            server.tickRateManager().setFrozen(false);
        }
    }

    private static double[] hold(ServerPlayer player) {
        return HELD.computeIfAbsent(player.getUUID(), id -> {
            double[] held = {player.getX(), player.getY(), player.getZ()};
            limit(player, Attributes.MOVEMENT_SPEED);
            limit(player, Attributes.JUMP_STRENGTH);
            player.setDeltaMovement(0.0, 0.0, 0.0);
            player.hurtMarked = true;
            return held;
        });
    }

    private static void limit(ServerPlayer player, Holder<Attribute> attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance != null && !instance.hasModifier(MODIFIER)) {
            instance.addTransientModifier(new AttributeModifier(MODIFIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void release(ServerPlayer player) {
        for (Holder<Attribute> attribute : java.util.List.of(Attributes.MOVEMENT_SPEED, Attributes.JUMP_STRENGTH)) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(MODIFIER);
            }
        }
    }

    public static void holdPosition(ServerPlayer player, double x, double y, double z, float yRot, float xRot) {
        double[] held = hold(player);
        if (Math.abs(x - held[0]) > 1.0E-3 || Math.abs(y - held[1]) > 1.0E-3 || Math.abs(z - held[2]) > 1.0E-3) {
            player.connection.teleport(held[0], held[1], held[2], yRot, xRot);
        }
    }
}
