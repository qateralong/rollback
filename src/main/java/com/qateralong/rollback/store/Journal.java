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
import java.util.Map;
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
        Files.createDirectories(file.getParent());
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

    public record ChunkRef(Path dir, String folder, int x, int z, long offset, int length) {
        public String key() {
            return chunkKey(this.folder, this.x, this.z);
        }
    }

    public static List<ChunkRef> indexChunks(Path dir) throws IOException {
        List<ChunkRef> result = new ArrayList<>();
        Path file = dir.resolve(CHUNKS);
        if (!Files.exists(file)) {
            return result;
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            long position = 0L;
            java.nio.ByteBuffer head = java.nio.ByteBuffer.allocate(2);
            while (position + 2 <= size) {
                head.clear();
                channel.read(head, position);
                head.flip();
                int nameLength = head.getShort() & 0xFFFF;
                if (position + 2 + nameLength + 9 > size) {
                    break;
                }
                java.nio.ByteBuffer meta = java.nio.ByteBuffer.allocate(2 + nameLength + 9);
                channel.read(meta, position);
                meta.flip();
                String folder = new DataInputStream(new java.io.ByteArrayInputStream(meta.array(), 0, 2 + nameLength)).readUTF();
                meta.position(2 + nameLength);
                int x = meta.getInt();
                int z = meta.getInt();
                boolean present = meta.get() != 0;
                long next = position + 2 + nameLength + 9;
                int length = -1;
                if (present) {
                    if (next + 4 > size) {
                        break;
                    }
                    java.nio.ByteBuffer len = java.nio.ByteBuffer.allocate(4);
                    channel.read(len, next);
                    len.flip();
                    length = len.getInt();
                    next += 4;
                    if (length < 0 || next + length > size) {
                        break;
                    }
                }
                result.add(new ChunkRef(dir, folder, x, z, next, length));
                position = next + Math.max(0, length);
            }
        }
        return result;
    }

    public static byte[] read(ChunkRef ref) throws IOException {
        if (ref.length() < 0) {
            return ABSENT;
        }
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(ref.dir().resolve(CHUNKS), StandardOpenOption.READ)) {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(ref.length());
            while (buffer.hasRemaining()) {
                if (channel.read(buffer, ref.offset() + buffer.position()) < 0) {
                    throw new EOFException();
                }
            }
            return buffer.array();
        }
    }

    public static List<byte[]> compact(String folder, List<byte[]> records) {
        List<byte[]> result = new ArrayList<>();
        if (!ChunkDiff.supported(folder)) {
            result.add(records.get(0));
            return result;
        }
        net.minecraft.nbt.CompoundTag merged = null;
        try {
            for (byte[] record : records) {
                if (!ChunkCodec.isDiff(record)) {
                    if (merged != null) {
                        result.add(ChunkCodec.diff(merged));
                    }
                    result.add(record);
                    return result;
                }
                net.minecraft.nbt.CompoundTag diff = ChunkCodec.read(record);
                if (merged == null) {
                    merged = diff;
                } else {
                    ChunkDiff.merge(merged, diff);
                }
            }
            result.add(ChunkCodec.diff(merged));
            return result;
        } catch (IOException | RuntimeException e) {
            result.clear();
            for (byte[] record : records) {
                result.add(record);
                if (!ChunkCodec.isDiff(record)) {
                    break;
                }
            }
            return result;
        }
    }

    public static void merge(Journal into, List<Journal> later) throws IOException {
        Map<String, List<ChunkRef>> refs = new java.util.LinkedHashMap<>();
        List<Journal> all = new ArrayList<>();
        all.add(into);
        all.addAll(later);
        for (Journal journal : all) {
            for (ChunkRef ref : indexChunks(journal.dir)) {
                refs.computeIfAbsent(ref.key(), key -> new ArrayList<>()).add(ref);
            }
        }
        Path tmp = into.dir.resolve(CHUNKS + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp), 65536))) {
            for (List<ChunkRef> list : refs.values()) {
                ChunkRef first = list.get(0);
                List<byte[]> records = new ArrayList<>();
                for (ChunkRef ref : list) {
                    byte[] payload = read(ref);
                    records.add(payload);
                    if (!ChunkCodec.isDiff(payload)) {
                        break;
                    }
                }
                for (byte[] payload : records.size() == 1 ? records : compact(first.folder(), records)) {
                    out.writeUTF(first.folder());
                    out.writeInt(first.x());
                    out.writeInt(first.z());
                    writeData(out, payload);
                }
            }
        }
        Files.move(tmp, into.dir.resolve(CHUNKS), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Set<String> fileKeys = new HashSet<>();
        readFiles(into.dir, (path, data) -> fileKeys.add(path));
        for (Journal journal : later) {
            IOException[] failure = new IOException[1];
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
