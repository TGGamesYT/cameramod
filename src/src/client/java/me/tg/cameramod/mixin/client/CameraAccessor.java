package me.tg.cameramod.mixin.client;

import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraAccessor {

    @Accessor("cameraY")
    float cameramod$getCameraY();

    @Accessor("cameraY")
    void cameramod$setCameraY(float value);

    @Accessor("lastCameraY")
    float cameramod$getLastCameraY();

    @Accessor("lastCameraY")
    void cameramod$setLastCameraY(float value);
}
