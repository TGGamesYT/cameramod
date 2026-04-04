package me.tg.cameramod;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Arm;
import net.minecraft.world.World;

public class CameraEntity extends LivingEntity {

    public static DefaultAttributeContainer.Builder createCameraAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(EntityAttributes.MAX_HEALTH, 1.0) // minimal health
                .add(EntityAttributes.MOVEMENT_SPEED, 0.0); // camera doesn’t move
    }

    public CameraEntity(EntityType<? extends LivingEntity> type, World world) {
        super(type, world);
    }

    @Override
    public void tick() {
        super.tick();

        // Use getter instead of direct field access
        if (!this.getWorld().isClient) {
            // Each tick, capture POV and send to Softcam
            //CameraManager.captureAndSend(this);
        }
    }

    @Override
    public Arm getMainArm() {
        return null;
    }

    @Override
    public boolean damage(ServerWorld world, DamageSource source, float amount) {
        return false;
    }

    @Override
    protected void readCustomData(ReadView view) {

    }

    @Override
    protected void writeCustomData(WriteView view) {

    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder); // important!
        // your own custom tracked data here, if needed
    }
}
