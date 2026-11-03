package com.qateralong.rollback.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TrackedFiles {
    private static final Logger LOGGER = LoggerFactory.getLogger("rollback");
    private static final Pattern TRACKED = Pattern.compile(
            "level\\.dat|playerdata/[^/]+\\.dat|advancements/[^/]+\\.json|stats/[^/]+\\.json"
                    + "|(DIM-1/|DIM1/|dimensions/[^/]+/[^/]+/)?data/.+");
    // live databases (Distant Horizons keeps its LOD cache in data/) can't be copied consistently and are regenerated anyway
    private static final Pattern DATABASE = Pattern.compile(
            ".*\\.(sqlite|sqlite3|db|mv\\.db)(-wal|-shm|-journal)?|.*\\.(lock|rbtmp)", Pattern.CASE_INSENSITIVE);
    public static final long MAX_SIZE = 32L << 20;
    private static final Object APPLY = new Object();
    private static final Map<String, long[]> META = new HashMap<>();
    private static final Set<String> WARNED = new HashSet<>();
    private static Set<String> mirroredCache;

    public record Changes(Map<String, byte[]> changed, Set<String> removed, Set<String> dropped) {
        public boolean empty() {
            return this.changed.isEmpty() && this.removed.isEmpty() && this.dropped.isEmpty();
        }
    }

    private TrackedFiles() {
    }

    public static boolean tracked(String rel) {
        return TRACKED.matcher(rel).matches() && !DATABASE.matcher(rel).matches()
                && !rel.endsWith(".tmp") && !rel.endsWith("_old") && !rel.endsWith(".dat_old");
    }

    public static Set<String> list(Path world) {
        Set<String> result = new TreeSet<>();
        List<Path> roots = new ArrayList<>(List.of(world.resolve("level.dat"), world.resolve("playerdata"), world.resolve("advancements"),
                world.resolve("stats"), world.resolve("data"), world.resolve("DIM-1").resolve("data"), world.resolve("DIM1").resolve("data")));
        Path dimensions = world.resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            try (Stream<Path> namespaces = Files.list(dimensions)) {
                for (Path namespace : namespaces.toList()) {
                    if (Files.isDirectory(namespace)) {
                        try (Stream<Path> dims = Files.list(namespace)) {
                            dims.forEach(dim -> roots.add(dim.resolve("data")));
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        for (Path root : roots) {
            if (Files.isRegularFile(root)) {
                add(world, root, result);
            } else if (Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root)) {
                    walk.filter(Files::isRegularFile).forEach(path -> add(world, path, result));
                } catch (IOException ignored) {
                }
            }
        }
        return result;
    }

    private static void add(Path world, Path path, Set<String> result) {
        String rel = world.relativize(path).toString().replace('\\', '/');
        if (tracked(rel)) {
            result.add(rel);
        }
    }

    public static Path mirror(Path world) {
        return world.resolve("rollback").resolve("mirror");
    }

    public static Set<String> mirrored(Path world) {
        Set<String> result = new TreeSet<>();
        Path mirror = mirror(world);
        if (!Files.isDirectory(mirror)) {
            return result;
        }
        try (Stream<Path> walk = Files.walk(mirror)) {
            walk.filter(Files::isRegularFile).forEach(path -> result.add(mirror.relativize(path).toString().replace('\\', '/')));
        } catch (IOException ignored) {
        }
        return result;
    }

    public static byte[] read(Path file) {
        try {
            return Files.exists(file) ? Files.readAllBytes(file) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public static void write(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".rbtmp");
        Files.write(tmp, data);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean oversized(String rel, long size) {
        if (size <= MAX_SIZE) {
            return false;
        }
        if (WARNED.add(rel)) {
            LOGGER.warn("Not tracking {}: {} MB is over the {} MB limit", rel, size >> 20, MAX_SIZE >> 20);
        }
        return true;
    }

    public static synchronized void rebuildMirror(Path world) throws IOException {
        synchronized (APPLY) {
            Journal.delete(mirror(world));
            META.clear();
            mirroredCache = null;
            for (String rel : list(world)) {
                Path file = world.resolve(rel);
                try {
                    if (oversized(rel, Files.size(file))) {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
                byte[] data = read(file);
                if (data != null) {
                    write(mirror(world).resolve(rel), data);
                }
            }
        }
    }

    // Runs on the server thread right after a save: only stats files and reads the ones that changed, so the bytes
    // match what the save wrote even if a later save rewrites them in place.
    public static synchronized Changes snapshot(Path world) {
        if (mirroredCache == null) {
            mirroredCache = mirrored(world);
        }
        Set<String> current = list(world);
        Map<String, byte[]> changed = new LinkedHashMap<>();
        Set<String> dropped = new TreeSet<>();
        for (String rel : current) {
            Path file = world.resolve(rel);
            long[] meta;
            try {
                meta = new long[]{Files.size(file), Files.getLastModifiedTime(file).toMillis()};
            } catch (IOException e) {
                continue;
            }
            if (oversized(rel, meta[0])) {
                if (mirroredCache.remove(rel)) {
                    dropped.add(rel);
                }
                continue;
            }
            long[] known = META.get(rel);
            if (known != null && Arrays.equals(known, meta) && mirroredCache.contains(rel)) {
                continue;
            }
            byte[] now = read(file);
            if (now == null) {
                continue;
            }
            changed.put(rel, now);
            META.put(rel, meta);
            mirroredCache.add(rel);
        }
        Set<String> removed = new TreeSet<>();
        for (Iterator<String> iterator = mirroredCache.iterator(); iterator.hasNext(); ) {
            String rel = iterator.next();
            if (!current.contains(rel)) {
                (tracked(rel) ? removed : dropped).add(rel);
                iterator.remove();
                META.remove(rel);
            }
        }
        return new Changes(changed, removed, dropped);
    }

    // Compares against the mirror and writes the journal; safe to run off the server thread.
    public static int apply(Path world, Changes changes, Journal journal) throws IOException {
        synchronized (APPLY) {
            Path mirror = mirror(world);
            int count = 0;
            for (Map.Entry<String, byte[]> entry : changes.changed().entrySet()) {
                Path copy = mirror.resolve(entry.getKey());
                byte[] before = read(copy);
                if (before == null || !Arrays.equals(before, entry.getValue())) {
                    journal.recordFile(entry.getKey(), before == null ? Journal.ABSENT : before);
                    write(copy, entry.getValue());
                    count++;
                }
            }
            for (String rel : changes.removed()) {
                byte[] before = read(mirror.resolve(rel));
                if (before != null) {
                    journal.recordFile(rel, before);
                }
                Files.deleteIfExists(mirror.resolve(rel));
                count++;
            }
            for (String rel : changes.dropped()) {
                Files.deleteIfExists(mirror.resolve(rel));
            }
            return count;
        }
    }

    public static int diffInto(Path world, Journal journal) throws IOException {
        return apply(world, snapshot(world), journal);
    }
}
