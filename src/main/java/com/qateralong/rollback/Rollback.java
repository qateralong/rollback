package com.qateralong.rollback;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.qateralong.rollback.store.Restorer;
import java.nio.file.Files;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Rollback implements ModInitializer {
    public static final String MOD_ID = "rollback";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static MinecraftServer server;
    private static long nextCheckpoint;

    public static MinecraftServer server() {
        return server;
    }

    @Override
    public void onInitialize() {
        RollbackConfig.load();
        CommandRegistrationCallback.EVENT.register((dispatcher, access, environment) -> BackupCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(Rollback::started);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            Overload.stop();
            Maintenance.stop();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            Recorder.stop();
            server = null;
            int code = RollbackConfig.get().exitCode;
            if (Countdown.restarting() && code != 0) {
                LOGGER.info("Exiting with code {} so the host restarts the server", code);
                Runtime.getRuntime().halt(code);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(Rollback::tick);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> !Freeze.blocks(player));
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> Freeze.blocks(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> Freeze.blocks(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> Freeze.blocks(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> Freeze.blocks(player) ? InteractionResult.FAIL : InteractionResult.PASS);
        UseItemCallback.EVENT.register((player, level, hand) -> Freeze.blocks(player)
                ? InteractionResultHolder.fail(player.getItemInHand(hand)) : InteractionResultHolder.pass(ItemStack.EMPTY));
    }

    private static void started(MinecraftServer s) {
        server = s;
        try {
            java.nio.file.Path world = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if (Files.exists(Restorer.pendingFile(world))) {
                LOGGER.warn("A rollback is still pending — it will be applied on the next start");
            }
            Recorder.start(world);
            scheduleNext();
            Overload.start(s);
            Maintenance.start();
            LOGGER.info("Recording world changes in {}", world.resolve("rollback"));
        } catch (Exception e) {
            LOGGER.error("Rollback recording could not start", e);
        }
    }

    private static void scheduleNext() {
        long step = Math.max(1, RollbackConfig.get().checkpointSeconds) * 1000L;
        nextCheckpoint = (System.currentTimeMillis() / step + 1) * step;
    }

    private static void tick(MinecraftServer s) {
        Overload.onTick(s);
        Countdown.tick(s);
        if (Recorder.active() && !Countdown.active() && System.currentTimeMillis() >= nextCheckpoint) {
            scheduleNext();
            Recorder.checkpoint(s);
        }
    }
}
