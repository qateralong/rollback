package com.qateralong.rollback;

import java.util.Map;
import net.minecraft.server.MinecraftServer;

public final class Diagnostics {
    private static final int FRAMES = 150;
    private static long stallDumpedFor;
    private static int stallDumps;
    private static long lastStallDump;
    private static long lastMemoryDump;
    private static int memoryDumps;

    private Diagnostics() {
    }

    public static void check(MinecraftServer server, long lastTickWall) {
        long now = System.currentTimeMillis();
        long stalled = now - lastTickWall;
        if (stallDumpedFor != lastTickWall) {
            stallDumpedFor = lastTickWall;
            stallDumps = 0;
        }
        if (stalled >= 5000L && stallDumps < 4 && now - lastStallDump >= 5000L) {
            stallDumps++;
            lastStallDump = now;
            Thread thread = server.getRunningThread();
            StringBuilder text = new StringBuilder("Server thread has not ticked for ").append(stalled / 1000L).append(" s, ").append(memory()).append('\n');
            append(text, thread, thread.getStackTrace());
            Rollback.LOGGER.warn(text.toString());
        }
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        if (now - lastMemoryDump > 600_000L) {
            memoryDumps = 0;
        }
        if (used > runtime.maxMemory() * 0.85 && memoryDumps < 3 && now - lastMemoryDump >= 10_000L) {
            memoryDumps++;
            lastMemoryDump = now;
            StringBuilder text = new StringBuilder("Memory is almost full: ").append(memory()).append(", running threads:\n");
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (thread.getState() == Thread.State.RUNNABLE && entry.getValue().length > 0 && thread != Thread.currentThread()) {
                    append(text, thread, entry.getValue());
                }
            }
            Rollback.LOGGER.warn(text.toString());
        }
    }

    private static String memory() {
        Runtime runtime = Runtime.getRuntime();
        return "heap " + (runtime.totalMemory() - runtime.freeMemory()) / 1048576L + "/" + runtime.maxMemory() / 1048576L + " MB";
    }

    private static void append(StringBuilder text, Thread thread, StackTraceElement[] trace) {
        text.append("\"").append(thread.getName()).append("\" ").append(thread.getState()).append('\n');
        for (int i = 0; i < Math.min(FRAMES, trace.length); i++) {
            text.append("    at ").append(trace[i]).append('\n');
        }
    }
}
