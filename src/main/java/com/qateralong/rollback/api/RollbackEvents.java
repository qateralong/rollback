package com.qateralong.rollback.api;

import java.time.Instant;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

public final class RollbackEvents {
    public static final Event<AllowRollback> ALLOW_ROLLBACK = EventFactory.createArrayBacked(AllowRollback.class, listeners -> (target, reason) -> {
        for (AllowRollback listener : listeners) {
            if (!listener.allow(target, reason)) {
                return false;
            }
        }
        return true;
    });

    public static final Event<BeforeRestart> BEFORE_RESTART = EventFactory.createArrayBacked(BeforeRestart.class, listeners -> (target, reason) -> {
        for (BeforeRestart listener : listeners) {
            listener.beforeRestart(target, reason);
        }
    });

    public static final Event<Checkpoint> CHECKPOINT = EventFactory.createArrayBacked(Checkpoint.class, listeners -> time -> {
        for (Checkpoint listener : listeners) {
            listener.onCheckpoint(time);
        }
    });

    private RollbackEvents() {
    }

    @FunctionalInterface
    public interface AllowRollback {
        boolean allow(Instant target, String reason);
    }

    @FunctionalInterface
    public interface BeforeRestart {
        void beforeRestart(Instant target, String reason);
    }

    @FunctionalInterface
    public interface Checkpoint {
        void onCheckpoint(Instant time);
    }
}
