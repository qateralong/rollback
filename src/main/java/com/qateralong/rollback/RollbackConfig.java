package com.qateralong.rollback;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

public class RollbackConfig {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("rollback.json");
    private static RollbackConfig instance = new RollbackConfig();

    public int checkpointSeconds = 60;
    public int keepEveryCheckpointHours = 2;
    public int keepTenMinutesHours = 24;
    public int retentionDays = 7;
    public double maxSizeGb = 20.0;
    public int countdownSeconds = 5;
    public String timeZone = "";
    public int permissionLevel = 3;
    public int exitCode = 0;
    public String kickMessage = "Откат сервера до %time%. Сервер перезапускается — зайдите через минуту.";
    public Overload overload = new Overload();

    public static class Overload {
        public boolean enabled = true;
        public double tpsBelow = 10.0;
        public int lowTpsSeconds = 15;
        public int hangSeconds = 10;
        public int rollbackMinutes = 2;
        public int startupGraceSeconds = 120;
        public int maxAutoRollbacksPerHour = 2;
        public int cooldownAfterCancelMinutes = 10;
        public int hangExitCode = 1;
    }

    public static RollbackConfig get() {
        return instance;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                try (Reader reader = Files.newBufferedReader(FILE)) {
                    RollbackConfig loaded = Rollback.GSON.fromJson(reader, RollbackConfig.class);
                    if (loaded != null) {
                        if (loaded.overload == null) {
                            loaded.overload = new Overload();
                        }
                        instance = loaded;
                    }
                }
            }
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                Rollback.GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            Rollback.LOGGER.error("Failed to load {}", FILE, e);
        }
    }
}
