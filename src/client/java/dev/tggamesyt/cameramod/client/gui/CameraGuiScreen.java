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

    // Right sidebar (fixed panel with action buttons) — thin column,
    // about a small-button width plus a couple of pixels on each side.
    private static final int SIDEBAR_W         = 22;
    private static final int SIDEBAR_GAP       = 2;
    private static final int SIDEBAR_BTN_SIZE  = 18;
    private static final int SIDEBAR_BTN_PAD   = 3;

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
        return this.width - 2 * SIDE_MARGIN - SIDEBAR_W - SIDEBAR_GAP;
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

    // ── Sidebar geometry ─────────────────────────────────────────────────────

    private int sidebarX()              { return this.width - SIDEBAR_W; }
    private int sidebarBtnX()           { return sidebarX() + (SIDEBAR_W - SIDEBAR_BTN_SIZE) / 2; }
    private int sidebarBtnY(int index)  { return GRID_TOP + SIDEBAR_BTN_PAD + index * (SIDEBAR_BTN_SIZE + SIDEBAR_BTN_PAD); }

    private boolean isOnSidebarBtn(int index, double mx, double my) {
        int bx = sidebarBtnX(), by = sidebarBtnY(index);
        return mx >= bx && mx < bx + SIDEBAR_BTN_SIZE && my >= by && my < by + SIDEBAR_BTN_SIZE;
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

            // Card interactions (only within the visible viewport)
            if (mouseY >= GRID_TOP && mouseY < this.height - GRID_BOTTOM_PAD) {
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
            int viewportH = this.height - GRID_TOP - GRID_BOTTOM_PAD;
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
        String name = card.name() == null ? "Camera" : card.name();
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
        // Sidebar background
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
        drawSidebarBtnBg(ctx, mouseX, mouseY, index, bx, by, bgColor);
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, tex,
                bx + 1, by + 1, 0f, 0f,
                SIDEBAR_BTN_SIZE - 2, SIDEBAR_BTN_SIZE - 2,
                64, 64, 64, 64);
    }

    private void drawSidebarBtnBg(DrawContext ctx, int mouseX, int mouseY, int index,
                                  int bx, int by, int bgColor) {
        boolean hover = isOnSidebarBtn(index, mouseX, mouseY);
        ctx.fill(bx - 1, by - 1, bx + SIDEBAR_BTN_SIZE + 1, by + SIDEBAR_BTN_SIZE + 1,
                hover ? 0xFF808080 : 0xFF505050);
        ctx.fill(bx, by, bx + SIDEBAR_BTN_SIZE, by + SIDEBAR_BTN_SIZE, bgColor);
    }

    private void drawSidebarTooltips(DrawContext ctx, int mouseX, int mouseY) {
        long now = System.currentTimeMillis();
        int hoveredIndex = -1;
        if      (isOnSidebarBtn(0, mouseX, mouseY)) hoveredIndex = 0;
        else if (isOnSidebarBtn(1, mouseX, mouseY)) hoveredIndex = 1;
        else if (isOnSidebarBtn(2, mouseX, mouseY)) hoveredIndex = 2;
        else if (isOnSidebarBtn(3, mouseX, mouseY)) hoveredIndex = 3;

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
            default -> { return; }
        }

        int tw = textRenderer.getWidth(tip) + 8;
        int th = 14;
        int tx = sidebarX() - tw - 4;
        int ty = sidebarBtnY(hoveredIndex) + (SIDEBAR_BTN_SIZE - th) / 2;
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
                    this.width / 2, this.height / 2, 0xFFAAAAAA);
            drawSidebar(ctx, mouseX, mouseY);
            return;
        }

        int ch = currentCardH();
        ctx.enableScissor(0, GRID_TOP, sidebarX() - SIDEBAR_GAP, this.height - GRID_BOTTOM_PAD);
        for (int i = 0; i < cameraCards.size(); i++) {
            int cx = cardX(i), cy = cardY(i);
            if (cy + ch < GRID_TOP || cy > this.height - GRID_BOTTOM_PAD) continue;
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
