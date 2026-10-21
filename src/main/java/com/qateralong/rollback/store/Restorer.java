package com.qateralong.rollback.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Restorer {
    private static final Logger LOGGER = LoggerFactory.getLogger("rollback");
    private static final Gson GSON = new Gson();

    private Restorer() {
    }

    public static Path pendingFile(Path world) {
        return world.resolve("rollback").resolve("pending.json");
    }

    public static void writePending(Path world, long target, String reason) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("target", target);
        json.addProperty("reason", reason);
        json.addProperty("requestedAt", System.currentTimeMillis());
        TrackedFiles.write(pendingFile(world), GSON.toJson(json).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void restoreIfPending(Path world) {
        Path pending = pendingFile(world);
        if (!Files.exists(pending)) {
            return;
        }
        long target;
        String reason;
        try (Reader reader = Files.newBufferedReader(pending)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            target = json.get("target").getAsLong();
            reason = json.has("reason") ? json.get("reason").getAsString() : "";
        } catch (Exception e) {
            LOGGER.error("Unreadable {}, rollback skipped", pending, e);
            return;
        }
        try {
            long started = System.currentTimeMillis();
            int[] counts = restore(world, target);
            Files.deleteIfExists(pending);
            JsonObject done = new JsonObject();
            done.addProperty("target", target);
            done.addProperty("reason", reason);
            done.addProperty("restoredAt", System.currentTimeMillis());
            done.addProperty("chunks", counts[0]);
            done.addProperty("files", counts[1]);
            TrackedFiles.write(world.resolve("rollback").resolve("last_restore.json"), GSON.toJson(done).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            LOGGER.info("Rolled back to {} ({}): {} chunks, {} files in {} ms", target, reason, counts[0], counts[1], System.currentTimeMillis() - started);
        } catch (Exception e) {
            LOGGER.error("Rollback to {} failed", target, e);
        }
    }

    public static int[] restore(Path world, long target) throws IOException {
        List<Long> ids = Journal.ids(world).stream().filter(id -> id >= target).toList();
        Map<String, Map<Long, byte[]>> chunks = new LinkedHashMap<>();
        Map<String, byte[]> files = new HashMap<>();
        for (long id : ids) {
            Path dir = Journal.open(world, id).dir;
            Journal.readChunks(dir, (folder, x, z, payload) ->
                    chunks.computeIfAbsent(folder, key -> new HashMap<>()).putIfAbsent(((long) x << 32) ^ (z & 0xFFFFFFFFL), payload));
            Journal.readFiles(dir, files::putIfAbsent);
        }
        Path mirror = TrackedFiles.mirror(world);
        Set<String> candidates = TrackedFiles.list(world);
        candidates.addAll(TrackedFiles.mirrored(world));
        for (String rel : candidates) {
            if (files.containsKey(rel)) {
                continue;
            }
            byte[] before = TrackedFiles.read(mirror.resolve(rel));
            byte[] now = TrackedFiles.read(world.resolve(rel));
            if (before == null && now != null) {
                files.put(rel, Journal.ABSENT);
            } else if (before != null && (now == null || !Arrays.equals(before, now))) {
                files.put(rel, before);
            }
        }
        int chunkCount = 0;
        for (Map.Entry<String, Map<Long, byte[]>> entry : chunks.entrySet()) {
            Path folder = world.resolve(entry.getKey()).normalize();
            if (!folder.startsWith(world)) {
                continue;
            }
            Map<Long, Map<Integer, byte[]>> regions = new HashMap<>();
            for (Map.Entry<Long, byte[]> chunk : entry.getValue().entrySet()) {
                int x = (int) (chunk.getKey() >> 32);
                int z = (int) (long) chunk.getKey();
                long region = ((long) (x >> 5) << 32) ^ ((z >> 5) & 0xFFFFFFFFL);
                regions.computeIfAbsent(region, key -> new HashMap<>()).put(RegionIO.index(x, z), chunk.getValue());
                chunkCount++;
            }
            for (Map.Entry<Long, Map<Integer, byte[]>> region : regions.entrySet()) {
                RegionIO.apply(folder, (int) (region.getKey() >> 32), (int) (long) region.getKey(), region.getValue());
            }
        }
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            Path path = world.resolve(file.getKey()).normalize();
            if (!path.startsWith(world) || !TrackedFiles.tracked(file.getKey())) {
                continue;
            }
            if (file.getValue() == Journal.ABSENT) {
                Files.deleteIfExists(path);
            } else {
                TrackedFiles.write(path, file.getValue());
            }
        }
        for (long id : ids) {
            Journal.delete(Journal.open(world, id).dir);
        }
        TrackedFiles.rebuildMirror(world);
        return new int[]{chunkCount, files.size()};
    }
}
