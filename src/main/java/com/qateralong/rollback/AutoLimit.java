package com.qateralong.rollback;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.qateralong.rollback.store.TrackedFiles;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AutoLimit {
    private AutoLimit() {
    }

    private static Path file(Path world) {
        return world.resolve("rollback").resolve("auto_rollbacks.json");
    }

    private static JsonArray read(Path world) {
        try {
            if (Files.exists(file(world))) {
                return Rollback.GSON.fromJson(Files.readString(file(world)), JsonObject.class).getAsJsonArray("times");
            }
        } catch (Exception ignored) {
        }
        return new JsonArray();
    }

    public static boolean allowed(Path world) {
        long hourAgo = System.currentTimeMillis() - 3_600_000L;
        int recent = 0;
        for (var element : read(world)) {
            if (element.getAsLong() > hourAgo) {
                recent++;
            }
        }
        return recent < RollbackConfig.get().overload.maxAutoRollbacksPerHour;
    }

    public static void record(Path world) {
        long dayAgo = System.currentTimeMillis() - 86_400_000L;
        JsonArray kept = new JsonArray();
        for (var element : read(world)) {
            if (element.getAsLong() > dayAgo) {
                kept.add(element);
            }
        }
        kept.add(System.currentTimeMillis());
        JsonObject json = new JsonObject();
        json.add("times", kept);
        try {
            TrackedFiles.write(file(world), Rollback.GSON.toJson(json).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Rollback.LOGGER.error("Failed to save {}", file(world), e);
        }
    }
}
