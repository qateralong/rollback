package com.qateralong.rollback.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class TrackedFiles {
    private static final Pattern TRACKED = Pattern.compile(
            "level\\.dat|playerdata/[^/]+\\.dat|advancements/[^/]+\\.json|stats/[^/]+\\.json"
                    + "|(DIM-1/|DIM1/|dimensions/[^/]+/[^/]+/)?data/.+");
    private static final Map<String, long[]> META = new HashMap<>();

    private TrackedFiles() {
    }

    public static boolean tracked(String rel) {
        return TRACKED.matcher(rel).matches() && !rel.endsWith(".tmp") && !rel.endsWith("_old") && !rel.endsWith(".dat_old");
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

    public static synchronized void rebuildMirror(Path world) throws IOException {
        Journal.delete(mirror(world));
        META.clear();
        for (String rel : list(world)) {
            byte[] data = read(world.resolve(rel));
            if (data != null) {
                write(mirror(world).resolve(rel), data);
            }
        }
    }

    public static synchronized int diffInto(Path world, Journal journal) throws IOException {
        Path mirror = mirror(world);
        Set<String> current = list(world);
        Set<String> previous = mirrored(world);
        int changed = 0;
        for (String rel : current) {
            Path file = world.resolve(rel);
            long[] meta;
            try {
                meta = new long[]{Files.size(file), Files.getLastModifiedTime(file).toMillis()};
            } catch (IOException e) {
                continue;
            }
            long[] known = META.get(rel);
            if (known != null && Arrays.equals(known, meta) && previous.contains(rel)) {
                continue;
            }
            byte[] now = read(file);
            if (now == null) {
                continue;
            }
            byte[] before = read(mirror.resolve(rel));
            if (before == null || !Arrays.equals(before, now)) {
                journal.recordFile(rel, before == null ? Journal.ABSENT : before);
                write(mirror.resolve(rel), now);
                changed++;
            }
            META.put(rel, meta);
        }
        for (String rel : previous) {
            if (!current.contains(rel)) {
                byte[] before = read(mirror.resolve(rel));
                if (before != null) {
                    journal.recordFile(rel, before);
                }
                Files.deleteIfExists(mirror.resolve(rel));
                META.remove(rel);
                changed++;
            }
        }
        return changed;
    }
}
