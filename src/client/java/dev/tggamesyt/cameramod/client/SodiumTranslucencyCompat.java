package dev.tggamesyt.cameramod.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional, reflection-only compatibility shim for Sodium-family rendering
 * engines (Sodium and forks such as Embeddium that keep the same class names).
 *
 * <h2>The problem</h2>
 * Sodium does its own translucency (water/glass) sorting. There is exactly
 * <b>one</b> sorted translucent index buffer per chunk section, shared by every
 * render pass. Our second (camera) render pass and the player pass both draw
 * that same buffer. Sodium re-sorts a section only when the camera crosses a
 * face-normal plane ("GFNI"), comparing {@code camera.getPos()} to a private
 * {@code lastCameraPos} field inside {@code SodiumWorldRenderer.setupTerrain}.
 *
 * <p>Crucially, that re-sort is <b>asynchronous</b> by default: {@code
 * processGFNIMovement} only marks sections dirty; the actual reorder runs on a
 * worker thread and is uploaded a frame or two later. So whichever viewpoint's
 * sort <i>completed most recently</i> owns the shared buffer. When the camera
 * and player look at the same water from different positions, the stationary
 * viewpoint renders the other one's sort order — "good for the player means bad
 * for the camera, and vice versa." No amount of swapping the gate fields fixes
 * this, because the <i>data</i> is shared, not just the trigger.
 *
 * <h2>The fix</h2>
 * Sodium has a sort-deferral setting, {@code SortBehavior}, whose {@code
 * *_ZERO_FRAMES} variants route sort tasks into the {@code ZERO_FRAME_DEFER}
 * queue — and that queue's collector is <b>awaited every frame</b> inside
 * {@code RenderSectionManager.updateChunks} (it completes <i>and uploads this
 * frame, before {@code drawChunkLayer}</i>). So while we render the camera, we
 * temporarily flip {@code RenderSectionManager.sortBehavior} to the matching
 * {@code ZERO_FRAMES} variant. With deferral off:
 * <ul>
 *   <li>The camera pass's own {@code setupTerrain} sees {@code lastCameraPos =
 *       playerPos}, so it re-sorts every section that differs between the two
 *       viewpoints <b>for the camera</b> — synchronously, before the camera
 *       draws. The captured camera image is correct.
 *   <li>The following player pass's {@code setupTerrain} sees {@code
 *       lastCameraPos = cameraPos}, so it re-sorts those sections back <b>for
 *       the player</b> — synchronously, before the player draws. The on-screen
 *       player image is correct.
 * </ul>
 * Because each pass re-sorts and uploads synchronously for its own viewpoint,
 * both views are correct every frame and the async ping-pong flicker is gone.
 * We deliberately do <b>not</b> touch {@code lastCameraPos} anymore — the
 * natural player&harr;camera ping-pong is exactly what drives the two
 * per-viewpoint re-sorts; suppressing it (the previous approach) is what left a
 * stationary viewpoint showing the other's stale sort.
 *
 * <p>The cost is the accepted trade-off: the differing translucent sections are
 * sorted twice per frame (once per viewpoint) instead of asynchronously once.
 *
 * <p>Everything here is reflection-based and self-disabling: if the Sodium
 * classes/fields are absent (vanilla, or a non-Sodium engine), every method is
 * a no-op. No hard dependency on Sodium is declared anywhere, and we only ever
 * override the behavior when it is already one of Sodium's {@code DYNAMIC_*}
 * sort modes (so we never change a user who has sorting OFF or STATIC).
 */
public final class SodiumTranslucencyCompat {

    private SodiumTranslucencyCompat() {}

    // Resolved once on first use. AVAILABLE stays false if anything is missing.
    private static boolean initialized = false;
    private static boolean available   = false;

    private static Method instanceNullable;         // static SodiumWorldRenderer instanceNullable()
    private static Field  renderSectionManagerField; // SodiumWorldRenderer.renderSectionManager
    private static Field  sortBehaviorField;         // RenderSectionManager.sortBehavior (final)
    private static Object syncSortBehavior;          // SortBehavior.DYNAMIC_DEFER_NEARBY_ZERO_FRAMES

