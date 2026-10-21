package com.qateralong.rollback;

import com.qateralong.rollback.store.Journal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class Maintenance {
    private static ScheduledExecutorService executor;

    private Maintenance() {
    }

    public static void start() {
        stop();
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "rollback maintenance");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        executor.scheduleWithFixedDelay(Maintenance::run, 2, 10, TimeUnit.MINUTES);
    }

    public static void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public static synchronized void run() {
        Path world = Recorder.world();
        if (world == null || Countdown.active()) {
            return;
        }
        try {
            RollbackConfig config = RollbackConfig.get();
            List<Long> ids = new ArrayList<>(Journal.ids(world));
            long current = Recorder.currentId();
            ids.removeIf(id -> id >= current);
            if (!ids.isEmpty()) {
                ids.remove(ids.size() - 1);
            }
            long now = System.currentTimeMillis() / 1000L;
            long retention = config.retentionDays * 86400L;
            List<Long> kept = new ArrayList<>();
            for (long id : ids) {
                if (now - id > retention) {
                    Journal.delete(Journal.open(world, id).dir);
                } else {
                    kept.add(id);
                }
            }
            int i = 0;
            while (i < kept.size()) {
                long id = kept.get(i);
                long bucket = bucket(now - id, config);
                if (bucket == 0) {
                    i++;
                    continue;
                }
                List<Journal> group = new ArrayList<>();
                int j = i + 1;
                while (j < kept.size() && bucket(now - kept.get(j), config) == bucket && kept.get(j) / bucket == id / bucket) {
                    group.add(Journal.open(world, kept.get(j)));
                    j++;
                }
                if (!group.isEmpty()) {
                    Journal.merge(Journal.open(world, id), group);
                }
                i = j;
            }
            List<Long> remaining = Journal.ids(world);
            long limit = (long) (config.maxSizeGb * 1024 * 1024 * 1024);
            long total = 0L;
            for (long id : remaining) {
                total += Journal.size(Journal.open(world, id).dir);
            }
            for (long id : remaining) {
                if (total <= limit || id >= current) {
                    break;
                }
                Path dir = Journal.open(world, id).dir;
                total -= Journal.size(dir);
                Journal.delete(dir);
            }
        } catch (Exception e) {
            Rollback.LOGGER.error("Rollback history maintenance failed", e);
        }
    }

    private static long bucket(long age, RollbackConfig config) {
        if (age <= config.keepEveryCheckpointHours * 3600L) {
            return 0L;
        }
        return age <= config.keepTenMinutesHours * 3600L ? 600L : 3600L;
    }

    public static long totalSize() {
        Path world = Recorder.world();
        long total = 0L;
        if (world != null) {
            for (long id : Journal.ids(world)) {
                total += Journal.size(Journal.open(world, id).dir);
            }
        }
        return total;
    }
}
