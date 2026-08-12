package dev.tggamesyt.cameramod.client.gui;

import dev.tggamesyt.cameramod.CameraEntity;
import dev.tggamesyt.cameramod.CameraServerThing;
import dev.tggamesyt.cameramod.client.CameraRenderer;
import dev.tggamesyt.cameramod.client.CameramodClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class EditCameraScreen extends Screen {

    private final CameraGuiScreen parent;
    private final UUID cameraUuid;
    private CameramodClient.TrackedCamera tracked;

    // Layout
    private static final int CONTENT_TOP        = 28;
    private static final int BOTTOM_H           = 30;
    private static final int ROW_H              = 20;
    private static final int ROW_STEP           = 24;
    private static final int SEC_STEP           = 22;   // a hair taller so the separator line sits clear of the text
    private static final int PREVIEW_LABEL_H    = 12;
    private static final int PREVIEW_IMG_H      = 90;
    private static final int PREVIEW_BAND_PAD   = 8;
    // Right-side preview panel: 160px image + 8px padding on each side.
    private static final int PREVIEW_PANEL_W    = 176;

    // Scroll (survives clearAndInit)
    private int scrollY   = 0;
    private int totalLogH = 0;
    // Scrollbar drag state. grabOffset is the distance from the cursor to the
    // top of the thumb at grab time, so the thumb stays under the cursor as
    // the user drags instead of snapping its top to the cursor.
    private boolean scrollbarDragging = false;
    private double  scrollbarGrabOffset = 0;

    // Preview textures (one per view). Recreated on first need; uploaded
    // when CameraRenderer's preview frame version advances.
    private net.minecraft.client.texture.NativeImageBackedTexture previewTexView;
    private net.minecraft.client.texture.NativeImageBackedTexture previewTexFront;
    private net.minecraft.client.texture.NativeImageBackedTexture previewTexBack;
    private net.minecraft.util.Identifier previewIdView;
    private net.minecraft.util.Identifier previewIdFront;
    private net.minecraft.util.Identifier previewIdBack;
    private int previewTexVersion = -1;

    // Widget references
    private TextFieldWidget nameField;
    private TextFieldWidget posXField, posYField, posZField;
    private TextFieldWidget yawField, pitchField;
    private TextFieldWidget fixedTargetField;
    private TextFieldWidget attachTargetField;
    private TextFieldWidget offXField, offYField, offZField;
    private TextFieldWidget zoomField;

    // Editable state (survives clearAndInit)
    private boolean stateLoaded  = false;
    private String  nameVal      = "";
    private String  posXVal      = "", posYVal = "", posZVal = "";
    private String  yawVal       = "", pitchVal = "";
    private String  fixedTargetVal = "";
    private int     fixerUIMode  = 0;   // 0=Off 1=LookAt 2=LookSameWay
    private String  attachTargetVal = "";
    private int     attachUIMode = 0;   // 0=Off 1=World 2=Head
    private String  offXVal = "0.000", offYVal = "0.000", offZVal = "0.000";
    private boolean gravityVal = true;
    private String  zoomVal = "1.00";

    // Originals captured on first open — used to restore state on Cancel.
    private boolean originalsCaptured = false;
    private double  origX, origY, origZ;
    private float   origYaw, origPitch;
    private UUID    origFixedTarget;
    private byte    origFixerMode;
    private UUID    origAttachTarget;
    private double  origOffX, origOffY, origOffZ;
    private byte    origAttachMode;
    private boolean origGravity;
    private float   origZoom;
    private String  origName;
    private Boolean origPerCamFlipped, origPerCamSeesChat, origPerCamNameTags,
                    origPerCamShowPlayerGuis;

    // Prevents the text-field setText() during init from re-triggering applyLive.
    private boolean suspendChangeListener = false;

    // ─── Rotate mode (in-screen mouse look) ──────────────────────────────────
    // Click the Rotate button to enter; mouse movement adjusts the camera's
    // yaw/pitch in real time. Any click exits. We lock the cursor while active
    // so GLFW reports continuous deltas instead of clipping at the window edge.
    private boolean inRotateMode  = false;
    private double  rotateLastX   = Double.NaN;
    private double  rotateLastY   = Double.NaN;
    // Authoritative yaw/pitch while rotate mode is active. Cursor deltas
    // accumulate here instead of reading them back off the camera entity —
    // a server-tracked camera's yaw is interpolated toward stale broadcast
    // values, so reading it mid-drag would silently lose the user's input.
    private double  rotateYaw     = 0;
    private double  rotatePitch   = 0;
    // Degrees-per-scaled-pixel; mirrors vanilla mouse-look feel without
    // dragging in the option-derived sensitivity multiplier.
    private static final float ROTATE_SENSITIVITY = 0.6f;

    // ─── Hold-to-drag on numeric fields ──────────────────────────────────────
    // Clicking and dragging horizontally on any numeric field adjusts its value.
    // A short click (movement < DRAG_THRESHOLD_PX) still opens normal text editing.
    private TextFieldWidget dragField    = null;
    private double          dragStartX   = 0;
    private double          dragStartY   = 0;
    private boolean         inDragMode   = false;
    private double          dragAccumX   = 0;
    // Last raw GLFW cursor X seen while dragging. GLFW_CURSOR_DISABLED reports
    // unbounded relative motion, so the delta between consecutive events drives
    // the value change — the same reliable mechanism rotate mode uses.
    private double          dragLastRawX = Double.NaN;
    private static final int DRAG_THRESHOLD_PX = 5;

    // Bottom-row button references for re-rendering over the bottom bar overdraw.
    private ButtonWidget doneBtn, cancelBtn, removeBtn;
    // Top-left "back to camera list" button.
    private ButtonWidget backBtn;
    // Active toggle button — its label is refreshed live each frame from the real
    // bound-camera state so it can't desync (server-mode toggles round-trip, so
    // the state isn't updated by the time toggleActive()'s clearAndInit() runs).
    private ButtonWidget activeBtn;

    // Previously-focused element; when focus leaves a text field we commit its
    // value (so clicking away from an input saves it).
    private net.minecraft.client.gui.Element lastFocusedElement;

    public EditCameraScreen(CameraGuiScreen parent, UUID cameraUuid) {
        super(Text.literal("Edit Camera"));
        this.parent     = parent;
        this.cameraUuid = cameraUuid;
    }

    // ─── Camera lookup ───────────────────────────────────────────────────────

    private CameraEntity findCamera() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e instanceof CameraEntity cam && cam.getUuid().equals(cameraUuid)) return cam;
            }
        }
        return CameramodClient.CLIENT_CAMERAS.get(cameraUuid);
    }

    private void syncTextFields() {
        if (nameField        != null) nameVal        = nameField.getText();
        if (posXField        != null) posXVal        = posXField.getText();
        if (posYField        != null) posYVal        = posYField.getText();
        if (posZField        != null) posZVal        = posZField.getText();
        if (yawField         != null) yawVal         = yawField.getText();
        if (pitchField       != null) pitchVal       = pitchField.getText();
        if (fixedTargetField != null) fixedTargetVal = fixedTargetField.getText();
        if (attachTargetField!= null) attachTargetVal= attachTargetField.getText();
        if (offXField        != null) offXVal        = offXField.getText();
        if (offYField        != null) offYVal        = offYField.getText();
        if (offZField        != null) offZVal        = offZField.getText();
        if (zoomField        != null) zoomVal        = zoomField.getText();
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        tracked = CameramodClient.TRACKED_CAMERAS.get(cameraUuid);
        if (tracked == null) { this.client.setScreen(parent); return; }

        // Tell CameraRenderer to start producing live preview frames for the
        // camera being edited. Idempotent — re-setting on each init() (called
        // by clearAndInit on scroll/toggles) is fine.
        CameraRenderer.setPreviewCameraUuid(cameraUuid);

        if (!stateLoaded) {
            CameraEntity cam = findCamera();
            nameVal = tracked.name == null ? "" : tracked.name;
            if (cam != null) {
                posXVal  = fmt3(cam.getX());
                posYVal  = fmt3(cam.getY());
                posZVal  = fmt3(cam.getZ());
                yawVal   = fmt2(cam.getYaw());
                pitchVal = fmt2(cam.getPitch());

                UUID ft = cam.getFixedTargetUuid();
                if (ft == null) { fixerUIMode = 0; fixedTargetVal = ""; }
                else { fixedTargetVal = ft.toString(); fixerUIMode = cam.getFixerMode() == 0 ? 1 : 2; }

                UUID at = cam.getAttachTargetUuid();
                if (at == null) { attachUIMode = 0; attachTargetVal = ""; }
                else { attachTargetVal = at.toString(); attachUIMode = cam.getAttachMode() == 0 ? 1 : 2; }

                net.minecraft.util.math.Vec3d off = cam.getAttachOffset();
                offXVal = fmt3(off.x); offYVal = fmt3(off.y); offZVal = fmt3(off.z);
                gravityVal = cam.isGravityEnabled();
                zoomVal    = fmt2(cam.getZoomLevel());
            }
            captureOriginals();
            stateLoaded = true;
        }

        // Abandon any in-progress drag (e.g. clearAndInit triggered by scroll).
        if (inDragMode || dragField != null) {
            inDragMode   = false;
            dragField    = null;
            dragAccumX   = 0;
            dragLastRawX = Double.NaN;
            if (!inRotateMode) {
                GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(),
                        GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            }
        }
        // Widgets are about to be recreated — drop the stale focus reference so
        // the focus-loss commit in render() doesn't fire on a removed widget.
        lastFocusedElement = null;

        suspendChangeListener = true;

        int rightPanelX = this.width - PREVIEW_PANEL_W;
        int leftAreaW   = rightPanelX - 8;   // 8px gap before right panel
        int cW = Math.min(leftAreaW - 20, 480);
        int cX = Math.max(10, (leftAreaW - cW) / 2);
        int ly = 0;

        // ── Section: Name + Active ───────────────────────────────────────────
        // SEC_STEP padding above the field so the section header drawn in
        // drawContentLabels() doesn't clip against the top of the scroll area.
        ly += SEC_STEP;
        int nameW = cW - 86;
        nameField = mkField(cX, ly, nameW, nameVal, t -> { nameVal = t; applyLive(); });
        boolean active = cameraUuid.equals(CameraRenderer.getBoundCameraUuid());
        activeBtn = mkBtn(cX + nameW + 4, ly, 80, "Active: " + (active ? "ON" : "OFF"),
                () -> { syncTextFields(); toggleActive(); });
        ly += ROW_STEP;

        // ── Section: Position ─────────────────────────────────────────────────
        ly += SEC_STEP;
        int moveBtnW = 50;
        int posInnerW = cW - moveBtnW - 4;
        int f3 = (posInnerW - 2 * 4 - 3 * 20) / 3;
        posXField = mkField(cX + 20,                       ly, f3, posXVal,  t -> { posXVal = t; applyLive(); });
        posYField = mkField(cX + 20 + f3 + 4 + 20,        ly, f3, posYVal,  t -> { posYVal = t; applyLive(); });
        posZField = mkField(cX + 20 * 2 + f3 * 2 + 4 * 2 + 20, ly, f3, posZVal,  t -> { posZVal = t; applyLive(); });
        mkBtn(cX + posInnerW + 4, ly, moveBtnW, "Move", () -> {
            syncTextFields();
            applyLive();
            CameramodClient.enterEditMoveMode(cameraUuid);
        });
        ly += ROW_STEP;

        // ── Section: Rotation ─────────────────────────────────────────────────
        ly += SEC_STEP;
        int rotBtnW = 60;
        int rotInnerW = cW - rotBtnW - 4;
        int f2 = (rotInnerW - 4 - 2 * 32) / 2;
        yawField   = mkField(cX + 32,              ly, f2, yawVal,   t -> { yawVal   = t; applyLive(); });
        pitchField = mkField(cX + 32 + f2 + 4 + 32, ly, f2, pitchVal, t -> { pitchVal = t; applyLive(); });
        mkBtn(cX + rotInnerW + 4, ly, rotBtnW, inRotateMode ? "Stop" : "Rotate", () -> {
            syncTextFields();
            if (inRotateMode) exitRotateMode();
            else              enterRotateMode();
        });
        ly += ROW_STEP;

        // ── Section: Look Target ─────────────────────────────────────────────
        ly += SEC_STEP;
        int mbW = (cW - 2 * 4) / 3;
        mkModeBtn(cX,             ly, mbW, "Off",          fixerUIMode == 0,
                () -> { syncTextFields(); fixerUIMode = 0; applyLive(); clearAndInit(); });
        mkModeBtn(cX + mbW + 4,   ly, mbW, "Look At",     fixerUIMode == 1,
                () -> { syncTextFields(); fixerUIMode = 1; applyLive(); clearAndInit(); });
        mkModeBtn(cX + (mbW + 4) * 2, ly, mbW, "Look Same Way", fixerUIMode == 2,
                () -> { syncTextFields(); fixerUIMode = 2; applyLive(); clearAndInit(); });
        ly += ROW_STEP;

        int ftSelfW   = 50;
        int ftPickW   = 50;
        int ftW = cW - ftSelfW - ftPickW - 8;
        fixedTargetField = mkField(cX, ly, ftW, fixedTargetVal, t -> { fixedTargetVal = t; applyLive(); });
        fixedTargetField.setEditable(fixerUIMode != 0);
        fixedTargetField.setPlaceholder(Text.literal("Target UUID or name"));
        MinecraftClient mc = MinecraftClient.getInstance();
        mkBtn(cX + ftW + 4, ly, ftSelfW, "Self", () -> {
            if (mc.player != null && fixerUIMode != 0) {
                syncTextFields();
                fixedTargetVal = mc.player.getUuid().toString();
                applyLive();
                clearAndInit();
            }
        });
        mkBtn(cX + ftW + 4 + ftSelfW + 4, ly, ftPickW, "Pick", () -> {
            if (fixerUIMode == 0) return;
            syncTextFields();
            applyLive();
            CameramodClient.enterEditSelectMode(cameraUuid, 1);
        });
        ly += ROW_STEP;

        // ── Section: Parent (Attachment) ─────────────────────────────────────
        ly += SEC_STEP;
        int atSelfW   = 46;
        int atPickW   = 46;
        int atDetachW = 56;
        int atW = cW - atSelfW - atPickW - atDetachW - 12;
        attachTargetField = mkField(cX, ly, atW, attachTargetVal, t -> { attachTargetVal = t; applyLive(); });
        attachTargetField.setPlaceholder(Text.literal("Target UUID or name"));
        mkBtn(cX + atW + 4, ly, atSelfW, "Self", () -> {
            if (mc.player != null) {
                syncTextFields();
                attachTargetVal = mc.player.getUuid().toString();
                if (attachUIMode == 0) {
                    // Re-attaching after a detach. If the camera was previously
                    // in Head (Orbit) mode, restore that mode directly — the
                    // stored local-space orbit offset is already the correct
                    // relative position (camera will resume near the player at
                    // the same radius). Recomputing would use the camera's
                    // current world position (old orbit spot, possibly far away
                    // after the player moved) and produce a large offset that
                    // keeps the camera at the old far location.
                    if (origAttachTarget == null && origAttachMode == 1) {
                        attachUIMode = 2;
                        // offXVal/Y/Z are already loaded from cam.getAttachOffset()
                        // in init() and hold the correct local-space orbit values.
                    } else {
                        attachUIMode = 1;
                        recomputeOffsetToKeepInPlace(1);
                    }
                } else {
                    recomputeOffsetToKeepInPlace(attachUIMode);
                }
                applyLive();
                clearAndInit();
            }
        });
        mkBtn(cX + atW + 4 + atSelfW + 4, ly, atPickW, "Pick", () -> {
            syncTextFields();
            applyLive();
            CameramodClient.enterEditSelectMode(cameraUuid, 2);
        });
        mkBtn(cX + atW + 4 + atSelfW + 4 + atPickW + 4, ly, atDetachW, "Detach", () -> {
            syncTextFields(); attachTargetVal = ""; attachUIMode = 0; applyLive(); clearAndInit();
        });
        ly += ROW_STEP;

        int amW = (cW - 4) / 2;
        mkModeBtn(cX,         ly, amW, "World (Fixed)", attachUIMode == 1, () -> {
            syncTextFields();
            if (!attachTargetVal.isBlank()) {
                attachUIMode = 1;
                recomputeOffsetToKeepInPlace(1);
                applyLive(); clearAndInit();
            }
        });
        mkModeBtn(cX + amW + 4, ly, amW, "Head (Orbit)", attachUIMode == 2, () -> {
            syncTextFields();
            if (!attachTargetVal.isBlank()) {
                attachUIMode = 2;
                recomputeOffsetToKeepInPlace(2);
                applyLive(); clearAndInit();
            }
        });
        ly += ROW_STEP;

        int f3o = f3;
        offXField = mkField(cX + 20,                        ly, f3o, offXVal, t -> { offXVal = t; applyLive(); });
        offYField = mkField(cX + 20 + f3o + 4 + 20,         ly, f3o, offYVal, t -> { offYVal = t; applyLive(); });
        offZField = mkField(cX + 20 * 2 + f3o * 2 + 4 * 2 + 20, ly, f3o, offZVal, t -> { offZVal = t; applyLive(); });
        ly += ROW_STEP;

        // ── Section: Overrides ────────────────────────────────────────────────
        ly += SEC_STEP;
        ly = addPerCamRow(cX, cW, ly, "Mirror image",
                () -> tracked.perCamFlipped,        v -> tracked.perCamFlipped        = v);
        ly = addPerCamRow(cX, cW, ly, "Include chat",
                () -> tracked.perCamSeesChat,       v -> tracked.perCamSeesChat       = v);
        ly = addPerCamRow(cX, cW, ly, "Name tags",
                () -> tracked.perCamNameTags,       v -> tracked.perCamNameTags       = v);
        ly = addPerCamRow(cX, cW, ly, "Include open menus",
                () -> tracked.perCamShowPlayerGuis, v -> tracked.perCamShowPlayerGuis = v);

        // ── Section: Other ────────────────────────────────────────────────────
        ly += SEC_STEP;
        mkBtn(cX, ly, 110, "Gravity: " + (gravityVal ? "ON" : "OFF"),
                () -> { syncTextFields(); gravityVal = !gravityVal; applyLive(); clearAndInit(); });
        zoomField = mkField(cX + 120 + 34, ly, cW - 120 - 34 - 4, zoomVal,
                t -> { zoomVal = t; applyLive(); });
        ly += ROW_STEP;
        ly += 8;

        totalLogH = ly;
        int viewportH = this.height - CONTENT_TOP - BOTTOM_H;
        scrollY = Math.max(0, Math.min(scrollY, Math.max(0, totalLogH - viewportH)));

        // Hide scrollable widgets that fall outside the viewport so they
        // don't catch clicks meant for the bottom-row buttons.
        clampVisibility();

        // ── Fixed bottom row ──────────────────────────────────────────────────
        int by  = this.height - BOTTOM_H + 5;
        int bW  = 80;
        int mid = leftAreaW / 2;
        doneBtn   = ButtonWidget.builder(Text.literal("Done"),   b -> doneAndClose())
                .dimensions(mid - bW - bW / 2 - 6, by, bW, ROW_H).build();
        cancelBtn = ButtonWidget.builder(Text.literal("Cancel"), b -> cancelAndClose())
                .dimensions(mid - bW / 2, by, bW, ROW_H).build();
        removeBtn = ButtonWidget.builder(Text.literal("Remove"), b -> { syncTextFields(); removeCamera(); })
                .dimensions(mid + bW / 2 + 6, by, bW, ROW_H).build();
        addDrawableChild(doneBtn);
        addDrawableChild(cancelBtn);
        addDrawableChild(removeBtn);

        // Top-left "back to camera list" button. Created after clampVisibility()
        // (like the bottom row) so it isn't hidden for sitting above CONTENT_TOP.
        // Edits are applied live, so back keeps changes and returns to the list.
        backBtn = ButtonWidget.builder(Text.literal("< Camera List"), b -> backToCameraList())
                .dimensions(6, 4, 96, ROW_H).build();
        addDrawableChild(backBtn);

        suspendChangeListener = false;
    }

    /** Hide widgets whose row sits above CONTENT_TOP or below the viewport. */
    private void clampVisibility() {
        int viewBottom   = this.height - BOTTOM_H;
        int bottomRowTop = this.height - BOTTOM_H;
        for (var element : this.children()) {
            if (!(element instanceof ClickableWidget w)) continue;
            int y = w.getY();
            int h = w.getHeight();
            // Leave the bottom-row buttons alone (they live at y >= bottomRowTop).
            if (y >= bottomRowTop) continue;
            boolean inView = (y >= CONTENT_TOP) && (y + h <= viewBottom);
            w.visible = inView;
            w.active  = w.active && inView; // belt-and-suspenders for click handling
        }
    }

    // ─── Widget helpers ───────────────────────────────────────────────────────

    private TextFieldWidget mkField(int x, int ly, int w, String value,
                                    Consumer<String> onChange) {
        int sy = CONTENT_TOP + ly - scrollY;
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, sy, w, ROW_H, Text.empty());
        f.setMaxLength(256);
        f.setText(value == null ? "" : value);
        f.setChangedListener(t -> {
            if (!suspendChangeListener) {
                onChange.accept(t);
            }
        });
        addDrawableChild(f);
        return f;
    }

    private ButtonWidget mkBtn(int x, int ly, int w, String label, Runnable action) {
        int sy = CONTENT_TOP + ly - scrollY;
        ButtonWidget btn = ButtonWidget.builder(Text.literal(label), b -> action.run())
                .dimensions(x, sy, w, ROW_H).build();
        addDrawableChild(btn);
        return btn;
    }

    private void mkModeBtn(int x, int ly, int w, String label, boolean selected, Runnable action) {
        int sy = CONTENT_TOP + ly - scrollY;
        ButtonWidget btn = ButtonWidget.builder(Text.literal(label), b -> action.run())
                .dimensions(x, sy, w, ROW_H).build();
        btn.active = !selected;
        addDrawableChild(btn);
    }

    /** Per-camera setting row: main button cycles null→true→false→null, "Default" button resets. */
    private int addPerCamRow(int cX, int cW, int ly, String name,
                             Supplier<Boolean> getter, Consumer<Boolean> setter) {
        Boolean cur = getter.get();
        String  stateLabel = cur == null ? "Default" : (cur ? "ON" : "OFF");
        Boolean nextCycle  = cur == null ? Boolean.TRUE : (cur ? Boolean.FALSE : null);
        int mainW = cW - 60;
        mkBtn(cX, ly, mainW, name + ": " + stateLabel,
                () -> { setter.accept(nextCycle); clearAndInit(); });
        int sy = CONTENT_TOP + ly - scrollY;
        ButtonWidget defBtn = ButtonWidget.builder(Text.literal("Default"),
                        b -> { setter.accept(null); clearAndInit(); })
                .dimensions(cX + mainW + 4, sy, 54, ROW_H).build();
        defBtn.active = cur != null;
        addDrawableChild(defBtn);
        return ly + ROW_STEP;
    }

    // ─── Render ───────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Re-apply GLFW_CURSOR_DISABLED each frame while rotate or drag is active.
        // MC's Mouse.tick() calls unlockCursor() once per tick when a screen is
        // open, so a one-shot lock is undone immediately; render() runs at ~60fps
        // and wins the race against tick()'s ~20fps. DISABLED gives unbounded
        // relative motion and restores the cursor to its prior spot on release.
        if (inRotateMode || inDragMode) {
            GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(),
                    GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        }
        CameraRenderer.setPreviewBoost(inRotateMode || inDragMode);

        // Keep the Active toggle's label in sync with the REAL bound-camera state
        // every frame. In server mode toggleActive() only sends a packet; the bind
        // state updates a round-trip later, so a label baked at init()/clearAndInit()
        // time would show the pre-toggle value and read inverted. Refreshing here
        // makes it always reflect the true state (and self-correct after the packet).
        if (activeBtn != null) {
            boolean nowActive = cameraUuid.equals(CameraRenderer.getBoundCameraUuid());
            activeBtn.setMessage(Text.literal("Active: " + (nowActive ? "ON" : "OFF")));
        }

        // Commit a text field's value when focus leaves it (clicking away).
        net.minecraft.client.gui.Element nowFocused = getFocused();
        if (nowFocused != lastFocusedElement) {
            if (lastFocusedElement instanceof TextFieldWidget) {
                syncTextFields();
                applyLive();
            }
            lastFocusedElement = nowFocused;
        }

        context.fill(0, 0, this.width, this.height, 0xD0101010);

        int viewBottom   = this.height - BOTTOM_H;
        int bottomRowTop = this.height - BOTTOM_H;
        int rightPanelX  = this.width - PREVIEW_PANEL_W;

        // Draw overlay backgrounds BEFORE super.render() so widgets (Done/Cancel/
        // Remove buttons in the bottom row, etc.) draw on top of them instead of
        // being covered.
        context.fill(0, 0, this.width, CONTENT_TOP, 0xFF202020);
        context.fill(0, CONTENT_TOP, this.width, CONTENT_TOP + 1, 0xFF606060);
        context.fill(0, bottomRowTop, this.width, this.height, 0xFF202020);
        context.fill(0, bottomRowTop, this.width, bottomRowTop + 1, 0xFF606060);
        context.fill(rightPanelX - 1, CONTENT_TOP, rightPanelX, viewBottom, 0xFF606060);
        context.fill(rightPanelX, CONTENT_TOP, this.width, viewBottom, 0xFF202020);

        // Scrollable content area clipped to the left of the preview panel.
        context.enableScissor(0, CONTENT_TOP, rightPanelX, viewBottom);
        drawContentLabels(context);
        context.disableScissor();

        super.render(context, mouseX, mouseY, delta);

        // Overdraw the bottom bar to cover scrollable widgets that may have visually
        // leaked below the viewport boundary during super.render(), then re-render the
        // Done/Cancel/Remove buttons on top of the freshly redrawn bar.
        context.fill(0, bottomRowTop, this.width, this.height, 0xFF202020);
        context.fill(0, bottomRowTop, this.width, bottomRowTop + 1, 0xFF606060);
        if (doneBtn   != null) doneBtn.render(context, mouseX, mouseY, delta);
        if (cancelBtn != null) cancelBtn.render(context, mouseX, mouseY, delta);
        if (removeBtn != null) removeBtn.render(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(textRenderer, "Edit Camera", this.width / 2, 9, 0xFFFFFFFF);

        drawPreviewPanel(context);
        drawScrollbar(context, viewBottom);
        if (inRotateMode) {
            context.drawCenteredTextWithShadow(textRenderer,
                    "Move mouse to rotate — click to stop",
                    this.width / 2, CONTENT_TOP + 6, 0xFFFFCC66);
        } else if (inDragMode) {
            context.drawCenteredTextWithShadow(textRenderer,
                    "← drag to decrease  /  increase →",
                    this.width / 2, CONTENT_TOP + 6, 0xFF66CCFF);
        }
    }

    /** Draw the three live camera-preview images (View / Front / Back) stacked vertically in the right panel. */
    private void drawPreviewPanel(DrawContext ctx) {
        int v = CameraRenderer.getPreviewFrameVersion();
        if (v != previewTexVersion) {
            previewTexVersion = v;
            uploadPreviewTexture(0, CameraRenderer.getPreviewFrameView());
            uploadPreviewTexture(1, CameraRenderer.getPreviewFrameFront());
            uploadPreviewTexture(2, CameraRenderer.getPreviewFrameBack());
        }
        int panelX      = this.width - PREVIEW_PANEL_W;
        int maxImgW     = PREVIEW_PANEL_W - PREVIEW_BAND_PAD * 2;  // = 160
        int panelAvailH = this.height - BOTTOM_H - CONTENT_TOP;
        // Fit 3 images + 3 labels + 2 inter-slot gaps + top padding.
        int maxImgH = Math.max(10, Math.min(PREVIEW_IMG_H,
                (panelAvailH - PREVIEW_BAND_PAD - 3 * PREVIEW_LABEL_H - 2 * 4) / 3));
        // Keep the source 16:9 aspect ratio (PREVIEW_W:PREVIEW_H) so the
        // previews aren't squished when the panel is narrow or short.
        int sw = CameraRenderer.PREVIEW_W;
        int sh = CameraRenderer.PREVIEW_H;
        int imgW = maxImgW;
        int imgH = imgW * sh / sw;
        if (imgH > maxImgH) {
            imgH = maxImgH;
            imgW = imgH * sw / sh;
        }
        int slotH  = imgH + PREVIEW_LABEL_H + 4;
        int imgX   = panelX + (PREVIEW_PANEL_W - imgW) / 2;
        int startY = CONTENT_TOP + PREVIEW_BAND_PAD;

        ctx.enableScissor(panelX, CONTENT_TOP, this.width, this.height - BOTTOM_H);
        for (int i = 0; i < 3; i++) {
            int imgY   = startY + i * slotH;
            int labelY = imgY + imgH + 2;
            String label = i == 0 ? "View" : i == 1 ? "Front" : "Back";
            net.minecraft.util.Identifier tid =
                    i == 0 ? previewIdView : i == 1 ? previewIdFront : previewIdBack;
            net.minecraft.client.texture.NativeImageBackedTexture tex =
                    i == 0 ? previewTexView : i == 1 ? previewTexFront : previewTexBack;
            drawOnePreview(ctx, imgX, imgY, imgW, imgH, label, tid, tex, labelY);
        }
        ctx.disableScissor();
    }

    private void drawOnePreview(DrawContext ctx, int x, int y, int imgW, int imgH,
                                String label,
                                net.minecraft.util.Identifier texId,
                                net.minecraft.client.texture.NativeImageBackedTexture tex,
                                int labelY) {
        ctx.fill(x - 1, y - 1, x + imgW + 1, y + imgH + 1, 0xFF606060);
        ctx.fill(x, y, x + imgW, y + imgH, 0xFF1A1A1A);
        if (tex != null && texId != null) {
            int sw = CameraRenderer.PREVIEW_W;
            int sh = CameraRenderer.PREVIEW_H;
            ctx.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, texId,
                    x, y, 0.0f, 0.0f, imgW, imgH, sw, sh, sw, sh);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer, "—",
                    x + imgW / 2, y + imgH / 2 - 4, 0xFF666666);
        }
        ctx.drawCenteredTextWithShadow(textRenderer, label,
                x + imgW / 2, labelY, 0xFFCCCCCC);
    }

    private void uploadPreviewTexture(int slot, byte[] frame) {
        if (frame == null) return;
        int w = CameraRenderer.PREVIEW_W;
        int h = CameraRenderer.PREVIEW_H;
        if (frame.length < w * h * 3) return;

        net.minecraft.client.texture.NativeImageBackedTexture tex;
        net.minecraft.util.Identifier id;
        switch (slot) {
            case 0 -> { tex = previewTexView;  id = previewIdView;  }
            case 1 -> { tex = previewTexFront; id = previewIdFront; }
            default -> { tex = previewTexBack; id = previewIdBack; }
        }
        net.minecraft.client.texture.NativeImage img;
        if (tex == null) {
            img = new net.minecraft.client.texture.NativeImage(w, h, false);
            final net.minecraft.client.texture.NativeImage finalImg = img;
            String suffix = slot == 0 ? "view" : slot == 1 ? "front" : "back";
            id = net.minecraft.util.Identifier.of(
                    dev.tggamesyt.cameramod.Cameramod.MOD_ID,
                    "edit_preview_" + suffix + "_" + cameraUuid.toString().replace("-", ""));
            tex = new net.minecraft.client.texture.NativeImageBackedTexture(
                    () -> "cameramod edit preview " + suffix, finalImg);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
            switch (slot) {
                case 0 -> { previewTexView  = tex; previewIdView  = id; }
                case 1 -> { previewTexFront = tex; previewIdFront = id; }
                default -> { previewTexBack = tex; previewIdBack = id; }
            }
        } else {
            img = tex.getImage();
        }
        int rowLen = w * 3;
        for (int ty = 0; ty < h; ty++) {
            int base = ty * rowLen;
            for (int x = 0; x < w; x++) {
                int i = base + x * 3;
                int b = frame[i]     & 0xFF;
                int g = frame[i + 1] & 0xFF;
                int r = frame[i + 2] & 0xFF;
                img.setColorArgb(x, ty, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }
        tex.upload();
    }

    @Override
    public void removed() {
        // Stop preview rendering and tear down our textures so we don't leak
        // GPU resources when the screen closes.
        CameraRenderer.setPreviewCameraUuid(null);
        var tm = MinecraftClient.getInstance().getTextureManager();
        if (previewIdView  != null) tm.destroyTexture(previewIdView);
        if (previewIdFront != null) tm.destroyTexture(previewIdFront);
        if (previewIdBack  != null) tm.destroyTexture(previewIdBack);
        previewTexView = previewTexFront = previewTexBack = null;
        previewIdView  = previewIdFront  = previewIdBack  = null;
        // Restore cursor if it was locked for rotate or drag mode.
        if (inRotateMode || inDragMode) {
            if (inRotateMode) {
                CameraEntity cam = findCamera();
                if (cam != null) cam.cameramod$unlockClientRotation();
            }
            inRotateMode = false;
            inDragMode   = false;
            dragField    = null;
            dragAccumX   = 0;
            dragLastRawX = Double.NaN;
            GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(),
                    GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        CameraRenderer.setPreviewBoost(false);
        super.removed();
    }

    private void drawContentLabels(DrawContext ctx) {
        int leftAreaW = (this.width - PREVIEW_PANEL_W) - 8;
        int cW = Math.min(leftAreaW - 20, 480);
        int cX = Math.max(10, (leftAreaW - cW) / 2);
        // Must match init() exactly so labels align with their fields.
        int moveBtnW  = 50;
        int f3        = ((cW - moveBtnW - 4) - 2 * 4 - 3 * 20) / 3;
        int rotBtnW   = 60;
        int f2        = ((cW - rotBtnW  - 4) - 4 - 2 * 32) / 2;
        int ly = 0;

        drawSec(ctx, cX, cW, ly, "Name"); ly += SEC_STEP;
        ly += ROW_STEP;

        drawSec(ctx, cX, cW, ly, "Position"); ly += SEC_STEP;
        drawLbl(ctx, cX,                               ly, "X:");
        drawLbl(ctx, cX + 20 + f3 + 4,                ly, "Y:");
        drawLbl(ctx, cX + 20 * 2 + f3 * 2 + 4 * 2,   ly, "Z:");
        ly += ROW_STEP;

        drawSec(ctx, cX, cW, ly, "Rotation"); ly += SEC_STEP;
        drawLbl(ctx, cX,                    ly, "Yaw:");
        drawLbl(ctx, cX + 32 + f2 + 4,     ly, "Pitch:");
        ly += ROW_STEP;

        drawSec(ctx, cX, cW, ly, "Look Target"); ly += SEC_STEP;
        ly += ROW_STEP;
        ly += ROW_STEP;

        drawSec(ctx, cX, cW, ly, "Parent (Attachment)"); ly += SEC_STEP;
        ly += ROW_STEP;
        ly += ROW_STEP;
        int f3o = f3;
        drawLbl(ctx, cX,                                  ly, "X:");
        drawLbl(ctx, cX + 20 + f3o + 4,                  ly, "Y:");
        drawLbl(ctx, cX + 20 * 2 + f3o * 2 + 4 * 2,      ly, "Z:");
        ly += ROW_STEP;

        drawSec(ctx, cX, cW, ly, "Overrides"); ly += SEC_STEP;
        ly += 5 * ROW_STEP;

        drawSec(ctx, cX, cW, ly, "Other"); ly += SEC_STEP;
        drawLbl(ctx, cX + 120, ly, "Zoom:");
    }

    private void drawSec(DrawContext ctx, int cX, int cW, int ly, String text) {
        int sy = CONTENT_TOP + ly - scrollY;
        // Text at sy+3 spans sy+3..sy+11; line at sy+16 sits clear of it with a 5px gap.
        ctx.drawTextWithShadow(textRenderer, text, cX + 2, sy + 3, 0xFFAAAAAA);
        ctx.fill(cX, sy + 16, cX + cW, sy + 17, 0xFF404040);
    }

    private void drawLbl(DrawContext ctx, int x, int ly, String text) {
        int sy = CONTENT_TOP + ly - scrollY + (ROW_H - 8) / 2;
        ctx.drawTextWithShadow(textRenderer, text, x, sy, 0xFF888888);
    }

    private void drawScrollbar(DrawContext ctx, int viewBottom) {
        int viewportH = viewBottom - CONTENT_TOP;
        if (totalLogH <= viewportH) return;
        int sbX    = (this.width - PREVIEW_PANEL_W) - 8 - 2;
        int trackH = viewportH - 4;
        int thumbH = Math.max(10, trackH * viewportH / totalLogH);
        int thumbY = CONTENT_TOP + 2 + (int) ((long) (trackH - thumbH) * scrollY / (totalLogH - viewportH));
        ctx.fill(sbX, CONTENT_TOP + 2, sbX + 3, viewBottom - 2, 0xFF303030);
        ctx.fill(sbX, thumbY, sbX + 3, thumbY + thumbH, 0xFF888888);
    }

    /** Returns true if (mouseX, mouseY) hits the scrollbar's clickable region.
     *  The visible bar is 3px wide which is awkward to grab, so we accept a few
     *  pixels of slop on either side. Returns false when no scrolling is needed. */
    private boolean isOverScrollbar(double mouseX, double mouseY) {
        int viewBottom = this.height - BOTTOM_H;
        int viewportH  = viewBottom - CONTENT_TOP;
        if (totalLogH <= viewportH) return false;
        int sbX = (this.width - PREVIEW_PANEL_W) - 8 - 2;
        return mouseX >= sbX - 2 && mouseX <= sbX + 5
            && mouseY >= CONTENT_TOP + 2 && mouseY <= viewBottom - 2;
    }

    /** Applies the cursor Y to scrollY, treating (mouseY - grabOffset) as the
     *  desired thumb-top position. Returns true if the scroll position changed. */
    private boolean applyScrollbarDrag(double mouseY) {
        int viewBottom = this.height - BOTTOM_H;
        int viewportH  = viewBottom - CONTENT_TOP;
        int maxScroll  = Math.max(0, totalLogH - viewportH);
        if (maxScroll <= 0) return false;
        int trackTop = CONTENT_TOP + 2;
        int trackH   = viewportH - 4;
        int thumbH   = Math.max(10, trackH * viewportH / totalLogH);
        int travel   = trackH - thumbH;
        if (travel <= 0) return false;
        double thumbTop = mouseY - scrollbarGrabOffset;
        thumbTop = Math.max(trackTop, Math.min(trackTop + travel, thumbTop));
        int newScroll = (int) Math.round((thumbTop - trackTop) * (double) maxScroll / travel);
        if (newScroll == scrollY) return false;
        scrollY = newScroll;
        return true;
    }

    // ─── Scroll ───────────────────────────────────────────────────────────────

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        syncTextFields();
        int viewportH = this.height - CONTENT_TOP - BOTTOM_H;
        int maxScroll = Math.max(0, totalLogH - viewportH);
        if (maxScroll > 0) {
            scrollY -= (int) (vertical * 20);
            scrollY  = Math.max(0, Math.min(maxScroll, scrollY));
            clearAndInit();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    // ─── Actions ──────────────────────────────────────────────────────────────

    private void toggleActive() {
        boolean active = cameraUuid.equals(CameraRenderer.getBoundCameraUuid());
        if (active) {
            if (CameramodClient.serverHasMod) sendActivatorPacket();
            else { CameraRenderer.clearBoundCamera(); CameraRenderer.setStreamingEnabled(false); }
        } else {
            if (CameramodClient.serverHasMod) sendActivatorPacket();
            else {
                CameraRenderer.setBoundCamera(cameraUuid);
                CameraRenderer.setStreamingEnabled(true);
                CameramodClient.addTrackedCamera(cameraUuid);
            }
        }
        clearAndInit();
    }

    private void sendActivatorPacket() {
        UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
        ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                (byte) 1, (byte) 1, cameraUuid, nil, 0, 0, 0, (byte) 0));
    }

    private void removeCamera() {
        if (cameraUuid.equals(CameraRenderer.getBoundCameraUuid())) {
            CameraRenderer.clearBoundCamera();
            CameraRenderer.setStreamingEnabled(false);
        }
        CameraEntity clientCam = CameramodClient.CLIENT_CAMERAS.remove(cameraUuid);
        if (clientCam != null) clientCam.discard();
        if (CameramodClient.serverHasMod) {
            UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 8, (byte) 0, cameraUuid, nil, 0, 0, 0, (byte) 0));
        }
        CameramodClient.TRACKED_CAMERAS.remove(cameraUuid);
        // Persist the deletion immediately. Without this the removal only
        // lives in memory until DISCONNECT, so reconnecting reloads the
        // deleted camera from disk and the Cameras list shows it again.
        CameramodClient.saveCurrentTrackedState();
        this.client.setScreen(parent);
    }

    private void doneAndClose() {
        // Edits have been auto-applied while in the screen — Done just closes.
        syncTextFields();
        applyLive();
        this.client.setScreen(parent);
    }

    /** "Back to camera list": always lands on the Cameras list. When the edit
     *  screen was opened by clicking a camera entity in the world, parent is null
     *  (see ClientInteractionMixin) — open a fresh list instead of closing to the
     *  game. Edits are applied live, so this keeps changes like Done. */
    private void backToCameraList() {
        syncTextFields();
        applyLive();
        CameraGuiScreen dest = parent != null
                ? parent
                : new CameraGuiScreen(CameraGuiScreen.Tab.CAMERAS);
        this.client.setScreen(dest);
    }

    private void cancelAndClose() {
        // Restore tracked.* (per-cam overrides + name) to originals
        tracked.name                = origName;
        tracked.perCamFlipped       = origPerCamFlipped;
        tracked.perCamSeesChat      = origPerCamSeesChat;
        tracked.perCamNameTags      = origPerCamNameTags;
        tracked.perCamShowPlayerGuis = origPerCamShowPlayerGuis;

        // Restore instance vars from originals so applyLive() pushes them
        nameVal  = origName == null ? "" : origName;
        posXVal  = fmt3(origX); posYVal = fmt3(origY); posZVal = fmt3(origZ);
        yawVal   = fmt2(origYaw); pitchVal = fmt2(origPitch);
        if (origFixedTarget == null) { fixerUIMode = 0; fixedTargetVal = ""; }
        else { fixedTargetVal = origFixedTarget.toString(); fixerUIMode = origFixerMode == 0 ? 1 : 2; }
        if (origAttachTarget == null) { attachUIMode = 0; attachTargetVal = ""; }
        else { attachTargetVal = origAttachTarget.toString(); attachUIMode = origAttachMode == 0 ? 1 : 2; }
        offXVal = fmt3(origOffX); offYVal = fmt3(origOffY); offZVal = fmt3(origOffZ);
        gravityVal = origGravity;
        zoomVal    = fmt2(origZoom);

        applyLive();
        this.client.setScreen(parent);
    }

    private void captureOriginals() {
        if (originalsCaptured) return;
        CameraEntity cam = findCamera();
        if (cam != null) {
            origX = cam.getX(); origY = cam.getY(); origZ = cam.getZ();
            origYaw = cam.getYaw(); origPitch = cam.getPitch();
            origFixedTarget = cam.getFixedTargetUuid();
            origFixerMode   = cam.getFixerMode();
            origAttachTarget = cam.getAttachTargetUuid();
            net.minecraft.util.math.Vec3d off = cam.getAttachOffset();
            origOffX = off.x; origOffY = off.y; origOffZ = off.z;
            origAttachMode = cam.getAttachMode();
            origGravity    = cam.isGravityEnabled();
            origZoom       = cam.getZoomLevel();
        }
        origName                  = tracked.name;
        origPerCamFlipped         = tracked.perCamFlipped;
        origPerCamSeesChat        = tracked.perCamSeesChat;
        origPerCamNameTags        = tracked.perCamNameTags;
        origPerCamShowPlayerGuis  = tracked.perCamShowPlayerGuis;
        originalsCaptured = true;
    }

    /** Apply current edits to the camera entity (client-only) or send packet (server). */
    private void applyLive() {
        if (tracked != null) {
            String n = nameVal.trim();
            if (!n.isEmpty()) tracked.name = n;
        }
        CameraEntity cam = findCamera();
        boolean clientOnly = cam != null && cam.isClientOnly();
        if (clientOnly) {
            applyToClientCamera(cam);
        } else if (CameramodClient.serverHasMod) {
            applyToServerCamera();
        }
    }

    private void applyToClientCamera(CameraEntity cam) {
        try {
            double x = Double.parseDouble(posXVal.trim());
            double y = Double.parseDouble(posYVal.trim());
            double z = Double.parseDouble(posZVal.trim());
            cam.setPosition(x, y, z);
            cam.lastRenderX = x; cam.lastRenderY = y; cam.lastRenderZ = z;
        } catch (NumberFormatException ignored) {}

        // Skip writing yaw/pitch from the text fields while a look-at fixer is
        // active. The fixer owns rotation each frame; writing the stale stored
        // values back here (e.g. on every position-drag event) makes the camera
        // thrash between the fixer's computed look and the typed yaw/pitch.
        if (fixerUIMode == 0) {
            try {
                float yaw   = Float.parseFloat(yawVal.trim());
                float pitch = Float.parseFloat(pitchVal.trim());
                cam.setYaw(yaw); cam.setPitch(pitch);
                cam.setHeadYaw(yaw); cam.setBodyYaw(yaw);
            } catch (NumberFormatException ignored) {}
        }

        if (fixerUIMode == 0) {
            cam.setFixedTargetUuid(null);
        } else {
            UUID ft = resolveTarget(fixedTargetVal);
            if (ft != null) cam.setFixedTargetUuid(ft);
            cam.setFixerMode((byte) (fixerUIMode - 1));
        }

        if (attachUIMode == 0) {
            cam.setAttachTargetUuid(null);
        } else {
            UUID at = resolveTarget(attachTargetVal);
            if (at != null) {
                cam.setAttachTargetUuid(at);
                cam.setAttachMode((byte) (attachUIMode - 1));
            }
        }

        try {
            double ox = Double.parseDouble(offXVal.trim());
            double oy = Double.parseDouble(offYVal.trim());
            double oz = Double.parseDouble(offZVal.trim());
            cam.setAttachOffset(new net.minecraft.util.math.Vec3d(ox, oy, oz));
        } catch (NumberFormatException ignored) {}

        cam.setGravityEnabled(gravityVal);

        try { cam.setZoomLevel(Float.parseFloat(zoomVal.trim())); }
        catch (NumberFormatException ignored) {}

        String n = nameVal.trim();
        if (!n.isEmpty()) { cam.setCustomName(net.minecraft.text.Text.literal(n)); cam.setCustomNameVisible(true); }
    }

    private void applyToServerCamera() {
        UUID nil   = CameraServerThing.CameraItemUseC2SPayload.NIL;
        int  flags = 0;
        double posX = 0, posY = 0, posZ = 0;
        float  yaw = 0, pitch = 0;
        UUID   fixedTarget  = nil;
        byte   fixerMode    = 0;
        UUID   attachTarget = nil;
        float  offX = 0, offY = 0, offZ = 0;
        byte   attachMode = 0;
        float  zoom = 1.0f;
        String customName = "";

        try {
            posX = Double.parseDouble(posXVal.trim());
            posY = Double.parseDouble(posYVal.trim());
            posZ = Double.parseDouble(posZVal.trim());
            flags |= CameraServerThing.CameraEditC2SPayload.FLAG_POS;
        } catch (NumberFormatException ignored) {}

        // Don't send rotation while a fixer is active — the server-side fixer
        // recomputes it, and pushing the stale typed yaw/pitch on every
        // position edit causes the same look-at thrash as the client path.
        if (fixerUIMode == 0) {
            try {
                yaw   = Float.parseFloat(yawVal.trim());
                pitch = Float.parseFloat(pitchVal.trim());
                flags |= CameraServerThing.CameraEditC2SPayload.FLAG_ROTATION;
            } catch (NumberFormatException ignored) {}
        }

        if (fixerUIMode == 0) {
            fixedTarget = nil;
            flags |= CameraServerThing.CameraEditC2SPayload.FLAG_FIXED;
        } else {
            UUID ft = resolveTarget(fixedTargetVal);
            fixedTarget = ft != null ? ft : nil;
            fixerMode   = (byte) (fixerUIMode - 1);
            flags |= CameraServerThing.CameraEditC2SPayload.FLAG_FIXED
                  |  CameraServerThing.CameraEditC2SPayload.FLAG_FIXER_MODE;
        }

        if (attachUIMode == 0) {
            attachTarget = nil;
            flags |= CameraServerThing.CameraEditC2SPayload.FLAG_ATTACH;
        } else {
            UUID at = resolveTarget(attachTargetVal);
            attachTarget = at != null ? at : nil;
            attachMode   = (byte) (attachUIMode - 1);
            flags |= CameraServerThing.CameraEditC2SPayload.FLAG_ATTACH
                  |  CameraServerThing.CameraEditC2SPayload.FLAG_ATTACH_MODE;
        }

        try {
            offX = Float.parseFloat(offXVal.trim());
            offY = Float.parseFloat(offYVal.trim());
            offZ = Float.parseFloat(offZVal.trim());
            flags |= CameraServerThing.CameraEditC2SPayload.FLAG_ATTACH_OFF;
        } catch (NumberFormatException ignored) {}

        flags |= CameraServerThing.CameraEditC2SPayload.FLAG_GRAVITY;

        try {
            zoom   = Float.parseFloat(zoomVal.trim());
            flags |= CameraServerThing.CameraEditC2SPayload.FLAG_ZOOM;
        } catch (NumberFormatException ignored) {}

        String n = nameVal.trim();
        if (!n.isEmpty()) { customName = n; flags |= CameraServerThing.CameraEditC2SPayload.FLAG_NAME; }

        ClientPlayNetworking.send(new CameraServerThing.CameraEditC2SPayload(
                cameraUuid, flags,
                posX, posY, posZ, yaw, pitch,
                fixedTarget, fixerMode,
                attachTarget, offX, offY, offZ, attachMode,
                gravityVal, zoom, customName));
    }

    private UUID resolveTarget(String input) {
        if (input == null || input.isBlank()) return null;
        try { return UUID.fromString(input.trim()); } catch (IllegalArgumentException ignored) {}
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e.getName().getString().equalsIgnoreCase(input.trim())) return e.getUuid();
            }
        }
        return null;
    }

    /**
     * Recompute the attachment offset from the camera's current world position
     * so attaching (or changing target / mode) leaves the camera exactly where
     * it is instead of teleporting it to the target.
     * World mode (1): offset is in world space.
     * Head mode (2): offset is in target-local space (rotated by target yaw).
     */
    private void recomputeOffsetToKeepInPlace(int mode) {
        if (mode == 0) return;
        UUID targetUuid = resolveTarget(attachTargetVal);
        if (targetUuid == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        Entity target = null;
        // The local player is often NOT returned by world.getEntities() on the
        // client, so resolve it explicitly (same as the per-frame attach loop).
        // Without this, attaching "to me" in Head mode would fall through with
        // target == null and skip the world->local offset conversion below — the
        // offset stays world-space and the per-frame orbit then rotates it by the
        // player's yaw, so the camera jumps to a rotated position instead of
        // staying where it is.
        if (mc.player != null && mc.player.getUuid().equals(targetUuid)) {
            target = mc.player;
        } else if (mc.world != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e.getUuid().equals(targetUuid)) { target = e; break; }
            }
        }
        if (target == null) return;

        double camX, camY, camZ;
        CameraEntity cam = findCamera();
        if (cam != null) {
            camX = cam.getX(); camY = cam.getY(); camZ = cam.getZ();
        } else {
            try {
                camX = Double.parseDouble(posXVal.trim());
                camY = Double.parseDouble(posYVal.trim());
                camZ = Double.parseDouble(posZVal.trim());
            } catch (NumberFormatException e) { return; }
        }
        double dx = camX - target.getX();
        double dy = camY - target.getY();
        double dz = camZ - target.getZ();
        if (mode == 2) {
            // Head: express the world offset in the target's local frame.
            double yawRad = target.getYaw() * Math.PI / 180.0;
            double cos = Math.cos(yawRad), sin = Math.sin(yawRad);
            offXVal = fmt3(dx * cos + dz * sin);
            offZVal = fmt3(-dx * sin + dz * cos);
        } else {
            offXVal = fmt3(dx);
            offZVal = fmt3(dz);
        }
        offYVal = fmt3(dy);
    }

    // ─── Rotate mode ─────────────────────────────────────────────────────────

    private void enterRotateMode() {
        inRotateMode = true;
        rotateLastX  = Double.NaN;
        rotateLastY  = Double.NaN;
        CameraEntity cam = findCamera();
        if (cam != null) {
            rotateYaw   = cam.getYaw();
            rotatePitch = cam.getPitch();
            // Lock client-side rotation so server interpolation stops fighting
            // the mouse input (would otherwise yank the camera back each tick).
            cam.cameramod$lockClientRotation(cam.getYaw(), cam.getPitch());
        }
        // Use GLFW directly — mc.mouse.lockCursor() gets overridden by MC's
        // Mouse.tick() every game tick whenever a screen is open.
        GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(),
                GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
        clearAndInit(); // refresh button label "Rotate" → "Stop"
    }

    private void exitRotateMode() {
        if (!inRotateMode) return;
        inRotateMode = false;
        GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(),
                GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        // Push the latest yaw/pitch back to the text fields and re-apply.
        CameraEntity cam = findCamera();
        if (cam != null) {
            cam.cameramod$unlockClientRotation();
            yawVal   = fmt2(cam.getYaw());
            pitchVal = fmt2(cam.getPitch());
        }
        applyLive();
        clearAndInit();
    }

    /** Called from MouseMixin's onCursorPos hook with raw GLFW cursor coordinates.
     *  Used instead of Screen.mouseMoved so rotate mode works even while the
     *  cursor is locked (Mouse.tick's mouseMoved dispatch can lag or drop
     *  ticks in that state). */
    public void cameramod$onRawCursorPos(double rawX, double rawY) {
        if (inRotateMode) {
            if (!Double.isNaN(rotateLastX)) {
                double dx = rawX - rotateLastX;
                double dy = rawY - rotateLastY;
                if (dx != 0 || dy != 0) applyRotateDelta(dx, dy);
            }
            rotateLastX = rawX;
            rotateLastY = rawY;
            return;
        }
        // Hold-to-drag, fully driven here. Before drag mode we accumulate raw
        // horizontal movement to detect intent; once past the threshold we
        // switch to GLFW_CURSOR_DISABLED (unbounded relative motion) and apply
        // the delta between consecutive events to the field value.
        if (dragField == null) return;
        double scale = MinecraftClient.getInstance().getWindow().getScaleFactor();
        if (!inDragMode) {
            if (!Double.isNaN(dragLastRawX)) {
                dragAccumX += rawX - dragLastRawX;
                if (Math.abs(dragAccumX) / scale > DRAG_THRESHOLD_PX) {
                    inDragMode   = true;
                    dragLastRawX = Double.NaN;   // DISABLED jumps coords — re-baseline
                    GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(),
                            GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
                    return;
                }
            }
            dragLastRawX = rawX;
        } else {
            if (!Double.isNaN(dragLastRawX)) {
                double dx = rawX - dragLastRawX;
                if (dx != 0) {
                    applyDragDelta(dx / scale);
                }
            }
            dragLastRawX = rawX;
        }
    }

    private void applyRotateDelta(double dx, double dy) {
        CameraEntity cam = findCamera();
        if (cam == null) return;
        // Accumulate into our own values — never read the entity's yaw back,
        // since it is being interpolated toward stale server values.
        rotateYaw   += dx * ROTATE_SENSITIVITY;
        rotatePitch += dy * ROTATE_SENSITIVITY;
        rotatePitch = Math.max(-90.0, Math.min(90.0, rotatePitch));
        float yaw   = (float) rotateYaw;
        float pitch = (float) rotatePitch;
        cam.setYaw(yaw);
        cam.setPitch(pitch);
        cam.setHeadYaw(yaw);
        cam.setBodyYaw(yaw);
        cam.cameramod$lockClientRotation(yaw, pitch);
        yawVal   = fmt2(yaw);
        pitchVal = fmt2(pitch);
        // Push to server for non-client cams. For client cams the entity
        // mutation above is already authoritative.
        if (!cam.isClientOnly() && CameramodClient.serverHasMod) {
            // Sync live position before sending so the server doesn't teleport
            // the camera back to the stale coordinates from when the screen opened.
            posXVal = fmt3(cam.getX());
            posYVal = fmt3(cam.getY());
            posZVal = fmt3(cam.getZ());
            applyToServerCamera();
        }
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
        double mouseX = click.x(), mouseY = click.y();
        int button = click.button();
        if (inRotateMode) {
            // A click exits rotate mode before the "Stop" button can receive
            // it, so play the UI click sound manually to keep the button feel.
            playUiClickSound();
            exitRotateMode();
            return true;
        }
        // The Done/Cancel/Remove bar is overdrawn on top of everything in
        // render(), so it must also win clicks — even when a scrolled widget
        // shares the same screen coordinates behind it.
        if (clickBottomButton(click, doubled)) return true;
        // Scrollbar: dragging the thumb scrolls the input section. Clicking
        // the track outside the thumb jumps the thumb to that point first.
        if (button == 0 && isOverScrollbar(mouseX, mouseY)) {
            int viewBottom = this.height - BOTTOM_H;
            int viewportH  = viewBottom - CONTENT_TOP;
            int trackTop   = CONTENT_TOP + 2;
            int trackH     = viewportH - 4;
            int thumbH     = Math.max(10, trackH * viewportH / totalLogH);
            int maxScroll  = Math.max(1, totalLogH - viewportH);
            int thumbY     = trackTop + (int) ((long) (trackH - thumbH) * scrollY / maxScroll);
            if (mouseY >= thumbY && mouseY <= thumbY + thumbH) {
                scrollbarGrabOffset = mouseY - thumbY;
            } else {
                scrollbarGrabOffset = thumbH / 2.0;
                if (applyScrollbarDrag(mouseY)) clearAndInit();
            }
            scrollbarDragging = true;
            return true;
        }
        // Intercept left-clicks on numeric fields to support hold-to-drag.
        // Actual text-edit focus is deferred to mouseReleased if no drag occurred.
        if (button == 0) {
            TextFieldWidget hit = getNumericFieldAt(mouseX, mouseY);
            if (hit != null) {
                dragField    = hit;
                dragStartX   = mouseX;
                dragStartY   = mouseY;
                inDragMode   = false;
                dragAccumX   = 0;
                dragLastRawX = Double.NaN;
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    /** Dispatches a click straight to a bottom-row button if it lands on one,
     *  bypassing the children-iteration order so they always win their region. */
    private boolean clickBottomButton(net.minecraft.client.gui.Click click, boolean doubled) {
        for (ButtonWidget b : new ButtonWidget[]{ doneBtn, cancelBtn, removeBtn }) {
            if (b != null && b.isMouseOver(click.x(), click.y())) {
                return b.mouseClicked(click, doubled);
            }
        }
        return false;
    }

    private void playUiClickSound() {
        MinecraftClient.getInstance().getSoundManager().play(
                net.minecraft.client.sound.PositionedSoundInstance.master(
                        net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    /** Hold-to-drag is driven entirely by the raw cursor hook
     *  (cameramod$onRawCursorPos), which fires reliably on every GLFW cursor
     *  event. mouseDragged just consumes the event so the field's own
     *  text-selection-by-drag doesn't also react. */
    @Override
    public boolean mouseDragged(net.minecraft.client.gui.Click click, double deltaX, double deltaY) {
        if (scrollbarDragging && click.button() == 0) {
            if (applyScrollbarDrag(click.y())) clearAndInit();
            return true;
        }
        if (dragField != null && click.button() == 0) return true;
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.gui.Click click) {
        int button = click.button();
        if (scrollbarDragging && button == 0) {
            scrollbarDragging = false;
            return true;
        }
        if (dragField != null && button == 0) {
            boolean wasDragging = inDragMode;
            dragField    = null;
            inDragMode   = false;
            dragAccumX   = 0;
            dragLastRawX = Double.NaN;
            if (wasDragging) {
                // DISABLED → NORMAL: GLFW restores the cursor to the position
                // it held before the drag, i.e. back onto the field.
                GLFW.glfwSetInputMode(MinecraftClient.getInstance().getWindow().getHandle(),
                        GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            } else {
                // Short click — hand it to normal screen dispatch so the field
                // gets focused and text editing works as usual.
                super.mouseClicked(new net.minecraft.client.gui.Click(
                        dragStartX, dragStartY, click.buttonInfo()), false);
                return super.mouseReleased(click);
            }
            return true;
        }
        return super.mouseReleased(click);
    }

    // ─── Drag-field helpers ───────────────────────────────────────────────────

    private TextFieldWidget getNumericFieldAt(double x, double y) {
        TextFieldWidget[] fields = {
            posXField, posYField, posZField,
            yawField,  pitchField,
            offXField, offYField, offZField,
            zoomField
        };
        for (TextFieldWidget f : fields) {
            if (f != null && f.visible && f.isMouseOver(x, y)) return f;
        }
        return null;
    }

    private double getDragStep(TextFieldWidget field) {
        if (field == yawField || field == pitchField) return 1.0;
        if (field == zoomField)                       return 0.02;
        return 0.1; // position and offset fields
    }

    private boolean isThreeDecimalField(TextFieldWidget field) {
        return field == posXField || field == posYField || field == posZField
            || field == offXField || field == offYField || field == offZField;
    }

    private void syncFieldVar(TextFieldWidget field, String val) {
        if      (field == posXField)  posXVal  = val;
        else if (field == posYField)  posYVal  = val;
        else if (field == posZField)  posZVal  = val;
        else if (field == yawField)   yawVal   = val;
        else if (field == pitchField) pitchVal = val;
        else if (field == offXField)  offXVal  = val;
        else if (field == offYField)  offYVal  = val;
        else if (field == offZField)  offZVal  = val;
        else if (field == zoomField)  zoomVal  = val;
    }

    private void applyDragDelta(double dx) {
        if (dragField == null) return;
        double step = getDragStep(dragField);
        String raw = dragField.getText().trim();
        try {
            double val = Double.parseDouble(raw);
            val += dx * step;
            if (dragField == pitchField) val = Math.max(-90.0, Math.min(90.0, val));
            String newText = isThreeDecimalField(dragField) ? fmt3(val) : fmt2(val);
            suspendChangeListener = true;
            dragField.setText(newText);
            suspendChangeListener = false;
            syncFieldVar(dragField, newText);
            applyLive();
        } catch (NumberFormatException ignored) {}
    }

    // ─── Keyboard ────────────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
        int keyCode = input.key();
        if (inRotateMode) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) { exitRotateMode(); return true; }
            return true; // swallow other keys while rotating
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            doneAndClose(); return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F9 && !anyFieldFocused()) {
            // Block the tick-side detector from treating this still-held F9
            // as a fresh press once the screen has closed.
            CameramodClient.f9LockedOutUntilRelease = true;
            doneAndClose(); return true;
        }
        // Enter commits the focused text field's value and unfocuses it.
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && anyFieldFocused()) {
            syncTextFields();
            applyLive();
            setFocused(null);
            return true;
        }
        return super.keyPressed(input);
    }

    private boolean anyFieldFocused() {
        return (nameField         != null && nameField.isFocused())
            || (posXField         != null && posXField.isFocused())
            || (posYField         != null && posYField.isFocused())
            || (posZField         != null && posZField.isFocused())
            || (yawField          != null && yawField.isFocused())
            || (pitchField        != null && pitchField.isFocused())
            || (fixedTargetField  != null && fixedTargetField.isFocused())
            || (attachTargetField != null && attachTargetField.isFocused())
            || (offXField         != null && offXField.isFocused())
            || (offYField         != null && offYField.isFocused())
            || (offZField         != null && offZField.isFocused())
            || (zoomField         != null && zoomField.isFocused());
    }

    @Override
    public boolean shouldPause() { return false; }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private static String fmt3(double v) { return String.format(java.util.Locale.ROOT, "%.3f", v); }
    private static String fmt2(double v) { return String.format(java.util.Locale.ROOT, "%.2f", v); }
}
