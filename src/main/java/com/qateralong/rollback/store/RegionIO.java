package com.qateralong.rollback.store;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class RegionIO {
    private static final int SECTOR = 4096;
    private static final int MAX_INLINE = 255 * SECTOR - 4;
    private static final int EXTERNAL = 128;

    private RegionIO() {
    }

    public static int index(int x, int z) {
        return (x & 31) + (z & 31) * 32;
    }

    public static Path regionFile(Path folder, int x, int z) {
        return folder.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
    }

    private static Path externalFile(Path folder, int x, int z) {
        return folder.resolve("c." + x + "." + z + ".mcc");
    }

    public static void apply(Path folder, int regionX, int regionZ, Map<Integer, byte[]> changes) throws IOException {
        Path file = folder.resolve("r." + regionX + "." + regionZ + ".mca");
        byte[][] payloads = new byte[1024][];
        int[] timestamps = new int[1024];
        if (Files.exists(file) && Files.size(file) >= 2L * SECTOR) {
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
                readFully(channel, header, 0L);
                header.flip();
                int[] locations = new int[1024];
                for (int i = 0; i < 1024; i++) {
                    locations[i] = header.getInt();
                }
                for (int i = 0; i < 1024; i++) {
                    timestamps[i] = header.getInt();
                }
                long size = channel.size();
                for (int i = 0; i < 1024; i++) {
                    int location = locations[i];
                    if (location == 0) {
                        continue;
                    }
                    long offset = (long) (location >>> 8) * SECTOR;
                    int sectors = location & 0xFF;
                    if (offset < 2L * SECTOR || offset + 5 > size || sectors == 0) {
                        continue;
                    }
                    ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
                    readFully(channel, lengthBuffer, offset);
                    lengthBuffer.flip();
                    int length = lengthBuffer.getInt();
                    if (length <= 0 || offset + 4 + length > size || length > sectors * SECTOR) {
                        continue;
                    }
                    ByteBuffer data = ByteBuffer.allocate(length);
                    readFully(channel, data, offset + 4);
                    payloads[i] = data.array();
                }
            }
        }
        int now = (int) (System.currentTimeMillis() / 1000L);
        for (Map.Entry<Integer, byte[]> change : changes.entrySet()) {
            int i = change.getKey();
            int x = regionX * 32 + (i & 31);
            int z = regionZ * 32 + (i >> 5);
            Path external = externalFile(folder, x, z);
            byte[] payload = change.getValue();
            if (payload == null || payload == Journal.ABSENT || payload.length == 0) {
                payloads[i] = null;
                timestamps[i] = 0;
                Files.deleteIfExists(external);
                continue;
            }
            if (payload.length > MAX_INLINE) {
                Files.createDirectories(folder);
                Files.write(external, java.util.Arrays.copyOfRange(payload, 1, payload.length));
                payloads[i] = new byte[]{(byte) (payload[0] | EXTERNAL)};
            } else {
                payloads[i] = payload;
                if ((payload[0] & EXTERNAL) == 0) {
                    Files.deleteIfExists(external);
                }
            }
            timestamps[i] = now;
        }
        boolean any = false;
        for (byte[] payload : payloads) {
            any |= payload != null;
        }
        if (!any) {
            Files.deleteIfExists(file);
            return;
        }
        Files.createDirectories(folder);
        Path tmp = folder.resolve(file.getFileName() + ".rbtmp");
        int[] locations = new int[1024];
        try (OutputStream out = Files.newOutputStream(tmp)) {
            int sector = 2;
            java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < 1024; i++) {
                byte[] payload = payloads[i];
                if (payload == null) {
                    continue;
                }
                int total = 4 + payload.length;
                int sectors = (total + SECTOR - 1) / SECTOR;
                locations[i] = (sector << 8) | sectors;
                ByteBuffer chunk = ByteBuffer.allocate(sectors * SECTOR);
                chunk.putInt(payload.length);
                chunk.put(payload);
                data.write(chunk.array());
                sector += sectors;
            }
            ByteBuffer header = ByteBuffer.allocate(2 * SECTOR);
            for (int location : locations) {
                header.putInt(location);
            }
            for (int timestamp : timestamps) {
                header.putInt(timestamp);
            }
            out.write(header.array());
            data.writeTo(out);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position + buffer.position());
            if (read < 0) {
                throw new IOException("Unexpected end of region file");
            }
        }
    }
}
