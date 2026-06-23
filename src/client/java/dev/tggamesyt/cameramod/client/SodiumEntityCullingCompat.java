package dev.tggamesyt.cameramod.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional, reflection-only compatibility shim for <b>Sodium</b>'s entity
 * culling (separate from tr7zw's EntityCulling mod — see {@link
 * EntityCullingCompat}). No hard dependency: every method is a no-op when
 * Sodium is absent.
 *
 * <h2>The problem</h2>
 * Sodium culls an entity whose bounding box doesn't intersect any <i>visible
 * chunk section</i> ({@code SodiumWorldRenderer.isEntityVisible}, wrapped into
 * {@code EntityRenderer.shouldRender}). The set of visible sections is rebuilt
 * <b>asynchronously</b> on worker threads. Our camera pass renders from a
 * different viewpoint than the player, so on the frames where Sodium's
 * camera-viewpoint visibility set hasn't finished rebuilding yet, entities the
 * camera can see are culled against a stale (player) section set — and as the
 * set catches up they pop back in, so entities flicker/jitter in the stream.
 *
 * <h2>The fix</h2>
 * Sodium gates the whole feature on {@code
 * SodiumClientMod.options().performance.useEntityCulling}. It copies that value
 * into a per-instance cached field once per {@code setupTerrain}; {@code
 * isEntityVisible} returns {@code true} unconditionally when it is {@code
 * false}. So we set the config flag to {@code false} <b>before</b> the camera
 * pass (its {@code setupTerrain}, which runs inside the camera {@code
 * renderWorld}, then caches {@code false} and skips section culling — the camera
 * renders every frustum-visible entity, no async-visibility flicker) and restore
 * it <b>after</b> the camera pass, so the player pass's {@code setupTerrain}
 * caches the real value again and the player keeps Sodium's culling/FPS benefit.
 *
 * <p>The user's config object is mutated only for the duration of the camera
 * render and always restored (in a finally), so the persisted option is never
 * actually changed.
 */
public final class SodiumEntityCullingCompat {

    private SodiumEntityCullingCompat() {}

    private static boolean initialized = false;
    private static boolean available   = false;

    private static Method optionsMethod;            // SodiumClientMod.options()
    private static Field  performanceField;          // SodiumOptions.performance
    private static Field  useEntityCullingField;     // PerformanceSettings.useEntityCulling

    private static boolean suspended = false;
    private static boolean savedValue = true;

    private static void ensureInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> mod = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod");
            optionsMethod = mod.getMethod("options");

            Class<?> opts = Class.forName("net.caffeinemc.mods.sodium.client.gui.SodiumOptions");
            performanceField = opts.getField("performance");

            // Nested PerformanceSettings.useEntityCulling (public, non-final).
            Class<?> perf = performanceField.getType();
            useEntityCullingField = perf.getField("useEntityCulling");

            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    /** True only when a Sodium build exposing the entity-culling option is loaded. */
    public static boolean isActive() {
        ensureInit();
        return available;
    }

    private static Object performanceSettings() {
        try {
            Object options = optionsMethod.invoke(null); // throws before config is ready
            if (options == null) return null;
            return performanceField.get(options);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Disable Sodium's entity culling for the upcoming camera-pass {@code
     * renderWorld}. Must be called before that pass so the pass's {@code
     * setupTerrain} caches the disabled value. Idempotent.
     */
    public static void beginCameraWork() {
        if (!isActive() || suspended) return;
        Object perf = performanceSettings();
        if (perf == null) return;
        try {
            savedValue = useEntityCullingField.getBoolean(perf);
            if (!savedValue) return; // already off — nothing to do
            useEntityCullingField.setBoolean(perf, false);
            suspended = true;
        } catch (Throwable ignored) {
            suspended = false;
        }
    }

    /**
     * Restore Sodium's entity-culling option so the player pass culls normally.
     * No-op if not currently suspended.
     */
    public static void endCameraWork() {
        if (!suspended) return;
        suspended = false;
        if (!isActive()) return;
        Object perf = performanceSettings();
        if (perf == null) return;
        try {
            useEntityCullingField.setBoolean(perf, savedValue);
        } catch (Throwable ignored) {}
    }
}