    // Our override state. originalSortBehavior holds whatever value was present
    // just before we first overrode it, so restore() can put it back exactly.
    private static boolean overridden = false;
    private static Object  originalSortBehavior = null;

    private static void ensureInit() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> swr = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
            instanceNullable = swr.getMethod("instanceNullable");

            renderSectionManagerField = swr.getDeclaredField("renderSectionManager");
            renderSectionManagerField.setAccessible(true);

            Class<?> rsm = Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager");
            sortBehaviorField = rsm.getDeclaredField("sortBehavior");
            sortBehaviorField.setAccessible(true);

            @SuppressWarnings({"unchecked", "rawtypes"})
            Class<? extends Enum> sortBehaviorClass = (Class<? extends Enum>) Class.forName(
                    "net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior");
            // NEARBY + ZERO_FRAMES: sections NEAR the viewpoint re-sort synchronously
            // (awaited-this-frame, uploaded before the pass draws), while distant
            // sections defer asynchronously as Sodium normally does. NEARBY (not ALL)
            // is deliberate: the natural player<->camera position ping-pong re-triggers
            // a sort every frame even when nothing moves, so ALL would synchronously
            // re-sort every triggered section (including far ones a distant camera
            // crosses) every frame — a large per-frame allocation spike that churns
            // the heap and causes GC lag spikes. NEARBY bounds the synchronous work to
            // the water actually in front of each viewpoint, which is what was visibly
            // mis-sorted, at a fraction of the allocation cost.
            syncSortBehavior = Enum.valueOf(sortBehaviorClass, "DYNAMIC_DEFER_NEARBY_ZERO_FRAMES");

            available = (syncSortBehavior != null);
        } catch (Throwable ignored) {
            available = false;
        }
    }

    /** True only when a compatible Sodium-family renderer is actually loaded. */
    public static boolean isActive() {
        ensureInit();
        return available;
    }

    private static Object currentRenderSectionManager() {
        try {
            Object renderer = instanceNullable.invoke(null);
            if (renderer == null) return null;
            return renderSectionManagerField.get(renderer);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Call immediately before the camera pass's {@code renderWorld}. Flips
     * Sodium's translucency sort to the synchronous (zero-frame-defer) variant
     * so the per-viewpoint re-sorts driven by the natural player&harr;camera
     * position ping-pong complete this frame, before each pass draws.
     *
     * <p>Only ever overrides a {@code DYNAMIC_*} sort mode (leaves OFF/STATIC
     * untouched, since those have no per-frame dynamic re-sort to make
     * synchronous). The override persists through the subsequent player pass and
     * is undone by {@link #restore()} on the next frame that does no camera pass.
     */
    public static void beforeCameraPass() {
        if (!isActive()) return;
        Object rsm = currentRenderSectionManager();
        if (rsm == null) return;
        try {
            Object current = sortBehaviorField.get(rsm);
            if (current == syncSortBehavior) return; // already synchronous
            // Only override Sodium's dynamic sort modes — never OFF/STATIC.
            if (current == null || !current.toString().startsWith("DYNAMIC")) return;
            originalSortBehavior = current;
            overridden = true;
            sortBehaviorField.set(rsm, syncSortBehavior);
        } catch (Throwable ignored) {
            // If a single frame fails, just skip the override for it.
        }
    }

    /**
     * Call immediately after the camera pass's {@code renderWorld}. No-op: the
     * synchronous sort override must remain in effect through the player pass
     * (which runs after our code returns); it is undone by {@link #restore()} at
     * the start of the next frame.
     */
    public static void afterCameraPass() {
        // Intentionally empty — see method doc.
    }

    /**
     * Restore Sodium's original sort behavior. Call once per frame before
     * deciding whether to do a camera pass: on frames that skip the camera pass
     * this returns normal async sorting (no needless synchronous-sort cost), and
     * on frames that do a camera pass {@link #beforeCameraPass()} re-applies the
     * override right before it. No-op if we never overrode anything.
     */
    public static void restore() {
        if (!overridden) return;
        if (!isActive()) { overridden = false; return; }
        Object rsm = currentRenderSectionManager();
        if (rsm != null) {
            try {
                sortBehaviorField.set(rsm, originalSortBehavior);
            } catch (Throwable ignored) {}
        }
        overridden = false;
        originalSortBehavior = null;
    }
}
