package com.qateralong.rollback.store;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

public final class ChunkDiff {
    private static final int SIZE = 4096;
    private static final Set<String> SKIP_CHUNK = Set.of("sections", "block_entities", "LastUpdate", "InhabitedTime");
    private static final Set<String> SKIP_ENTITIES = Set.of("Entities");
    private static final Tag MISSING = ByteTag.valueOf((byte) 0);

    private ChunkDiff() {
    }

    public static boolean supported(String folder) {
        String kind = kind(folder);
        return kind.equals("region") || kind.equals("entities");
    }

    private static String kind(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    public static CompoundTag diff(String folder, CompoundTag old, CompoundTag now) {
        boolean entities = kind(folder).equals("entities");
        CompoundTag diff = new CompoundTag();
        diff.putString("k", entities ? "ent" : "chunk");
        CompoundTag top = new CompoundTag();
        ListTag removed = new ListTag();
        Set<String> skip = entities ? SKIP_ENTITIES : SKIP_CHUNK;
        for (String key : old.getAllKeys()) {
            if (!skip.contains(key) && !old.get(key).equals(now.get(key))) {
                top.put(key, old.get(key).copy());
            }
        }
        for (String key : now.getAllKeys()) {
            if (!skip.contains(key) && !old.contains(key)) {
                removed.add(StringTag.valueOf(key));
            }
        }
        if (!top.isEmpty()) {
            diff.put("T", top);
        }
        if (!removed.isEmpty()) {
            diff.put("R", removed);
        }
        if (entities) {
            CompoundTag changed = keyedDiff(entityMap(old), entityMap(now));
            if (changed == null) {
                return null;
            }
            if (!changed.isEmpty()) {
                diff.put("U", changed);
            }
        } else {
            CompoundTag blockEntities = keyedDiff(blockEntityMap(old), blockEntityMap(now));
            if (blockEntities == null) {
                return null;
            }
            if (!blockEntities.isEmpty()) {
                diff.put("E", blockEntities);
            }
            CompoundTag sections = sectionsDiff(sectionMap(old), sectionMap(now));
            if (!sections.isEmpty()) {
                diff.put("S", sections);
            }
        }
        return diff.size() > 1 ? diff : new CompoundTag();
    }

    private static CompoundTag keyedDiff(Map<String, CompoundTag> old, Map<String, CompoundTag> now) {
        if (old == null || now == null) {
            return null;
        }
        CompoundTag result = new CompoundTag();
        for (Map.Entry<String, CompoundTag> entry : old.entrySet()) {
            if (!entry.getValue().equals(now.get(entry.getKey()))) {
                result.put(entry.getKey(), entry.getValue().copy());
            }
        }
        for (String key : now.keySet()) {
            if (!old.containsKey(key)) {
                result.put(key, MISSING);
            }
        }
        return result;
    }

    private static Map<String, CompoundTag> entityMap(CompoundTag tag) {
        return entityMap(tag, true);
    }

    private static Map<String, CompoundTag> entityMap(CompoundTag tag, boolean strict) {
        Map<String, CompoundTag> map = new LinkedHashMap<>();
        int unnamed = 0;
        for (Tag element : tag.getList("Entities", Tag.TAG_COMPOUND)) {
            CompoundTag entity = (CompoundTag) element;
            String key = entity.hasUUID("UUID") ? entity.getUUID("UUID").toString() : "#" + unnamed++;
            if ((map.put(key, entity) != null || key.startsWith("#")) && strict) {
                return null;
            }
        }
        return map;
    }

    private static Map<String, CompoundTag> blockEntityMap(CompoundTag tag) {
        return blockEntityMap(tag, true);
    }

    private static Map<String, CompoundTag> blockEntityMap(CompoundTag tag, boolean strict) {
        Map<String, CompoundTag> map = new LinkedHashMap<>();
        for (Tag element : tag.getList("block_entities", Tag.TAG_COMPOUND)) {
            CompoundTag entity = (CompoundTag) element;
            if (map.put(entity.getInt("x") + "," + entity.getInt("y") + "," + entity.getInt("z"), entity) != null && strict) {
                return null;
            }
        }
        return map;
    }

    private static Map<Integer, CompoundTag> sectionMap(CompoundTag tag) {
        Map<Integer, CompoundTag> map = new LinkedHashMap<>();
        for (Tag element : tag.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) element;
            map.put((int) section.getByte("Y"), section);
        }
        return map;
    }

