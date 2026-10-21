package com.qateralong.rollback.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class Journal {
    public static final byte[] ABSENT = new byte[0];
    private static final String CHUNKS = "chunks.bin";
    private static final String FILES = "files.bin";

    public final long id;
    public final Path dir;
    private DataOutputStream chunks;
    private DataOutputStream files;

    public interface ChunkVisitor {
        void visit(String folder, int x, int z, byte[] payload);
    }

    public interface FileVisitor {
        void visit(String path, byte[] data);
    }

    private Journal(long id, Path dir) {
        this.id = id;
        this.dir = dir;
    }

    public static Path root(Path world) {
        return world.resolve("rollback").resolve("journal");
    }

    public static synchronized Journal create(Path world, long id) throws IOException {
        Path root = root(world);
        Files.createDirectories(root);
        while (Files.exists(root.resolve(Long.toString(id)))) {
            id++;
        }
        Path dir = root.resolve(Long.toString(id));
        Files.createDirectories(dir);
        return new Journal(id, dir);
    }

    public static Journal open(Path world, long id) {
        return new Journal(id, root(world).resolve(Long.toString(id)));
    }

    public static List<Long> ids(Path world) {
        List<Long> result = new ArrayList<>();
        Path root = root(world);
        if (!Files.isDirectory(root)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(path -> {
                try {
                    result.add(Long.parseLong(path.getFileName().toString()));
                } catch (NumberFormatException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    public static String chunkKey(String folder, int x, int z) {
        return folder + ':' + x + ':' + z;
    }

    public synchronized void recordChunk(String folder, int x, int z, byte[] payload) throws IOException {
        if (this.chunks == null) {
            this.chunks = open(this.dir.resolve(CHUNKS));
        }
        this.chunks.writeUTF(folder);
        this.chunks.writeInt(x);
        this.chunks.writeInt(z);
        writeData(this.chunks, payload);
        this.chunks.flush();
    }

    public synchronized void recordFile(String path, byte[] data) throws IOException {
        if (this.files == null) {
            this.files = open(this.dir.resolve(FILES));
        }
        this.files.writeUTF(path);
        writeData(this.files, data);
        this.files.flush();
    }

    public synchronized void close() {
        try {
            if (this.chunks != null) {
                this.chunks.close();
            }
            if (this.files != null) {
                this.files.close();
            }
        } catch (IOException ignored) {
        }
        this.chunks = null;
        this.files = null;
    }

    private static DataOutputStream open(Path file) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND), 65536));
    }

    private static void writeData(DataOutputStream out, byte[] data) throws IOException {
        if (data == null || data == ABSENT) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeInt(data.length);
            out.write(data);
        }
    }

    private static byte[] readData(DataInputStream in) throws IOException {
        if (!in.readBoolean()) {
            return ABSENT;
        }
        int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new EOFException();
        }
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    public static void readChunks(Path dir, ChunkVisitor visitor) throws IOException {
        Path file = dir.resolve(CHUNKS);
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 65536))) {
            while (true) {
                String folder;
                int x;
                int z;
                byte[] data;
                try {
                    folder = in.readUTF();
                    x = in.readInt();
                    z = in.readInt();
                    data = readData(in);
                } catch (EOFException e) {
                    return;
                }
                visitor.visit(folder, x, z, data);
            }
        }
    }

    public static void readFiles(Path dir, FileVisitor visitor) throws IOException {
        Path file = dir.resolve(FILES);
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream raw = Files.newInputStream(file); DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 65536))) {
            while (true) {
                String path;
                byte[] data;
                try {
                    path = in.readUTF();
                    data = readData(in);
                } catch (EOFException e) {
                    return;
                }
                visitor.visit(path, data);
            }
        }
    }

    public static void merge(Journal into, List<Journal> later) throws IOException {
        Set<String> chunkKeys = new HashSet<>();
        Set<String> fileKeys = new HashSet<>();
        readChunks(into.dir, (folder, x, z, payload) -> chunkKeys.add(chunkKey(folder, x, z)));
        readFiles(into.dir, (path, data) -> fileKeys.add(path));
        for (Journal journal : later) {
            IOException[] failure = new IOException[1];
            readChunks(journal.dir, (folder, x, z, payload) -> {
                if (failure[0] == null && chunkKeys.add(chunkKey(folder, x, z))) {
                    try {
                        into.recordChunk(folder, x, z, payload);
                    } catch (IOException e) {
                        failure[0] = e;
                    }
                }
            });
            readFiles(journal.dir, (path, data) -> {
                if (failure[0] == null && fileKeys.add(path)) {
                    try {
                        into.recordFile(path, data);
                    } catch (IOException e) {
                        failure[0] = e;
                    }
                }
            });
            if (failure[0] != null) {
                throw failure[0];
            }
        }
        into.close();
        for (Journal journal : later) {
            delete(journal.dir);
        }
    }

    public static long size(Path dir) {
        long total = 0L;
        for (String name : new String[]{CHUNKS, FILES}) {
            try {
                total += Files.size(dir.resolve(name));
            } catch (IOException ignored) {
            }
        }
        return total;
    }

    public static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
