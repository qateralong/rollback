package com.qateralong.rollback.mixin;

import com.qateralong.rollback.Recorder;
import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RegionFileStorage.class)
public abstract class RegionFileStorageMixin {
    @Shadow
    @Final
    private Path folder;

    @Shadow
    public abstract CompoundTag read(ChunkPos pos) throws IOException;

    @Inject(method = "write", at = @At("HEAD"))
    private void rollback$journal(ChunkPos pos, CompoundTag tag, CallbackInfo ci) {
        Recorder.beforeRegionWrite(this.folder, pos, this::read);
    }
}