    private static CompoundTag sectionsDiff(Map<Integer, CompoundTag> old, Map<Integer, CompoundTag> now) {
        CompoundTag result = new CompoundTag();
        for (Map.Entry<Integer, CompoundTag> entry : old.entrySet()) {
            CompoundTag before = entry.getValue();
            CompoundTag after = now.get(entry.getKey());
            if (after == null) {
                CompoundTag whole = new CompoundTag();
                whole.put("s", before.copy());
                result.put(String.valueOf(entry.getKey()), whole);
                continue;
            }
            if (before.equals(after)) {
                continue;
            }
            CompoundTag section = new CompoundTag();
            for (String key : before.getAllKeys()) {
                if (key.equals("block_states") || key.equals("Y")) {
                    continue;
                }
                if (!before.get(key).equals(after.get(key))) {
                    section.put("o" + key, before.get(key).copy());
                }
            }
            for (String key : after.getAllKeys()) {
                if (!key.equals("block_states") && !key.equals("Y") && !before.contains(key)) {
                    section.put("o" + key, MISSING);
                }
            }
            Tag beforeStates = before.get("block_states");
            Tag afterStates = after.get("block_states");
            if (beforeStates != null && !beforeStates.equals(afterStates)) {
                Tag[] oldBlocks = decode(before.getCompound("block_states"));
                Tag[] newBlocks = afterStates instanceof CompoundTag states ? decode(states) : null;
                if (oldBlocks == null || newBlocks == null) {
                    CompoundTag whole = new CompoundTag();
                    whole.put("s", before.copy());
                    result.put(String.valueOf(entry.getKey()), whole);
                    continue;
                }
                List<Integer> positions = new ArrayList<>();
                for (int i = 0; i < SIZE; i++) {
                    if (oldBlocks[i] != newBlocks[i] && !oldBlocks[i].equals(newBlocks[i])) {
                        positions.add(i);
                    }
                }
                putBlocks(section, positions, oldBlocks);
            }
            if (!section.isEmpty()) {
                result.put(String.valueOf(entry.getKey()), section);
            }
        }
        for (Integer y : now.keySet()) {
            if (!old.containsKey(y)) {
                CompoundTag gone = new CompoundTag();
                gone.putBoolean("n", true);
                result.put(String.valueOf(y), gone);
            }
        }
        return result;
    }

    private static void putBlocks(CompoundTag section, List<Integer> positions, Tag[] states) {
        if (positions.isEmpty()) {
            return;
        }
        Map<Tag, Integer> palette = new LinkedHashMap<>();
        int[] indexes = new int[positions.size()];
        int[] values = new int[positions.size()];
        for (int n = 0; n < positions.size(); n++) {
            int i = positions.get(n);
            indexes[n] = i;
            values[n] = palette.computeIfAbsent(states[i], key -> palette.size());
        }
        ListTag list = new ListTag();
        for (Tag state : palette.keySet()) {
            list.add(state.copy());
        }
        section.put("p", list);
        section.putIntArray("i", indexes);
        section.putIntArray("v", values);
    }

    private static Map<Integer, Tag> blocks(CompoundTag section) {
        Map<Integer, Tag> result = new LinkedHashMap<>();
        if (!section.contains("i")) {
            return result;
        }
        ListTag palette = section.getList("p", Tag.TAG_COMPOUND);
        int[] indexes = section.getIntArray("i");
        int[] values = section.getIntArray("v");
        for (int n = 0; n < indexes.length && n < values.length; n++) {
            result.put(indexes[n], palette.get(values[n]));
        }
        return result;
    }

