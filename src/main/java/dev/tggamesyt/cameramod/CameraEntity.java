package dev.tggamesyt.cameramod;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemStack;
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
    // Attach mode: 0 = world-space (fixed relative), 1 = head-space (rotates with entity yaw)
    private static final TrackedData<Byte> ATTACH_MODE =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.BYTE);

    // UUID of the player currently moving this camera with the Camera Mover.
    // Synced so the client can position the camera per-frame from the local
    // player's lerped pos + rotation, instead of waiting for the 20 Hz server
    // requestTeleport stream (which stutters when the player flies fast).
    // "" = nobody is moving it.
    private static final TrackedData<String> MOVER_PLAYER =
            DataTracker.registerData(CameraEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Float> MOVER_DISTANCE =
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
        builder.add(ATTACH_MODE, (byte) 0);
        builder.add(MOVER_PLAYER, "");
        builder.add(MOVER_DISTANCE, 5.0f);
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

    // --- Attach mode ---
    public byte getAttachMode() {
        return this.dataTracker.get(ATTACH_MODE);
    }

    public void setAttachMode(byte mode) {
        this.dataTracker.set(ATTACH_MODE, mode);
    }

    // --- Mover state (synced for per-frame client-side prediction) ---
    public UUID getMoverPlayerUuid() {
        String s = this.dataTracker.get(MOVER_PLAYER);
        if (s == null || s.isEmpty()) return null;
        try { return UUID.fromString(s); } catch (IllegalArgumentException e) { return null; }
    }

    public void setMoverPlayerUuid(UUID uuid) {
        this.dataTracker.set(MOVER_PLAYER, uuid != null ? uuid.toString() : "");
    }

    public float getMoverDistance() {
        return this.dataTracker.get(MOVER_DISTANCE);
    }

    public void setMoverDistance(float distance) {
        this.dataTracker.set(MOVER_DISTANCE, distance);
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

    // Client-only flag: set when this entity has no server counterpart.
    // Allows client-side gravity without fighting server position updates.
    private boolean clientOnly = false;
    public void setClientOnly(boolean b) { this.clientOnly = b; }
    public boolean isClientOnly() { return this.clientOnly; }

    // Client-side rotation lock. While EditCameraScreen's "Rotate" mode drives
    // this camera, the client owns its rotation. A server-tracked entity is
    // otherwise interpolated toward the values the server last broadcast, so
    // every tick the interpolator drags the yaw/pitch back toward a stale
    // value — that fight is the visible rotate "vibration". While locked, tick()
    // cancels the interpolation and re-asserts the client's rotation.
    private boolean clientRotationLocked = false;
    private float   clientLockedYaw   = 0f;
    private float   clientLockedPitch = 0f;

    /** Lock client-side rotation to the given values (also used to update them). */
    public void cameramod$lockClientRotation(float yaw, float pitch) {
        this.clientRotationLocked = true;
        this.clientLockedYaw   = yaw;
        this.clientLockedPitch = pitch;
    }

    /** Release the rotation lock so normal server interpolation resumes. */
    public void cameramod$unlockClientRotation() {
        this.clientRotationLocked = false;
    }

    // Expose protected Entity.unsetRemoved() so CameramodClient can re-add
    // a client-only camera that was evicted from the world entity list.
    public void resetRemoval() { this.unsetRemoved(); }

    @Override
    public boolean canMoveVoluntarily() {
        // For client-only entities, the client is the authoritative side, so it must
        // tick movement (gravity, collisions). Default behavior returns true only on
        // the server, which prevents client-side gravity from ever applying.
        return !this.getWorld().isClient || this.clientOnly;
    }

    @Override
    public void tick() {
        this.lastBodyYaw = this.bodyYaw;
        this.lastHeadYaw = this.headYaw;

        // Gravity: always server-side; client-side only for client-owned entities
        // (server-tracked entities get authoritative position each tick, so running
        // gravity client-side too would cause jitter).
        if (!this.getWorld().isClient || this.clientOnly) {
            if (!isGravityEnabled() || isBeingMoved()) {
                this.setNoGravity(true);
            } else if (this.dataTracker.get(GROUNDED)) {
                this.setNoGravity(true);
                this.setVelocity(Vec3d.ZERO);
            } else if (this.isOnGround() || hasBlockBelow()) {
                this.dataTracker.set(GROUNDED, true);
                this.setNoGravity(true);
                this.setVelocity(Vec3d.ZERO);
            } else {
                this.setNoGravity(false);
            }
        }

        // Attachment: server-side only (client handles per-frame in WorldRenderEvents.START)
        if (!this.getWorld().isClient) {
            UUID attachUuid = getAttachTargetUuid();
            if (attachUuid != null && this.getWorld() instanceof ServerWorld sw) {
                net.minecraft.entity.Entity attachTarget = sw.getEntity(attachUuid);
                if (attachTarget != null) {
                    Vec3d offset = getAttachOffset();
                    double wx, wz;
                    if (getAttachMode() == 1) {
                        float yawRad = (float) (attachTarget.getYaw() * Math.PI / 180.0);
                        wx = offset.x * Math.cos(yawRad) - offset.z * Math.sin(yawRad);
                        wz = offset.x * Math.sin(yawRad) + offset.z * Math.cos(yawRad);
                    } else {
                        wx = offset.x;
                        wz = offset.z;
                    }
                    this.requestTeleport(
                            attachTarget.getX() + wx,
                            attachTarget.getY() + offset.y,
                            attachTarget.getZ() + wz);
                }
            }
        }

        super.tick();

        // Client-side: when the client owns this camera's rotation/position (it's
        // being driven every frame in WorldRenderEvents.START by the fixer or an
        // attachment), clear the position interpolator that super.tick() advances.
        // PositionInterpolator.tick() calls setPosition()/setRotation() toward the
        // server's last-broadcast values every tick — CameraEntityLerpMixin cancels
        // the legacy lerpPosAndRotation path but NOT this interpolator. Left running,
        // it drags the camera back toward the stale server rotation each tick while
        // the per-frame fixer pulls it toward the target: that tug-of-war is the
        // "rapid look-at oscillation" seen when a fixed camera is off-screen (the
        // entity still ticks, but the renderer never refreshes its visual state).
        if (this.getWorld().isClient && !this.clientRotationLocked
                && (getFixedTargetUuid() != null || getAttachTargetUuid() != null)) {
            this.getInterpolator().clear();
        }

        // Client-side: while the edit screen is rotating this camera, override
        // the server-driven interpolation that super.tick() just applied so the
        // camera holds exactly the rotation the user's mouse produced.
        if (this.getWorld().isClient && this.clientRotationLocked) {
            this.getInterpolator().clear();
            this.headTrackingIncrements = 0;
            this.setYaw(this.clientLockedYaw);
            this.setPitch(this.clientLockedPitch);
            this.setHeadYaw(this.clientLockedYaw);
            this.setBodyYaw(this.clientLockedYaw);
            // Match the previous-tick values so render interpolation doesn't
            // smear between a stale and a fresh angle for one frame.
            this.lastYaw      = this.clientLockedYaw;
            this.lastPitch    = this.clientLockedPitch;
            this.lastHeadYaw  = this.clientLockedYaw;
            this.lastBodyYaw  = this.clientLockedYaw;
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
    public void kill(ServerWorld world) {
        this.remove(RemovalReason.KILLED);
    }

    @Override
    public boolean handleFallDamage(double fallDistance, float damagePerDistance, DamageSource damageSource) {
        return false;
    }

    @Override
    public ItemStack getPickBlockStack() {
        return new ItemStack(ServerItems.CAMERA_ITEM);
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
        this.dataTracker.set(ATTACH_MODE, view.getByte("AttachMode", (byte) 0));
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
            byte attachMode = getAttachMode();
            if (attachMode != 0) view.putByte("AttachMode", attachMode);
        }
    }
}
