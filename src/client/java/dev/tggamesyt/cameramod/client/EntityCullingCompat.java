package dev.tggamesyt.cameramod.client;

import java.lang.reflect.Field;

/**
 * Optional, reflection-only compatibility shim for tr7zw's <b>EntityCulling</b>
 * mod. No hard dependency: if the mod is absent every method is a no-op.
 *
 * <h2>The problem</h2>
 * EntityCulling decides whether an entity (or block entity) is visible with a
 * background thread that raytraces occlusion from a <b>single</b> camera
 * position — the player's render camera. It stores the result as a per-entity
 * {@code culled} flag (via its {@code Cullable} interface) and, at render time,
 * skips any entity whose flag is set.
 *
 * <p>Our second (camera) render pass draws the world from a different viewpoint
 * but reads that <i>same</i> player-derived culled flag. So an entity the camera
 * can see but the player can't is skipped in the stream — and because the cull
 * flag is recomputed asynchronously, it toggles between frames, making such
 * entities flicker/jitter in the camera image. Turning EntityCulling off makes
 * the jitter vanish, confirming it as the source.
 *
 * <h2>The fix</h2>
 * EntityCulling exposes a single global kill-switch: the static field {@code
 * EntityCullingVersionlessBase.enabled}. When it is {@code false}, {@code
 * Cullable.isCulled()} short-circuits to {@code false} for every entity, so the
 * render path culls nothing. We flip it off for the duration of the camera (and
 * preview) render and restore it immediately afterwards, so:
 * <ul>
 *   <li>The camera pass renders every entity inside its own view frustum
 *       (vanilla frustum culling still applies — only the occlusion culling is
 *       suspended), exactly as if EntityCulling were disabled. No async-cull
 *       flicker in the stream.
 *   <li>The following player pass runs with EntityCulling fully enabled again,
 *       so the player keeps its normal occlusion-culling FPS benefit.
 * </ul>
 *
 * <p>While disabled, EntityCulling's per-frame debug counters ({@code
 * renderedEntities}, {@code renderedBlockEntities}, …) would otherwise be
 * incremented by our camera pass too, doubling the values the F3 "[Culling]"
 * lines show on frames that run a camera pass. We snapshot those counters when
 * suspending and write them back when restoring, so F3 reflects only the
 * player's pass.
 *
 * <p>The brief {@code enabled = false} window is observed by EntityCulling's
 * background cull thread as well; at worst it skips a single cull iteration and
 * resumes on its next loop — harmless.
 */
public final class EntityCullingCompat {

    private EntityCullingCompat() {}

    private static boolean initialized = false;
    private static boolean available   = false;

    private static Field enabledField;   // static boolean EntityCullingVersionlessBase.enabled
    private static Field instanceField;   // static EntityCullingModBase.instance
    // Per-frame debug counters on the mod instance (all public int). Any that
    // are missing in the loaded version are simply left out — never fatal.
    private static final String[] COUNTER_NAMES = {
            "renderedBlockEntities", "skippedBlockEntities",
            "renderedEntities", "skippedEntities",
            "tickedEntities", "skippedEntityTicks"
    };
    private static Field[] counterFields;

    // Suspend state.
    private static boolean suspended = false;
    private static boolean savedEnabled = true;
    private static int[]   savedCounters;

    private static void ensureInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> base = Class.forName(
                    "dev.tr7zw.entityculling.versionless.EntityCullingVersionlessBase");
            enabledField = base.getField("enabled");      // public static boolean

            Class<?> mod = Class.forName("dev.tr7zw.entityculling.EntityCullingModBase");
            instanceField = mod.getField("instance");     // public static

            counterFields = new Field[COUNTER_NAMES.length];
            for (int i = 0; i < COUNTER_NAMES.length; i++) {
                try {
                    counterFields[i] = base.getField(COUNTER_NAMES[i]); // public int (inherited)
                } catch (NoSuchFieldException e) {
                    counterFields[i] = null; // tolerate version differences
                }
            }
            savedCounters = new int[COUNTER_NAMES.length];
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    /** True only when EntityCulling is actually loaded. */
    public static boolean isActive() {
        ensureInit();
        return available;
    }

    private static Object modInstance() {
        try {
            return instanceField.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Suspend EntityCulling for the camera/preview render. Disables its global
     * occlusion culling so the camera renders all frustum-visible entities, and
     * snapshots its debug counters. Idempotent: a second call without an
     * intervening {@link #endCameraWork()} does nothing.
     */
    public static void beginCameraWork() {
        if (!isActive() || suspended) return;
        try {
            savedEnabled = enabledField.getBoolean(null);
            // Nothing to suspend if the user already has it off.
            if (!savedEnabled) return;

            Object inst = modInstance();
            if (inst != null && counterFields != null) {
                for (int i = 0; i < counterFields.length; i++) {
                    savedCounters[i] = counterFields[i] != null
                            ? counterFields[i].getInt(inst) : 0;
                }
            }
            enabledField.setBoolean(null, false);
            suspended = true;
        } catch (Throwable ignored) {
            suspended = false;
        }
    }

    /**
     * Re-enable EntityCulling and restore its debug counters to their pre-camera
     * values, so the upcoming player pass culls normally and F3 reports only the
     * player's render. No-op if not currently suspended.
     */
    public static void endCameraWork() {
        if (!suspended) return;
        suspended = false;
        if (!isActive()) return;
        try {
            enabledField.setBoolean(null, savedEnabled);
            Object inst = modInstance();
            if (inst != null && counterFields != null) {
                for (int i = 0; i < counterFields.length; i++) {
                    if (counterFields[i] != null) {
                        counterFields[i].setInt(inst, savedCounters[i]);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }
}