    static Tag[] decode(CompoundTag states) {
        ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
        int size = palette.size();
        if (size == 0) {
            return null;
        }
        Tag[] result = new Tag[SIZE];
        if (size == 1) {
            Arrays.fill(result, palette.get(0));
            return result;
        }
        int bits = Math.max(4, ceilLog2(size));
        long[] data = states.getLongArray("data");
        int perLong = 64 / bits;
        if (data.length != (SIZE + perLong - 1) / perLong) {
            return null;
        }
        long mask = (1L << bits) - 1L;
        for (int i = 0; i < SIZE; i++) {
            int value = (int) ((data[i / perLong] >>> ((i % perLong) * bits)) & mask);
            if (value >= size) {
                return null;
            }
            result[i] = palette.get(value);
        }
        return result;
    }

    static CompoundTag encode(Tag[] blocks) {
        Map<Tag, Integer> palette = new LinkedHashMap<>();
        int[] values = new int[SIZE];
        for (int i = 0; i < SIZE; i++) {
            values[i] = palette.computeIfAbsent(blocks[i], key -> palette.size());
        }
        CompoundTag states = new CompoundTag();
        ListTag list = new ListTag();
        for (Tag state : palette.keySet()) {
            list.add(state.copy());
        }
        states.put("palette", list);
        if (palette.size() > 1) {
            int bits = Math.max(4, ceilLog2(palette.size()));
            int perLong = 64 / bits;
            long[] data = new long[(SIZE + perLong - 1) / perLong];
            for (int i = 0; i < SIZE; i++) {
                data[i / perLong] |= ((long) values[i]) << ((i % perLong) * bits);
            }
            states.put("data", new LongArrayTag(data));
        }
        return states;
    }

