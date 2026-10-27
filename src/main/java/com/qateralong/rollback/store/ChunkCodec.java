package com.qateralong.rollback.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

public final class ChunkCodec {
    public static final byte FULL = 2;
    public static final byte DIFF = 0x44;
    private static final int EXTERNAL = 128;

    private ChunkCodec() {
    }

    public static byte[] full(CompoundTag tag) throws IOException {
        return pack(FULL, tag);
    }

    public static byte[] diff(CompoundTag tag) throws IOException {
        return pack(DIFF, tag);
    }

    private static byte[] pack(byte type, CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        bytes.write(type);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(bytes)))) {
            NbtIo.write(tag, out);
        }
        return bytes.toByteArray();
    }

    public static boolean isDiff(byte[] payload) {
        return payload != null && payload.length > 0 && payload[0] == DIFF;
    }

    public static boolean isAbsent(byte[] payload) {
        return payload == null || payload.length == 0;
    }

    public static CompoundTag read(byte[] payload) throws IOException {
        return read(payload, null, 0, 0);
    }

    public static CompoundTag read(byte[] payload, Path folder, int x, int z) throws IOException {
        int type = payload[0] & 0xFF;
        InputStream data;
        if ((type & EXTERNAL) != 0 && type != DIFF) {
            if (folder == null) {
                throw new IOException("External chunk without folder");
            }
            data = Files.newInputStream(folder.resolve("c." + x + "." + z + ".mcc"));
            type &= ~EXTERNAL;
        } else {
            data = new ByteArrayInputStream(payload, 1, payload.length - 1);
        }
        InputStream stream = switch (type) {
            case 1 -> new GZIPInputStream(data);
            case 2, DIFF -> new InflaterInputStream(data);
            case 3 -> data;
            case 4 -> new net.jpountz.lz4.LZ4BlockInputStream(data);
            default -> throw new IOException("Unsupported chunk compression " + type);
        };
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
            return NbtIo.read(in, NbtAccounter.unlimitedHeap());
        }
    }
}
