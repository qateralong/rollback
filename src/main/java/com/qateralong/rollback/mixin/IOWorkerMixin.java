package com.qateralong.rollback.mixin;

import com.qateralong.rollback.Recorder;
import java.nio.file.Path;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IOWorker.class)
public class IOWorkerMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void rollback$register(RegionStorageInfo info, Path folder, boolean sync, CallbackInfo ci) {
        Recorder.registerWorker((IOWorker) (Object) this);
    }
}
