package com.qateralong.rollback.api;

import com.qateralong.rollback.Countdown;
import com.qateralong.rollback.Overload;
import com.qateralong.rollback.Recorder;
import com.qateralong.rollback.Rollback;
import com.qateralong.rollback.RollbackConfig;
import com.qateralong.rollback.store.Journal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class RollbackApi {
    private RollbackApi() {
    }

    public static List<Instant> checkpoints() {
        return Recorder.world() == null ? List.of() : Journal.ids(Recorder.world()).stream().map(Instant::ofEpochSecond).toList();
    }

    public static Optional<Instant> checkpointAtOrBefore(Instant time) {
        long id = Recorder.world() == null ? -1L : Overload.checkpointAtOrBefore(time.getEpochSecond());
        return id < 0 ? Optional.empty() : Optional.of(Instant.ofEpochSecond(id));
    }

    public static boolean rollback(Instant time, String reason) {
        return rollback(time, reason, RollbackConfig.get().countdownSeconds);
    }

    public static boolean rollback(Instant time, String reason, int countdownSeconds) {
        Optional<Instant> target = checkpointAtOrBefore(time);
        return target.isPresent() && Rollback.server() != null
                && Countdown.start(Rollback.server(), target.get().getEpochSecond(), reason, false, countdownSeconds);
    }

    public static boolean cancel() {
        return Rollback.server() != null && Countdown.cancel(Rollback.server(), "api");
    }

    public static boolean isPending() {
        return Countdown.active();
    }

    public static Optional<Instant> checkpointNow() {
        long id = Rollback.server() == null ? -1L : Recorder.checkpoint(Rollback.server());
        return id < 0 ? Optional.empty() : Optional.of(Instant.ofEpochSecond(id));
    }
}
