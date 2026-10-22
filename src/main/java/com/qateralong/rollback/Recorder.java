package com.qateralong.rollback;

import com.qateralong.rollback.api.RollbackEvents;
import com.qateralong.rollback.store.Journal;
import com.qateralong.rollback.store.TrackedFiles;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import com.qateralong.rollback.mixin.IOWorkerAccessor;
import net.minecraft.world.level.chunk.storage.IOWorker;

public final class Recorder {
    private static final Set<IOWorker> WORKERS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile Interval interval;
    private static volatile Path world;
    private static volatile boolean switching;
    private static volatile boolean saving;
    private static volatile long switchingSince;
    private static Journal previous;

    private record Interval(Journal journal, Set<String> recorded) {
        Interval(Journal journal) {
            this(journal, ConcurrentHashMap.newKeySet());
        }
    }

    private Recorder() {
    }

    public static Path world() {
        return world;
    }

    public static void registerWorker(IOWorker worker) {
        WORKERS.add(worker);
    }

    public static boolean active() {
        return interval != null;
    }

    public static long currentId() {
        Interval current = interval;
        return current == null ? -1L : current.journal().id;
    }

    public static boolean saving() {
        return saving;
    }

    public static void start(Path worldPath) throws IOException {
        world = worldPath;
        List<Long> ids = Journal.ids(worldPath);
        if (!Files.isDirectory(TrackedFiles.mirror(worldPath)) || ids.isEmpty()) {
            TrackedFiles.rebuildMirror(worldPath);
        } else {
            Journal last = Journal.open(worldPath, ids.get(ids.size() - 1));
            TrackedFiles.diffInto(worldPath, last);
            last.close();
        }
        interval = new Interval(Journal.create(worldPath, System.currentTimeMillis() / 1000L));
    }

    public static void stop() {
        Interval current = interval;
        interval = null;
        if (current != null) {
            current.journal().close();
        }
        if (previous != null) {
            previous.close();
            previous = null;
        }
    }

    public static void beforeRegionWrite(Path folder, ChunkPos pos, ChunkReader reader) {
        Interval current = interval;
        Path root = world;
        if (current == null || root == null) {
            return;
        }
        Path absolute = folder.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || absolute.startsWith(root.resolve("rollback"))) {
            return;
        }
        String rel = root.relativize(absolute).toString().replace('\\', '/');
        if (!current.recorded().add(Journal.chunkKey(rel, pos.x, pos.z))) {
            return;
        }
        try {
            CompoundTag old = reader.read(pos);
            current.journal().recordChunk(rel, pos.x, pos.z, old == null ? Journal.ABSENT : encode(old));
        } catch (Exception e) {
            Rollback.LOGGER.error("Failed to journal chunk {} in {}", pos, rel, e);
        }
    }

    public interface ChunkReader {
        CompoundTag read(ChunkPos pos) throws IOException;
    }

    private static byte[] encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8192);
        bytes.write(2);
        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new DeflaterOutputStream(bytes)))) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    public static long checkpoint(MinecraftServer server) {
        Interval closing = interval;
        if (closing == null) {
            return -1L;
        }
        if (switching) {
            if (System.currentTimeMillis() - switchingSince < 60_000L) {
                return -1L;
            }
            Rollback.LOGGER.warn("Previous checkpoint never finished flushing — continuing anyway");
        }
        switching = true;
        switchingSince = System.currentTimeMillis();
        try {
            saving = true;
            try {
                server.saveEverything(true, false, false);
            } finally {
                saving = false;
            }
            TrackedFiles.diffInto(world, closing.journal());
            long now = System.currentTimeMillis() / 1000L;
            long step = Math.max(1, RollbackConfig.get().checkpointSeconds);
            long id = Math.max(closing.journal().id + 1, now / step * step);
            Journal next = Journal.create(world, id);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            synchronized (WORKERS) {
                for (IOWorker worker : WORKERS) {
                    if (!((IOWorkerAccessor) worker).rollback$shutdownRequested().get()) {
                        futures.add(worker.synchronize(false));
                    }
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).whenComplete((result, error) -> {
                if (error != null) {
                    Rollback.LOGGER.warn("Region writes did not flush in 15 s, switching checkpoint anyway");
                }
                if (interval == closing) {
                    interval = new Interval(next);
                }
                Journal stale = previous;
                previous = closing.journal();
                if (stale != null) {
                    stale.close();
                }
                switching = false;
                server.execute(() -> RollbackEvents.CHECKPOINT.invoker().onCheckpoint(java.time.Instant.ofEpochSecond(next.id)));
            });
            return next.id;
        } catch (Throwable e) {
            switching = false;
            saving = false;
            Rollback.LOGGER.error("Checkpoint failed", e);
            Notify.throttled(server, "checkpoint", 1_800_000L, true, "Не удалось создать точку отката: " + e);
            return -1L;
        }
    }
}
