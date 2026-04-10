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

    // Fixer mode: 0 = look_at (face target), 1 = look_same_way (copy target's rotation)
    private static final TrackedData<Byte> FIXER_MODE =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.BYTE);

    // Whether gravity is enabled for this camera (toggled by gravity item)
    private static final TrackedData<Boolean> GRAVITY_ENABLED =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // UUID of the entity this camera is attached to (moves with it)
    private static final TrackedData<String> ATTACH_TARGET =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.STRING);
    // Relative offset from the attached entity
    private static final TrackedData<Float> ATTACH_OFFSET_X =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ATTACH_OFFSET_Y =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> ATTACH_OFFSET_Z =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.FLOAT);

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
        builder.add(FIXER_MODE, (byte) 0);
        builder.add(GRAVITY_ENABLED, true);
        builder.add(ATTACH_TARGET, "");
        builder.add(ATTACH_OFFSET_X, 0.0f);
        builder.add(ATTACH_OFFSET_Y, 0.0f);
        builder.add(ATTACH_OFFSET_Z, 0.0f);
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

    // --- Fixer mode ---
    public byte getFixerMode() {
        return this.dataTracker.get(FIXER_MODE);
    }

    public void setFixerMode(byte mode) {
        this.dataTracker.set(FIXER_MODE, mode);
    }

    // --- Gravity enabled ---
    public boolean isGravityEnabled() {
        return this.dataTracker.get(GRAVITY_ENABLED);
    }

    public void setGravityEnabled(boolean enabled) {
        this.dataTracker.set(GRAVITY_ENABLED, enabled);
        if (!enabled) {
            // Disable gravity immediately, un-ground
            this.dataTracker.set(GROUNDED, false);
            this.setNoGravity(true);
            this.setVelocity(Vec3d.ZERO);
        }
    }

    // --- Attachment target ---
    public UUID getAttachTargetUuid() {
        String s = this.dataTracker.get(ATTACH_TARGET);
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setAttachTargetUuid(UUID uuid) {
        this.dataTracker.set(ATTACH_TARGET, uuid != null ? uuid.toString() : "");
    }

    public Vec3d getAttachOffset() {
        return new Vec3d(
                this.dataTracker.get(ATTACH_OFFSET_X),
                this.dataTracker.get(ATTACH_OFFSET_Y),
                this.dataTracker.get(ATTACH_OFFSET_Z));
    }

    public void setAttachOffset(Vec3d offset) {
        this.dataTracker.set(ATTACH_OFFSET_X, (float) offset.x);
        this.dataTracker.set(ATTACH_OFFSET_Y, (float) offset.y);
        this.dataTracker.set(ATTACH_OFFSET_Z, (float) offset.z);
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
            if (!isGravityEnabled() || isBeingMoved()) {
                // Gravity disabled or mover active: float freely
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

            // Attachment: follow attached entity with saved offset
            UUID attachUuid = getAttachTargetUuid();
            if (attachUuid != null && this.getWorld() instanceof ServerWorld sw) {
                net.minecraft.entity.Entity attachTarget = sw.getEntity(attachUuid);
                if (attachTarget != null) {
                    Vec3d offset = getAttachOffset();
                    this.requestTeleport(
                            attachTarget.getX() + offset.x,
                            attachTarget.getY() + offset.y,
                            attachTarget.getZ() + offset.z);
                }
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
        this.dataTracker.set(FIXER_MODE, view.getByte("FixerMode", (byte) 0));
        this.dataTracker.set(GRAVITY_ENABLED, view.getBoolean("GravityEnabled", true));

        view.getOptionalString("AttachTarget").ifPresent(s -> {
            try {
                setAttachTargetUuid(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                setAttachTargetUuid(null);
            }
        });
        this.dataTracker.set(ATTACH_OFFSET_X, view.getFloat("AttachOffsetX", 0.0f));
        this.dataTracker.set(ATTACH_OFFSET_Y, view.getFloat("AttachOffsetY", 0.0f));
        this.dataTracker.set(ATTACH_OFFSET_Z, view.getFloat("AttachOffsetZ", 0.0f));
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
        byte fixerMode = this.dataTracker.get(FIXER_MODE);
        if (fixerMode != 0) {
            view.putByte("FixerMode", fixerMode);
        }
        if (!isGravityEnabled()) {
            view.putBoolean("GravityEnabled", false);
        }
        UUID attachTarget = getAttachTargetUuid();
        if (attachTarget != null) {
            view.putString("AttachTarget", attachTarget.toString());
            Vec3d offset = getAttachOffset();
            view.putFloat("AttachOffsetX", (float) offset.x);
            view.putFloat("AttachOffsetY", (float) offset.y);
            view.putFloat("AttachOffsetZ", (float) offset.z);
        }
    }
}
