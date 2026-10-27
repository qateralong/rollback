package com.qateralong.rollback;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.qateralong.rollback.store.Journal;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

public final class PanelNet {
    private PanelNet() {
    }

    public static void init() {
        PayloadTypeRegistry.playC2S().register(PanelPayload.TYPE, PanelPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PanelPayload.TYPE, PanelPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(PanelPayload.TYPE, (payload, context) -> handle(context.player(), payload.json()));
    }

    private static void handle(ServerPlayer player, String json) {
        if (!player.hasPermissions(RollbackConfig.get().permissionLevel)) {
            if (ServerPlayNetworking.canSend(player, PanelPayload.TYPE)) {
                JsonObject denied = new JsonObject();
                denied.addProperty("denied", true);
                try {
                    denied.addProperty("rid", JsonParser.parseString(json).getAsJsonObject().get("rid").getAsInt());
                } catch (RuntimeException ignored) {
                }
                ServerPlayNetworking.send(player, new PanelPayload(denied.toString()));
            }
            return;
        }
        JsonObject request;
        try {
            request = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return;
        }
        String op = request.has("op") ? request.get("op").getAsString() : "";
        if (!op.equals("status")) {
            return;
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("op", op);
        reply.addProperty("rid", request.has("rid") ? request.get("rid").getAsInt() : 0);
        List<Long> ids = Recorder.world() == null ? List.of() : Journal.ids(Recorder.world());
        JsonArray points = new JsonArray();
        for (int i = ids.size() - 1; i >= 0; i--) {
            long id = ids.get(i);
            JsonObject point = new JsonObject();
            point.addProperty("id", id);
            point.addProperty("label", Times.format(id));
            point.addProperty("arg", Times.argument(id));
            points.add(point);
        }
        RollbackConfig config = RollbackConfig.get();
        reply.add("points", points);
        reply.addProperty("now", System.currentTimeMillis() / 1000L);
        reply.addProperty("sizeMb", Maintenance.totalSize() / 1048576.0);
        reply.addProperty("maxGb", config.maxSizeGb);
        reply.addProperty("checkpointSeconds", config.checkpointSeconds);
        reply.addProperty("retentionDays", config.retentionDays);
        reply.addProperty("pending", Countdown.active() ? Times.format(Countdown.target()) : "");
        reply.addProperty("watchdog", Overload.describe());
        if (ServerPlayNetworking.canSend(player, PanelPayload.TYPE)) {
            ServerPlayNetworking.send(player, new PanelPayload(reply.toString()));
        }
    }
}
