package com.qateralong.rollback.mixin;

import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.world.level.chunk.storage.IOWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(IOWorker.class)
public interface IOWorkerAccessor {
    @Accessor("shutdownRequested")
    AtomicBoolean rollback$shutdownRequested();
}
