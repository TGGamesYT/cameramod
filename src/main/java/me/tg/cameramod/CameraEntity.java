package me.tg.cameramod;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.ReadView;
import net.minecraft.storage.WriteView;
import net.minecraft.util.Arm;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.Optional;
import java.util.UUID;

public class CameraEntity extends LivingEntity {

    private static final TrackedData<Float> ZOOM_LEVEL =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Boolean> IS_BEING_MOVED =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    // GROUNDED latch: once the camera touches the ground, stays true until a mover
    // picks it up.  Prevents gravity from oscillating on/off each tick.
    private static final TrackedData<Boolean> GROUNDED =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // UUID of the entity this camera is fixed to look at (synced to client for smooth tracking)
    // Stored as string ("" = no target) since OPTIONAL_UUID doesn't exist in 1.21.8
    private static final TrackedData<String> FIXED_TARGET =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.STRING);

    public static DefaultAttributeContainer.Builder createCameraAttributes() {
        return LivingEntity.createLivingAttributes()
                .add(EntityAttributes.MAX_HEALTH, 1.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.0);
    }

    public CameraEntity(EntityType<? extends LivingEntity> type, World world) {
        super(type, world);
        this.setNoGravity(true);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(ZOOM_LEVEL, 1.0f);
        builder.add(IS_BEING_MOVED, false);
        builder.add(GROUNDED, true); // cameras start grounded (placed on a surface)
        builder.add(FIXED_TARGET, "");
    }

    // --- Zoom ---
    public float getZoomLevel() {
        return this.dataTracker.get(ZOOM_LEVEL);
    }

    public void setZoomLevel(float zoom) {
        this.dataTracker.set(ZOOM_LEVEL, Math.max(0.1f, zoom));
    }

    // --- Being moved by Camera Mover ---
    public boolean isBeingMoved() {
        return this.dataTracker.get(IS_BEING_MOVED);
    }

    public void setBeingMoved(boolean moved) {
        this.dataTracker.set(IS_BEING_MOVED, moved);
        if (moved) {
            // Un-ground when mover picks camera up
            this.dataTracker.set(GROUNDED, false);
        }
    }

    // --- Grounded state ---
    public boolean isGrounded() {
        return this.dataTracker.get(GROUNDED);
    }

    // --- Fixed target ---
    public UUID getFixedTargetUuid() {
        String s = this.dataTracker.get(FIXED_TARGET);
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setFixedTargetUuid(UUID uuid) {
        this.dataTracker.set(FIXED_TARGET, uuid != null ? uuid.toString() : "");
    }

    /**
     * Check if there's a solid block directly below the camera's feet.
     * Uses the entity's actual Y coordinate (minus a small epsilon) to avoid
     * false positives when the entity is floating half a block above ground.
     */
    public boolean hasBlockBelow() {
        // Check the block at the entity's feet minus a tiny offset.
        // If feet are at y=65.0 (standing on a block whose top is y=65),
        // this checks y=64.95 → BlockPos y=64 → the solid block below → true.
        // If feet are at y=65.5 (floating), this checks y=65.45 → BlockPos y=65 → air → false.
        BlockPos feetBlock = BlockPos.ofFloored(this.getX(), this.getY() - 0.05, this.getZ());
        World w = this.getWorld();
        return !w.getBlockState(feetBlock).getCollisionShape(w, feetBlock).isEmpty();
    }

    @Override
    public void tick() {
        this.lastBodyYaw = this.bodyYaw;
        this.lastHeadYaw = this.headYaw;

        // Gravity logic (server-side only):
        // Uses a GROUNDED latch to prevent oscillation.
        // Once grounded, stays grounded until a mover picks it up.
        if (!this.getWorld().isClient) {
            if (isBeingMoved()) {
                // Mover active: float freely, no gravity
                this.setNoGravity(true);
            } else if (this.dataTracker.get(GROUNDED)) {
                // Grounded latch active: no gravity, stay in place
                this.setNoGravity(true);
                this.setVelocity(Vec3d.ZERO);
            } else if (this.isOnGround() || hasBlockBelow()) {
                // Just landed: latch as grounded
                this.dataTracker.set(GROUNDED, true);
                this.setNoGravity(true);
                this.setVelocity(Vec3d.ZERO);
            } else {
                // In air, not moved, not grounded: fall
                this.setNoGravity(false);
            }
        }

        super.tick();
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
    public void kill(ServerWorld world) {
        this.remove(RemovalReason.KILLED);
    }

    @Override
    public boolean handleFallDamage(double fallDistance, float damagePerDistance, DamageSource damageSource) {
        return false;
    }

    @Override
    protected void readCustomData(ReadView view) {
        Optional<String> targetStr = view.getOptionalString("FixedTarget");
        targetStr.ifPresent(s -> {
            try {
                setFixedTargetUuid(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                setFixedTargetUuid(null);
            }
        });
        float zoom = view.getFloat("ZoomLevel", 1.0f);
        this.dataTracker.set(ZOOM_LEVEL, zoom);
    }

    @Override
    protected void writeCustomData(WriteView view) {
        UUID target = getFixedTargetUuid();
        if (target != null) {
            view.putString("FixedTarget", target.toString());
        }
        float zoom = this.dataTracker.get(ZOOM_LEVEL);
        if (zoom != 1.0f) {
            view.putFloat("ZoomLevel", zoom);
        }
    }
}
