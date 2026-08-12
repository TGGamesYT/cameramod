package dev.tggamesyt.cameramod.client;

import net.minecraft.client.gl.Framebuffer;
import net.minecraft.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Vivecraft (VR) interop, verified against Vivecraft 1.3.9 (MC 1.21.6-11).
 *
 * <h2>Session flag vs. per-eye flag</h2>
 * {@code VRState.VR_RUNNING} is only true <em>while an eye pass is executing</em>.
 * On the frame-graph pipeline (MC 1.21.9+) {@code MinecraftClient.render} still
 * calls the vanilla desktop {@code GameRenderer.render} with {@code VR_RUNNING =
 * false}, even though VR is on and Vivecraft's multi-pass targets are installed.
 * Our camera pass runs from a flat {@code GameRenderer.render} (the desktop one on
 * 1.21.9+, or {@code MinecraftClientRenderMixin} on the older per-eye pipeline),
 * so it must decide "is VR on" from the SESSION flag {@link #isVrMode()}
 * ({@code VR_INITIALIZED}), not {@code VR_RUNNING}. Keying the mono levers off
 * {@code VR_RUNNING} meant that on 1.21.9+ they were never applied during the
 * desktop render, and the camera {@code renderWorld} hit the multi-pass target
 * with no VANILLA lever — the {@code MultiPassTextureTarget} NPE that cascaded
 * into a JOML matrix-stack overflow and crashed the game.
 *
 * <p>The camera pass draws a flat mono view from the camera entity into our own
 * offscreen FBO, wrapped in {@link #beginMonoRender()}/{@link #endMonoRender()}.
 * The "view resets when I turn" bug was separate and is handled in CameraRenderer
 * (a getCameraEntity override instead of setCameraEntity, so Vivecraft's
 * onCameraEntitySet re-origin never fires).
 *
 * <h2>The crash (also fixed by the mono levers)</h2>
 * While the pass runs, Vivecraft has installed its stereo render pass
 * ({@code RenderPassManager.setWorldRenderPass(STEREO_XR)} → {@code RENDER_PASS_TYPE
 * = WORLD_ONLY}, {@code WRP = STEREO_XR}, {@code MC.framebuffer = STEREO_XR.target},
 * {@code currentPass = LEFT/RIGHT}).
 *
 * <p>The entity-outline framebuffer Vivecraft installs is a
 * {@code MultiPassTextureTarget}; its {@code getCurrent()} resolves to
 * {@code vanilla} when {@code RenderPassType.isVanilla()} and otherwise to
 * {@code vrTargets.get(currentPass)}. If {@code currentPass} is ever
 * {@code RenderPass.VANILLA} while {@code RENDER_PASS_TYPE != VANILLA}, that map
 * lookup returns {@code null} and {@code setLast(null)} NPEs on
 * {@code current.textureWidth} — every frame, crashing the game (the reported
 * crash).
 *
 * <h2>The fix</h2>
 * For the duration of the camera pass we drop Vivecraft into its vanilla path with
 * the two minimal, fully-reversible levers — and nothing else:
 * <ul>
 *   <li>{@code VRState.VR_RUNNING = false} — Vivecraft's camera/projection/pick/
 *       FOV mixins all fall through to vanilla, so {@code renderWorld} draws a flat
 *       mono view from the camera entity instead of the VR head pose; and</li>
 *   <li>{@code RenderPassManager.RENDER_PASS_TYPE = VANILLA} (set directly, NOT via
 *       {@code setVanillaRenderPass()}) — the outline {@code MultiPassTextureTarget}
 *       resolves to its safe vanilla sub-target, so it can't NPE even with glowing
 *       entities in view.</li>
 * </ul>
 *
 * <p>Crucially we do <em>not</em> call {@code setVanillaRenderPass()}: that helper
 * also overwrites {@code ClientDataHolderVR.currentPass} (→ VANILLA) and
 * {@code MC.framebuffer} (→ vanilla target). Those are exactly the fields whose
 * un-restored leftovers caused (a) the crash above — a stale {@code currentPass =
 * VANILLA} surviving into Vivecraft's eye render — and (b) a blank camera stream —
 * the clobbered framebuffer redirecting our camera render away from the offscreen
 * FBO {@code CameraRenderer} bound. Setting only {@code RENDER_PASS_TYPE} leaves
 * {@code currentPass}, {@code WRP} and the framebuffer (our offscreen FBO) intact,
 * so Vivecraft's eye render resumes exactly where it left off and the camera POV
 * lands in our FBO.
 *
 * <p>Both levers are saved and restored around the pass. All Vivecraft access is
 * reflective, so this adds no compile/load dependency and is a cheap no-op without
 * Vivecraft. Single render thread only.
 */
public final class VivecraftCompat {

    private VivecraftCompat() {}

    private static volatile boolean init = false;
    private static Field vrRunningField;        // org.vivecraft.client_vr.VRState.VR_RUNNING : boolean (static)
    private static Field vrInitializedField;    // org.vivecraft.client_vr.VRState.VR_INITIALIZED : boolean (static)
    private static Field renderPassTypeField;   // RenderPassManager.RENDER_PASS_TYPE : RenderPassType (static)
    private static Object vanillaPassValue;     // RenderPassType.VANILLA enum constant

    // Saved Vivecraft state for the currently-open mono-render scope. The render
    // thread is the only caller and the scope is synchronous, so plain statics
    // are fine.
    private static Object savedPassType;
    private static boolean savedVrRunning;
    // True while a beginMonoRender()..endMonoRender() scope is open. During that
    // scope the live VR_RUNNING flag is our own forced false, so isVrPassActive()
    // reports savedVrRunning (the value captured at pass start) instead.
    private static boolean monoActive;

    private static void ensureInit() {
        if (init) return;
        init = true;
        try {
            Class<?> vrState = Class.forName("org.vivecraft.client_vr.VRState");
            vrRunningField = vrState.getField("VR_RUNNING");
            try {
                vrInitializedField = vrState.getField("VR_INITIALIZED");
            } catch (Throwable ignored) {
                vrInitializedField = null; // fall back to VR_RUNNING for session detection
            }
            Class<?> rpm = Class.forName("org.vivecraft.client_xr.render_pass.RenderPassManager");
            renderPassTypeField = rpm.getField("RENDER_PASS_TYPE");
            Class<?> rpt = Class.forName("org.vivecraft.client_xr.render_pass.RenderPassType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object vanilla = Enum.valueOf((Class) rpt, "VANILLA");
            vanillaPassValue = vanilla;
        } catch (Throwable t) {
            vrRunningField = null;
            vrInitializedField = null;
            renderPassTypeField = null;
            vanillaPassValue = null;
        }
    }

    /**
     * True while Vivecraft is <em>actively rendering an eye pass</em>
     * ({@code VRState.VR_RUNNING}). This is a PER-PASS flag, not a session flag:
     * on the newer frame-graph pipeline (MC 1.21.9+) it is {@code false} during
     * the vanilla desktop {@code GameRenderer.render} that {@code MinecraftClient
     * .render} still invokes, even though VR is on and Vivecraft's multi-pass
     * targets are installed. Use {@link #isVrMode()} to decide "is VR on at all".
     */
    public static boolean isVrActive() {
        ensureInit();
        if (vrRunningField == null) return false;
        try {
            return vrRunningField.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when VR is <em>actively rendering</em> (headset on, {@code VR_RUNNING})
     * — but corrected for the camera pass's own mono lever, which forces
     * {@code VR_RUNNING = false} for the duration of {@link #beginMonoRender()}..
     * {@link #endMonoRender()}. While that scope is open the live flag is our own
     * false, so we report {@link #savedVrRunning} (the value captured at pass start)
     * instead; outside the scope this is just the live {@code VR_RUNNING}.
     *
     * <p>Distinct from {@link #isVrMode()} ({@code VR_INITIALIZED}), which stays true
     * when the headset is taken off or VR is hot-switch-paused. The local-player VR
     * avatar keys off THIS flag so it reverts to the vanilla model the instant VR
     * stops running — otherwise the camera keeps drawing the last frozen VR pose.
     */
    public static boolean isVrPassActive() {
        if (monoActive) return savedVrRunning;
        return isVrActive();
    }

    /**
     * True whenever VR is on for this session — {@code VR_INITIALIZED} if Vivecraft
     * exposes it, else {@code VR_RUNNING}. Unlike {@link #isVrActive()} this stays
     * true across the whole frame, including the vanilla desktop render where
     * {@code VR_RUNNING} is false but Vivecraft's {@code MultiPassTextureTarget} is
     * still the world-render target. The camera pass keys ALL of its VR handling
     * (mono levers, the getCameraEntity override, off-image fallbacks) off this, so
     * a second {@code renderWorld} can never hit Vivecraft's multi-pass target
     * without the {@code RENDER_PASS_TYPE = VANILLA} lever applied (the repeated
     * {@code MultiPassTextureTarget} NPE → matrix-stack-overflow crash on 1.21.9+).
     */
    public static boolean isVrMode() {
        ensureInit();
        try {
            if (vrInitializedField != null && vrInitializedField.getBoolean(null)) return true;
        } catch (Throwable ignored) {
        }
        if (vrRunningField == null) return false;
        try {
            return vrRunningField.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True if {@code fb} is (or extends) a Vivecraft framebuffer target. Used as a
     * belt-and-suspenders guard before reading a framebuffer that might be one of
     * Vivecraft's multi-pass targets.
     */
    public static boolean isVivecraftTarget(Framebuffer fb) {
        if (fb == null) return false;
        for (Class<?> c = fb.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("org.vivecraft.")) return true;
        }
        return false;
    }

    /**
     * Enter a flat mono render (see class doc). Activates whenever VR is on for the
     * session ({@link #isVrMode()}) — NOT only when an eye pass is running — because
     * the camera pass can run during the vanilla desktop {@code GameRenderer.render}
     * where {@code VR_RUNNING} is already false but Vivecraft's multi-pass target is
     * still installed and would NPE without the {@code RENDER_PASS_TYPE = VANILLA}
     * lever. Saves and restores the PRIOR {@code VR_RUNNING} value (which may be
     * false here) rather than assuming it was true. Returns {@code true} only if
     * state was changed — the caller MUST pair it with {@link #endMonoRender()} in a
     * finally. No-op (returns {@code false}) without Vivecraft / when VR is off.
     */
    public static boolean beginMonoRender() {
        ensureInit();
        if (vrRunningField == null || !isVrMode()) return false;
        try {
            savedVrRunning = vrRunningField.getBoolean(null);
            savedPassType = (renderPassTypeField != null) ? renderPassTypeField.get(null) : null;
            vrRunningField.setBoolean(null, false);
            if (renderPassTypeField != null && vanillaPassValue != null) {
                renderPassTypeField.set(null, vanillaPassValue);
            }
            monoActive = true;
            return true;
        } catch (Throwable t) {
            // Half-applied — try to put VR_RUNNING back to what it was and bail.
            try { vrRunningField.setBoolean(null, savedVrRunning); } catch (Throwable ignored) {}
            savedPassType = null;
            return false;
        }
    }

    // ── Local-player VR avatar in the camera stream ──────────────────────────
    // Vivecraft draws a player's VR body only when its render state carries a
    // RotInfo. It populates the LOCAL player's RotInfo (ClientVRPlayers
    // .getRotationsForPlayer → getMainPlayerRotInfo) only when VR_RUNNING AND
    // mc.getCameraEntity()==mc.player — both of which the camera pass deliberately
    // breaks (mono levers set VR_RUNNING=false; the getCameraEntity override points
    // at the CameraEntity). So it nulls our render state's RotInfo and we render as
    // a vanilla model. We re-apply the live main-player RotInfo ourselves, straight
    // from getMainPlayerRotInfo (which only needs LOCAL_PLAYER_ROT_INFO, set every
    // VR frame — no VR_RUNNING gate). All reflective; no compile dep on Vivecraft.
    private static volatile boolean vrBodyInit = false;
    private static Method getMainPlayerRotInfoM; // ClientVRPlayers.getMainPlayerRotInfo(LivingEntity,float):RotInfo
    private static Method setRotInfoM;            // EntityRenderStateExtension.vivecraft$setRotInfo(RotInfo)
    private static Method setFirstPersonPlayerM;  // EntityRenderStateExtension.vivecraft$setFirstPersonPlayer(boolean)
    private static Method getBodyYawRadM;         // RotInfo.getBodyYawRad():float (instance)

    private static void ensureVrBodyInit() {
        if (vrBodyInit) return;
        vrBodyInit = true;
        try {
            Class<?> cvp = Class.forName("org.vivecraft.client.ClientVRPlayers");
            getMainPlayerRotInfoM = cvp.getMethod("getMainPlayerRotInfo", LivingEntity.class, float.class);
            Class<?> ext = Class.forName("org.vivecraft.client.extensions.EntityRenderStateExtension");
            Class<?> rotInfo = Class.forName("org.vivecraft.client.ClientVRPlayers$RotInfo");
            setRotInfoM = ext.getMethod("vivecraft$setRotInfo", rotInfo);
            try {
                setFirstPersonPlayerM = ext.getMethod("vivecraft$setFirstPersonPlayer", boolean.class);
            } catch (Throwable ignored) {
                setFirstPersonPlayerM = null;
            }
            try {
                getBodyYawRadM = rotInfo.getMethod("getBodyYawRad");
            } catch (Throwable ignored) {
                getBodyYawRadM = null;
            }
        } catch (Throwable t) {
            getMainPlayerRotInfoM = null;
            setRotInfoM = null;
            setFirstPersonPlayerM = null;
            getBodyYawRadM = null;
        }
    }

    /**
     * Give {@code renderState} (the local player's entity render state) Vivecraft's
     * live main-player {@code RotInfo} so its VR avatar (HMD head + controller arms)
     * is drawn in the camera stream instead of the vanilla model. Called from the
     * player renderer's {@code updateRenderState} RETURN — after Vivecraft's own
     * hook has nulled it — for {@code mc.player} during the camera pass. Fully
     * guarded: any failure (no Vivecraft, no local pose yet) leaves the state
     * unchanged, so the player just falls back to the vanilla model.
     */
    public static void applyLocalPlayerVrBody(Object renderState, LivingEntity player, float tickDelta) {
        ensureVrBodyInit();
        if (getMainPlayerRotInfoM == null || setRotInfoM == null) return;
        try {
            Object rot = getMainPlayerRotInfoM.invoke(null, player, tickDelta);
            if (rot == null) return;
            setRotInfoM.invoke(renderState, rot);

            // (1) Route the avatar through Vivecraft's THIRD-PERSON path (the one it
            // uses for other VR players / your F5 body), which reads the RotInfo we
            // just set. Its first-person path uses live VRData that's inert+snapping
            // during our off-axis camera pass. animateVRModel branches on this flag.
            if (setFirstPersonPlayerM != null) {
                setFirstPersonPlayerM.invoke(renderState, false);
            }

            // (2) Make the parts world-accurate. animateVRModel feeds getBodyYawRad
            // into ModelUtils.worldToModel as the frame it rotates the world-space
            // head/hand data INTO. The vanilla setupTransforms then rotates the whole
            // pose by state.bodyYaw. Those only cancel — leaving the parts at their
            // true world positions — when state.bodyYaw matches that same body yaw.
            // Feeding a mismatched value (getFacingYaw / a magic offset) is exactly
            // why the avatar was position-dependently wrong. Set them equal.
            if (getBodyYawRadM != null
                    && renderState instanceof net.minecraft.client.render.entity.state.LivingEntityRenderState s) {
                float rad = (float) getBodyYawRadM.invoke(rot);
                s.bodyYaw = (float) Math.toDegrees(rad);
                s.relativeHeadYaw = 0.0f;
            }
        } catch (Throwable ignored) {
        }
    }

    /** Restore the VR state saved by {@link #beginMonoRender()}. */
    public static void endMonoRender() {
        try {
            if (renderPassTypeField != null && savedPassType != null) {
                renderPassTypeField.set(null, savedPassType);
            }
            if (vrRunningField != null) vrRunningField.setBoolean(null, savedVrRunning);
        } catch (Throwable ignored) {
        } finally {
            savedPassType = null;
            monoActive = false;
        }
    }
}
