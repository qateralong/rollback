package com.qateralong.rollback.store;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

public class RollbackPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.SERVER) {
            return;
        }
        Restorer.restoreIfPending(worldPath());
    }

    public static Path worldPath() {
        Path run = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        String name = "world";
        Path properties = run.resolve("server.properties");
        if (Files.exists(properties)) {
            try (Reader reader = Files.newBufferedReader(properties)) {
                Properties props = new Properties();
                props.load(reader);
                name = props.getProperty("level-name", "world");
            } catch (Exception ignored) {
            }
        }
        return run.resolve(name).toAbsolutePath().normalize();
    }
}
