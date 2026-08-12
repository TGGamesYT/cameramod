package dev.tggamesyt.cameramod.client.gui;

import dev.tggamesyt.cameramod.Cameramod;
import dev.tggamesyt.cameramod.CameraServerThing;
import dev.tggamesyt.cameramod.client.CameraRenderer;
import dev.tggamesyt.cameramod.client.CameramodClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CameraGuiScreen extends Screen {

    public enum Tab { CAMERAS, SETTINGS }

    private Tab currentTab;
    private boolean f9Suppressed = false;

    // Top-of-screen tab buttons. Kept as fields so clampSettingsWidgets can
    // skip them explicitly when hiding settings widgets that scroll past
    // GRID_TOP — they live above the scroll viewport and should never be
    // clipped or marked inactive by the clamp.
    private ButtonWidget tabCamerasBtn;
    private ButtonWidget tabSettingsBtn;

    // ─── Settings-tab layout data ────────────────────────────────────────────

    private record SettingRowInfo(String name, String desc, int labelX, int y, boolean hasLocal) {}
    private record SectionInfo(String title, int x, int y, int w) {}

    private final List<SettingRowInfo> settingRows     = new ArrayList<>();
    private final List<SectionInfo>   settingSections  = new ArrayList<>();
    private int settingsTotalH   = 0;
    private int settingsScrollY  = 0;

    private static final int SET_TOP      = 40;
    private static final int SET_ROW_H    = 20;
    private static final int SET_DESC_H   = 12;   // gap + 8px text
    private static final int SET_ROW_STEP = SET_ROW_H + SET_DESC_H + 4;
    private static final int SET_SEC_H    = 22;
    private static final int SET_SEC_GAP  = 8;    // extra vertical gap before each section
    private static final int SET_LABEL_W  = 200;
    private static final int SET_CTRL_W   = 60;
    private static final int SET_RESET_W  = 60;
    private static final int SET_GAP      = 8;

    // ─── Cameras-tab grid constants ─────────────────────────────────────────

    // Auto GUI scale on big screens (e.g. 2560×1440 fullscreen → scale 5/6,
    // so GUI width is only ~426-512 px). PREF_CARD_W=140 keeps 3 cards/row
    // fitting on 1080p auto-scale, and 2-3 cards/row on 1440p/4K auto-scale.
    private static final int PREF_CARD_W       = 140;
    private static final int MIN_CARD_W        = 110;
    private static final int CARD_TEXT_H       = 22;
    private static final int CARD_GAP          = 8;
    private static final int SIDE_MARGIN       = 8;
    private static final int GRID_TOP          = 38;
    private static final int GRID_BOTTOM_PAD   = 8;
    private static final int MAX_CARDS_PER_ROW = 3;

    // Right sidebar — vertical strip, button size and width scale with screen height.
    private static final int SIDEBAR_BTN_MIN  = 18;
    private static final int SIDEBAR_BTN_MAX  = 30;
    private static final int SIDEBAR_GAP_MIN  = 3;
    private static final int SIDEBAR_SIDE_PAD = 2;

    // Per-card active-indicator button (drawn in the text area, right-aligned)
    private static final int CARD_BTN_SIZE     = 14;

    // Tooltip hover delay in milliseconds
    private static final long TOOLTIP_DELAY_MS = 700L;

    // Static texture references (PNG files on the resource path)
    private static final Identifier TEX_ACTIVATOR =
            Identifier.of(Cameramod.MOD_ID, "textures/item/camera_activator.png");
    private static final Identifier TEX_REMOVER =
            Identifier.of(Cameramod.MOD_ID, "textures/item/camera_remover.png");
    private static final Identifier TEX_DISABLED_CAM =
            Identifier.of(Cameramod.MOD_ID, "textures/gui/disabled_camera.png");
    private static final Identifier TEX_PLUS =
            Identifier.of(Cameramod.MOD_ID, "textures/gui/plus_icon.png");
    private static final Identifier TEX_ATTACHER =
            Identifier.of(Cameramod.MOD_ID, "textures/item/camera_attacher.png");
    private static final Identifier TEX_ATTACHER_OFF =
            Identifier.of(Cameramod.MOD_ID, "textures/item/camera_attacher_disabled.png");
    private static final Identifier TEX_ATTACH_MODE_WORLD =
            Identifier.of(Cameramod.MOD_ID, "textures/gui/attach_mode_world.png");
    private static final Identifier TEX_ATTACH_MODE_HEAD =
            Identifier.of(Cameramod.MOD_ID, "textures/gui/attach_mode_head.png");
    private static final Identifier TEX_ATTACH_MODE_WORLD_OFF =
            Identifier.of(Cameramod.MOD_ID, "textures/gui/attach_mode_world_disabled.png");
    private static final Identifier TEX_ATTACH_MODE_HEAD_OFF =
            Identifier.of(Cameramod.MOD_ID, "textures/gui/attach_mode_head_disabled.png");
    // "Hide cameras" toggle: normal camera item when showing, semi-transparent
    // dashed-outline camera when hidden.
    private static final Identifier TEX_CAMERA_ITEM =
            Identifier.of(Cameramod.MOD_ID, "textures/item/camera_item.png");
    private static final Identifier TEX_HIDE_CAMERAS =
            Identifier.of(Cameramod.MOD_ID, "textures/gui/hidden_camera.png");
    // Camera fixer item texture: a tripod-camera on the left and a player figure
    // on the right. The name-tag buttons sample only the player figure region.
    private static final Identifier TEX_CAMERA_FIXER =
            Identifier.of(Cameramod.MOD_ID, "textures/item/camera_fixer.png");
    // Player-figure sub-region within the 64×64 camera_fixer.png (measured from the
    // texture: opaque pixels span x 36..59, y 13..50).
    private static final int FIGURE_U = 36, FIGURE_V = 13, FIGURE_W = 24, FIGURE_H = 38;

    // Total number of sidebar buttons (vertical strip on the right).
    private static final int SIDEBAR_BTN_COUNT = 7;

    // ─── Cameras-tab state ───────────────────────────────────────────────────

    private final List<CardEntry> cameraCards         = new ArrayList<>();
    private final Map<UUID, Identifier>              cardTextureIds      = new HashMap<>();
    private final Map<UUID, NativeImageBackedTexture> cardTextures       = new HashMap<>();
    private final Map<UUID, Integer>                 cardTextureVersions = new HashMap<>();
    private int scrollY = 0;

    private record CardEntry(UUID uuid, String name, byte[] frame, int frameVersion,
                             int frameW, int frameH) {}

    // Tooltip hover tracking (cameras tab)
    private String tooltipHoverId    = null;
    private long   tooltipHoverStart = 0L;

    // ─── Constructor ─────────────────────────────────────────────────────────

    public CameraGuiScreen(Tab initialTab) { this(initialTab, false); }

    public CameraGuiScreen(Tab initialTab, boolean f9Suppressed) {
        super(Text.literal("Cameramod"));
        this.currentTab   = initialTab;
        this.f9Suppressed = f9Suppressed;
    }

    // ─── Init ────────────────────────────────────────────────────────────────

    @Override
    protected void init() {
        int tabW = 100, tabH = 20, tabsY = 8, gap = 4;
        tabCamerasBtn = ButtonWidget.builder(Text.literal("Cameras"),
                b -> switchTab(Tab.CAMERAS))
                .dimensions(this.width / 2 - tabW - gap, tabsY, tabW, tabH).build();
        tabSettingsBtn = ButtonWidget.builder(Text.literal("Settings"),
                b -> switchTab(Tab.SETTINGS))
                .dimensions(this.width / 2 + gap, tabsY, tabW, tabH).build();
        tabCamerasBtn.active = currentTab != Tab.CAMERAS;
        tabSettingsBtn.active = currentTab != Tab.SETTINGS;
        addDrawableChild(tabCamerasBtn);
        addDrawableChild(tabSettingsBtn);

        if (currentTab == Tab.SETTINGS) {
            buildSettings();
        } else {
            purgeDeadCameras();
            rebuildCameraCards();
        }
    }

    private void switchTab(Tab t) {
        currentTab = t;
        scrollY = 0;
        settingsScrollY = 0;
        clearAndInit();
    }

    public Tab getCurrentTab() { return currentTab; }

    // ==================== Settings tab ====================

    private void buildSettings() {
        settingRows.clear();
        settingSections.clear();

        int totalW = SET_LABEL_W + SET_GAP + SET_CTRL_W + SET_GAP + SET_RESET_W;
        int xL = this.width / 2 - totalW / 2;
        int xC = xL + SET_LABEL_W + SET_GAP;
        int xR = xC + SET_CTRL_W + SET_GAP;
        int y  = SET_TOP - settingsScrollY;

        // ── Section: Image ───────────────────────────────────────────────────
        y += SET_SEC_GAP;
        settingSections.add(new SectionInfo("Image", xL, y, totalW));
        y += SET_SEC_H;

        addSettingRow(xL, xC, xR, y, "Mirror image",
                "Flip the camera output horizontally.",
                CameraRenderer::getCameraFlipped, CameraRenderer::getLocalFlipped, CameraRenderer::setLocalFlipped);
        y += SET_ROW_STEP;

        addSettingRow(xL, xC, xR, y, "Show entity name tags",
                "Display entity name labels in the camera view.",
                CameraRenderer::getCameraNameTags, CameraRenderer::getLocalNameTags, CameraRenderer::setLocalNameTags);
        y += SET_ROW_STEP;

        // ── Section: Overlays ────────────────────────────────────────────────
        y += SET_SEC_GAP;
        settingSections.add(new SectionInfo("Overlays", xL, y, totalW));
        y += SET_SEC_H;

        addSettingRow(xL, xC, xR, y, "Include chat in stream",
                "Overlay chat messages in the camera output.",
                CameraRenderer::getCameraSeesChat, CameraRenderer::getLocalSeesChat, CameraRenderer::setLocalSeesChat);
        y += SET_ROW_STEP;

        addSettingRow(xL, xC, xR, y, "Include open menus in stream",
                "Render open inventories and screens in the camera output.",
                CameraRenderer::getCameraShowPlayerGuis, CameraRenderer::getLocalShowPlayerGuis, CameraRenderer::setLocalShowPlayerGuis);
        y += SET_ROW_STEP;

        // ── Section: Controls ────────────────────────────────────────────────
        y += SET_SEC_GAP;
        settingSections.add(new SectionInfo("Controls", xL, y, totalW));
        y += SET_SEC_H;

        addSettingRow(xL, xC, xR, y, "F9 opens camera list",
                "On: pressing F9 opens this camera list screen. Off: F9 toggles a virtual camera-tool hotbar instead.",
                CameraRenderer::getCameraGuiMode, CameraRenderer::getLocalGuiMode, CameraRenderer::setLocalGuiMode);
        y += SET_ROW_STEP;

        // ── Section: Frame Rates ─────────────────────────────────────────────
        y += SET_SEC_GAP;
        settingSections.add(new SectionInfo("Frame Rates", xL, y, totalW));
        y += SET_SEC_H;

        addIntSettingRow(xL, xC, xR, y, "HTTP stream FPS",
                "Frames per second cap for the MJPEG stream (localhost:7236).",
                CameraRenderer::getStreamFps, CameraRenderer::getLocalStreamFps, CameraRenderer::setLocalStreamFps);
        y += SET_ROW_STEP;

        addIntSettingRow(xL, xC, xR, y, "Virtual webcam FPS",
                "Frames per second cap for the virtual webcam (SoftCam).",
                CameraRenderer::getVirtualFps, CameraRenderer::getLocalVirtualFps, CameraRenderer::setLocalVirtualFps);
        y += SET_ROW_STEP;

        settingsTotalH = (y + settingsScrollY) - SET_TOP + 8;
        clampSettingsWidgets();
    }

    /** Hides settings widgets that fall outside [GRID_TOP, height-GRID_BOTTOM_PAD]. */
    private void clampSettingsWidgets() {
        for (var el : this.children()) {
            if (!(el instanceof ClickableWidget w)) continue;
            // Tab buttons live above the scroll viewport and must never be
            // clamped — they're the only way to navigate away from this tab.
            // Identify by reference (y-based heuristic missed widgets that
            // scrolled into the same y-band).
            if (w == tabCamerasBtn || w == tabSettingsBtn) continue;
            boolean inView = (w.getY() >= GRID_TOP)
                    && (w.getY() + w.getHeight() <= this.height - GRID_BOTTOM_PAD);
            w.visible = inView;
            w.active  = w.active && inView;
        }
    }

    private void addSettingRow(int xL, int xC, int xR, int y,
                               String name, String desc,
                               BooleanSupplier effective,
                               Supplier<Boolean> localGetter,
                               Consumer<Boolean> localSetter) {
        boolean cur      = effective.getAsBoolean();
        boolean hasLocal = localGetter.get() != null;
        settingRows.add(new SettingRowInfo(name, desc, xL, y, hasLocal));

        ButtonWidget toggle = ButtonWidget.builder(Text.literal(cur ? "ON" : "OFF"), b -> {
            localSetter.accept(!cur);
            CameramodClient.saveClientConfig();
            clearAndInit();
        }).dimensions(xC, y, SET_CTRL_W, SET_ROW_H).build();
        addDrawableChild(toggle);

        ButtonWidget reset = ButtonWidget.builder(Text.literal("Reset"), b -> {
            localSetter.accept(null);
            CameramodClient.saveClientConfig();
            clearAndInit();
        }).dimensions(xR, y, SET_RESET_W, SET_ROW_H).build();
        reset.active = hasLocal;
        addDrawableChild(reset);
    }

    private void addIntSettingRow(int xL, int xC, int xR, int y,
                                  String name, String desc,
                                  java.util.function.IntSupplier effective,
                                  Supplier<Integer> localGetter,
                                  Consumer<Integer> localSetter) {
        boolean hasLocal = localGetter.get() != null;
        settingRows.add(new SettingRowInfo(name, desc, xL, y, hasLocal));

        net.minecraft.client.gui.widget.TextFieldWidget field =
                new net.minecraft.client.gui.widget.TextFieldWidget(
                        textRenderer, xC, y, SET_CTRL_W, SET_ROW_H, Text.empty());
        field.setMaxLength(4);
        field.setText(Integer.toString(effective.getAsInt()));
        field.setChangedListener(t -> {
            if (t == null || t.isEmpty()) return;
            try {
                int v = Integer.parseInt(t.trim());
                if (v < 1 || v > 240) return;
                localSetter.accept(v);
                CameramodClient.saveClientConfig();
            } catch (NumberFormatException ignored) {}
        });
        addDrawableChild(field);

        ButtonWidget reset = ButtonWidget.builder(Text.literal("Reset"), b -> {
            localSetter.accept(null);
            CameramodClient.saveClientConfig();
            clearAndInit();
        }).dimensions(xR, y, SET_RESET_W, SET_ROW_H).build();
        reset.active = hasLocal;
        addDrawableChild(reset);
    }

    // ==================== Cameras tab ====================

    private void rebuildCameraCards() {
        cameraCards.clear();
        for (CameramodClient.TrackedCamera tc : CameramodClient.TRACKED_CAMERAS.values()) {
            // Hide cards for cameras whose entity isn't currently loaded.
            // Persistent purge happens in purgeDeadCameras() below; this just
            // keeps transient absences (chunk briefly out of range) from
            // showing ghost cards in the list.
            if (!CameramodClient.isTrackedCameraAlive(tc.uuid)) continue;
            cameraCards.add(new CardEntry(tc.uuid, tc.name, tc.frame, tc.frameVersion,
                    tc.frameW, tc.frameH));
        }
    }

    /** Drops tracked cameras whose entity is no longer loaded anywhere we can
     *  see, and persists the result. Called on screen open / tab switch — not
     *  per-frame, so a transient chunk unload won't permanently delete the
     *  entry between frames. */
    private void purgeDeadCameras() {
        java.util.Iterator<Map.Entry<UUID, CameramodClient.TrackedCamera>> it =
                CameramodClient.TRACKED_CAMERAS.entrySet().iterator();
        boolean purged = false;
        while (it.hasNext()) {
            Map.Entry<UUID, CameramodClient.TrackedCamera> e = it.next();
            if (!CameramodClient.isTrackedCameraAlive(e.getKey())) {
                it.remove();
                purged = true;
            }
        }
        if (purged) CameramodClient.saveCurrentTrackedState();
    }

    // ── Grid geometry ────────────────────────────────────────────────────────

    private int gridAvailableWidth() {
        return this.width - 2 * SIDE_MARGIN - sidebarW() - SIDEBAR_SIDE_PAD;
    }

    private int cardsPerRow() {
        int avail = gridAvailableWidth() + CARD_GAP;
        return Math.min(MAX_CARDS_PER_ROW, Math.max(1, avail / (PREF_CARD_W + CARD_GAP)));
    }

    private int currentCardW() {
        int n = cardsPerRow();
        return Math.max(MIN_CARD_W, (gridAvailableWidth() - (n - 1) * CARD_GAP) / n);
    }

    private int currentCardImgH() { return (currentCardW() * 9) / 16; }
    private int currentCardH()    { return currentCardImgH() + CARD_TEXT_H; }

    private int gridLeftMargin() {
        int n      = cardsPerRow();
        int cw     = currentCardW();
        int totalW = n * cw + (n - 1) * CARD_GAP;
        return SIDE_MARGIN + Math.max(0, (gridAvailableWidth() - totalW) / 2);
    }

    private int cardX(int idx) {
        return gridLeftMargin() + (idx % cardsPerRow()) * (currentCardW() + CARD_GAP);
    }

    private int cardY(int idx) {
        return GRID_TOP + (idx / cardsPerRow()) * (currentCardH() + CARD_GAP) - scrollY;
    }

    private int totalGridHeight() {
        if (cameraCards.isEmpty()) return 0;
        int rows = (cameraCards.size() + cardsPerRow() - 1) / cardsPerRow();
        return rows * currentCardH() + (rows - 1) * CARD_GAP;
    }

    // ── Sidebar geometry (vertical strip on the right) ───────────────────────

    private int sidebarBtnSize() {
        // Scale with available screen height; cap so buttons aren't huge.
        int viewH = this.height - GRID_TOP - GRID_BOTTOM_PAD;
        return Math.max(SIDEBAR_BTN_MIN, Math.min(SIDEBAR_BTN_MAX, viewH / 14));
    }
    private int sidebarW()    { return sidebarBtnSize() + 2 * SIDEBAR_SIDE_PAD; }
    private int sidebarX()    { return this.width - sidebarW(); }
    private int sidebarBtnX() { return sidebarX() + SIDEBAR_SIDE_PAD; }
    private int sidebarBtnY(int index) {
        // SIDEBAR_BTN_COUNT buttons equally spaced top-to-bottom in the sidebar.
        int sz   = sidebarBtnSize();
        int viewH = this.height - GRID_TOP - GRID_BOTTOM_PAD;
        int gap  = Math.max(SIDEBAR_GAP_MIN, (viewH - SIDEBAR_BTN_COUNT * sz) / (SIDEBAR_BTN_COUNT + 1));
        return GRID_TOP + gap + index * (sz + gap);
    }
    private int gridBottomEdge() { return this.height - GRID_BOTTOM_PAD; }

    private boolean isOnSidebarBtn(int index, double mx, double my) {
        int bx = sidebarBtnX(), by = sidebarBtnY(index), sz = sidebarBtnSize();
        return mx >= bx && mx < bx + sz && my >= by && my < by + sz;
    }

    // ── Per-card active button ───────────────────────────────────────────────

    private int cardBtnX(int i) { return cardX(i) + currentCardW() - CARD_BTN_SIZE - 3; }
    private int cardBtnY(int i) { return cardY(i) + currentCardImgH() + (CARD_TEXT_H - CARD_BTN_SIZE) / 2; }

    private boolean isOnCardBtn(int i, double mx, double my) {
        int bx = cardBtnX(i), by = cardBtnY(i);
        return mx >= bx && mx < bx + CARD_BTN_SIZE && my >= by && my < by + CARD_BTN_SIZE;
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private void toggleCameraActive(UUID uuid) {
        boolean isActive = uuid.equals(CameraRenderer.getBoundCameraUuid())
                && CameraRenderer.isStreamingEnabled();
        if (CameramodClient.serverHasMod) {
            UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 1, (byte) 1, uuid, nil, 0, 0, 0, (byte) 0));
        } else {
            if (isActive) {
                CameraRenderer.clearBoundCamera();
                CameraRenderer.setStreamingEnabled(false);
            } else {
                CameraRenderer.setBoundCamera(uuid);
                CameraRenderer.setStreamingEnabled(true);
                CameramodClient.addTrackedCamera(uuid);
            }
        }
    }

    private void toggleActivatorOnAir() {
        // Sidebar streaming toggle is a hard reset: enabling always starts at
        // player POV, never resumes a previously bound camera. (The Activator
        // item's own toggle still restores the saved cam — different surface,
        // different behavior.)
        if (CameramodClient.serverHasMod) {
            UUID nil = CameraServerThing.CameraItemUseC2SPayload.NIL;
            ClientPlayNetworking.send(new CameraServerThing.CameraItemUseC2SPayload(
                    (byte) 1, (byte) 2, nil, nil, 0, 0, 0, (byte) 0));
        } else {
            boolean wasOn = CameraRenderer.isStreamingEnabled();
            CameraRenderer.clearBoundCamera();
            CameraRenderer.setStreamingEnabled(!wasOn);
        }
    }

    // ─── Mouse interaction ────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (currentTab == Tab.CAMERAS && button == 0) {
            // Sidebar buttons (outside grid scissor area, no scroll needed)
            if (isOnSidebarBtn(0, mouseX, mouseY)) {
                toggleActivatorOnAir();
                return true;
            }
            if (isOnSidebarBtn(1, mouseX, mouseY)) {
                UUID newCam = CameramodClient.spawnCameraAtPlayerFromGui();
                if (newCam != null) {
                    // Don't open the edit screen until the entity exists client-
                    // side (server spawn round-trip) — otherwise its position/
                    // rotation fields would be empty. The tick loop opens it.
                    CameramodClient.requestEditScreenWhenReady(newCam);
                }
                return true;
            }
            if (isOnSidebarBtn(2, mouseX, mouseY)) {
                CameramodClient.cycleAttachModeOnLastAttached();
                return true;
            }
            if (isOnSidebarBtn(3, mouseX, mouseY)) {
                CameramodClient.toggleAttachLastToPlayer();
                return true;
            }
            if (isOnSidebarBtn(4, mouseX, mouseY)) {
                CameraRenderer.setHideCameraModels(!CameraRenderer.isHideCameraModels());
                CameramodClient.saveClientConfig();
                return true;
            }
            // Button 5: hide camera entity name tags in THIS player's view.
            if (isOnSidebarBtn(5, mouseX, mouseY)) {
                CameraRenderer.setHideCameraNameTagsForPlayers(
                        !CameraRenderer.isHideCameraNameTagsForPlayers());
                CameramodClient.saveClientConfig();
                return true;
            }
            // Button 6: hide entity/player name tags inside the camera stream
            // (toggles the global "show name tags in camera" override).
            if (isOnSidebarBtn(6, mouseX, mouseY)) {
                CameraRenderer.setLocalNameTags(!CameraRenderer.getCameraNameTags());
                CameramodClient.saveClientConfig();
                return true;
            }

            // Card interactions (only within the visible viewport)
            if (mouseY >= GRID_TOP && mouseY < gridBottomEdge()) {
                int cw = currentCardW(), ch = currentCardH();
                for (int i = 0; i < cameraCards.size(); i++) {
                    int cx = cardX(i), cy = cardY(i);
                    if (mouseX < cx || mouseX >= cx + cw || mouseY < cy || mouseY >= cy + ch) continue;
                    // Per-card active button takes priority over card click
                    if (isOnCardBtn(i, mouseX, mouseY)) {
                        toggleCameraActive(cameraCards.get(i).uuid());
                        return true;
                    }
                    this.client.setScreen(new EditCameraScreen(this, cameraCards.get(i).uuid()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (currentTab == Tab.CAMERAS) {
            int viewportH = gridBottomEdge() - GRID_TOP;
            int max = Math.max(0, totalGridHeight() - viewportH);
            if (max > 0) {
                scrollY -= (int) (vertical * 24);
                scrollY = Math.max(0, Math.min(max, scrollY));
                return true;
            }
        } else if (currentTab == Tab.SETTINGS) {
            int viewportH = this.height - GRID_TOP - GRID_BOTTOM_PAD;
            int max = Math.max(0, settingsTotalH - viewportH);
            if (max > 0) {
                settingsScrollY -= (int) (vertical * 24);
                settingsScrollY = Math.max(0, Math.min(max, settingsScrollY));
                clearAndInit();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    // ─── Card texture management ──────────────────────────────────────────────

    private NativeImageBackedTexture ensureCardTexture(CardEntry card) {
        if (card.frame() == null) return null;
        Integer cachedVer = cardTextureVersions.get(card.uuid());
        NativeImageBackedTexture tex = cardTextures.get(card.uuid());
        if (tex != null && cachedVer != null && cachedVer == card.frameVersion()) return tex;

        int w = card.frameW() > 0 ? card.frameW() : Cameramod.camwidth;
        int h = card.frameH() > 0 ? card.frameH() : Cameramod.camheight;
        if (card.frame().length < w * h * 3) return null;

        if (tex != null && (tex.getImage().getWidth() != w || tex.getImage().getHeight() != h)) {
            Identifier old = cardTextureIds.remove(card.uuid());
            if (old != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(old);
            cardTextures.remove(card.uuid());
            tex = null;
        }

        NativeImage img;
        Identifier id;
        if (tex == null) {
            img = new NativeImage(w, h, false);
            id  = Identifier.of(Cameramod.MOD_ID, "card_" + card.uuid().toString().replace("-", ""));
            final NativeImage fi = img;
            tex = new NativeImageBackedTexture(() -> "cameramod card " + card.uuid(), fi);
            MinecraftClient.getInstance().getTextureManager().registerTexture(id, tex);
            cardTextureIds.put(card.uuid(), id);
            cardTextures.put(card.uuid(), tex);
        } else {
            img = tex.getImage();
            id  = cardTextureIds.get(card.uuid());
        }

        int rowLen = w * 3;
        byte[] f = card.frame();
        for (int ty = 0; ty < h; ty++) {
            int base = ty * rowLen;
            for (int x = 0; x < w; x++) {
                int i2 = base + x * 3;
                int b2 = f[i2]     & 0xFF;
                int g2 = f[i2 + 1] & 0xFF;
                int r2 = f[i2 + 2] & 0xFF;
                img.setColorArgb(x, ty, 0xFF000000 | (r2 << 16) | (g2 << 8) | b2);
            }
        }
        tex.upload();
        cardTextureVersions.put(card.uuid(), card.frameVersion());
        return tex;
    }

    // ─── Draw helpers ─────────────────────────────────────────────────────────

    private void drawCard(DrawContext ctx, CardEntry card, int x, int y, int mouseX, int mouseY) {
        int cw   = currentCardW();
        int imgH = currentCardImgH();
        int ch   = currentCardH();
        boolean hover = mouseX >= x && mouseX < x + cw && mouseY >= y && mouseY < y + ch;
        ctx.fill(x - 1, y - 1, x + cw + 1, y + ch + 1, 0xFF606060);
        ctx.fill(x, y, x + cw, y + ch, hover ? 0xFF454545 : 0xFF2A2A2A);

        // Camera preview image
        Identifier texId = cardTextureIds.get(card.uuid());
        NativeImageBackedTexture tex = ensureCardTexture(card);
        if (tex != null && texId != null) {
            int fw = card.frameW() > 0 ? card.frameW() : Cameramod.camwidth;
            int fh = card.frameH() > 0 ? card.frameH() : Cameramod.camheight;
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, texId, x, y, 0f, 0f, cw, imgH, fw, fh, fw, fh);
        } else {
            ctx.drawCenteredTextWithShadow(textRenderer, "No preview yet",
                    x + cw / 2, y + imgH / 2 - 4, 0xFFAAAAAA);
        }

        // Camera name (left-aligned with padding, right-padded for button)
        String name = (card.name() == null || card.name().isEmpty()) ? "Camera" : card.name();
        int nameMaxW = cw - CARD_BTN_SIZE - 8;
        ctx.drawTextWithShadow(textRenderer,
                textRenderer.trimToWidth(name, nameMaxW),
                x + 4, y + imgH + (CARD_TEXT_H - 8) / 2, 0xFFFFFFFF);

        // Per-card active/inactive indicator button
        boolean isActive = card.uuid().equals(CameraRenderer.getBoundCameraUuid())
                && CameraRenderer.isStreamingEnabled();
        int bx = cardBtnX(indexOf(card));
        int by = cardBtnY(indexOf(card));
        Identifier btnTex = isActive ? TEX_ACTIVATOR : TEX_DISABLED_CAM;
        ctx.fill(bx - 1, by - 1, bx + CARD_BTN_SIZE + 1, by + CARD_BTN_SIZE + 1,
                isActive ? 0xFF336633 : 0xFF444444);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, btnTex,
                bx, by, 0f, 0f, CARD_BTN_SIZE, CARD_BTN_SIZE, 64, 64, 64, 64);
    }

    private int indexOf(CardEntry card) {
        for (int i = 0; i < cameraCards.size(); i++)
            if (cameraCards.get(i).uuid().equals(card.uuid())) return i;
        return 0;
    }

    private void drawSidebar(DrawContext ctx, int mouseX, int mouseY) {
        int sx = sidebarX();
        // Sidebar background: separator line on left, then the strip
        ctx.fill(sx - 1, GRID_TOP, sx, this.height - GRID_BOTTOM_PAD, 0xFF606060);
        ctx.fill(sx, GRID_TOP, this.width, this.height - GRID_BOTTOM_PAD, 0xFF1E1E1E);

        boolean streaming = CameraRenderer.isStreamingEnabled();

        // Button 0: activator (streaming on) or remover (streaming off)
        drawSidebarBtnTex(ctx, mouseX, mouseY, 0,
                streaming ? TEX_ACTIVATOR : TEX_REMOVER,
                streaming ? 0xFF224422 : 0xFF442222);

        // Button 1: spawn camera at player (plus icon)
        drawSidebarBtnTex(ctx, mouseX, mouseY, 1, TEX_PLUS, 0xFF223344);

        // Button 2: cycle attach mode (world ↔ head). Greyed out when nothing
        // is currently attached to the player.
        boolean attached = CameramodClient.isLastAttachedCurrentlyAttached();
        byte mode = currentAttachModeForDisplay();
        Identifier modeTex;
        if (attached) {
            modeTex = mode == 1 ? TEX_ATTACH_MODE_HEAD : TEX_ATTACH_MODE_WORLD;
        } else {
            modeTex = mode == 1 ? TEX_ATTACH_MODE_HEAD_OFF : TEX_ATTACH_MODE_WORLD_OFF;
        }
        drawSidebarBtnTex(ctx, mouseX, mouseY, 2, modeTex,
                attached ? 0xFF443322 : 0xFF2A2A2A);

        // Button 3: toggle attach-to-player. Greyed out when nothing is attached.
        drawSidebarBtnTex(ctx, mouseX, mouseY, 3,
                attached ? TEX_ATTACHER : TEX_ATTACHER_OFF,
                attached ? 0xFF334422 : 0xFF2A2A2A);

        // Button 4: hide/show camera 3D models for this client. When hidden,
        // show the dashed semi-transparent camera; when showing, the normal one.
        boolean hidden = CameraRenderer.isHideCameraModels();
        drawSidebarBtnTex(ctx, mouseX, mouseY, 4,
                hidden ? TEX_HIDE_CAMERAS : TEX_CAMERA_ITEM,
                hidden ? 0xFF332244 : 0xFF2A2A2A);

        // Button 5: hide camera name tags for players (the camera-list player's
        // own view). Icon: the camera item; when name tags ARE shown it's drawn
        // squeezed with a little morse-code "tg" name tag above it, when hidden
        // it's the plain camera item.
        boolean camTagsHidden = CameraRenderer.isHideCameraNameTagsForPlayers();
        drawSidebarNameTagBtn(ctx, mouseX, mouseY, 5,
                TEX_CAMERA_ITEM, 0, 0, 64, 64,
                !camTagsHidden,
                camTagsHidden ? 0xFF332244 : 0xFF2A2A2A);

        // Button 6: hide player/entity name tags inside the camera stream. Same
        // icon design but the player figure (from the camera fixer texture)
        // instead of the camera item.
        boolean camViewTagsShown = CameraRenderer.getCameraNameTags();
        drawSidebarNameTagBtn(ctx, mouseX, mouseY, 6,
                TEX_CAMERA_FIXER, FIGURE_U, FIGURE_V, FIGURE_W, FIGURE_H,
                camViewTagsShown,
                camViewTagsShown ? 0xFF2A2A2A : 0xFF332244);
    }

    /**
     * Draws a sidebar button whose icon is a figure (sampled from {@code tex} at
     * {@code u,v,rw,rh} of a 64×64 texture) that optionally wears a tiny name tag.
     * The figure ALWAYS aspect-fits the full inner button area (centered, never
     * stretched). When {@code withTag} a thin dark name-tag bar with morse code
     * "tg" is overlaid across the top of the icon (like a vanilla floating name),
     * without shrinking the figure.
     */
    private void drawSidebarNameTagBtn(DrawContext ctx, int mouseX, int mouseY, int index,
                                       Identifier tex, int u, int v, int rw, int rh,
                                       boolean withTag, int bgColor) {
        int bx = sidebarBtnX();
        int by = sidebarBtnY(index);
        int sz = sidebarBtnSize();
        drawSidebarBtnBg(ctx, mouseX, mouseY, index, bx, by, bgColor);

        int innerX = bx + 1;
        int innerY = by + 1;
        int innerW = sz - 2;
        int innerH = sz - 2;

        // Aspect-fit the figure into the full inner area, centered on both axes.
        int[] fit = fitRegion(rw, rh, innerW, innerH);
        int figX = innerX + fit[0];
        int figY = innerY + fit[1];
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex,
                figX, figY, (float) u, (float) v, fit[2], fit[3], rw, rh, 64, 64);

        if (!withTag) return;

        // Name-tag bar overlaid across the top of the icon. Sized/positioned
        // from the button's inner area (NOT the figure's drawn width) so it
        // looks identical on every name-tag button regardless of whether the
        // icon is a square camera item or a narrow pillarboxed player figure.
        int tagH = Math.max(3, innerH / 4);
        int tagX0 = innerX;
        int tagX1 = innerX + innerW;
        int tagY0 = innerY;
        int tagY1 = innerY + tagH;
        ctx.fill(tagX0, tagY0, tagX1, tagY1, 0xCC000000);
        drawMorseTg(ctx, tagX0 + 1, tagY0 + 1, tagX1 - 1, tagY1 - 1, 0xFFFFFFFF);
    }

    /**
     * Returns [offsetX, offsetY, drawW, drawH] to center a {@code srcW × srcH}
     * region within a {@code dstW × dstH} area while preserving the source
     * aspect ratio (letter/pillarbox, never stretch).
     */
    private static int[] fitRegion(int srcW, int srcH, int dstW, int dstH) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0)
            return new int[]{0, 0, Math.max(1, dstW), Math.max(1, dstH)};
        int fitW, fitH;
        if ((long) srcW * dstH > (long) srcH * dstW) {
            fitW = dstW;
            fitH = Math.max(1, srcH * dstW / srcW);
        } else {
            fitH = dstH;
            fitW = Math.max(1, srcW * dstH / srcH);
        }
        return new int[]{(dstW - fitW) / 2, (dstH - fitH) / 2, fitW, fitH};
    }

    /**
     * Draws the morse code for "tg" — T = "−", G = "−−·" — as white marks across
     * the given rectangle: dash, [letter gap], dash, dash, dot. Dashes are 3 units
     * and the dot 1 unit, but at sidebar-button scale the dashes are clamped to a
     * minimum 2px so they never round down to look like dots (which made the whole
     * thing read as ". .-" before).
     */
    private void drawMorseTg(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        int w = x1 - x0;
        if (w <= 0 || y1 <= y0) return;
        // Marks in order: T(−), then G(− − ·). dash=true, dot=false.
        final boolean[] dash      = {true, true, true, false};
        // Gap (in units) BEFORE each mark: 0 first, 2 = letter gap, 1 = intra-letter.
        final int[]     gapBefore = {0,    2,    1,    1};
        int totalUnits = 0;
        for (int i = 0; i < dash.length; i++) totalUnits += gapBefore[i] + (dash[i] ? 3 : 1);
        double uw = (double) w / totalUnits;
        double cx = x0;
        for (int i = 0; i < dash.length; i++) {
            cx += gapBefore[i] * uw;
            int units = dash[i] ? 3 : 1;
            int xa = (int) Math.round(cx);
            int xb = (int) Math.round(cx + units * uw);
            int minW = dash[i] ? 2 : 1;          // dashes stay visibly long
            if (xb - xa < minW) xb = xa + minW;
            if (xb > x1) xb = x1;
            ctx.fill(xa, y0, xb, y1, color);
            cx += units * uw;
        }
    }

    /** Mode of the lastAttached camera if found, else 0 (World). */
    private byte currentAttachModeForDisplay() {
        if (CameramodClient.lastAttachedToPlayerCameraUuid == null) return 0;
        var mc = MinecraftClient.getInstance();
        var cam = CameramodClient.findCameraByUuid(
                CameramodClient.lastAttachedToPlayerCameraUuid, mc);
        return cam == null ? 0 : cam.getAttachMode();
    }

    private void drawSidebarBtnTex(DrawContext ctx, int mouseX, int mouseY, int index,
                                   Identifier tex, int bgColor) {
        int bx = sidebarBtnX();
        int by = sidebarBtnY(index);
        int sz = sidebarBtnSize();
        drawSidebarBtnBg(ctx, mouseX, mouseY, index, bx, by, bgColor);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex,
                bx + 1, by + 1, 0f, 0f,
                sz - 2, sz - 2,
                64, 64, 64, 64);
    }

    private void drawSidebarBtnBg(DrawContext ctx, int mouseX, int mouseY, int index,
                                  int bx, int by, int bgColor) {
        boolean hover = isOnSidebarBtn(index, mouseX, mouseY);
        int sz = sidebarBtnSize();
        ctx.fill(bx - 1, by - 1, bx + sz + 1, by + sz + 1,
                hover ? 0xFF808080 : 0xFF505050);
        ctx.fill(bx, by, bx + sz, by + sz, bgColor);
    }

    private void drawSidebarTooltips(DrawContext ctx, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        int hoveredIndex = -1;
        for (int i = 0; i < SIDEBAR_BTN_COUNT; i++) {
            if (isOnSidebarBtn(i, mouseX, mouseY)) { hoveredIndex = i; break; }
        }

        if (hoveredIndex < 0) {
            tooltipHoverId = null;
            return;
        }
        String hoverId = "sb" + hoveredIndex;
        if (!hoverId.equals(tooltipHoverId)) {
            tooltipHoverId    = hoverId;
            tooltipHoverStart = now;
            return;
        }
        if (now - tooltipHoverStart < TOOLTIP_DELAY_MS) return;

        String tip;
        boolean attached = CameramodClient.isLastAttachedCurrentlyAttached();
        byte mode        = currentAttachModeForDisplay();
        switch (hoveredIndex) {
            case 0  -> tip = CameraRenderer.isStreamingEnabled() ? "Stop streaming" : "Start streaming";
            case 1  -> tip = "Add camera at player";
            case 2  -> {
                String modeName = mode == 1 ? "Orbit (head)" : "Fixed (world)";
                tip = attached
                        ? "Attach mode: " + modeName + " — click to switch"
                        : "Attach mode: " + modeName + " (no camera attached)";
            }
            case 3  -> tip = attached
                        ? "Detach camera from you"
                        : (CameramodClient.lastAttachedToPlayerCameraUuid != null
                                ? "Re-attach last camera to you"
                                : "No camera to attach");
            case 4  -> tip = CameraRenderer.isHideCameraModels()
                        ? "Camera models hidden — click to show"
                        : "Hide camera models";
            case 5  -> tip = CameraRenderer.isHideCameraNameTagsForPlayers()
                        ? "Camera name tags hidden from you — click to show"
                        : "Hide camera name tags from you";
            case 6  -> tip = CameraRenderer.getCameraNameTags()
                        ? "Name tags shown in camera — click to hide"
                        : "Name tags hidden in camera — click to show";
            default -> { return; }
        }

        int tw = textRenderer.getWidth(tip) + 8;
        int th = 14;
        // Tooltip to the left of the button, vertically centered on it
        int tx = sidebarX() - tw - 4;
        int ty = sidebarBtnY(hoveredIndex) + (sidebarBtnSize() - th) / 2;
        ctx.fill(tx - 1, ty - 1, tx + tw + 1, ty + th + 1, 0xFF606060);
        ctx.fill(tx, ty, tx + tw, ty + th, 0xFF101010);
        ctx.drawTextWithShadow(textRenderer, tip, tx + 4, ty + 3, 0xFFFFFFFF);
    }

    // ─── Render ───────────────────────────────────────────────────────────────

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        if (currentTab == Tab.SETTINGS) {
            renderSettings(ctx);
            return;
        }

        // ── Cameras tab ─────────────────────────────────────────────────────
        rebuildCameraCards();

        if (cameraCards.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    "No cameras tracked yet — bind a camera to see it here",
                    (this.width - sidebarW()) / 2, this.height / 2, 0xFFAAAAAA);
            drawSidebar(ctx, mouseX, mouseY);
            return;
        }

        int ch = currentCardH();
        ctx.enableScissor(0, GRID_TOP, sidebarX() - SIDEBAR_SIDE_PAD, gridBottomEdge());
        for (int i = 0; i < cameraCards.size(); i++) {
            int cx = cardX(i), cy = cardY(i);
            if (cy + ch < GRID_TOP || cy > gridBottomEdge()) continue;
            drawCard(ctx, cameraCards.get(i), cx, cy, mouseX, mouseY);
        }
        ctx.disableScissor();

        drawSidebar(ctx, mouseX, mouseY);
        drawSidebarTooltips(ctx, mouseX, mouseY);
    }

    private void renderSettings(DrawContext ctx) {
        ctx.drawCenteredTextWithShadow(textRenderer, "Cameramod settings",
                this.width / 2, 30, 0xFFFFFFFF);

        ctx.enableScissor(0, GRID_TOP, this.width, this.height - GRID_BOTTOM_PAD);

        // Section headers: title + horizontal line
        for (SectionInfo sec : settingSections) {
            ctx.drawTextWithShadow(textRenderer, sec.title(), sec.x() + 2, sec.y() + 4, 0xFFCCCCCC);
            ctx.fill(sec.x(), sec.y() + 15, sec.x() + sec.w(), sec.y() + 16, 0xFF505050);
        }

        // Row labels, local/server tag, description
        for (SettingRowInfo row : settingRows) {
            int labelY = row.y() + (SET_ROW_H - 8) / 2;
            ctx.drawTextWithShadow(textRenderer, row.name(), row.labelX(), labelY, 0xFFFFFFFF);
            String tag = row.hasLocal() ? "(local)" : "(server)";
            int tagColor = row.hasLocal() ? 0xFFAABBFF : 0xFF666666;
            ctx.drawTextWithShadow(textRenderer, tag,
                    row.labelX() + SET_LABEL_W - textRenderer.getWidth(tag) - 2, labelY, tagColor);
            ctx.drawTextWithShadow(textRenderer, row.desc(),
                    row.labelX() + 2, row.y() + SET_ROW_H + 2, 0xFF888888);
        }

        ctx.disableScissor();
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void removed() {
        var tm = MinecraftClient.getInstance().getTextureManager();
        for (Map.Entry<UUID, Identifier> e : cardTextureIds.entrySet())
            tm.destroyTexture(e.getValue());
        cardTextureIds.clear();
        cardTextures.clear();
        cardTextureVersions.clear();
        super.removed();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F9) {
            if (f9Suppressed) return true;
            CameramodClient.f9LockedOutUntilRelease = true;
            this.close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_F9) f9Suppressed = false;
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() { return false; }
}