    private static int ceilLog2(int value) {
        return 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    public static void merge(CompoundTag into, CompoundTag later) {
        CompoundTag top = into.getCompound("T");
        ListTag removed = into.getList("R", Tag.TAG_STRING);
        Set<String> known = new java.util.HashSet<>(top.getAllKeys());
        for (Tag key : removed) {
            known.add(key.getAsString());
        }
        CompoundTag laterTop = later.getCompound("T");
        for (String key : laterTop.getAllKeys()) {
            if (known.add(key)) {
                top.put(key, laterTop.get(key).copy());
            }
        }
        for (Tag key : later.getList("R", Tag.TAG_STRING)) {
            if (known.add(key.getAsString())) {
                removed.add(key.copy());
            }
        }
        putIfNotEmpty(into, "T", top);
        putIfNotEmpty(into, "R", removed);
        for (String group : new String[]{"U", "E"}) {
            CompoundTag mine = into.getCompound(group);
            CompoundTag theirs = later.getCompound(group);
            for (String key : theirs.getAllKeys()) {
                if (!mine.contains(key)) {
                    mine.put(key, theirs.get(key).copy());
                }
            }
            putIfNotEmpty(into, group, mine);
        }
        CompoundTag sections = into.getCompound("S");
        CompoundTag laterSections = later.getCompound("S");
        for (String y : laterSections.getAllKeys()) {
            CompoundTag theirs = laterSections.getCompound(y);
            if (!sections.contains(y)) {
                sections.put(y, theirs.copy());
                continue;
            }
            CompoundTag mine = sections.getCompound(y);
            if (mine.contains("s") || mine.contains("n") || theirs.contains("n")) {
                continue;
            }
            if (theirs.contains("s")) {
                sections.put(y, wholeSection(applySection(theirs.getCompound("s").copy(), mine)));
                continue;
            }
            for (String key : theirs.getAllKeys()) {
                if (key.startsWith("o") && !mine.contains(key)) {
                    mine.put(key, theirs.get(key).copy());
                }
            }
            Map<Integer, Tag> blocks = blocks(theirs);
            blocks.putAll(blocks(mine));
            if (!blocks.isEmpty()) {
                List<Integer> positions = new ArrayList<>(blocks.keySet());
                Tag[] states = new Tag[SIZE];
                for (Map.Entry<Integer, Tag> entry : blocks.entrySet()) {
                    states[entry.getKey()] = entry.getValue();
                }
                mine.remove("p");
                mine.remove("i");
                mine.remove("v");
                putBlocks(mine, positions, states);
            }
        }
        putIfNotEmpty(into, "S", sections);
    }

    private static CompoundTag wholeSection(CompoundTag section) {
        CompoundTag whole = new CompoundTag();
        whole.put("s", section);
        return whole;
    }

    private static void putIfNotEmpty(CompoundTag tag, String key, Tag value) {
        if (value instanceof CompoundTag compound && compound.isEmpty() || value instanceof ListTag list && list.isEmpty()) {
            tag.remove(key);
        } else {
            tag.put(key, value);
        }
    }

    private static CompoundTag applySection(CompoundTag current, CompoundTag diff) {
        if (diff.contains("s")) {
            return diff.getCompound("s").copy();
        }
        for (String key : diff.getAllKeys()) {
            if (key.startsWith("o")) {
                Tag value = diff.get(key);
                if (value.equals(MISSING)) {
                    current.remove(key.substring(1));
                } else {
                    current.put(key.substring(1), value.copy());
                }
            }
        }
        Map<Integer, Tag> blocks = blocks(diff);
        if (!blocks.isEmpty()) {
            Tag[] states = current.contains("block_states") ? decode(current.getCompound("block_states")) : null;
            if (states == null) {
                states = new Tag[SIZE];
                CompoundTag air = new CompoundTag();
                air.putString("Name", "minecraft:air");
                Arrays.fill(states, air);
            }
            for (Map.Entry<Integer, Tag> entry : blocks.entrySet()) {
                states[entry.getKey()] = entry.getValue();
            }
            current.put("block_states", encode(states));
        }
        return current;
    }

    public static CompoundTag apply(CompoundTag current, CompoundTag diff) {
        CompoundTag result = current.copy();
        CompoundTag top = diff.getCompound("T");
        for (String key : top.getAllKeys()) {
            result.put(key, top.get(key).copy());
        }
        for (Tag key : diff.getList("R", Tag.TAG_STRING)) {
            result.remove(key.getAsString());
        }
        if (diff.getString("k").equals("ent")) {
            result.put("Entities", applyKeyed(entityMap(result, false), diff.getCompound("U")));
            return result;
        }
        if (diff.contains("E")) {
            result.put("block_entities", applyKeyed(blockEntityMap(result, false), diff.getCompound("E")));
        }
        CompoundTag sections = diff.getCompound("S");
        if (!sections.isEmpty()) {
            Map<Integer, CompoundTag> map = sectionMap(result);
            for (String y : sections.getAllKeys()) {
                int level = Integer.parseInt(y);
                CompoundTag change = sections.getCompound(y);
                if (change.contains("n")) {
                    map.remove(level);
                    continue;
                }
                CompoundTag section = map.containsKey(level) ? map.get(level).copy() : new CompoundTag();
                section = applySection(section, change);
                section.putByte("Y", (byte) level);
                map.put(level, section);
            }
            ListTag list = new ListTag();
            map.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> list.add(entry.getValue()));
            result.put("sections", list);
        }
        return result;
    }

    private static ListTag applyKeyed(Map<String, CompoundTag> current, CompoundTag changes) {
        Map<String, CompoundTag> map = current == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current);
        for (String key : changes.getAllKeys()) {
            Tag value = changes.get(key);
            if (value.equals(MISSING)) {
                map.remove(key);
            } else {
                map.put(key, (CompoundTag) value.copy());
            }
        }
        ListTag list = new ListTag();
        list.addAll(map.values());
        return list;
    }

    public static CompoundTag resolve(List<byte[]> records, CompoundTag current) throws java.io.IOException {
        CompoundTag merged = null;
        for (byte[] record : records) {
            if (!ChunkCodec.isDiff(record)) {
                CompoundTag base = ChunkCodec.isAbsent(record) ? null : ChunkCodec.read(record);
                return base == null ? null : merged == null ? base : apply(base, merged);
            }
            CompoundTag diff = ChunkCodec.read(record);
            if (merged == null) {
                merged = diff;
            } else {
                merge(merged, diff);
            }
        }
        if (current == null) {
            return null;
        }
        return merged == null ? current : apply(current, merged);
    }
}
