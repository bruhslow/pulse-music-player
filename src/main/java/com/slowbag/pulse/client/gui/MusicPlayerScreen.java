package com.slowbag.pulse.client.gui;

import com.slowbag.pulse.MusicPlayerMod;
import com.slowbag.pulse.client.audio.AudioManager;
import com.slowbag.pulse.client.audio.AudioTrack;
import com.slowbag.pulse.client.audio.LocalMusicScanner;
import com.slowbag.pulse.client.audio.MusicSearchService;
import com.slowbag.pulse.client.audio.RecentUrlManager;
import com.slowbag.pulse.client.audio.PartyManager;
import com.slowbag.pulse.client.audio.YouTubeResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import com.slowbag.pulse.client.audio.SettingsManager;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Modern Pulse Glassmorphism Music Player GUI matching the reference UI layout.
 * Features a floating top pill, dual-column cards, interactive waveform equalizer,
 * queue card, volume slider card, and high-fidelity audio engine.
 */
public class MusicPlayerScreen extends Screen {

    private final Screen parentScreen;
    private final AudioManager audioManager;

    private float openAnimationProgress = 0.0f;
    private static boolean isExpanded = false;
    private float expandProgress = isExpanded ? 1.0f : 0.0f;
    private float vinylAngle = 0.0f;
    private final float[] smoothBands = new float[16];
    private final float[] smoothWaveBars = new float[64];

    public static final int HERO_ART_WIDTH = 80;
    public static final int HERO_ART_HEIGHT = 52;

    // Notification toast on refresh / actions
    private String statusMessage = null;
    private long statusMessageTime = 0;

    private final PartyManager partyManager = PartyManager.getInstance();

    // Tabs: 0: For You / Search, 1: Local, 2: Starred, 3: URL, 4: Rooms, 5: Settings
    private int selectedTab = 0;
    private final String[] tabs = {"🔥 For You", "📍 Local", "★ Starred", "🔗 URL", "👥 Rooms", "⚙ Settings"};
    private int settingsScrollOffset = 0;

    // Rooms (Party Rooms) state
    private boolean isCreatingRoom = false;
    private String newRoomName = "";
    private boolean isRoomNameFocused = false;
    private String newRoomPassword = "";
    private boolean isRoomPasswordFocused = false;
    private PartyManager.RoomEntry passwordPromptRoom = null;
    private String promptPasswordText = "";
    private String passwordErrorMsg = "";
    private int roomScrollOffset = 0;

    // Search bar & scrolling
    private String searchQuery = "";
    private boolean isSearchFocused = false;
    private final CopyOnWriteArrayList<AudioTrack> searchResults = new CopyOnWriteArrayList<>();
    private boolean isSearching = false;
    private int searchScrollOffset = 0;

    // Local music scrolling
    private int localScrollOffset = 0;

    // Starred music scrolling
    private int favoriteScrollOffset = 0;

    // Queue scrolling
    private int queueScrollOffset = 0;

    // Custom URL input
    private String customUrl = "";
    private boolean isUrlFocused = false;

    // Interactive slider dragging
    private boolean isDraggingVolume = false;
    private boolean isDraggingScrubber = false;
    private double draggedScrubRatio = -1.0;
    private List<AudioTrack> cachedLocalTracks = new ArrayList<>();

    // Direct input safety tracking across 26.x versions
    private boolean wasEscDown = false;
    private boolean wasLeftMouseDown = false;
    private long lastClickHandledTime = 0;

    public MusicPlayerScreen(Screen parentScreen) {
        super(Component.literal("Pulse Music Player"));
        this.parentScreen = parentScreen;
        this.audioManager = AudioManager.getInstance();
    }

    public void setSelectedTab(int tab) {
        this.selectedTab = Math.max(0, Math.min(tabs.length - 1, tab));
    }

    private boolean notifyIfGuestInRoom() {
        if (partyManager.isInRoom() && !partyManager.isHost()) {
            playClickSound();
            statusMessage = "§eHost (" + partyManager.getCurrentHost() + ") controls playback! Songs you queue will be sent to the room 🎵";
            statusMessageTime = System.currentTimeMillis();
            return true;
        }
        return false;
    }

    private void playOrQueueTrack(AudioTrack track) {
        if (track == null) return;
        if (partyManager.isInRoom() && !partyManager.isHost()) {
            partyManager.addTrackToRoomQueue(track);
            statusMessage = "§a➕ Added to room queue: §f" + truncate(track.getTitle(), 20) + " §7(Host controls playback)";
            statusMessageTime = System.currentTimeMillis();
            return;
        }
        audioManager.playTrack(track);
        statusMessage = "§a▶ Playing: " + truncate(track.getTitle(), 22) + " 💖";
        statusMessageTime = System.currentTimeMillis();
    }

    private void queueTrackOnly(AudioTrack track) {
        if (track == null) return;
        if (partyManager.isInRoom() && !partyManager.isHost()) {
            partyManager.addTrackToRoomQueue(track);
            statusMessage = "§a➕ Added to room queue: §f" + truncate(track.getTitle(), 20);
            statusMessageTime = System.currentTimeMillis();
            return;
        }
        audioManager.enqueueTrack(track);
        int qPos = audioManager.getQueueSize();
        String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
        statusMessage = "§a➕ Queued: " + truncate(track.getTitle(), 18) + " ~ " + ord + " 🎶";
        statusMessageTime = System.currentTimeMillis();
    }

    @Override
    protected void init() {
        super.init();
        this.openAnimationProgress = 0.0f;
        this.expandProgress = isExpanded ? 1.0f : 0.0f;
        this.isDraggingScrubber = false;
        this.isDraggingVolume = false;
        this.draggedScrubRatio = -1.0;
        this.wasEscDown = false;
        this.wasLeftMouseDown = false;
        if (this.minecraft != null) {
            ScreenCompat.handleMouseRelease(this.minecraft, this);
        }
        if (audioManager.getCurrentTrack() != null) {
            AudioTrack cur = audioManager.getCurrentTrack();
            String art = cur.getArtworkUrl();
            if ((art == null || art.isBlank()) && YouTubeResolver.isYouTubeUrl(cur.getUrl())) {
                String vid = YouTubeResolver.extractVideoId(cur.getUrl());
                if (vid != null) art = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
            }
            AlbumArtManager.getInstance().loadArtworkAsync(art);
        }
        this.cachedLocalTracks = LocalMusicScanner.scanLocalTracks();
        if (searchQuery.isEmpty()) {
            this.searchResults.clear();
            this.searchResults.addAll(MusicSearchService.getDefaultHits());
        }
    }

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            this.searchResults.clear();
            this.searchResults.addAll(MusicSearchService.getDefaultHits());
            this.isSearching = false;
            return;
        }
        this.isSearching = true;
        this.searchScrollOffset = 0;
        MusicSearchService.searchSongsAsync(query.trim(), results -> {
            this.isSearching = false;
            if (results != null && !results.isEmpty()) {
                this.searchResults.clear();
                this.searchResults.addAll(results);
            } else if (searchQuery.isEmpty()) {
                this.searchResults.clear();
                this.searchResults.addAll(MusicSearchService.getDefaultHits());
            }
        });
    }

    private void triggerRefresh() {
        playClickSound();
        statusMessage = "§a🔄 Refreshed entire mod! All tracks & settings up to date ✨";
        statusMessageTime = System.currentTimeMillis();

        try {
            cachedLocalTracks = LocalMusicScanner.scanLocalTracks();
            localScrollOffset = 0;
            favoriteScrollOffset = 0;
        } catch (Throwable ignored) {}

        try {
            AlbumArtManager.getInstance().clearTexture();
            AudioTrack cur = audioManager.getCurrentTrack();
            if (cur != null) {
                String art = cur.getArtworkUrl();
                if ((art == null || art.isBlank()) && YouTubeResolver.isYouTubeUrl(cur.getUrl())) {
                    String vid = YouTubeResolver.extractVideoId(cur.getUrl());
                    if (vid != null) art = "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
                }
                if (art != null) {
                    AlbumArtManager.getInstance().loadArtworkAsync(art);
                }
            }
        } catch (Throwable ignored) {}

        if (selectedTab == 0) {
            performSearch(searchQuery.isEmpty() ? "top english hits" : searchQuery);
        }
    }

    private void playClickSound() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client != null && client.getSoundManager() != null) {
                client.getSoundManager().play(
                        SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f)
                );
            }
        } catch (Throwable ignored) {}
    }

    private List<String> getOnlinePlayerNames() {
        List<String> list = new ArrayList<>();
        try {
            if (this.minecraft != null && this.minecraft.getConnection() != null) {
                String myName = this.minecraft.getUser().getName();
                for (var info : this.minecraft.getConnection().getOnlinePlayers()) {
                    String name = info.getProfile().name();
                    if (name != null && !name.equalsIgnoreCase(myName)) {
                        list.add(name);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    // Responsive Layout Dimensions
    private int getPillY() {
        return Math.max(4, isExpanded ? (getHeroY() - 25) : ((this.height - 108) / 2 - 28));
    }

    private int getExpTotalWidth() {
        return Math.min(640, this.width - 24);
    }

    private int getExpLeftWidth() {
        int totalW = getExpTotalWidth();
        return (this.width >= 340) ? (int) (totalW * 0.58) : totalW;
    }

    private int getExpRightWidth() {
        return (this.width >= 340) ? (getExpTotalWidth() - getExpLeftWidth() - 8) : 0;
    }

    private int getExpStartX() {
        return (this.width - getExpTotalWidth()) / 2;
    }

    private float getEasedExpand() {
        return AnimationUtil.easeInOutCubic(expandProgress);
    }

    private int getHeroX() {
        int compactW = Math.min(480, this.width - 24);
        int compactX = (this.width - compactW) / 2;
        int expStartX = getExpStartX();
        return (int) AnimationUtil.lerp(compactX, expStartX, getEasedExpand());
    }

    private int getHeroY() {
        int compactH = 108;
        int compactY = (this.height - compactH) / 2;
        int totalExpH = getHeroHeight() + 8 + getDrawerHeight();
        int expStartY = Math.max(16, (this.height - totalExpH) / 2);
        return (int) AnimationUtil.lerp(compactY, expStartY, getEasedExpand());
    }

    private int getHeroWidth() {
        int compactW = Math.min(480, this.width - 24);
        int expLeftW = getExpLeftWidth();
        return (int) AnimationUtil.lerp(compactW, expLeftW, getEasedExpand());
    }

    private int getHeroHeight() {
        return 108;
    }

    private int getDrawerY() {
        return getHeroY() + getHeroHeight() + 8;
    }

    private int getDrawerHeight() {
        int avail = this.height - getHeroHeight() - 36;
        return Math.max(140, Math.min(210, avail));
    }

    private int getVisibleSettingsCount() {
        int rowH = 32;
        int availH = getDrawerHeight() - 56;
        return Math.max(2, availH / rowH);
    }

    private boolean isTwoColumn() {
        return this.width >= 340 && isExpanded;
    }

    private int getTotalWidth() {
        return isExpanded ? getExpTotalWidth() : getHeroWidth();
    }

    private int getLeftWidth() {
        return isExpanded ? getExpLeftWidth() : getHeroWidth();
    }

    private int getRightWidth() {
        return isExpanded ? getExpRightWidth() : 0;
    }

    private int getStartX() {
        return isExpanded ? getExpStartX() : getHeroX();
    }

    private int getStartY() {
        return getHeroY();
    }

    private int getArtWidth() {
        return (int) AnimationUtil.lerp(104.0f, 72.0f, getEasedExpand());
    }

    private int getArtHeight() {
        return (int) AnimationUtil.lerp(70.0f, 48.0f, getEasedExpand());
    }

    @Override
    public void onClose() {
        ScreenCompat.setScreen(this.minecraft, this.parentScreen);
    }

    public void method_25419() {
        onClose();
    }

    private void pollDirectInputSafety(int mouseX, int mouseY) {
        try {
            if (this.minecraft == null) return;
            if (this.minecraft.mouseHandler != null) {
                this.minecraft.mouseHandler.releaseMouse();
            }
            if (this.minecraft.getWindow() == null) return;
            long handle = this.minecraft.getWindow().handle();
            if (handle == 0) return;

            // Ensure cursor is unlocked, released and visible (fixes 26.3 screen freeze)
            if (GLFW.glfwGetInputMode(handle, GLFW.GLFW_CURSOR) != GLFW.GLFW_CURSOR_NORMAL) {
                GLFW.glfwSetInputMode(handle, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
            }

            // Accurate mouse coordinates directly from GLFW window
            double curX = mouseX;
            double curY = mouseY;
            int winW = this.minecraft.getWindow().getWidth();
            int winH = this.minecraft.getWindow().getHeight();
            if (winW > 0 && winH > 0 && this.width > 0 && this.height > 0) {
                double[] xPos = new double[1];
                double[] yPos = new double[1];
                GLFW.glfwGetCursorPos(handle, xPos, yPos);
                curX = xPos[0] * (double) this.width / (double) winW;
                curY = yPos[0] * (double) this.height / (double) winH;
            }

            // 1. Direct ESC key safety fallback
            boolean escDown = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
            if (escDown && !this.wasEscDown) {
                this.wasEscDown = true;
                handleKeyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
            } else if (!escDown) {
                this.wasEscDown = false;
            }

            // 2. Direct Left Mouse Button safety fallback
            boolean mouseDown = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (mouseDown && !this.wasLeftMouseDown) {
                this.wasLeftMouseDown = true;
                if (System.currentTimeMillis() - lastClickHandledTime > 120L) {
                    handleMouseClicked(curX, curY, GLFW.GLFW_MOUSE_BUTTON_LEFT, false);
                }
            } else if (!mouseDown && this.wasLeftMouseDown) {
                this.wasLeftMouseDown = false;
                handleMouseReleased(curX, curY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            } else if (mouseDown && (isDraggingScrubber || isDraggingVolume)) {
                handleMouseDragged(curX, curY, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0, 0);
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        try {
            super.extractRenderState(graphics, mouseX, mouseY, delta);
        } catch (Throwable ignored) {}
        try {
            pollDirectInputSafety(mouseX, mouseY);
            openAnimationProgress = AnimationUtil.lerp(openAnimationProgress, 1.0f, 0.25f);
            float ease = AnimationUtil.easeOutCubic(openAnimationProgress);

            expandProgress = AnimationUtil.lerp(expandProgress, isExpanded ? 1.0f : 0.0f, 0.16f);
            float eased = getEasedExpand();

            // 1. Dark semi-transparent backdrop (world subtly visible through)
            int backdropAlpha = (int) (45 * ease);
            graphics.fill(0, 0, this.width, this.height, (backdropAlpha << 24));

            // 2. Top Floating Pill Bar ("~ PULSE")
            int pillW = Math.min(270, this.width - 20);
            int pillX = (this.width - pillW) / 2;
            int pillY = getPillY();
            int pillH = 22;
            renderHeaderPill(graphics, pillX, pillY, pillW, pillH, mouseX, mouseY);

            // 3. Hero Card (centered in compact mode, top-left in expanded mode)
            int heroX = getHeroX();
            int heroY = getHeroY();
            int heroW = getHeroWidth();
            int heroH = getHeroHeight();
            renderNowPlayingHero(graphics, heroX, heroY, heroW, heroH, mouseX, mouseY);

            // 4. Expanded Drawer & Right Panel with smooth vertical scissoring
            if (eased > 0.02f) {
                int expStartX = getExpStartX();
                int leftW = getExpLeftWidth();
                int rightW = getExpRightWidth();
                int drawerY = getDrawerY();
                int fullDrawerH = getDrawerHeight();
                int drawerH = (int) (fullDrawerH * eased);

                // Lower Drawer Card
                if (drawerH > 8) {
                    graphics.enableScissor(expStartX, drawerY, expStartX + leftW, drawerY + drawerH);
                    renderBrowserCard(graphics, expStartX, drawerY, leftW, fullDrawerH, mouseX, mouseY);
                    graphics.disableScissor();
                }

                // Right Column: Volume Card & Queue Card
                if (this.width >= 340 && rightW > 40 && drawerH > 24) {
                    int rightX = expStartX + leftW + 8;
                    int volY = heroY;
                    int volH = 26;
                    renderVolumeCard(graphics, rightX, volY, rightW, volH, mouseX, mouseY);

                    int queueY = volY + volH + 6;
                    int queueH = (drawerY + drawerH) - queueY;
                    if (queueH > 10) {
                        graphics.enableScissor(rightX, queueY, rightX + rightW, drawerY + drawerH);
                        renderQueueCard(graphics, rightX, queueY, rightW, (drawerY + fullDrawerH) - queueY, mouseX, mouseY);
                        graphics.disableScissor();
                    }
                }
            }

            // Clear last error message silently
            if (audioManager.getLastErrorMessage() != null) {
                audioManager.clearLastErrorMessage();
            }

            // Status Notification Toast
            if (statusMessage != null && System.currentTimeMillis() - statusMessageTime < 3500) {
                int toastW = this.font.width(statusMessage) + 24;
                int tx = (this.width - toastW) / 2;
                int ty = Math.max(4, heroY - 20);
                drawSoftBox(graphics, tx, ty, toastW, 18, 5, 0xF0181A1B, 0xFFA855F7);
                graphics.centeredText(this.font, Component.literal(statusMessage), this.width / 2, ty + 5, 0xFFFFFFFF);
            }

            // Password Prompt Modal Dialog for locked rooms
            if (passwordPromptRoom != null) {
                renderPasswordModal(graphics, mouseX, mouseY);
            }

            super.extractRenderState(graphics, mouseX, mouseY, delta);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Music Player] Error in extractRenderState", t);
        }
    }

    // Top Floating Pill Bar
    private void renderHeaderPill(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawSoftBox(graphics, x, y, w, h, 5, 0xF0181A1B, 0x30FFFFFF);
        graphics.fill(x + 6, y + 1, x + w - 6, y + 2, 0x20FFFFFF);

        // Music note icon + title in signature amber orange
        graphics.text(this.font, Component.literal("§d♫ §f§lPULSE"), x + 8, y + 6, 0xFFFFFFFF, true);

        // Right Action Icons: ⚙ Settings | 📌 Pin HUD | ♫ Tag Badge | ✕ Close
        HudConfigManager hudCfg = HudConfigManager.getInstance();
        boolean hudActive = hudCfg.isHudEnabled();
        boolean badgeActive = com.slowbag.pulse.client.audio.MusicBadgeManager.isBadgeEnabled();

        int iconY = y + 3;
        int closeX = x + w - 20;
        int badgeBtnX = closeX - 22;
        int pinBtnX = badgeBtnX - 22;
        int settingBtnX = pinBtnX - 22;

        // Settings / Accessibility Toggle
        boolean setHover = mouseX >= settingBtnX && mouseX <= settingBtnX + 16 && mouseY >= iconY && mouseY <= iconY + 16;
        boolean setOpen = (selectedTab == 5);
        drawSoftBox(graphics, settingBtnX, iconY, 16, 16, 3, setOpen ? 0x80A855F7 : (setHover ? 0x50A855F7 : 0x20FFFFFF), setHover ? 0xFFA855F7 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal(setOpen || setHover ? "§d⚙" : "§7⚙"), settingBtnX + 4, iconY + 4, 0xFFFFFFFF, false);

        // Pin In-Game HUD
        boolean pinHover = mouseX >= pinBtnX && mouseX <= pinBtnX + 16 && mouseY >= iconY && mouseY <= iconY + 16;
        drawSoftBox(graphics, pinBtnX, iconY, 16, 16, 3, hudActive ? (pinHover ? 0x80A855F7 : 0x40A855F7) : (pinHover ? 0x60FFFFFF : 0x20FFFFFF), pinHover ? 0xFFA855F7 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal(hudActive ? "§d📌" : "§7📌"), pinBtnX + 4, iconY + 4, 0xFFFFFFFF, false);

        // Nametag Badge Toggle
        boolean badgeHover = mouseX >= badgeBtnX && mouseX <= badgeBtnX + 16 && mouseY >= iconY && mouseY <= iconY + 16;
        drawSoftBox(graphics, badgeBtnX, iconY, 16, 16, 3, badgeActive ? (badgeHover ? 0x80A855F7 : 0x40A855F7) : (badgeHover ? 0x60FFFFFF : 0x20FFFFFF), badgeHover ? 0xFFA855F7 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal(badgeActive ? "§d♫" : "§7—"), badgeBtnX + 4, iconY + 4, 0xFFFFFFFF, false);

        // Close button '✕'
        boolean closeHover = mouseX >= closeX && mouseX <= closeX + 16 && mouseY >= iconY && mouseY <= iconY + 16;
        drawSoftBox(graphics, closeX, iconY, 16, 16, 3, closeHover ? 0x90FF3344 : 0x20FFFFFF, closeHover ? 0xFFFF4455 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal("§c✕"), closeX + 4, iconY + 4, 0xFFFFFFFF, false);
    }

    // Now Playing Hero Card (Top-Left or Centered)
    private void renderNowPlayingHero(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        // Base dark container with polished warm obsidian glass (semi-transparent)
        drawGlassCard(graphics, x, y, w, h, 0x78141619, 0x40FFFFFF, 0x18FFFFFF);

        AudioTrack track = audioManager.getCurrentTrack();
        boolean isPlaying = audioManager.isPlaying() && !audioManager.isPaused();
        boolean isBuffering = audioManager.isBuffering();

        if (isPlaying && !isBuffering && !SettingsManager.getInstance().isReducedMotion()) {
            vinylAngle = (vinylAngle + 3.0f) % 360.0f;
        }

        int ctrlAreaW = 118;
        int infoAreaW = w - ctrlAreaW - 6;

        // Dedicated Frosted Deep Obsidian Container for Left section (subtle translucent glass)
        int leftCardW = infoAreaW;
        drawSoftBox(graphics, x + 3, y + 3, leftCardW, 83, 4, 0x30181A1D, 0x25FFFFFF);
        graphics.fill(x + 8, y + 4, x + leftCardW - 2, y + 5, 0x15FFFFFF);

        // 1. Dynamic Album Art Thumbnail & Glassmorphic Container
        int artW = getArtWidth();
        int artH = getArtHeight();
        int artX = x + 7;
        int artY = (int) AnimationUtil.lerp(y + 7, y + 8, getEasedExpand());

        Identifier artId = AlbumArtManager.getInstance().getAlbumArtId();
        int glowColor = isBuffering ? 0x60FFCC00 : (isPlaying ? 0x50A855F7 : 0x20FFFFFF);
        int borderColor = isBuffering ? 0xFFFFD700 : (isPlaying ? 0xFFA855F7 : 0x50FFFFFF);

        // Ambient outer glow & depth shadow
        drawSoftBox(graphics, artX - 1, artY - 1, artW + 2, artH + 2, 5, 0x1A000000, glowColor);

        // Obsidian container with rounded bevel
        drawSoftBox(graphics, artX, artY, artW, artH, 4, 0xFF121418, borderColor);

        // Blit texture with clean 1px border inset
        if (artId != null) {
            graphics.blit(artId, artX + 1, artY + 1, artX + artW - 1, artY + artH - 1, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            graphics.blit(AlbumArtManager.getInstance().getDefaultAlbumArtId(), artX + 1, artY + 1, artX + artW - 1, artY + artH - 1, 0.0f, 1.0f, 0.0f, 1.0f);
        }

        // Top glossy acrylic sheen
        graphics.fill(artX + 2, artY + 1, artX + artW - 2, artY + 2, 0x45FFFFFF);
        graphics.fill(artX + 2, artY + 2, artX + Math.max(10, artW / 2), artY + 3, 0x20FFFFFF);

        // Bottom subtle gradient shade
        graphics.fill(artX + 1, artY + artH - 14, artX + artW - 1, artY + artH - 1, 0x65000000);

        // Live bouncing equalizer indicator or status badge on bottom-left of thumbnail
        if (isPlaying && !isBuffering) {
            int badgeW = 20;
            int badgeH = 9;
            int badgeX = artX + 4;
            int badgeY = artY + artH - badgeH - 3;
            drawSoftBox(graphics, badgeX, badgeY, badgeW, badgeH, 2, 0xB0000000, 0x80A855F7);
            long nowMs = System.currentTimeMillis();
            int h1 = 2 + (int) (Math.abs(Math.sin(nowMs * 0.007)) * 4);
            int h2 = 2 + (int) (Math.abs(Math.sin(nowMs * 0.011 + 1.2)) * 4);
            int h3 = 2 + (int) (Math.abs(Math.sin(nowMs * 0.009 + 2.4)) * 4);
            graphics.fill(badgeX + 3, badgeY + badgeH - 1 - h1, badgeX + 5, badgeY + badgeH - 1, 0xFFA855F7);
            graphics.fill(badgeX + 8, badgeY + badgeH - 1 - h2, badgeX + 10, badgeY + badgeH - 1, 0xFFC084FC);
            graphics.fill(badgeX + 13, badgeY + badgeH - 1 - h3, badgeX + 15, badgeY + badgeH - 1, 0xFFE9D5FF);
        } else if (isBuffering) {
            int badgeX = artX + 4;
            int badgeY = artY + artH - 12;
            drawSoftBox(graphics, badgeX, badgeY, 26, 9, 2, 0xB0000000, 0x80FFCC00);
            graphics.text(this.font, Component.literal("§eLOAD"), badgeX + 2, badgeY + 1, 0xFFFFFFFF, false);
        }

        // 2. Track Info & Source
        int infoX = artX + artW + 8;
        int starBtnW = 16;
        int starBtnH = 16;
        int starX = x + leftCardW - starBtnW - 4;
        int starY = y + 6;
        int maxTextW = Math.max(20, (starX - 8) - infoX);

        String title = (track != null) ? track.getTitle() : "No Track Selected";
        String artist = (track != null) ? track.getArtist() : "Select a track from For You, Local, or URL";
        String sourceTag = isBuffering ? "§e⏳ Buffering audio..." : ((track != null) ? (track.isLiveStream() ? "§c🔴 LIVE Stream" : "Source: " + track.getCategory()) : "Ready");

        String titlePrefix = isPlaying ? "§d§l" : "§f§l";
        graphics.text(this.font, Component.literal(titlePrefix + truncateToWidth(title, maxTextW, true)), infoX, y + 7, 0xFFFFFFFF, true);
        graphics.text(this.font, Component.literal("§7" + truncateToWidth(artist, maxTextW, false)), infoX, y + 19, 0xFFA0988E, false);
        graphics.text(this.font, Component.literal("§8" + sourceTag), infoX, y + 29, 0xFF888888, false);

        // Star / Favorite toggle button on Hero card (isolated from title)
        boolean isFav = (track != null) && com.slowbag.pulse.client.audio.FavoritesManager.getInstance().isFavorite(track);
        boolean starHover = mouseX >= starX - 1 && mouseX <= starX + starBtnW + 1 && mouseY >= starY - 1 && mouseY <= starY + starBtnH + 1;
        drawSoftBox(graphics, starX, starY, starBtnW, starBtnH, 3, isFav ? 0x40A855F7 : (starHover ? 0x30FFFFFF : 0x14FFFFFF), isFav ? 0xFFA855F7 : (starHover ? 0x70FFFFFF : 0x25FFFFFF));
        String starIcon = isFav ? "§d★" : (starHover ? "§f★" : "§7☆");
        graphics.centeredText(this.font, Component.literal(starIcon), starX + starBtnW / 2, starY + 4, 0xFFFFFFFF);

        // 3. Time Display & Progress Scrubber
        int scrubX = infoX + 26;
        int scrubY = y + 42;
        int scrubW = (leftCardW - (scrubX - x)) - 32;
        int scrubH = 4;

        long curSecs = audioManager.getElapsedSeconds();
        long totalSecs = (track != null) ? track.getDurationSeconds() : 0;
        double ratio = (totalSecs > 0) ? (double) curSecs / totalSecs : 0.0;
        if (isDraggingScrubber && draggedScrubRatio >= 0) {
            ratio = draggedScrubRatio;
            curSecs = (long) (ratio * totalSecs);
        }
        ratio = Math.max(0.0, Math.min(1.0, ratio));

        String timeStr = curSecs / 60 + ":" + String.format("%02d", curSecs % 60);
        String totalStr = (totalSecs > 0) ? (totalSecs / 60 + ":" + String.format("%02d", totalSecs % 60)) : "--:--";

        graphics.text(this.font, Component.literal("§7" + timeStr), infoX, scrubY - 2, 0xFFA0988E, false);

        // Scrubber bar (soft rounded groove)
        drawSoftBox(graphics, scrubX, scrubY, scrubW, scrubH, 2, 0x35FFFFFF, 0);
        int filledW = (int) (scrubW * ratio);
        if (filledW > 0) {
            drawSoftBox(graphics, scrubX, scrubY, filledW, scrubH, 2, 0xFFA855F7, 0);
        }
        // Cream circular handle with amber accent
        int thumbX = scrubX + filledW;
        drawSoftBox(graphics, thumbX - 4, scrubY - 3, 8, scrubH + 6, 4, 0xFFFFFFFF, 0xFFA855F7);

        // Floating tooltip while dragging timeline
        if (isDraggingScrubber && draggedScrubRatio >= 0) {
            String dragTip = timeStr;
            int tipW = this.font.width(dragTip) + 8;
            drawSoftBox(graphics, thumbX - tipW / 2, scrubY - 16, tipW, 11, 2, 0xF0181A1B, 0xFFA855F7);
            graphics.centeredText(this.font, Component.literal("§d" + dragTip), thumbX, scrubY - 15, 0xFFFFFFFF);
        }

        graphics.text(this.font, Component.literal("§7" + totalStr), scrubX + scrubW + 4, scrubY - 2, 0xFFA0988E, false);

        // 4. Live Audio Waveform Equalizer (Positioned under scrubber, never overlapping album art)
        float[] rawBands = audioManager.getVisualizerBands();
        int waveX = infoX;
        int maxWaveW = Math.max(28, leftCardW - (infoX - x) - 16);
        int waveBars = Math.max(8, maxWaveW / 7);
        int waveY = y + 74;
        int maxWaveH = 8;
        long timeMs = System.currentTimeMillis();
        double t = timeMs / 1000.0;
        float peak = audioManager.getPeakLevel();

        for (int i = 0; i < waveBars; i++) {
            int bandIdx = Math.min(15, i * 16 / waveBars);
            float audioVal = (rawBands != null && bandIdx < rawBands.length) ? rawBands[bandIdx] : 0.0f;
            audioVal = Math.max(0.0f, Math.min(1.0f, audioVal));

            double w1 = Math.sin(t * 3.8 - i * 0.38);
            double w2 = Math.sin(t * 5.6 + i * 0.52) * 0.35;
            double w3 = Math.cos(t * 2.2 - i * 0.18) * 0.25;
            double waveShape = 0.50 + (0.32 * w1) + w2 + w3;
            waveShape = Math.max(0.08, Math.min(0.92, waveShape));

            float target;
            if (isPlaying && !isBuffering && !SettingsManager.getInstance().isReducedMotion()) {
                double energyMod = 0.30 + (0.45 * audioVal) + (0.25 * peak);
                target = (float) (0.12 + (waveShape * energyMod));
                target = Math.max(0.10f, Math.min(0.88f, target));
            } else {
                target = 0.05f;
            }

            int barIdx = i % smoothWaveBars.length;
            smoothWaveBars[barIdx] = AnimationUtil.lerp(smoothWaveBars[barIdx], target, 0.32f);

            int barH = Math.max(2, Math.min(maxWaveH, (int) (smoothWaveBars[barIdx] * maxWaveH)));
            int barX = waveX + (i * 7);
            if (barX + 5 > waveX + maxWaveW) break;
            int barY = waveY - barH;

            // Warm amber gradient with bright golden caps
            int barColor = AnimationUtil.colorLerp(0xFFA855F7, 0xFFE9D5FF, (float) i / (float) waveBars);
            graphics.fill(barX, barY, barX + 5, waveY, barColor);
            graphics.fill(barX, barY, barX + 5, barY + 1, 0xFFFFD090);
        }

        // Horizontal separator above the expand/collapse footer
        graphics.fill(x + 6, y + 87, x + w - 6, y + 88, 0x14FFFFFF);

        // 5. Expand / Collapse Drawer Button at bottom center (Lengthy pill bar with glowing rim)
        int toggleW = isExpanded ? Math.min(180, w - 40) : Math.min(220, w - 40);
        int toggleH = 15;
        int toggleX = x + (w - toggleW) / 2;
        int toggleY = y + 89;
        boolean toggleHov = mouseX >= toggleX && mouseX <= toggleX + toggleW && mouseY >= toggleY && mouseY <= toggleY + toggleH;
        int toggleBg = toggleHov ? 0x90A855F7 : 0x4516181B;
        int toggleBorder = toggleHov ? 0xFFE9D5FF : 0x80A855F7;
        drawSoftBox(graphics, toggleX, toggleY, toggleW, toggleH, 4, toggleBg, toggleBorder);
        String toggleText = isExpanded ? "§d▲  C O L L A P S E  ▲" : "§d▼  E X P A N D  ▼";
        graphics.centeredText(this.font, Component.literal(toggleText), toggleX + toggleW / 2, toggleY + 4, toggleHov ? 0xFFFFFFFF : 0xFFFFD090);

        // 6. Right Section: Integrated Controls Panel
        int ctrlX = x + w - ctrlAreaW;
        drawSoftBox(graphics, ctrlX + 2, y + 3, ctrlAreaW - 5, 83, 4, 0x20181A1D, 0x20FFFFFF);
        graphics.fill(ctrlX, y + 6, ctrlX + 1, y + 84, 0x18FFFFFF);

        // Row 1: Previous | Big Play/Pause | Next
        int prevX = ctrlX + 10;
        int playX = ctrlX + 42;
        int nextX = ctrlX + 82;
        int playBtnY = y + 10;

        drawGlassButton(graphics, prevX, playBtnY + 5, 24, 22, "§f⏮", 0x30FFFFFF, mouseX, mouseY);

        // Circular Big Play Button with Amber Gradient and Rim Glow
        boolean playHover = mouseX >= playX && mouseX <= playX + 32 && mouseY >= playBtnY && mouseY <= playBtnY + 32;
        int playOuterGlow = isBuffering ? 0x80FFCC00 : (playHover ? 0x80A855F7 : 0x40A855F7);
        int playBg = playHover ? 0xFFC084FC : 0xFFA855F7;
        drawSoftBox(graphics, playX - 1, playBtnY - 1, 34, 34, 5, playOuterGlow, 0);
        drawSoftBox(graphics, playX, playBtnY, 32, 32, 5, playBg, 0xFFE9D5FF);
        drawSoftBox(graphics, playX + 1, playBtnY + 1, 30, 30, 4, 0, 0x40FFFFFF);
        String playIcon = isBuffering ? "§e⏳" : (isPlaying ? "§f❚❚" : "§f▶");
        graphics.centeredText(this.font, Component.literal(playIcon), playX + 16, playBtnY + 11, 0xFFFFFFFF);

        drawGlassButton(graphics, nextX, playBtnY + 5, 24, 22, "§f⏭", 0x30FFFFFF, mouseX, mouseY);

        // Row 2: Secondary Controls: Loop & Star (Curvy rounded buttons)
        int row2Y = y + 48;
        boolean loop = audioManager.isLoopCurrent();
        isFav = (track != null) && com.slowbag.pulse.client.audio.FavoritesManager.getInstance().isFavorite(track);
        drawGlassButton(graphics, ctrlX + 8, row2Y, 52, 16, loop ? "§d🔁 Loop" : "§7🔁 Loop", loop ? 0xFFA855F7 : 0x25FFFFFF, mouseX, mouseY);
        drawGlassButton(graphics, ctrlX + 64, row2Y, 52, 16, isFav ? "§d★ Star" : "§7☆ Star", isFav ? 0xFFA855F7 : 0x25FFFFFF, mouseX, mouseY);

        // Row 3: Utility Controls: Reload & + Queue (Curvy rounded buttons, text fits cleanly)
        int row3Y = y + 68;
        drawGlassButton(graphics, ctrlX + 8, row3Y, 52, 16, "§7🔄 Reload", 0x20FFFFFF, mouseX, mouseY);
        drawGlassButton(graphics, ctrlX + 64, row3Y, 52, 16, "§7+ Queue", 0x20FFFFFF, mouseX, mouseY);
    }

    // Browser Card (Bottom-Left)
    private void renderBrowserCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0x78141619, 0x40FFFFFF, 0x18FFFFFF);

        // Top Tab Bar
        int tabCount = tabs.length;
        int tabW = (w - 16) / tabCount;
        int tabY = y + 5;

        for (int i = 0; i < tabCount; i++) {
            int tx = x + 8 + (i * tabW);
            boolean isSel = (selectedTab == i);
            boolean isHov = mouseX >= tx && mouseX <= tx + tabW - 4 && mouseY >= tabY && mouseY <= tabY + 18;

            int bg = isSel ? 0x50A855F7 : (isHov ? 0x25A855F7 : 0x15FFFFFF);
            int outline = isSel ? 0xFFA855F7 : (isHov ? 0x40A855F7 : 0x20FFFFFF);
            drawSoftBox(graphics, tx, tabY, tabW - 4, 18, 3, bg, outline);
            if (isSel) {
                graphics.fill(tx + 4, tabY + 15, tx + tabW - 8, tabY + 17, 0xFFA855F7);
            }
            String tabLabel = tabs[i];
            if (i == 2) {
                tabLabel = "§d★ §fStarred";
            }
            graphics.centeredText(this.font, Component.literal(tabLabel), tx + (tabW - 4) / 2, tabY + 5, isSel ? 0xFFFFFFFF : 0xFFA0988E);
        }

        // Tab Content
        renderTabContent(graphics, x, y + 26, w, h - 28, mouseX, mouseY);
    }

    private void renderTabContent(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (selectedTab == 0) {
            // Tab 0: Direct Online Song Search & For You Recommendations
            int searchX = x + 10;
            int searchY = y + 2;
            int searchW = w - 20;
            int searchH = 18;

            drawSoftBox(graphics, searchX, searchY, searchW, searchH, 3, 0x60000000, isSearchFocused ? 0xFFA855F7 : 0x35FFFFFF);

            String displayText = searchQuery.isEmpty() ? (isSearchFocused ? "" : "§8🔍 Search English hits, artists or keywords...") : searchQuery;
            graphics.text(this.font, Component.literal(displayText), searchX + 6, searchY + 5, isSearchFocused ? 0xFFFFFFFF : 0xFFA0988E, false);

            if (!searchQuery.isEmpty()) {
                graphics.text(this.font, Component.literal("§7✕"), searchX + searchW - 14, searchY + 5, 0xFFAAAAAA, false);
            }

            int itemY = searchY + 22;
            if (isSearching) {
                graphics.centeredText(this.font, Component.literal("§d⚡ Searching music catalog..."), x + w / 2, itemY + 20, 0xFFE0E0E0);
            } else if (searchResults.isEmpty()) {
                graphics.centeredText(this.font, Component.literal("§7Type a song title above and hit Enter! ✨"), x + w / 2, itemY + 20, 0xFFAAAAAA);
            } else {
                if (searchQuery.isEmpty()) {
                    graphics.text(this.font, Component.literal("§d🔥 For You • Top English Hits"), searchX + 2, itemY, 0xFFFFFFFF, false);
                    itemY += 13;
                }
                int totalResults = searchResults.size();
                int visibleRows = Math.max(3, (h - (searchQuery.isEmpty() ? 39 : 26)) / 24);
                int startIdx = Math.min(searchScrollOffset, Math.max(0, totalResults - visibleRows));
                int endIdx = Math.min(totalResults, startIdx + visibleRows);

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack t = searchResults.get(i);
                    renderTrackRow(graphics, x + 8, itemY, w - 16, t, mouseX, mouseY);
                    itemY += 24;
                }
            }

        } else if (selectedTab == 1) {
            // Tab 1: Local Music
            int headerX = x + 10;
            int headerY = y + 2;
            graphics.text(this.font, Component.literal("§f§l📁 Local Music Files"), headerX, headerY + 4, 0xFFFFFFFF, true);

            int openX = x + w - 110;
            boolean openHover = mouseX >= openX && mouseX <= openX + 96 && mouseY >= headerY && mouseY <= headerY + 16;
            drawSoftBox(graphics, openX, headerY, 96, 16, 3, openHover ? 0xFFC084FC : 0x40FFFFFF, openHover ? 0xFFE9D5FF : 0x30FFFFFF);
            graphics.centeredText(this.font, Component.literal("§f📂 Open Folder"), openX + 48, headerY + 4, 0xFFFFFFFF);

            if (cachedLocalTracks.isEmpty()) {
                cachedLocalTracks = LocalMusicScanner.scanLocalTracks();
            }
            List<AudioTrack> locals = cachedLocalTracks;
            int itemY = headerY + 22;

            if (locals.isEmpty()) {
                graphics.centeredText(this.font, Component.literal("§7No local .mp3/.wav/.m4a files found."), x + w / 2, itemY + 20, 0xFFAAAAAA);
                graphics.centeredText(this.font, Component.literal("§dClick 'Open Folder' above to drop songs in!"), x + w / 2, itemY + 34, 0xFFA855F7);
            } else {
                int visibleRows = Math.max(3, (h - 26) / 24);
                int startIdx = Math.min(localScrollOffset, Math.max(0, locals.size() - visibleRows));
                int endIdx = Math.min(locals.size(), startIdx + visibleRows);

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack t = locals.get(i);
                    renderTrackRow(graphics, x + 8, itemY, w - 16, t, mouseX, mouseY);
                    itemY += 24;
                }
            }

        } else if (selectedTab == 2) {
            // Tab 2: Starred / Favorites Music
            int headerX = x + 10;
            int headerY = y + 2;
            graphics.text(this.font, Component.literal("§d§l★ Starred Tracks & Favorites"), headerX, headerY + 4, 0xFFA855F7, true);

            int shufX = x + w - 110;
            boolean shufHover = mouseX >= shufX && mouseX <= shufX + 96 && mouseY >= headerY && mouseY <= headerY + 16;
            drawSoftBox(graphics, shufX, headerY, 96, 16, 3, shufHover ? 0xFFC084FC : 0x40FFFFFF, shufHover ? 0xFFE9D5FF : 0x30FFFFFF);
            graphics.centeredText(this.font, Component.literal("§f🔀 Shuffle Starred"), shufX + 48, headerY + 4, 0xFFFFFFFF);

            List<AudioTrack> favs = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().getFavorites();
            int itemY = headerY + 22;

            if (favs.isEmpty()) {
                graphics.centeredText(this.font, Component.literal("§7No starred tracks yet!"), x + w / 2, itemY + 20, 0xFFAAAAAA);
                graphics.centeredText(this.font, Component.literal("§dClick the ★ button on any track to star it! 💕"), x + w / 2, itemY + 34, 0xFFA855F7);
            } else {
                int visibleRows = Math.max(3, (h - 26) / 24);
                int startIdx = Math.min(favoriteScrollOffset, Math.max(0, favs.size() - visibleRows));
                int endIdx = Math.min(favs.size(), startIdx + visibleRows);

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack t = favs.get(i);
                    renderTrackRow(graphics, x + 8, itemY, w - 16, t, mouseX, mouseY);
                    itemY += 24;
                }
            }

        } else if (selectedTab == 3) {
            // Tab 3: YouTube Video / Playlist & Custom URL Input (Clean 3-row layout, spacious & never overlapping)
            int headerX = x + 10;
            int headerY = y + 2;
            graphics.text(this.font, Component.literal("§f§l▶ YouTube & Stream URL"), headerX, headerY + 2, 0xFFFFFFFF, true);

            int inputX = x + 10;
            int inputY = headerY + 16;
            int inputW = w - 20;
            int inputH = 18;

            // Row 1: Full-width URL input bar
            drawSoftBox(graphics, inputX, inputY, inputW, inputH, 3, 0x60000000, isUrlFocused ? 0xFFA855F7 : 0x35FFFFFF);

            String disp = customUrl.isEmpty() ? (isUrlFocused ? "" : "§8Paste YouTube link or playlist...") : customUrl;
            int maxInputTextW = inputW - (customUrl.isEmpty() ? 12 : 24);
            graphics.text(this.font, Component.literal(truncateToWidth(disp, maxInputTextW)), inputX + 6, inputY + 5, isUrlFocused ? 0xFFFFFFFF : 0xFFA0988E, false);

            if (!customUrl.isEmpty()) {
                boolean clearHov = mouseX >= inputX + inputW - 16 && mouseX <= inputX + inputW - 2 && mouseY >= inputY + 2 && mouseY <= inputY + inputH - 2;
                graphics.text(this.font, Component.literal(clearHov ? "§f✕" : "§7✕"), inputX + inputW - 12, inputY + 5, 0xFFAAAAAA, false);
            }

            // Row 2: 3 spacious action buttons: 📋 Paste | ▶ Play | ➕ Queue
            int btnRowY = inputY + 22;
            int btnGap = 6;
            int btnW = (inputW - (btnGap * 2)) / 3;
            int btnH = 18;

            int pasteBtnX = inputX;
            int playBtnX = pasteBtnX + btnW + btnGap;
            int qBtnX = playBtnX + btnW + btnGap;

            // Dedicated "📋 Paste" Button
            boolean pasteHover = mouseX >= pasteBtnX && mouseX <= pasteBtnX + btnW && mouseY >= btnRowY && mouseY <= btnRowY + btnH;
            drawSoftBox(graphics, pasteBtnX, btnRowY, btnW, btnH, 3, pasteHover ? 0x80A855F7 : 0x25FFFFFF, pasteHover ? 0xFFA855F7 : 0x40FFFFFF);
            drawCleanCenteredText(graphics, "📋 Paste", pasteBtnX + btnW / 2, btnRowY + 5, 0xFFFFFFFF);

            // "▶ Play" Button (Warm Amber / Orange highlight)
            boolean playHover = mouseX >= playBtnX && mouseX <= playBtnX + btnW && mouseY >= btnRowY && mouseY <= btnRowY + btnH;
            drawSoftBox(graphics, playBtnX, btnRowY, btnW, btnH, 3, playHover ? 0xFFC084FC : 0xFFA855F7, playHover ? 0xFFE9D5FF : 0x60A855F7);
            drawCleanCenteredText(graphics, "▶ Play", playBtnX + btnW / 2, btnRowY + 5, 0xFFFFFFFF);

            // "+Q" Queue Button
            boolean qHover = mouseX >= qBtnX && mouseX <= qBtnX + btnW && mouseY >= btnRowY && mouseY <= btnRowY + btnH;
            drawSoftBox(graphics, qBtnX, btnRowY, btnW, btnH, 3, qHover ? 0x80A855F7 : 0x25FFFFFF, qHover ? 0xFFA855F7 : 0x40FFFFFF);
            drawCleanCenteredText(graphics, "➕ Queue", qBtnX + btnW / 2, btnRowY + 5, 0xFFFFFFFF);

            // Row 3: Recent Links & Playlists
            int recentHeaderY = btnRowY + 24;
            graphics.text(this.font, Component.literal("§7Recent Links & Playlists:"), inputX, recentHeaderY, 0xFFA0988E, false);
            int recentListY = recentHeaderY + 13;

            List<String> recents = RecentUrlManager.getRecentUrls();
            if (recents.isEmpty()) {
                graphics.text(this.font, Component.literal("§8No links played yet. Paste any YouTube video or playlist link!"), inputX, recentListY + 4, 0xFF888888, false);
            } else {
                int maxRecents = Math.min(recents.size(), Math.max(1, (h - (recentListY - y)) / 22));
                for (int i = 0; i < maxRecents; i++) {
                    String rUrl = recents.get(i);
                    boolean isYt = YouTubeResolver.isYouTubeUrl(rUrl);
                    boolean isPl = YouTubeResolver.extractPlaylistId(rUrl) != null;
                    String badge = isPl ? "§d[Playlist] " : (isYt ? "§c[YouTube] " : "§b[Stream] ");

                    int rowW = w - 20;
                    int rowH = 18;
                    int curRowY = recentListY + (i * 21);

                    boolean rHover = mouseX >= inputX && mouseX <= inputX + rowW && mouseY >= curRowY && mouseY <= curRowY + rowH;
                    drawSoftBox(graphics, inputX, curRowY, rowW, rowH, 3, rHover ? 0x30A855F7 : 0x15FFFFFF, rHover ? 0x40A855F7 : 0x20FFFFFF);

                    // Row action buttons on right: +Q (w: 24) and ▶ Play (w: 36)
                    int rowPlayW = 36;
                    int rowQW = 24;
                    int rowPlayX = inputX + rowW - rowPlayW - 4;
                    int rowQX = rowPlayX - rowQW - 4;

                    // Text bounded so it NEVER overlaps buttons (deducting badge width):
                    int availRowW = rowQX - (inputX + 6) - 8;
                    int badgeW = this.font.width(Component.literal(badge));
                    int maxUrlW = Math.max(20, availRowW - badgeW - 4);
                    graphics.text(this.font, Component.literal(badge + "§f" + truncateToWidth(rUrl, maxUrlW, false)), inputX + 6, curRowY + 5, 0xFFFFFFFF, false);

                    // Row +Q button
                    boolean rowQHover = mouseX >= rowQX && mouseX <= rowQX + rowQW && mouseY >= curRowY + 2 && mouseY <= curRowY + 16;
                    drawSoftBox(graphics, rowQX, curRowY + 2, rowQW, 14, 3, rowQHover ? 0x80A855F7 : 0x25FFFFFF, rowQHover ? 0xFFA855F7 : 0x30FFFFFF);
                    drawCleanCenteredText(graphics, "+Q", rowQX + rowQW / 2, curRowY + 3, 0xFFFFFFFF);

                    // Row Play button
                    boolean rowPlayHover = mouseX >= rowPlayX && mouseX <= rowPlayX + rowPlayW && mouseY >= curRowY + 2 && mouseY <= curRowY + 16;
                    drawSoftBox(graphics, rowPlayX, curRowY + 2, rowPlayW, 14, 3, rowPlayHover ? 0xFFC084FC : 0xFFA855F7, 0);
                    drawCleanCenteredText(graphics, "▶ Play", rowPlayX + rowPlayW / 2, curRowY + 3, 0xFFFFFFFF);
                }
            }

        } else if (selectedTab == 4) {
            renderRoomsTab(graphics, x, y, w, h, mouseX, mouseY);
        } else if (selectedTab == 5) {
            renderSettingsTab(graphics, x, y, w, h, mouseX, mouseY);
        }
    }

    // Tab 4: Synchronized Party Rooms (Simple Voice Chat Style)
    private void renderRoomsTab(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (!partyManager.isInRoom()) {
            // LOBBY / BROWSER VIEW
            int topY = y + 2;

            // Header toolbar - Row 1: Title on left, Action buttons on right
            graphics.text(this.font, Component.literal("§d👥 Music Rooms"), x + 8, topY + 4, 0xFFFFFFFF, true);

            int btnH = 16;
            int rightEdge = x + w - 8;
            int modeBtnW = isCreatingRoom ? 84 : 80;
            int modeBtnX = rightEdge - modeBtnW;
            int refreshBtnW = 56;
            int refreshBtnX = modeBtnX - refreshBtnW - 4;

            // Refresh Button [ ⟳ Refresh ]
            boolean refHov = mouseX >= refreshBtnX && mouseX <= refreshBtnX + refreshBtnW && mouseY >= topY && mouseY <= topY + btnH;
            drawSoftBox(graphics, refreshBtnX, topY, refreshBtnW, btnH, 3, refHov ? 0x60A855F7 : 0x20FFFFFF, refHov ? 0xFFA855F7 : 0x30FFFFFF);
            drawCleanCenteredText(graphics, "§f⟳ Refresh", refreshBtnX + refreshBtnW / 2, topY + 4, 0xFFFFFFFF);

            // Mode Toggle Button: [ ＋ Create Room ] or [ ✕ Browse ]
            boolean modeHov = mouseX >= modeBtnX && mouseX <= modeBtnX + modeBtnW && mouseY >= topY && mouseY <= topY + btnH;
            drawSoftBox(graphics, modeBtnX, topY, modeBtnW, btnH, 3, isCreatingRoom ? (modeHov ? 0x80FF4455 : 0x30FF4455) : (modeHov ? 0x80A855F7 : 0x40A855F7), isCreatingRoom ? 0xFFFF4455 : 0xFFA855F7);
            drawCleanCenteredText(graphics, isCreatingRoom ? "§f✕ Browse" : "§f＋ Create Room", modeBtnX + modeBtnW / 2, topY + 4, 0xFFFFFFFF);

            // Row 2: Subtitle positioned below the buttons with clean padding to avoid any overlap
            int subY = topY + 20;
            graphics.text(this.font, Component.literal(truncateToWidth("§8Browse active parties or host your own synchronized room", w - 16)), x + 8, subY, 0xFFA0988E, false);

            int contentY = subY + 12;
            int contentH = (y + h) - contentY - 4;

            if (isCreatingRoom) {
                // CREATE ROOM FORM
                drawSoftBox(graphics, x + 8, contentY, w - 16, contentH, 4, 0x18FFFFFF, 0x35A855F7);

                int formY = contentY + 8;
                graphics.text(this.font, Component.literal("§d✦ Host a New Music Room"), x + 16, formY, 0xFFFFFFFF, true);
                graphics.text(this.font, Component.literal("§8Members who join will automatically listen in sync with your playback"), x + 16, formY + 12, 0xFF888888, false);

                // Row 1: Room Name Input
                int field1Y = formY + 26;
                graphics.text(this.font, Component.literal("§fRoom Name:"), x + 16, field1Y, 0xFFFFFFFF, false);
                int inputW = Math.max(120, w - 48);
                int inputH = 18;
                int nameBoxY = field1Y + 11;
                boolean nameHov = mouseX >= x + 16 && mouseX <= x + 16 + inputW && mouseY >= nameBoxY && mouseY <= nameBoxY + inputH;
                drawSoftBox(graphics, x + 16, nameBoxY, inputW, inputH, 3, 0x60000000, isRoomNameFocused ? 0xFFA855F7 : (nameHov ? 0x50FFFFFF : 0x25FFFFFF));

                String nameDisp = newRoomName;
                if (nameDisp.isEmpty()) {
                    String defaultPlaceholder = "My Music Room";
                    try {
                        if (Minecraft.getInstance().getUser() != null) {
                            defaultPlaceholder = Minecraft.getInstance().getUser().getName() + "'s Room";
                        }
                    } catch (Throwable ignored) {}
                    graphics.text(this.font, Component.literal(isRoomNameFocused ? "§d_" : "§8" + defaultPlaceholder), x + 20, nameBoxY + 5, 0xFFFFFFFF, false);
                } else {
                    String cursor = isRoomNameFocused && (System.currentTimeMillis() / 500 % 2 == 0) ? "§d_" : "";
                    graphics.text(this.font, Component.literal("§f" + truncateToWidth(nameDisp, inputW - 16) + cursor), x + 20, nameBoxY + 5, 0xFFFFFFFF, false);
                }

                // Row 2: Room Password Input (Optional)
                int field2Y = nameBoxY + 22;
                graphics.text(this.font, Component.literal("§fPassword (Optional - leave empty for Open room):"), x + 16, field2Y, 0xFFFFFFFF, false);
                int passBoxY = field2Y + 11;
                boolean passHov = mouseX >= x + 16 && mouseX <= x + 16 + inputW && mouseY >= passBoxY && mouseY <= passBoxY + inputH;
                drawSoftBox(graphics, x + 16, passBoxY, inputW, inputH, 3, 0x60000000, isRoomPasswordFocused ? 0xFFA855F7 : (passHov ? 0x50FFFFFF : 0x25FFFFFF));

                if (newRoomPassword.isEmpty()) {
                    graphics.text(this.font, Component.literal(isRoomPasswordFocused ? "§d_" : "§8No password (Open room - anyone can join)"), x + 20, passBoxY + 5, 0xFF888888, false);
                } else {
                    String cursor = isRoomPasswordFocused && (System.currentTimeMillis() / 500 % 2 == 0) ? "§d_" : "";
                    graphics.text(this.font, Component.literal("§f" + truncateToWidth(newRoomPassword, inputW - 16) + cursor), x + 20, passBoxY + 5, 0xFFFFFFFF, false);
                }

                // Status Badge: Open vs Closed
                int badgeY = passBoxY + 22;
                boolean isOpen = newRoomPassword.trim().isEmpty();
                String badgeText = isOpen ? "§a🔓 Room Mode: Open (Anyone can join with one click)" : "§e🔒 Room Mode: Closed (Password required to enter)";
                graphics.text(this.font, Component.literal(badgeText), x + 16, badgeY, 0xFFFFFFFF, false);

                // Action Buttons: [ ✕ Cancel ] and [ ＋ Create Room ]
                int actY = badgeY + 16;
                int actBtnH = 20;
                int createBtnW = 100;
                int cancelBtnW = 60;
                int cancelBtnX = x + 16;
                int createBtnX = cancelBtnX + cancelBtnW + 8;

                boolean cnHov = mouseX >= cancelBtnX && mouseX <= cancelBtnX + cancelBtnW && mouseY >= actY && mouseY <= actY + actBtnH;
                drawSoftBox(graphics, cancelBtnX, actY, cancelBtnW, actBtnH, 3, cnHov ? 0x40FFFFFF : 0x20FFFFFF, cnHov ? 0xFFFFFFFF : 0x30FFFFFF);
                drawCleanCenteredText(graphics, "§7✕ Cancel", cancelBtnX + cancelBtnW / 2, actY + 6, 0xFFFFFFFF);

                boolean crHov = mouseX >= createBtnX && mouseX <= createBtnX + createBtnW && mouseY >= actY && mouseY <= actY + actBtnH;
                drawSoftBox(graphics, createBtnX, actY, createBtnW, actBtnH, 3, crHov ? 0x90A855F7 : 0x50A855F7, crHov ? 0xFFFFFFFF : 0xFFA855F7);
                drawCleanCenteredText(graphics, "§f＋ Create & Host", createBtnX + createBtnW / 2, actY + 6, 0xFFFFFFFF);

            } else {
                // BROWSE ROOM LIST
                List<PartyManager.RoomEntry> rooms = partyManager.getDiscoveredRooms();
                if (rooms.isEmpty()) {
                    drawSoftBox(graphics, x + 8, contentY, w - 16, contentH, 4, 0x12FFFFFF, 0x20FFFFFF);
                    int midY = contentY + Math.max(10, (contentH - 40) / 2);
                    drawCleanCenteredText(graphics, "§8👥 No active music rooms found on server.", x + w / 2, midY, 0xFFA0988E);
                    drawCleanCenteredText(graphics, "§7Click §d'＋ Create Room' §7above to host your own room!", x + w / 2, midY + 14, 0xFFFFFFFF);
                } else {
                    int itemH = 30;
                    int visibleCount = Math.max(1, contentH / itemH);
                    int startIdx = Math.min(roomScrollOffset, Math.max(0, rooms.size() - visibleCount));
                    int endIdx = Math.min(rooms.size(), startIdx + visibleCount);

                    int itemY = contentY;
                    for (int i = startIdx; i < endIdx; i++) {
                        PartyManager.RoomEntry r = rooms.get(i);
                        boolean rHov = mouseX >= x + 8 && mouseX <= x + w - 8 && mouseY >= itemY && mouseY <= itemY + itemH - 3;
                        drawSoftBox(graphics, x + 8, itemY, w - 16, itemH - 3, 3, rHov ? 0x30A855F7 : 0x18FFFFFF, rHov ? 0xFFA855F7 : 0x25FFFFFF);

                        // Lock / Open icon
                        String lockIcon = r.hasPassword() ? "§e🔒" : "§a🔓";
                        graphics.text(this.font, Component.literal(lockIcon), x + 14, itemY + 5, 0xFFFFFFFF, false);

                        // Room Name & Host/Members subtitle
                        int textX = x + 28;
                        int joinBtnW = 48;
                        int joinBtnX = x + w - joinBtnW - 14;
                        int maxNameW = joinBtnX - textX - 6;

                        String codeStr = (r.getCode() != null && !r.getCode().isEmpty()) ? " §e#" + r.getCode() : "";
                        String rName = truncateToWidth("§f§l" + r.getName() + codeStr, maxNameW);
                        graphics.text(this.font, Component.literal(rName), textX, itemY + 3, 0xFFFFFFFF, false);

                        String sub = "§8Host: §7" + truncate(r.getHost(), 14) + " §8• §d👥 " + r.getMemberCount() + (r.getMemberCount() == 1 ? " player" : " players");
                        graphics.text(this.font, Component.literal(truncateToWidth(sub, maxNameW)), textX, itemY + 15, 0xFFA0988E, false);

                        // Join Button [ ▶ Join ]
                        int joinBtnY = itemY + 5;
                        int joinBtnH = 18;
                        boolean jHov = mouseX >= joinBtnX && mouseX <= joinBtnX + joinBtnW && mouseY >= joinBtnY && mouseY <= joinBtnY + joinBtnH;
                        drawSoftBox(graphics, joinBtnX, joinBtnY, joinBtnW, joinBtnH, 3, jHov ? 0x90A855F7 : 0x40A855F7, jHov ? 0xFFFFFFFF : 0xFFA855F7);
                        drawCleanCenteredText(graphics, "§f▶ Join", joinBtnX + joinBtnW / 2, joinBtnY + 5, 0xFFFFFFFF);

                        itemY += itemH;
                    }

                    // Scrollbar if needed
                    if (rooms.size() > visibleCount) {
                        int trackX = x + w - 6;
                        int trackY = contentY;
                        int trackH = contentH;
                        graphics.fill(trackX, trackY, trackX + 3, trackY + trackH, 0x25FFFFFF);
                        float ratio = (float) startIdx / (float) (rooms.size() - visibleCount);
                        int thumbH = Math.max(16, (int) ((float) visibleCount / (float) rooms.size() * trackH));
                        int thumbY = trackY + (int) (ratio * (trackH - thumbH));
                        graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFFA855F7);
                    }
                }
            }

        } else {
            // IN A ROOM VIEW: Active room status, synchronized track, and members
            int topY = y + 2;

            // Header Banner
            drawSoftBox(graphics, x + 8, topY, w - 16, 36, 4, 0x25A855F7, 0x60A855F7);
            String rCode = partyManager.getRoomCode();

            // Action Buttons on Right: [ ⎘ #Code ] and [ ✕ Disband / Leave ]
            int copyBtnW = 62;
            int exitBtnW = partyManager.isHost() ? 66 : 56;
            int exitBtnX = x + w - exitBtnW - 14;
            int copyBtnX = exitBtnX - copyBtnW - 4;
            int exitBtnY = topY + 9;
            int exitBtnH = 18;

            int maxLeftW = copyBtnX - (x + 14) - 6;

            // Row 1: Room Name (safely truncated before button margin)
            String rName = partyManager.getRoomName();
            if (rName == null || rName.isBlank()) rName = "Music Room";
            graphics.text(this.font, Component.literal("§d👥 §f§l" + truncateToWidth(rName, maxLeftW)), x + 14, topY + 5, 0xFFFFFFFF, true);

            // Row 2: Role Info (safely truncated before button margin)
            String roleInfo = partyManager.isHost()
                    ? "§e👑 Host §8• Broadcasting to room"
                    : "§a🎧 Synced §8• §f" + truncate(partyManager.getCurrentHost(), 12);
            graphics.text(this.font, Component.literal(truncateToWidth(roleInfo, maxLeftW)), x + 14, topY + 18, 0xFFA0988E, false);

            if (rCode != null && !rCode.isEmpty()) {
                boolean cpHov = mouseX >= copyBtnX && mouseX <= copyBtnX + copyBtnW && mouseY >= exitBtnY && mouseY <= exitBtnY + exitBtnH;
                drawSoftBox(graphics, copyBtnX, exitBtnY, copyBtnW, exitBtnH, 3, cpHov ? 0x90A855F7 : 0x40A855F7, cpHov ? 0xFFFFFFFF : 0xFFA855F7);
                drawCleanCenteredText(graphics, "§e#" + rCode, copyBtnX + copyBtnW / 2, exitBtnY + 5, 0xFFFFFFFF);
            }

            // Right Action Button: [ ✕ Disband ] (if Host) or [ ✕ Leave ] (if Member)
            boolean exitHov = mouseX >= exitBtnX && mouseX <= exitBtnX + exitBtnW && mouseY >= exitBtnY && mouseY <= exitBtnY + exitBtnH;
            drawSoftBox(graphics, exitBtnX, exitBtnY, exitBtnW, exitBtnH, 3, exitHov ? 0x90FF3344 : 0x35FF3344, exitHov ? 0xFFFF4455 : 0x80FF3344);
            drawCleanCenteredText(graphics, partyManager.isHost() ? "§c✕ Disband" : "§c✕ Leave", exitBtnX + exitBtnW / 2, exitBtnY + 5, 0xFFFFFFFF);

            // Sync Status Card
            int syncCardY = topY + 42;
            int syncCardH = 40;
            drawSoftBox(graphics, x + 8, syncCardY, w - 16, syncCardH, 4, 0x18FFFFFF, 0x25FFFFFF);

            AudioTrack cur = audioManager.getCurrentTrack();
            if (cur != null) {
                graphics.text(this.font, Component.literal("§d♫ " + truncateToWidth(cur.getTitle(), w - 110)), x + 14, syncCardY + 4, 0xFFFFFFFF, true);
                String artistAndSync = "§7" + truncate(cur.getArtist(), 20) + " §8• §a∿ Synchronized";
                graphics.text(this.font, Component.literal(artistAndSync), x + 14, syncCardY + 16, 0xFFA0988E, false);

                if (!partyManager.isHost()) {
                    // Re-sync button for members
                    int resyncBtnW = 62;
                    int resyncBtnX = x + w - resyncBtnW - 14;
                    int resyncBtnY = syncCardY + 10;
                    int resyncBtnH = 18;
                    boolean resHov = mouseX >= resyncBtnX && mouseX <= resyncBtnX + resyncBtnW && mouseY >= resyncBtnY && mouseY <= resyncBtnY + resyncBtnH;
                    drawSoftBox(graphics, resyncBtnX, resyncBtnY, resyncBtnW, resyncBtnH, 3, resHov ? 0x80A855F7 : 0x35A855F7, resHov ? 0xFFFFFFFF : 0xFFA855F7);
                    drawCleanCenteredText(graphics, "§f⟳ Re-sync", resyncBtnX + resyncBtnW / 2, resyncBtnY + 5, 0xFFFFFFFF);
                }
            } else {
                graphics.text(this.font, Component.literal("§8♫ No music playing right now"), x + 14, syncCardY + 6, 0xFF888888, false);
                String guide = partyManager.isHost()
                        ? "§7Choose any song from For You, Local or URL to broadcast!"
                        : "§7Waiting for room host to start playing music...";
                graphics.text(this.font, Component.literal(guide), x + 14, syncCardY + 20, 0xFFA0988E, false);
            }

            // Members List Card
            int memCardY = syncCardY + syncCardH + 6;
            int memCardH = h - (memCardY - y) - 4;
            drawSoftBox(graphics, x + 8, memCardY, w - 16, memCardH, 4, 0x12FFFFFF, 0x20FFFFFF);

            List<String> members = partyManager.getMembers();
            graphics.text(this.font, Component.literal("§fConnected Listeners §8(" + members.size() + "):"), x + 14, memCardY + 4, 0xFFFFFFFF, true);

            int memItemY = memCardY + 18;
            int maxMemDisp = Math.max(1, (memCardH - 22) / 16);
            int dispCount = Math.min(members.size(), maxMemDisp);
            for (int i = 0; i < dispCount; i++) {
                String mem = members.get(i);
                boolean isHostMem = mem.contains("[Host]");
                boolean isMe = mem.contains("(You)");

                drawSoftBox(graphics, x + 12, memItemY, w - 24, 14, 3, 0x15FFFFFF, 0);
                String badge = isHostMem ? "§6👑 " : (isMe ? "§b👤 " : "§a🎧 ");
                graphics.text(this.font, Component.literal(badge + "§f" + mem), x + 16, memItemY + 3, 0xFFFFFFFF, false);
                memItemY += 16;
            }
        }
    }

    private void renderPasswordModal(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        if (passwordPromptRoom == null) return;

        // Darkened backdrop
        graphics.fill(0, 0, this.width, this.height, 0xB0000000);

        int mw = 220;
        int mh = 104;
        int mx = (this.width - mw) / 2;
        int my = (this.height - mh) / 2;

        // Royal purple bordered glass card
        drawSoftBox(graphics, mx, my, mw, mh, 5, 0xF0181A1B, 0xFFA855F7);
        graphics.fill(mx + 6, my + 1, mx + mw - 6, my + 2, 0x30FFFFFF);

        // Header
        graphics.centeredText(this.font, Component.literal("§e🔒 Enter Room Password"), mx + mw / 2, my + 8, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("§7Room: §f" + truncate(passwordPromptRoom.getName(), 18)), mx + mw / 2, my + 22, 0xFFA0988E);

        // Password Input Box
        int pBoxX = mx + 16;
        int pBoxY = my + 38;
        int pBoxW = mw - 32;
        int pBoxH = 20;
        drawSoftBox(graphics, pBoxX, pBoxY, pBoxW, pBoxH, 3, 0x80000000, 0xFFA855F7);

        if (promptPasswordText.isEmpty()) {
            graphics.text(this.font, Component.literal("§8Enter password..."), pBoxX + 6, pBoxY + 6, 0xFF888888, false);
        } else {
            String cursor = (System.currentTimeMillis() / 500 % 2 == 0) ? "§d_" : "";
            graphics.text(this.font, Component.literal("§f" + promptPasswordText + cursor), pBoxX + 6, pBoxY + 6, 0xFFFFFFFF, false);
        }

        // Error message if any
        if (!passwordErrorMsg.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(passwordErrorMsg), mx + mw / 2, my + 60, 0xFFFF5555);
        }

        // Action Buttons Row
        int btnY = my + 74;
        int btnW = 84;
        int btnH = 20;
        int cancelX = mx + 16;
        int joinX = mx + mw - btnW - 16;

        // Cancel button
        boolean cancelHov = mouseX >= cancelX && mouseX <= cancelX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        drawSoftBox(graphics, cancelX, btnY, btnW, btnH, 3, cancelHov ? 0x40FFFFFF : 0x20FFFFFF, cancelHov ? 0xFFFFFFFF : 0x30FFFFFF);
        graphics.centeredText(this.font, Component.literal("§7✕ Cancel"), cancelX + btnW / 2, btnY + 6, 0xFFFFFFFF);

        // Join button
        boolean joinHov = mouseX >= joinX && mouseX <= joinX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
        drawSoftBox(graphics, joinX, btnY, btnW, btnH, 3, joinHov ? 0x90A855F7 : 0x50A855F7, joinHov ? 0xFFFFFFFF : 0xFFA855F7);
        graphics.centeredText(this.font, Component.literal("§f▶ Join"), joinX + btnW / 2, btnY + 6, 0xFFFFFFFF);
    }

    private void submitPasswordPrompt() {
        if (passwordPromptRoom == null) return;
        boolean ok = partyManager.joinRoom(passwordPromptRoom.getName(), promptPasswordText);
        if (ok) {
            statusMessage = "§a🎉 Joining room '" + passwordPromptRoom.getName() + "'... ✨";
            statusMessageTime = System.currentTimeMillis();
            passwordPromptRoom = null;
            promptPasswordText = "";
            passwordErrorMsg = "";
        } else {
            passwordErrorMsg = "§cIncorrect password!";
        }
    }

    private boolean handleRoomsClick(int startX, int browserY, int leftW, int browserH, int mx, int my) {
        int x = startX;
        int y = browserY;
        int w = leftW;
        int h = browserH;

        if (!partyManager.isInRoom()) {
            int topY = y + 2;
            int btnH = 16;
            int rightEdge = x + w - 8;
            int modeBtnW = isCreatingRoom ? 84 : 80;
            int modeBtnX = rightEdge - modeBtnW;
            int refreshBtnW = 56;
            int refreshBtnX = modeBtnX - refreshBtnW - 4;

            // Refresh Button
            if (mx >= refreshBtnX && mx <= refreshBtnX + refreshBtnW && my >= topY && my <= topY + btnH) {
                playClickSound();
                partyManager.refreshRooms();
                statusMessage = "§a⟳ Refreshing music rooms list... ✨";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // Mode Toggle: [ ＋ Create Room ] or [ ✕ Browse ]
            if (mx >= modeBtnX && mx <= modeBtnX + modeBtnW && my >= topY && my <= topY + btnH) {
                playClickSound();
                isCreatingRoom = !isCreatingRoom;
                if (isCreatingRoom && newRoomName.isEmpty()) {
                    try {
                        if (Minecraft.getInstance().getUser() != null) {
                            newRoomName = Minecraft.getInstance().getUser().getName() + "'s Room";
                        }
                    } catch (Throwable ignored) {
                        newRoomName = "My Music Room";
                    }
                }
                isRoomNameFocused = false;
                isRoomPasswordFocused = false;
                return true;
            }

            int contentY = topY + 32;
            int contentH = (y + h) - contentY - 4;

            if (isCreatingRoom) {
                int formY = contentY + 8;
                int field1Y = formY + 26;
                int inputW = Math.max(120, w - 48);
                int inputH = 18;
                int nameBoxY = field1Y + 11;

                // Click Room Name input box
                if (mx >= x + 16 && mx <= x + 16 + inputW && my >= nameBoxY && my <= nameBoxY + inputH) {
                    playClickSound();
                    isRoomNameFocused = true;
                    isRoomPasswordFocused = false;
                    return true;
                }

                int field2Y = nameBoxY + 22;
                int passBoxY = field2Y + 11;

                // Click Password input box
                if (mx >= x + 16 && mx <= x + 16 + inputW && my >= passBoxY && my <= passBoxY + inputH) {
                    playClickSound();
                    isRoomPasswordFocused = true;
                    isRoomNameFocused = false;
                    return true;
                }

                int badgeY = passBoxY + 22;
                int actY = badgeY + 16;
                int actBtnH = 20;
                int cancelBtnW = 60;
                int cancelBtnX = x + 16;
                int createBtnW = 100;
                int createBtnX = cancelBtnX + cancelBtnW + 8;

                // Cancel button
                if (mx >= cancelBtnX && mx <= cancelBtnX + cancelBtnW && my >= actY && my <= actY + actBtnH) {
                    playClickSound();
                    isCreatingRoom = false;
                    isRoomNameFocused = false;
                    isRoomPasswordFocused = false;
                    return true;
                }

                // Create & Host button
                if (mx >= createBtnX && mx <= createBtnX + createBtnW && my >= actY && my <= actY + actBtnH) {
                    playClickSound();
                    String finalName = newRoomName.trim();
                    if (finalName.isEmpty()) {
                        try {
                            if (Minecraft.getInstance().getUser() != null) {
                                finalName = Minecraft.getInstance().getUser().getName() + "'s Room";
                            }
                        } catch (Throwable ignored) {
                            finalName = "Music Room";
                        }
                    }
                    partyManager.createRoom(finalName, newRoomPassword.trim());
                    isCreatingRoom = false;
                    isRoomNameFocused = false;
                    isRoomPasswordFocused = false;
                    statusMessage = "§a🎉 Created room '" + finalName + "'! Broadcasting music... ✨";
                    statusMessageTime = System.currentTimeMillis();
                    return true;
                }

            } else {
                // Browse Rooms List Click Handling
                List<PartyManager.RoomEntry> rooms = partyManager.getDiscoveredRooms();
                if (!rooms.isEmpty()) {
                    int itemH = 30;
                    int visibleCount = Math.max(1, contentH / itemH);
                    int startIdx = Math.min(roomScrollOffset, Math.max(0, rooms.size() - visibleCount));
                    int endIdx = Math.min(rooms.size(), startIdx + visibleCount);

                    int itemY = contentY;
                    for (int i = startIdx; i < endIdx; i++) {
                        PartyManager.RoomEntry r = rooms.get(i);
                        int joinBtnW = 48;
                        int joinBtnX = x + w - joinBtnW - 14;
                        int joinBtnY = itemY + 5;
                        int joinBtnH = 18;

                        if (mx >= joinBtnX && mx <= joinBtnX + joinBtnW && my >= joinBtnY && my <= joinBtnY + joinBtnH) {
                            playClickSound();
                            if (r.hasPassword()) {
                                passwordPromptRoom = r;
                                promptPasswordText = "";
                                passwordErrorMsg = "";
                            } else {
                                partyManager.joinRoom(r.getName(), "");
                                statusMessage = "§a🎉 Joined '" + r.getName() + "'! Synchronizing audio... ✨";
                                statusMessageTime = System.currentTimeMillis();
                            }
                            return true;
                        }

                        itemY += itemH;
                    }
                }
            }

        } else {
            // In-Room Click Handling
            int topY = y + 2;
            int copyBtnW = 62;
            int exitBtnW = partyManager.isHost() ? 66 : 56;
            int exitBtnX = x + w - exitBtnW - 14;
            int copyBtnX = exitBtnX - copyBtnW - 4;
            int exitBtnY = topY + 9;
            int exitBtnH = 18;

            // Copy Code button
            String rCode = partyManager.getRoomCode();
            if (rCode != null && !rCode.isEmpty() && mx >= copyBtnX && mx <= copyBtnX + copyBtnW && my >= exitBtnY && my <= exitBtnY + exitBtnH) {
                playClickSound();
                setClipboardString(rCode);
                statusMessage = "§a📋 Copied room code #" + rCode + " to clipboard! ✨";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // Leave or Disband
            if (mx >= exitBtnX && mx <= exitBtnX + exitBtnW && my >= exitBtnY && my <= exitBtnY + exitBtnH) {
                playClickSound();
                if (partyManager.isHost()) {
                    partyManager.disbandRoom();
                    statusMessage = "§cRoom disbanded.";
                } else {
                    partyManager.leaveRoom();
                    statusMessage = "§eLeft room.";
                }
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // Re-sync button (for members)
            if (!partyManager.isHost()) {
                int syncCardY = topY + 42;
                int resyncBtnW = 62;
                int resyncBtnX = x + w - resyncBtnW - 14;
                int resyncBtnY = syncCardY + 10;
                int resyncBtnH = 18;
                if (mx >= resyncBtnX && mx <= resyncBtnX + resyncBtnW && my >= resyncBtnY && my <= resyncBtnY + resyncBtnH) {
                    playClickSound();
                    partyManager.reSync();
                    statusMessage = "§a⟳ Re-syncing audio with host... ✨";
                    statusMessageTime = System.currentTimeMillis();
                    return true;
                }
            }
        }
        return false;
    }

    // Tab 5: Settings & Accessibility
    private void renderSettingsTab(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
            SettingsManager sm = SettingsManager.getInstance();
            int curY = y + 2;

            graphics.text(this.font, Component.literal("§f§l⚙ Settings §8& §dAccessibility"), x + 10, curY, 0xFFFFFFFF, false);
            graphics.text(this.font, Component.literal("§7Choose audio output streams, devices & accessibility options."), x + 10, curY + 12, 0xFFA0988E, false);

            int listStartY = curY + 26;
            int rowH = 32;
            int totalSettings = 12;
            int visibleSettings = getVisibleSettingsCount();
            int startIdx = Math.min(settingsScrollOffset, Math.max(0, totalSettings - visibleSettings));
            int endIdx = Math.min(totalSettings, startIdx + visibleSettings);

            int itemY = listStartY;
            for (int i = startIdx; i < endIdx; i++) {
                try {
                    int rowBg = 0x18FFFFFF;
                    drawSoftBox(graphics, x + 8, itemY, w - 16, rowH - 4, 3, rowBg, 0x25FFFFFF);

                    int btnY = itemY + 5;
                    int btnH = 18;

                    if (i == 0) {
                        // 1. Output Stream / Primary Audio Device
                        String curDev = sm.getOutputDevice();
                        int arrowBtnW = 18;
                        int labelW = Math.max(65, Math.min(135, w - 215));
                        int rightEdge = x + w - 14;
                        int nextBtnX = rightEdge - arrowBtnW;
                        int labelBoxX = nextBtnX - labelW - 4;
                        int prevBtnX = labelBoxX - arrowBtnW - 4;

                        int maxDescW = prevBtnX - (x + 14) - 6;
                        boolean isVoiceChat = curDev.equalsIgnoreCase(com.slowbag.pulse.client.audio.SettingsManager.VOICE_CHAT_DEVICE);
                        String header = isVoiceChat ? "§fVoice Chat Microphone:" : "§fPrimary Audio Output:";
                        String sub = isVoiceChat ? "§dDirect in-game mic broadcast to nearby players" : "§8Route sound to speakers or headphones";
                        graphics.text(this.font, Component.literal(truncateToWidth(header, maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth(sub, maxDescW)), x + 14, itemY + 15, isVoiceChat ? 0xFFE9D5FF : 0xFF888888, false);

                        // Prev ◀
                        boolean prevHov = mouseX >= prevBtnX && mouseX <= prevBtnX + arrowBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, prevBtnX, btnY, arrowBtnW, btnH, 3, prevHov ? 0x80A855F7 : 0x30FFFFFF, prevHov ? 0xFFA855F7 : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, "◀", prevBtnX + arrowBtnW / 2, btnY + 5, 0xFFFFFFFF);

                        // Device Name Box
                        boolean devHov = mouseX >= labelBoxX && mouseX <= labelBoxX + labelW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, labelBoxX, btnY, labelW, btnH, 3, devHov ? 0x40A855F7 : 0x25000000, devHov ? 0xFFA855F7 : 0x50A855F7);
                        drawCleanCenteredText(graphics, truncateToWidth("§d" + curDev, labelW - 4), labelBoxX + labelW / 2, btnY + 5, 0xFFFFFFFF);

                        // Next ▶
                        boolean nextHov = mouseX >= nextBtnX && mouseX <= nextBtnX + arrowBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, nextBtnX, btnY, arrowBtnW, btnH, 3, nextHov ? 0x80A855F7 : 0x30FFFFFF, nextHov ? 0xFFA855F7 : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, "▶", nextBtnX + arrowBtnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 1) {
                        // 2. Configurable Hotkey to Open Music Player GUI (Prominently placed on Row 2)
                        com.slowbag.pulse.client.input.KeybindManager km = com.slowbag.pulse.client.input.KeybindManager.getInstance();
                        int btnW = 96;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fOpen Player Hotkey:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Click to record key or combo (e.g. J, Ctrl+Q)", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        boolean listening = (km != null && km.isListening());
                        String keyText = listening ? "§e[ Press key... ]" : ("§f[ " + (km != null ? km.getKeybindDisplay() : "J") + " ]");
                        int bgCol = listening ? 0x90F59E0B : (bHov ? 0x80A855F7 : 0x30FFFFFF);
                        int outlineCol = listening ? 0xFFFBBF24 : (bHov ? 0xFFA855F7 : 0x40FFFFFF);
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, bgCol, outlineCol);
                        drawCleanCenteredText(graphics, keyText, btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 2) {
                        // 3. In-Game HUD Overlay
                        HudConfigManager hudCfg = HudConfigManager.getInstance();
                        int moveBtnW = 44;
                        int moveBtnX = x + w - moveBtnW - 14;
                        int modeBtnW = 54;
                        int modeBtnX = moveBtnX - modeBtnW - 6;

                        int maxDescW = modeBtnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fIn-Game HUD Overlay:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Floating now-playing HUD widget in game", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        String hudText = !hudCfg.isHudEnabled() ? "Disabled" : hudCfg.getPosition();
                        boolean modeHov = mouseX >= modeBtnX && mouseX <= modeBtnX + modeBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, modeBtnX, btnY, modeBtnW, btnH, 3, hudCfg.isHudEnabled() ? (modeHov ? 0x80A855F7 : 0x40A855F7) : (modeHov ? 0x50FFFFFF : 0x25FFFFFF), modeHov ? 0xFFFFFFFF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, hudText, modeBtnX + modeBtnW / 2, btnY + 5, 0xFFFFFFFF);

                        boolean moveHov = mouseX >= moveBtnX && mouseX <= moveBtnX + moveBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, moveBtnX, btnY, moveBtnW, btnH, 3, moveHov ? 0x8038BDF8 : 0x4038BDF8, moveHov ? 0xFFFFFFFF : 0x5038BDF8);
                        drawCleanCenteredText(graphics, "✥ Move", moveBtnX + moveBtnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 3) {
                        // 4. Nametag Music Badge
                        boolean badge = com.slowbag.pulse.client.audio.MusicBadgeManager.isBadgeEnabled();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fPlayer Nametag Badge:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Animated music note above nametag", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, badge ? (bHov ? 0xFFC084FC : 0xFFA855F7) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFE9D5FF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, badge ? "✔ Active" : "✕ Hidden", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 4) {
                        // 5. Mono Audio Downmix (Accessibility)
                        boolean mono = sm.isMonoAudio();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fMono Audio Downmix:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Combines left & right channels for hearing", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, mono ? (bHov ? 0xFFC084FC : 0xFFA855F7) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFE9D5FF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, mono ? "✔ Mono (On)" : "Stereo (Off)", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 5) {
                        // 6. Reduced Motion / Visualizer (Accessibility)
                        boolean reduced = sm.isReducedMotion();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fWaveform Motion:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Pauses or softens dancing animations", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, reduced ? (bHov ? 0xFFE9D5FF : 0xFFA855F7) : (bHov ? 0xFFC084FC : 0xFFA855F7), bHov ? 0xFFFFFFFF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, reduced ? "Reduced" : "Dynamic", btnX + btnW / 2, btnY + 5, reduced ? 0xFF000000 : 0xFFFFFFFF);

                    } else if (i == 6) {
                        // 7. Background Audio Playback
                        boolean bg = sm.isBackgroundPlayback();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fBackground Audio Playback:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Keep songs playing when unfocused", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, bg ? (bHov ? 0xFFC084FC : 0xFFA855F7) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFE9D5FF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, bg ? "Always Play" : "Mute Focus", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 7) {
                        // 8. Network Lag Buffer Cushion
                        int preloadSec = sm.getPreloadSeconds();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fNetwork Lag Buffer Shield:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Preload audio to prevent lag stutter", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        String bufLabel = (preloadSec == 2) ? "2s Fast" : ((preloadSec == 5) ? "✔ 5s Shield" : "10s Max");
                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, bHov ? 0x80A855F7 : 0x35FFFFFF, bHov ? 0xFFA855F7 : 0x40FFFFFF);
                        drawCleanCenteredText(graphics, bufLabel, btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 8) {
                        // 9. Toast Alerts & Notifications
                        boolean toast = sm.isToastNotifications();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fIn-Game Toast Alerts:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Display popups on track changes", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, toast ? (bHov ? 0xFFC084FC : 0xFFA855F7) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFE9D5FF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, toast ? "✔ Enabled" : "✕ Muted", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 9) {
                        // 10. Game Sound Auto-Ducking
                        boolean duck = sm.isAutoDucking();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fGame Sound Auto-Ducking:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Lower ambient sounds during music", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, duck ? (bHov ? 0xFFC084FC : 0xFFA855F7) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFE9D5FF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, duck ? "✔ Ducking" : "✕ Normal", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 10) {
                        // 11. Continuous Playback
                        boolean cont = sm.isContinuousPlayback();
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fContinuous Playback:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Keep playing similar songs at end", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, cont ? (bHov ? 0xFFC084FC : 0xFFA855F7) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFE9D5FF : 0x35FFFFFF);
                        drawCleanCenteredText(graphics, cont ? "✔ Endless" : "✕ Stop", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                    } else if (i == 11) {
                        // 12. Audio Cache Storage
                        int btnW = 86;
                        int btnX = x + w - btnW - 14;

                        int maxDescW = btnX - (x + 14) - 6;
                        graphics.text(this.font, Component.literal(truncateToWidth("§fAudio Stream Cache:", maxDescW)), x + 14, itemY + 3, 0xFFFFFFFF, false);
                        graphics.text(this.font, Component.literal(truncateToWidth("§8Clear temp audio chunks on disk", maxDescW)), x + 14, itemY + 15, 0xFF888888, false);

                        boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                        drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, bHov ? 0x90FF3344 : 0x40FF3344, bHov ? 0xFFFF4455 : 0x60FF4455);
                        drawCleanCenteredText(graphics, "Clear Cache", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);
                    }
                } catch (Throwable rowError) {
                    MusicPlayerMod.LOGGER.error("[Music Player] Error rendering settings row " + i, rowError);
                }

                itemY += rowH;
            }

            // Visible Royal Purple Scrollbar Cue on right edge
            if (totalSettings > visibleSettings) {
                int scrollTrackX = x + w - 8;
                int scrollTrackY = listStartY;
                int scrollTrackH = Math.min(visibleSettings * rowH - 4, (y + h) - listStartY - 4);
                // Track groove
                graphics.fill(scrollTrackX, scrollTrackY, scrollTrackX + 3, scrollTrackY + scrollTrackH, 0x25FFFFFF);
                // Glowing thumb knob
                float ratio = (float) startIdx / (float) (totalSettings - visibleSettings);
                int thumbH = Math.max(16, (int) ((float) visibleSettings / (float) totalSettings * scrollTrackH));
                int thumbY = scrollTrackY + (int) (ratio * (scrollTrackH - thumbH));
                graphics.fill(scrollTrackX, thumbY, scrollTrackX + 3, thumbY + thumbH, 0xFFA855F7);
                graphics.fill(scrollTrackX + 1, thumbY + 1, scrollTrackX + 2, thumbY + thumbH - 1, 0xFFE9D5FF);
            }
    }

    // Single Song Row Component matching reference layout
    private void renderTrackRow(GuiGraphicsExtractor graphics, int rx, int ry, int rw, AudioTrack t, int mouseX, int mouseY) {
        boolean rowHover = mouseX >= rx && mouseX <= rx + rw && mouseY >= ry && mouseY <= ry + 22;
        boolean isCurrent = (audioManager.getCurrentTrack() != null && audioManager.getCurrentTrack().getTitle().equals(t.getTitle()));
        boolean isFav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().isFavorite(t);

        // Warm Obsidian pill with amber glow for Selected/Current track, subtle dark glass for others
        if (isCurrent) {
            drawSoftBox(graphics, rx, ry, rw, 22, 3, 0xF0181424, 0xFFA855F7);
            graphics.fill(rx + 2, ry + 4, rx + 4, ry + 18, 0xFFC084FC); // Left accent indicator
        } else {
            int bg = rowHover ? 0x25FFFFFF : 0x14FFFFFF;
            int outline = rowHover ? 0x30FFFFFF : 0;
            drawSoftBox(graphics, rx, ry, rw, 22, 3, bg, outline);
        }

        // Mini thumbnail / icon
        int thumbX = rx + 6;
        int thumbY = ry + 3;
        drawSoftBox(graphics, thumbX, thumbY, 16, 16, 3, isCurrent ? 0xFF181A1B : 0xFF16181B, isCurrent ? 0xFFA855F7 : 0x35FFFFFF);
        if (isCurrent && AlbumArtManager.getInstance().getAlbumArtId() != null) {
            graphics.blit(AlbumArtManager.getInstance().getAlbumArtId(), thumbX + 1, thumbY + 1, thumbX + 15, thumbY + 15, 0.0f, 1.0f, 0.0f, 1.0f);
        } else if (isCurrent) {
            graphics.blit(AlbumArtManager.getInstance().getDefaultAlbumArtId(), thumbX + 1, thumbY + 1, thumbX + 15, thumbY + 15, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            graphics.text(this.font, Component.literal("§7♫"), thumbX + 4, thumbY + 4, 0xFFA0988E, false);
        }

        // Title and Subtitle: Razor-sharp clean rendering without drop shadows or blurry double layers
        int textX = thumbX + 22;
        int heartX = rx + rw - 86;
        int maxTitleW = Math.max(30, heartX - textX - 6);
        String titlePrefix = isCurrent ? "§d§l" : "§f";
        String artistPrefix = isCurrent ? "§e" : "§7";
        graphics.text(this.font, Component.literal(titlePrefix + truncateToWidth(t.getTitle(), maxTitleW, isCurrent)), textX, ry + 3, isCurrent ? 0xFFFFD090 : 0xFFFFFFFF, false);
        graphics.text(this.font, Component.literal(artistPrefix + truncateToWidth(t.getArtist() + " • " + t.getCategory(), maxTitleW, false)), textX, ry + 13, isCurrent ? 0xFFE9D5FF : 0xFFA0988E, false);

        // Heart Icon: Amber heart
        int heartY = ry + 4;
        boolean heartHover = mouseX >= heartX && mouseX <= heartX + 14 && mouseY >= heartY && mouseY <= heartY + 14;
        String heartStr = isFav ? "§d♥" : (heartHover ? "§d♡" : (isCurrent ? "§d♡" : "§7♡"));
        graphics.text(this.font, Component.literal(heartStr), heartX + 2, heartY + 2, isFav || heartHover ? 0xFFA855F7 : 0xFF888888, false);

        // "+Q" Queue Next Button
        int qBtnX = rx + rw - 69;
        int qBtnY = ry + 3;
        boolean qBtnHover = mouseX >= qBtnX && mouseX <= qBtnX + 22 && mouseY >= qBtnY && mouseY <= qBtnY + 16;
        int qBg = qBtnHover ? 0x80A855F7 : (isCurrent ? 0x40A855F7 : 0x20FFFFFF);
        drawSoftBox(graphics, qBtnX, qBtnY, 22, 16, 3, qBg, qBtnHover ? 0xFFA855F7 : 0x30FFFFFF);
        graphics.centeredText(this.font, Component.literal("§d+Q"), qBtnX + 11, qBtnY + 4, 0xFFFFFFFF);

        // "▶ Play" Pill Button
        int btnX = rx + rw - 44;
        int btnY = ry + 3;
        boolean btnHover = mouseX >= btnX && mouseX <= btnX + 40 && mouseY >= btnY && mouseY <= btnY + 16;
        int btnBg = btnHover ? 0xFFC084FC : (isCurrent ? 0x90A855F7 : 0x35FFFFFF);
        drawSoftBox(graphics, btnX, btnY, 40, 16, 3, btnBg, btnHover ? 0xFFE9D5FF : (isCurrent ? 0xFFA855F7 : 0x40FFFFFF));
        graphics.centeredText(this.font, Component.literal("§f▶ Play"), btnX + 20, btnY + 4, 0xFFFFFFFF);
    }

    // Volume Card (Top-Right)
    private void renderVolumeCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0x78141619, 0x40FFFFFF, 0x18FFFFFF);

        float vol = audioManager.getVolume();
        boolean muted = audioManager.isMuted();

        String volIcon = (muted || vol == 0) ? "§c🔇" : (vol < 0.4f ? "§7🔈" : (vol < 0.8f ? "§f🔉" : "§d🔊"));
        graphics.text(this.font, Component.literal(volIcon), x + 8, y + 8, 0xFFFFFFFF, false);

        int sliderX = x + 26;
        int sliderY = y + 11;
        int sliderW = w - 74;
        int sliderH = 4;

        // Background groove (rounded soft edges)
        drawSoftBox(graphics, sliderX, sliderY, sliderW, sliderH, 1, 0x35FFFFFF, 0);
        int filled = (int) (sliderW * vol);
        // Filled warm amber bar
        if (filled > 0) {
            drawSoftBox(graphics, sliderX, sliderY, filled, sliderH, 1, 0xFFA855F7, 0);
        }
        // Cream circular handle with amber accent
        drawSoftBox(graphics, sliderX + filled - 3, sliderY - 3, 7, sliderH + 6, 2, 0xFFF5F0E6, 0xFFA855F7);

        String volText = (muted) ? "§c0%" : ((int) (vol * 100) + "%");
        graphics.text(this.font, Component.literal("§7" + volText), sliderX + sliderW + 6, y + 8, 0xFFA0988E, false);

        // Mute button toggle on far right
        int muteBtnX = x + w - 18;
        boolean muteHover = mouseX >= muteBtnX && mouseX <= muteBtnX + 14 && mouseY >= y + 6 && mouseY <= y + 20;
        graphics.text(this.font, Component.literal(muted ? "§d🔊" : "§7🔇"), muteBtnX, y + 8, 0xFFFFFFFF, false);
    }

    // Mini Waveform Card (Middle-Right)
    private void renderMiniWaveformCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0x78141619, 0x40FFFFFF, 0x18FFFFFF);

        graphics.text(this.font, Component.literal("§d🔊"), x + 8, y + 12, 0xFFA855F7, false);

        float[] rawBands = audioManager.getVisualizerBands();
        boolean isPlaying = audioManager.isPlaying() && !audioManager.isPaused();
        int waveX = x + 26;
        int dotsW = 20;
        int availWaveW = w - 26 - dotsW - 6;
        int waveY = y + h - 8;
        int bars = 16;
        int maxH = h - 14;

        for (int i = 0; i < bars; i++) {
            float target = (rawBands != null && isPlaying && !SettingsManager.getInstance().isReducedMotion()) ? rawBands[i % 16] : 0.05f;
            int barH = Math.max(2, (int) (target * maxH * 1.8f));
            barH = Math.min(maxH, barH);

            int bx = waveX + (i * (availWaveW / bars));
            int by = waveY - barH;
            int color = AnimationUtil.colorLerp(0xFFA855F7, 0xFFE9D5FF, (float) i / (float) bars);
            graphics.fill(bx, by, bx + 3, waveY, color);
        }

        // Right three dots menu icon matching reference
        graphics.text(this.font, Component.literal("§8•••"), x + w - dotsW, y + 12, 0xFF8A8278, false);
    }

    // Queue Card (Bottom-Right)
    private void renderQueueCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0x78141619, 0x40FFFFFF, 0x18FFFFFF);

        List<AudioTrack> upNext = audioManager.getUpcomingQueue();
        int upNextCount = upNext.size();

        String queueHeader = (upNextCount > 0) ? ("§f§lQueue §7(" + upNextCount + ")") : "§f§lQueue §7(Empty)";
        graphics.text(this.font, Component.literal(queueHeader), x + 8, y + 7, 0xFFFFFFFF, true);

        // Clear & Shuffle queue buttons
        if (upNextCount > 0) {
            int clearX = x + w - 40;
            int shufX = clearX - 46;

            // Shuffle queue button (🔀 Shuf)
            boolean shufHov = mouseX >= shufX && mouseX <= shufX + 42 && mouseY >= y + 5 && mouseY <= y + 17;
            drawSoftBox(graphics, shufX, y + 5, 42, 12, 3, shufHov ? 0x80A855F7 : 0x20FFFFFF, shufHov ? 0xFFE9D5FF : 0x40FFFFFF);
            graphics.centeredText(this.font, Component.literal("§d🔀 Shuf"), shufX + 21, y + 6, 0xFFFFFFFF);

            // Clear queue button
            boolean clearHov = mouseX >= clearX && mouseX <= clearX + 36 && mouseY >= y + 5 && mouseY <= y + 17;
            drawSoftBox(graphics, clearX, y + 5, 36, 12, 3, clearHov ? 0x80FF3344 : 0x20FFFFFF, clearHov ? 0xFFFF4455 : 0x40FFFFFF);
            graphics.centeredText(this.font, Component.literal("§cClear"), clearX + 18, y + 6, 0xFFFFFFFF);
        }

        int itemY = y + 24;
        if (upNext.isEmpty()) {
            int centerY = y + 24 + Math.max(10, (h - 24) / 2);
            graphics.centeredText(this.font, Component.literal("§7Queue is empty."), x + w / 2, centerY - 14, 0xFFA0988E);
            graphics.centeredText(this.font, Component.literal("§8Click §d+Q §8on any track"), x + w / 2, centerY, 0xFF706860);
            graphics.centeredText(this.font, Component.literal("§8to play it next!"), x + w / 2, centerY + 14, 0xFF706860);
        } else {
            int visibleRows = Math.max(2, (h - 26) / 26);
            int startIdx = Math.min(queueScrollOffset, Math.max(0, upNext.size() - visibleRows));
            int endIdx = Math.min(upNext.size(), startIdx + visibleRows);

            for (int i = startIdx; i < endIdx; i++) {
                AudioTrack t = upNext.get(i);
                boolean rHover = mouseX >= x + 6 && mouseX <= x + w - 6 && mouseY >= itemY && mouseY <= itemY + 24;

                int rowBg = (i == 0) ? 0x25A855F7 : (rHover ? 0x25FFFFFF : 0x10FFFFFF);
                int rowOutline = (i == 0) ? 0x50A855F7 : (rHover ? 0x30FFFFFF : 0);
                drawSoftBox(graphics, x + 6, itemY, w - 12, 24, 3, rowBg, rowOutline);

                // Queue position badge
                int thumbX = x + 8;
                int thumbY = itemY + 4;
                drawSoftBox(graphics, thumbX, thumbY, 15, 15, 3, 0xFF181A1B, 0x30FFFFFF);
                graphics.centeredText(this.font, Component.literal("§d" + (i + 1)), thumbX + 7, thumbY + 3, 0xFFFFFFFF);

                // Action buttons on right: [▲] [▼] [✕]
                int btnW = 14;
                int btnH = 15;
                int btnY = itemY + 4;
                int gap = 3;

                int removeX = x + w - 8 - btnW;
                int downX = removeX - gap - btnW;
                int upX = downX - gap - btnW;

                // Move Up button
                boolean canMoveUp = (i > 0);
                boolean upHover = canMoveUp && mouseX >= upX && mouseX <= upX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                drawSoftBox(graphics, upX, btnY, btnW, btnH, 3, upHover ? 0x60A855F7 : 0x20FFFFFF, upHover ? 0xFFE9D5FF : 0x25FFFFFF);
                graphics.centeredText(this.font, Component.literal(canMoveUp ? (upHover ? "§d▲" : "§f▲") : "§8▲"), upX + btnW / 2, btnY + 3, 0xFFFFFFFF);

                // Move Down button
                boolean canMoveDown = (i < upNext.size() - 1);
                boolean downHover = canMoveDown && mouseX >= downX && mouseX <= downX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                drawSoftBox(graphics, downX, btnY, btnW, btnH, 3, downHover ? 0x60A855F7 : 0x20FFFFFF, downHover ? 0xFFE9D5FF : 0x25FFFFFF);
                graphics.centeredText(this.font, Component.literal(canMoveDown ? (downHover ? "§d▼" : "§f▼") : "§8▼"), downX + btnW / 2, btnY + 3, 0xFFFFFFFF);

                // Remove X button
                boolean remHover = mouseX >= removeX && mouseX <= removeX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                drawSoftBox(graphics, removeX, btnY, btnW, btnH, 3, remHover ? 0x80FF3344 : 0x20FFFFFF, remHover ? 0xFFFF4455 : 0x25FFFFFF);
                graphics.centeredText(this.font, Component.literal(remHover ? "§c✕" : "§7✕"), removeX + btnW / 2, btnY + 3, 0xFFFFFFFF);

                // Song Title and Subtitle - Generously bounded, never overlapping buttons
                int textX = thumbX + 19;
                int textW = upX - textX - 8;
                graphics.text(this.font, Component.literal("§f" + truncateToWidth(t.getTitle(), textW, false)), textX, itemY + 3, 0xFFFFFFFF, false);
                String badge = (i == 0 ? "§dNext" : "§8Queued");
                int badgeW = this.font.width(badge + " • ");
                int maxArtistW = Math.max(15, textW - badgeW - 2);
                String sub = badge + " • §7" + truncateToWidth(t.getArtist(), maxArtistW, false);
                graphics.text(this.font, Component.literal(sub), textX, itemY + 13, 0xFFA0988E, false);

                itemY += 26;
            }
        }
    }

    public static void drawSoftBox(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int radius, int bg, int outline) {
        if (w <= 0 || h <= 0) return;

        // Soft curved fill
        if (bg != 0) {
            if (radius >= 5 && w >= 12 && h >= 12) {
                graphics.fill(x + 5, y, x + w - 5, y + 1, bg);
                graphics.fill(x + 3, y + 1, x + w - 3, y + 2, bg);
                graphics.fill(x + 2, y + 2, x + w - 2, y + 3, bg);
                graphics.fill(x + 1, y + 3, x + w - 1, y + 5, bg);
                graphics.fill(x, y + 5, x + w, y + h - 5, bg);
                graphics.fill(x + 1, y + h - 5, x + w - 1, y + h - 3, bg);
                graphics.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, bg);
                graphics.fill(x + 3, y + h - 2, x + w - 3, y + h - 1, bg);
                graphics.fill(x + 5, y + h - 1, x + w - 5, y + h, bg);
            } else if (radius >= 3 && w >= 8 && h >= 8) {
                graphics.fill(x + 3, y, x + w - 3, y + 1, bg);
                graphics.fill(x + 2, y + 1, x + w - 2, y + 2, bg);
                graphics.fill(x + 1, y + 2, x + w - 1, y + 3, bg);
                graphics.fill(x, y + 3, x + w, y + h - 3, bg);
                graphics.fill(x + 1, y + h - 3, x + w - 1, y + h - 2, bg);
                graphics.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, bg);
                graphics.fill(x + 3, y + h - 1, x + w - 3, y + h, bg);
            } else if (radius >= 2 && w >= 6 && h >= 6) {
                graphics.fill(x + 2, y, x + w - 2, y + 1, bg);
                graphics.fill(x + 1, y + 1, x + w - 1, y + 2, bg);
                graphics.fill(x, y + 2, x + w, y + h - 2, bg);
                graphics.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, bg);
                graphics.fill(x + 2, y + h - 1, x + w - 2, y + h, bg);
            } else if (radius >= 1 && w >= 4 && h >= 4) {
                graphics.fill(x + 1, y, x + w - 1, y + h, bg);
                graphics.fill(x, y + 1, x + w, y + h - 1, bg);
            } else {
                graphics.fill(x, y, x + w, y + h, bg);
            }
        }

        // Soft curved outline
        if (outline != 0) {
            if (radius >= 5 && w >= 12 && h >= 12) {
                graphics.fill(x + 5, y, x + w - 5, y + 1, outline);
                graphics.fill(x + 5, y + h - 1, x + w - 5, y + h, outline);
                graphics.fill(x, y + 5, x + 1, y + h - 5, outline);
                graphics.fill(x + w - 1, y + 5, x + w, y + h - 5, outline);

                graphics.fill(x + 3, y + 1, x + 5, y + 2, outline);
                graphics.fill(x + 2, y + 2, x + 3, y + 3, outline);
                graphics.fill(x + 1, y + 3, x + 2, y + 5, outline);

                graphics.fill(x + w - 5, y + 1, x + w - 3, y + 2, outline);
                graphics.fill(x + w - 3, y + 2, x + w - 2, y + 3, outline);
                graphics.fill(x + w - 2, y + 3, x + w - 1, y + 5, outline);

                graphics.fill(x + 1, y + h - 5, x + 2, y + h - 3, outline);
                graphics.fill(x + 2, y + h - 3, x + 3, y + h - 2, outline);
                graphics.fill(x + 3, y + h - 2, x + 5, y + h - 1, outline);

                graphics.fill(x + w - 2, y + h - 5, x + w - 1, y + h - 3, outline);
                graphics.fill(x + w - 3, y + h - 3, x + w - 2, y + h - 2, outline);
                graphics.fill(x + w - 5, y + h - 2, x + w - 3, y + h - 1, outline);
            } else if (radius >= 3 && w >= 8 && h >= 8) {
                graphics.fill(x + 3, y, x + w - 3, y + 1, outline);
                graphics.fill(x + 3, y + h - 1, x + w - 3, y + h, outline);
                graphics.fill(x, y + 3, x + 1, y + h - 3, outline);
                graphics.fill(x + w - 1, y + 3, x + w, y + h - 3, outline);

                graphics.fill(x + 2, y + 1, x + 3, y + 2, outline);
                graphics.fill(x + 1, y + 2, x + 2, y + 3, outline);

                graphics.fill(x + w - 3, y + 1, x + w - 2, y + 2, outline);
                graphics.fill(x + w - 2, y + 2, x + w - 1, y + 3, outline);

                graphics.fill(x + 1, y + h - 3, x + 2, y + h - 2, outline);
                graphics.fill(x + 2, y + h - 2, x + 3, y + h - 1, outline);

                graphics.fill(x + w - 2, y + h - 3, x + w - 1, y + h - 2, outline);
                graphics.fill(x + w - 3, y + h - 2, x + w - 2, y + h - 1, outline);
            } else if (radius >= 2 && w >= 6 && h >= 6) {
                graphics.fill(x + 2, y, x + w - 2, y + 1, outline);
                graphics.fill(x + 2, y + h - 1, x + w - 2, y + h, outline);
                graphics.fill(x, y + 2, x + 1, y + h - 2, outline);
                graphics.fill(x + w - 1, y + 2, x + w, y + h - 2, outline);

                graphics.fill(x + 1, y + 1, x + 2, y + 2, outline);
                graphics.fill(x + w - 2, y + 1, x + w - 1, y + 2, outline);
                graphics.fill(x + 1, y + h - 2, x + 2, y + h - 1, outline);
                graphics.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, outline);
            } else if (radius >= 1 && w >= 4 && h >= 4) {
                graphics.fill(x + 1, y, x + w - 1, y + 1, outline);
                graphics.fill(x + 1, y + h - 1, x + w - 1, y + h, outline);
                graphics.fill(x, y + 1, x + 1, y + h - 1, outline);
                graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, outline);
            } else {
                graphics.outline(x, y, w, h, outline);
            }
        }
    }

    private void drawGlassCard(GuiGraphicsExtractor graphics, int cx, int cy, int cw, int ch, int bg, int border1, int border2) {
        drawSoftBox(graphics, cx, cy, cw, ch, 5, bg, border1);
        if (border2 != 0 && cw > 4 && ch > 4) {
            drawSoftBox(graphics, cx + 1, cy + 1, cw - 2, ch - 2, 4, 0, border2);
        }
        if (cw > 14) {
            graphics.fill(cx + 6, cy + 1, cx + cw - 6, cy + 2, 0x25FFFFFF);
        }
    }

    private void drawCleanCenteredText(GuiGraphicsExtractor graphics, String text, int cx, int cy, int color) {
        if (text == null || text.isEmpty()) return;
        String clean = text.replaceAll("§[0-9a-fk-or]", "");
        Component comp = Component.literal(clean);
        int tw = this.font.width(comp);
        graphics.text(this.font, comp, cx - tw / 2, cy, color, false);
    }

    private String truncateToWidth(String text, int maxWidth) {
        return truncateToWidth(text, maxWidth, false);
    }

    private String truncateToWidth(String text, int maxWidth, boolean bold) {
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 15) return "";
        try {
            String clean = text.replaceAll("§[0-9a-fk-or]", "");
            String formatted = bold ? ("§l" + clean) : clean;
            if (this.font.width(Component.literal(formatted)) <= maxWidth) return text;
            int dotsW = this.font.width(Component.literal(bold ? ".." : ".."));
            int avail = Math.max(8, maxWidth - dotsW - 3);
            StringBuilder sb = new StringBuilder();
            int len = clean.length();
            for (int offset = 0; offset < len; ) {
                int codePoint = clean.codePointAt(offset);
                int charCount = Character.charCount(codePoint);
                sb.append(clean, offset, offset + charCount);
                String candidate = bold ? ("§l" + sb.toString()) : sb.toString();
                if (this.font.width(Component.literal(candidate)) > avail) {
                    if (sb.length() >= charCount) {
                        sb.delete(sb.length() - charCount, sb.length());
                    }
                    break;
                }
                offset += charCount;
            }
            return sb.toString().trim() + "..";
        } catch (Throwable t) {
            return text;
        }
    }

    private void drawGlassButton(GuiGraphicsExtractor graphics, int bx, int by, int bw, int bh, String text, int accent, int mx, int my) {
        boolean hover = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        boolean isActive = (accent == 0xFFA855F7 || accent == 0xFFC084FC);
        int bg;
        int outline;
        if (isActive) {
            bg = hover ? 0xFFC084FC : 0xCCA855F7;
            outline = 0xFFE9D5FF;
        } else {
            bg = hover ? 0x50FFFFFF : 0x22FFFFFF;
            outline = hover ? 0xFFA855F7 : 0x35FFFFFF;
        }

        drawSoftBox(graphics, bx, by, bw, bh, 3, bg, outline);
        drawCleanCenteredText(graphics, text, bx + bw / 2, by + (bh - 8) / 2, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        try {
            double mx = normalizeX(mouseX);
            double my = normalizeY(mouseY);

            if (isExpanded) {
                int expStartX = getExpStartX();
                int leftW = getExpLeftWidth();
                int rightW = getExpRightWidth();
                int drawerY = getDrawerY();
                int drawerH = getDrawerHeight();

                // Right column queue scroll (Only when hovering over the right column)
                if (this.width >= 340 && rightW > 40 && mx >= expStartX + leftW + 6 && mx <= expStartX + leftW + 8 + rightW) {
                    List<AudioTrack> upNext = audioManager.getUpcomingQueue();
                    int visibleQ = Math.max(2, (drawerH - 26) / 26);
                    if (verticalAmount > 0) {
                        queueScrollOffset = Math.max(0, queueScrollOffset - 1);
                    } else if (verticalAmount < 0) {
                        int max = Math.max(0, upNext.size() - visibleQ);
                        queueScrollOffset = Math.min(max, queueScrollOffset + 1);
                    }
                    return true;
                }

                // Left column tab content scroll
                int visibleRows = Math.max(3, (drawerH - 54) / 24);
                if (my >= drawerY && mx >= expStartX && mx <= expStartX + leftW + 4) {
                    if (selectedTab == 0) {
                        if (verticalAmount > 0) {
                            searchScrollOffset = Math.max(0, searchScrollOffset - 1);
                        } else if (verticalAmount < 0) {
                            int max = Math.max(0, searchResults.size() - visibleRows);
                            searchScrollOffset = Math.min(max, searchScrollOffset + 1);
                        }
                        return true;
                    } else if (selectedTab == 1) {
                        if (cachedLocalTracks.isEmpty()) {
                            cachedLocalTracks = LocalMusicScanner.scanLocalTracks();
                        }
                        List<AudioTrack> locals = cachedLocalTracks;
                        if (verticalAmount > 0) {
                            localScrollOffset = Math.max(0, localScrollOffset - 1);
                        } else if (verticalAmount < 0) {
                            int max = Math.max(0, locals.size() - visibleRows);
                            localScrollOffset = Math.min(max, localScrollOffset + 1);
                        }
                        return true;
                    } else if (selectedTab == 2) {
                        List<AudioTrack> favs = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().getFavorites();
                        if (verticalAmount > 0) {
                            favoriteScrollOffset = Math.max(0, favoriteScrollOffset - 1);
                        } else if (verticalAmount < 0) {
                            int max = Math.max(0, favs.size() - visibleRows);
                            favoriteScrollOffset = Math.min(max, favoriteScrollOffset + 1);
                        }
                        return true;
                    } else if (selectedTab == 4) {
                        List<PartyManager.RoomEntry> rooms = partyManager.getDiscoveredRooms();
                        int maxListItems = Math.max(1, (drawerH - 52) / 30);
                        if (verticalAmount > 0) {
                            roomScrollOffset = Math.max(0, roomScrollOffset - 1);
                        } else if (verticalAmount < 0) {
                            int max = Math.max(0, rooms.size() - maxListItems);
                            roomScrollOffset = Math.min(max, roomScrollOffset + 1);
                        }
                        return true;
                    } else if (selectedTab == 5) {
                        int totalSettings = 12;
                        int visible = getVisibleSettingsCount();
                        int maxScroll = Math.max(0, totalSettings - visible);
                        if (verticalAmount > 0) {
                            settingsScrollOffset = Math.max(0, settingsScrollOffset - 1);
                        } else if (verticalAmount < 0) {
                            settingsScrollOffset = Math.min(maxScroll, settingsScrollOffset + 1);
                        }
                        return true;
                    }
                }
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseScrolled", t);
            return false;
        }
    }

    
    private double normalizeX(double mx) {
        if (this.width > 0 && this.minecraft != null && this.minecraft.getWindow() != null) {
            int winW = this.minecraft.getWindow().getWidth();
            if (winW > this.width && mx > this.width * 1.05) {
                return mx * (double) this.width / (double) winW;
            }
        }
        return mx;
    }

    private double normalizeY(double my) {
        if (this.height > 0 && this.minecraft != null && this.minecraft.getWindow() != null) {
            int winH = this.minecraft.getWindow().getHeight();
            if (winH > this.height && my > this.height * 1.05) {
                return my * (double) this.height / (double) winH;
            }
        }
        return my;
    }

    private boolean handleMouseClicked(double mx, double my, int btn, boolean isDoubleClick) {
        try {
            lastClickHandledTime = System.currentTimeMillis();
            if (passwordPromptRoom != null) {
            int mw = 220, mh = 104;
            int mx0 = (this.width - mw) / 2;
            int my0 = (this.height - mh) / 2;
            int btnY = my0 + 74;
            int btnW = 84, btnH = 20;
            int cancelX = mx0 + 16;
            int joinX = mx0 + mw - btnW - 16;

            if (mx >= joinX && mx <= joinX + btnW && my >= btnY && my <= btnY + btnH) {
                playClickSound();
                submitPasswordPrompt();
                return true;
            }

            if (mx >= cancelX && mx <= cancelX + btnW && my >= btnY && my <= btnY + btnH) {
                playClickSound();
                passwordPromptRoom = null;
                promptPasswordText = "";
                passwordErrorMsg = "";
                return true;
            }

            // Click outside dialog to dismiss
            if (mx < mx0 || mx > mx0 + mw || my < my0 || my > my0 + mh) {
                passwordPromptRoom = null;
                promptPasswordText = "";
                passwordErrorMsg = "";
            }
            return true;
        }

        if (btn == 0) { // Left click
            int startX = getStartX();
            int startY = getStartY();
            int leftW = getLeftWidth();
            int rightW = getRightWidth();
            boolean twoCol = isTwoColumn();

            // 1. Top Floating Header Pill
            int pillW = Math.min(270, this.width - 20);
            int pillX = (this.width - pillW) / 2;
            int pillY = getPillY();
            int iconY = pillY + 3;
            int closeX = pillX + pillW - 20;
            int badgeBtnX = closeX - 22;
            int pinBtnX = badgeBtnX - 22;
            int settingBtnX = pinBtnX - 22;

            // Close button
            if (mx >= closeX && mx <= closeX + 16 && my >= iconY && my <= iconY + 16) {
                playClickSound();
                onClose();
                return true;
            }

            // Nametag badge toggle
            if (mx >= badgeBtnX && mx <= badgeBtnX + 16 && my >= iconY && my <= iconY + 16) {
                playClickSound();
                boolean enabled = com.slowbag.pulse.client.audio.MusicBadgeManager.toggleBadgeEnabled();
                statusMessage = enabled ? "§a✔ Nametag Music Badge: Enabled ~ Shining bright! ✨" : "§c✕ Nametag Music Badge: Disabled ~ Sleep well! 🌙";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // Pin in-game HUD
            if (mx >= pinBtnX && mx <= pinBtnX + 16 && my >= iconY && my <= iconY + 16) {
                playClickSound();
                String newSetting = HudConfigManager.getInstance().cycleSetting();
                statusMessage = "§b🎛 In-Game HUD: " + newSetting + " ~ Looking gorgeous! ✨";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // Settings & Accessibility button
            if (mx >= settingBtnX && mx <= settingBtnX + 16 && my >= iconY && my <= iconY + 16) {
                playClickSound();
                if (!isExpanded) isExpanded = true;
                selectedTab = (selectedTab == 5) ? 0 : 5;
                isSearchFocused = false;
                isUrlFocused = false;
                isRoomNameFocused = false;
                isRoomPasswordFocused = false;
                return true;
            }

            // 2. Now Playing Hero Card
            int heroX = getHeroX();
            int heroY = getHeroY();
            int heroW = getHeroWidth();
            int heroH = getHeroHeight();
            int ctrlAreaW = 118;
            int leftCardW = heroW - ctrlAreaW - 6;

            // Expand / Collapse Drawer Button at bottom center
            int toggleW = isExpanded ? Math.min(180, heroW - 40) : Math.min(220, heroW - 40);
            int toggleH = 15;
            int toggleX = heroX + (heroW - toggleW) / 2;
            int toggleY = heroY + 89;
            if (mx >= toggleX && mx <= toggleX + toggleW && my >= toggleY && my <= toggleY + toggleH) {
                playClickSound();
                isExpanded = !isExpanded;
                return true;
            }

            // Star / Favorite toggle on Hero card
            int starBtnW = 16;
            int starBtnH = 16;
            int starX = heroX + leftCardW - starBtnW - 4;
            int starY = heroY + 6;
            if (mx >= starX - 2 && mx <= starX + starBtnW + 2 && my >= starY - 2 && my <= starY + starBtnH + 2) {
                if (audioManager.getCurrentTrack() != null) {
                    playClickSound();
                    boolean fav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().toggleFavorite(audioManager.getCurrentTrack());
                    statusMessage = fav ? "§a★ Starred song added with love! ✨" : "§7☆ Removed from your favorites. We'll miss it! 💕";
                    statusMessageTime = System.currentTimeMillis();
                    return true;
                }
            }

            // Timeline Progress Scrubber
            int artW = getArtWidth();
            int infoX = heroX + 7 + artW + 8;
            int scrubX = infoX + 26;
            int scrubY = heroY + 42;
            int scrubW = (leftCardW - (scrubX - heroX)) - 32;
            int scrubH = 4;

            if (mx >= scrubX - 6 && mx <= scrubX + scrubW + 6 && my >= scrubY - 8 && my <= scrubY + scrubH + 8) {
                if (notifyIfGuestInRoom()) return true;
                if (audioManager.getCurrentTrack() != null && !audioManager.getCurrentTrack().isLiveStream() && audioManager.getCurrentTrack().getDurationSeconds() > 0) {
                    playClickSound();
                    isDraggingScrubber = true;
                    draggedScrubRatio = Math.max(0.0, Math.min(1.0, (mx - scrubX) / (double) scrubW));
                    audioManager.seekTo(draggedScrubRatio);
                    return true;
                }
            }

            // Controls on Hero Card
            int ctrlX = heroX + heroW - ctrlAreaW;
            int prevX = ctrlX + 10;
            int playX = ctrlX + 42;
            int nextX = ctrlX + 82;
            int playBtnY = heroY + 10;

            if (mx >= prevX && mx <= prevX + 24 && my >= playBtnY + 5 && my <= playBtnY + 27) {
                if (notifyIfGuestInRoom()) return true;
                playClickSound();
                audioManager.previous();
                return true;
            }

            if (mx >= playX && mx <= playX + 32 && my >= playBtnY && my <= playBtnY + 32) {
                if (notifyIfGuestInRoom()) return true;
                playClickSound();
                if (audioManager.isBuffering()) return true;
                if (audioManager.getCurrentTrack() == null) {
                    if (!searchResults.isEmpty()) {
                        audioManager.playTrack(searchResults.get(0));
                    } else {
                        statusMessage = "§eSelect a track below to play! 🎵";
                        statusMessageTime = System.currentTimeMillis();
                    }
                } else {
                    audioManager.togglePause();
                }
                return true;
            }

            if (mx >= nextX && mx <= nextX + 24 && my >= playBtnY + 5 && my <= playBtnY + 27) {
                if (notifyIfGuestInRoom()) return true;
                playClickSound();
                audioManager.next();
                return true;
            }

            int row2Y = heroY + 48;
            // 1. Loop Button
            if (mx >= ctrlX + 8 && mx <= ctrlX + 60 && my >= row2Y && my <= row2Y + 16) {
                playClickSound();
                audioManager.toggleLoop();
                statusMessage = audioManager.isLoopCurrent() ? "§a🔁 Loop: On (Repeating track)" : "§7🔁 Loop: Off";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // 2. Star / Favorite Button
            if (mx >= ctrlX + 64 && mx <= ctrlX + 116 && my >= row2Y && my <= row2Y + 16) {
                playClickSound();
                AudioTrack cur = audioManager.getCurrentTrack();
                if (cur != null) {
                    boolean fav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().toggleFavorite(cur);
                    statusMessage = fav ? "§a★ Starred track added to favorites! ✨" : "§7☆ Track removed from favorites.";
                } else {
                    selectedTab = 2; // Jump to Starred tab
                    statusMessage = "§e★ Opened Starred Favorites list!";
                }
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            int row3Y = heroY + 68;
            // 3. Refresh / Reload Button
            if (mx >= ctrlX + 8 && mx <= ctrlX + 60 && my >= row3Y && my <= row3Y + 16) {
                triggerRefresh();
                return true;
            }

            // 4. + Queue Button
            if (mx >= ctrlX + 64 && mx <= ctrlX + 116 && my >= row3Y && my <= row3Y + 16) {
                playClickSound();
                AudioTrack cur = audioManager.getCurrentTrack();
                if (cur != null) {
                    audioManager.enqueueTrackLast(cur);
                    statusMessage = "§a➕ Queued: " + truncate(cur.getTitle(), 18) + "! 🎶";
                } else {
                    selectedTab = 0; // Jump to search to queue songs
                    statusMessage = "§eSearch any track below to add to queue!";
                }
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // 3. Browser Card (Tabs) & Right Column (Expanded mode only)
            if (isExpanded) {
                int browserY = getDrawerY();
                int browserH = getDrawerHeight();
                int tabCount = tabs.length;
                int tabW = (leftW - 16) / tabCount;
                int tabY = browserY + 5;

                for (int i = 0; i < tabCount; i++) {
                    int tx = startX + 8 + (i * tabW);
                    if (mx >= tx && mx <= tx + tabW - 4 && my >= tabY && my <= tabY + 18) {
                        playClickSound();
                        selectedTab = i;
                        isSearchFocused = (i == 0);
                        isUrlFocused = (i == 3);
                        isRoomNameFocused = false;
                        isRoomPasswordFocused = false;
                        if (i == 4) {
                            partyManager.refreshRooms();
                            roomScrollOffset = 0;
                        }
                        return true;
                    }
                }

            // Tab 0: Search
            if (selectedTab == 0) {
                int searchX = startX + 10;
                int searchY = browserY + 28;
                int searchW = leftW - 20;
                int searchH = 18;

                isSearchFocused = (mx >= searchX && mx <= searchX + searchW && my >= searchY && my <= searchY + searchH);

                if (!searchQuery.isEmpty() && mx >= searchX + searchW - 20 && mx <= searchX + searchW && my >= searchY && my <= searchY + searchH) {
                    playClickSound();
                    searchQuery = "";
                    searchResults.clear();
                    searchResults.addAll(MusicSearchService.getDefaultHits());
                    searchScrollOffset = 0;
                    return true;
                }

                int totalResults = searchResults.size();
                int visibleRows = Math.max(3, (browserH - (searchQuery.isEmpty() ? 39 : 26)) / 24);
                int startIdx = Math.min(searchScrollOffset, Math.max(0, totalResults - visibleRows));
                int endIdx = Math.min(totalResults, startIdx + visibleRows);
                int itemY = searchY + (searchQuery.isEmpty() ? 35 : 22);

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack track = searchResults.get(i);
                    int rx = startX + 8;
                    int rw = leftW - 16;
                    int heartBtnX = rx + rw - 86;
                    int qBtnX = rx + rw - 69;
                    int playBtnX = rx + rw - 44;

                    if (mx >= heartBtnX - 2 && mx <= heartBtnX + 15 && my >= itemY && my <= itemY + 22) {
                        playClickSound();
                        boolean fav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().toggleFavorite(track);
                        statusMessage = fav ? "§a★ Starred song added with love! ✨" : "§7☆ Removed from your favorites. We'll miss it! 💕";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    if (mx >= qBtnX - 3 && mx <= qBtnX + 23 && my >= itemY && my <= itemY + 22) {
                        playClickSound();
                        queueTrackOnly(track);
                        return true;
                    }

                    if ((mx >= playBtnX - 2 && mx <= playBtnX + 42 && my >= itemY && my <= itemY + 22) ||
                        (mx >= rx && mx <= heartBtnX - 4 && my >= itemY && my <= itemY + 22)) {
                        playClickSound();
                        playOrQueueTrack(track);
                        return true;
                    }
                    itemY += 24;
                }
            }

            // Tab 1: Local
            if (selectedTab == 1) {
                int openX = startX + leftW - 110;
                int headerY = browserY + 28;
                if (mx >= openX && mx <= openX + 96 && my >= headerY && my <= headerY + 16) {
                    playClickSound();
                    LocalMusicScanner.openMusicFolder();
                    return true;
                }

                if (cachedLocalTracks.isEmpty()) {
                cachedLocalTracks = LocalMusicScanner.scanLocalTracks();
            }
            List<AudioTrack> locals = cachedLocalTracks;
                int itemY = headerY + 22;
                int visibleRows = Math.max(3, (browserH - 54) / 24);
                int startIdx = Math.min(localScrollOffset, Math.max(0, locals.size() - visibleRows));
                int endIdx = Math.min(locals.size(), startIdx + visibleRows);

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack track = locals.get(i);
                    int rx = startX + 8;
                    int rw = leftW - 16;
                    int heartBtnX = rx + rw - 86;
                    int qBtnX = rx + rw - 69;
                    int playBtnX = rx + rw - 44;

                    if (mx >= heartBtnX - 2 && mx <= heartBtnX + 15 && my >= itemY && my <= itemY + 22) {
                        playClickSound();
                        boolean fav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().toggleFavorite(track);
                        statusMessage = fav ? "§a★ Starred song added with love! ✨" : "§7☆ Removed from your favorites. We'll miss it! 💕";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    if (mx >= qBtnX - 3 && mx <= qBtnX + 23 && my >= itemY && my <= itemY + 22) {
                        playClickSound();
                        queueTrackOnly(track);
                        return true;
                    }

                    if ((mx >= playBtnX - 2 && mx <= playBtnX + 42 && my >= itemY && my <= itemY + 22) ||
                        (mx >= rx && mx <= heartBtnX - 4 && my >= itemY && my <= itemY + 22)) {
                        playClickSound();
                        playOrQueueTrack(track);
                        return true;
                    }
                    itemY += 24;
                }
            }

            // Tab 2: Starred
            if (selectedTab == 2) {
                int shufX = startX + leftW - 110;
                int headerY = browserY + 28;
                if (mx >= shufX && mx <= shufX + 96 && my >= headerY && my <= headerY + 16) {
                    playClickSound();
                    List<AudioTrack> favs = new ArrayList<>(com.slowbag.pulse.client.audio.FavoritesManager.getInstance().getFavorites());
                    if (!favs.isEmpty()) {
                        java.util.Collections.shuffle(favs);
                        audioManager.setPlaylist(favs);
                        statusMessage = "§a🔀 Shuffled your starred playlist with love! 💖";
                        statusMessageTime = System.currentTimeMillis();
                    }
                    return true;
                }

                List<AudioTrack> favs = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().getFavorites();
                int itemY = headerY + 22;
                int visibleRows = Math.max(3, (browserH - 54) / 24);
                int startIdx = Math.min(favoriteScrollOffset, Math.max(0, favs.size() - visibleRows));
                int endIdx = Math.min(favs.size(), startIdx + visibleRows);

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack track = favs.get(i);
                    int rx = startX + 8;
                    int rw = leftW - 16;
                    int heartBtnX = rx + rw - 86;
                    int qBtnX = rx + rw - 69;
                    int playBtnX = rx + rw - 44;

                    if (mx >= heartBtnX - 2 && mx <= heartBtnX + 15 && my >= itemY && my <= itemY + 22) {
                        playClickSound();
                        boolean fav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().toggleFavorite(track);
                        statusMessage = fav ? "§a★ Starred song added with love! ✨" : "§7☆ Removed from your favorites. We'll miss it! 💕";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    if (mx >= qBtnX - 3 && mx <= qBtnX + 23 && my >= itemY && my <= itemY + 22) {
                        playClickSound();
                        queueTrackOnly(track);
                        return true;
                    }

                    if ((mx >= playBtnX - 2 && mx <= playBtnX + 42 && my >= itemY && my <= itemY + 22) ||
                        (mx >= rx && mx <= heartBtnX - 4 && my >= itemY && my <= itemY + 22)) {
                        playClickSound();
                        playOrQueueTrack(track);
                        return true;
                    }
                    itemY += 24;
                }
            }

            // Tab 3: URL
            if (selectedTab == 3) {
                int headerY = browserY + 28;
                int inputX = startX + 10;
                int inputY = headerY + 16;
                int inputW = leftW - 20;
                int inputH = 18;

                // URL input box focus / clear
                if (mx >= inputX && mx <= inputX + inputW && my >= inputY && my <= inputY + inputH) {
                    playClickSound();
                    isUrlFocused = true;
                    if (!customUrl.isEmpty() && mx >= inputX + inputW - 16) {
                        customUrl = "";
                    }
                    return true;
                }

                // Row 2 Buttons: 📋 Paste | ▶ Play | ➕ Queue
                int btnRowY = inputY + 22;
                int btnGap = 6;
                int btnW = (inputW - (btnGap * 2)) / 3;
                int btnH = 18;

                int pasteBtnX = inputX;
                int playBtnX = pasteBtnX + btnW + btnGap;
                int qBtnX = playBtnX + btnW + btnGap;

                // Dedicated "📋 Paste" Button
                if (mx >= pasteBtnX && mx <= pasteBtnX + btnW && my >= btnRowY && my <= btnRowY + btnH) {
                    playClickSound();
                    String clip = getClipboardString();
                    if (!clip.isBlank()) {
                        customUrl = cleanPastedUrl(clip);
                        isUrlFocused = true;
                        statusMessage = "§a📋 Pasted link: " + truncate(customUrl, 24) + " ✨";
                        statusMessageTime = System.currentTimeMillis();
                    } else {
                        statusMessage = "§cClipboard is empty! Copy a link first (Ctrl+C).";
                        statusMessageTime = System.currentTimeMillis();
                    }
                    return true;
                }

                // "▶ Play" Button
                if (mx >= playBtnX && mx <= playBtnX + btnW && my >= btnRowY && my <= btnRowY + btnH) {
                    playClickSound();
                    if (!customUrl.isBlank()) {
                        statusMessage = "§e⚡ Resolving YouTube link / playlist... 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        String toPlay = customUrl.trim();
                        boolean isGuest = partyManager.isInRoom() && !partyManager.isHost();
                        RecentUrlManager.parseUrlOrPlaylistAsync(toPlay, tracks -> {
                            if (!tracks.isEmpty()) {
                                if (isGuest) {
                                    for (AudioTrack t : tracks) {
                                        partyManager.addTrackToRoomQueue(t);
                                    }
                                    statusMessage = "§a➕ Added " + tracks.size() + " track(s) to room queue! 🎶";
                                } else {
                                    audioManager.setPlaylist(tracks);
                                    if (tracks.size() > 1) {
                                        statusMessage = "§a🎉 Loaded " + tracks.size() + " tracks from playlist!";
                                    } else {
                                        statusMessage = "§a▶ Playing: " + truncate(tracks.get(0).getTitle(), 22) + " ~ Enjoy! 🎶";
                                    }
                                }
                                statusMessageTime = System.currentTimeMillis();
                            } else {
                                statusMessage = "§c✕ Could not resolve YouTube link or playlist.";
                                statusMessageTime = System.currentTimeMillis();
                            }
                        });
                    }
                    return true;
                }

                // "+Q" Queue Next Button
                if (mx >= qBtnX && mx <= qBtnX + btnW && my >= btnRowY && my <= btnRowY + btnH) {
                    playClickSound();
                    if (!customUrl.isBlank()) {
                        statusMessage = "§e⚡ Resolving link / playlist to queue... 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        String toQueue = customUrl.trim();
                        boolean isGuest = partyManager.isInRoom() && !partyManager.isHost();
                        RecentUrlManager.parseUrlOrPlaylistAsync(toQueue, tracks -> {
                            if (!tracks.isEmpty()) {
                                if (isGuest) {
                                    for (AudioTrack t : tracks) {
                                        partyManager.addTrackToRoomQueue(t);
                                    }
                                    statusMessage = "§a➕ Added " + tracks.size() + " track(s) to room queue! 🎶";
                                } else {
                                    for (AudioTrack t : tracks) {
                                        audioManager.enqueueTrackLast(t);
                                    }
                                    if (tracks.size() > 1) {
                                        statusMessage = "§a➕ Queued " + tracks.size() + " tracks from playlist!";
                                    } else {
                                        int qPos = audioManager.getQueueSize();
                                        String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
                                        statusMessage = "§a➕ Queued: " + truncate(tracks.get(0).getTitle(), 18) + " ~ " + ord + " 🎶";
                                    }
                                }
                                statusMessageTime = System.currentTimeMillis();
                            } else {
                                statusMessage = "§c✕ Could not resolve YouTube link or playlist.";
                                statusMessageTime = System.currentTimeMillis();
                            }
                        });
                    }
                    return true;
                }

                // Recent Links / Playlists Click
                int recentHeaderY = btnRowY + 24;
                int recentListY = recentHeaderY + 13;
                List<String> recents = RecentUrlManager.getRecentUrls();
                int maxRecents = Math.min(recents.size(), Math.max(1, (browserH - (recentListY - browserY)) / 22));

                for (int i = 0; i < maxRecents; i++) {
                    String rUrl = recents.get(i);
                    int curRowY = recentListY + (i * 21);
                    int rowW = leftW - 20;
                    int rowPlayW = 36;
                    int rowQW = 24;
                    int rowPlayX = inputX + rowW - rowPlayW - 4;
                    int rowQX = rowPlayX - rowQW - 4;

                    // Row +Q Click
                    if (mx >= rowQX && mx <= rowQX + rowQW && my >= curRowY && my <= curRowY + 18) {
                        playClickSound();
                        statusMessage = "§e⚡ Queuing: " + truncate(rUrl, 20) + "...";
                        statusMessageTime = System.currentTimeMillis();
                        RecentUrlManager.parseUrlOrPlaylistAsync(rUrl, tracks -> {
                            if (!tracks.isEmpty()) {
                                for (AudioTrack t : tracks) {
                                    audioManager.enqueueTrackLast(t);
                                }
                                statusMessage = "§a➕ Queued " + tracks.size() + " track(s) from playlist! 🎶";
                                statusMessageTime = System.currentTimeMillis();
                            }
                        });
                        return true;
                    }

                    // Row Play Click
                    if (mx >= rowPlayX && mx <= rowPlayX + rowPlayW && my >= curRowY && my <= curRowY + 18) {
                        playClickSound();
                        statusMessage = "§e⚡ Loading: " + truncate(rUrl, 20) + "...";
                        statusMessageTime = System.currentTimeMillis();
                        RecentUrlManager.parseUrlOrPlaylistAsync(rUrl, tracks -> {
                            if (!tracks.isEmpty()) {
                                audioManager.setPlaylist(tracks);
                                if (tracks.size() > 1) {
                                    statusMessage = "§a🎉 Loaded " + tracks.size() + " tracks from playlist!";
                                } else {
                                    statusMessage = "§a▶ Playing: " + truncate(tracks.get(0).getTitle(), 22) + " ~ Enjoy! 🎶";
                                }
                                statusMessageTime = System.currentTimeMillis();
                            }
                        });
                        return true;
                    }

                    // Click on the recent URL row itself -> fills the customUrl input box!
                    if (mx >= inputX && mx <= rowQX - 4 && my >= curRowY && my <= curRowY + 18) {
                        playClickSound();
                        customUrl = rUrl;
                        isUrlFocused = true;
                        statusMessage = "§aSelected link: " + truncate(rUrl, 24);
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }
                }
            }

            // Tab 4: Rooms (Simple Voice Chat Style)
            if (selectedTab == 4) {
                if (handleRoomsClick(startX, browserY + 28, leftW, browserH - 28, (int) mx, (int) my)) {
                    return true;
                }
            }

            // Tab 5: Settings & Accessibility
            if (selectedTab == 5) {
                int curY = browserY + 28;
                int listStartY = curY + 26;
                int rowH = 32;
                int totalSettings = 12;
                int visibleSettings = getVisibleSettingsCount();
                int startIdx = Math.min(settingsScrollOffset, Math.max(0, totalSettings - visibleSettings));
                int endIdx = Math.min(totalSettings, startIdx + visibleSettings);

                int itemY = listStartY;
                for (int i = startIdx; i < endIdx; i++) {
                    int btnY = itemY + 5;
                    int btnH = 18;
                    boolean rowClicked = (mx >= startX + 8 && mx <= startX + leftW - 8 && my >= itemY && my <= itemY + rowH - 4);

                    if (i == 0) {
                        // Output Device: Prev ◀ | Name | Next ▶
                        int arrowBtnW = 18;
                        int labelW = Math.max(65, Math.min(135, leftW - 215));
                        int rightEdge = startX + leftW - 14;
                        int nextBtnX = rightEdge - arrowBtnW;
                        int labelBoxX = nextBtnX - labelW - 4;
                        int prevBtnX = labelBoxX - arrowBtnW - 4;

                        if (mx >= prevBtnX && mx <= prevBtnX + arrowBtnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            String dev = SettingsManager.getInstance().cycleOutputDevice(false);
                            audioManager.switchOutputDevice(dev);
                            statusMessage = "§a🔊 Audio output stream: " + dev + " ✨";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                        if ((mx >= labelBoxX && mx <= labelBoxX + labelW && my >= btnY && my <= btnY + btnH) ||
                            (mx >= nextBtnX && mx <= nextBtnX + arrowBtnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            String dev = SettingsManager.getInstance().cycleOutputDevice(true);
                            audioManager.switchOutputDevice(dev);
                            statusMessage = "§a🔊 Audio output stream: " + dev + " ✨";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 1) {
                        // 1. Configurable Hotkey (Row 2 in UI)
                        com.slowbag.pulse.client.input.KeybindManager km = com.slowbag.pulse.client.input.KeybindManager.getInstance();
                        int btnW = 96;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean listening = !km.isListening();
                            km.setListening(listening);
                            statusMessage = listening ? "§e⌨ Press any key or combination (or Escape to clear)..." : "§7⌨ Hotkey recording canceled.";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 2) {
                        // 2. In-Game HUD: [Mode] and [✥ Move]
                        int moveBtnW = 44;
                        int moveBtnX = startX + leftW - moveBtnW - 14;
                        int modeBtnW = 54;
                        int modeBtnX = moveBtnX - modeBtnW - 6;

                        if (mx >= moveBtnX && mx <= moveBtnX + moveBtnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            ScreenCompat.setScreen(this.minecraft, new HudMoveScreen(this));
                            return true;
                        }

                        if (rowClicked || (mx >= modeBtnX && mx <= modeBtnX + modeBtnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            String setting = HudConfigManager.getInstance().cycleSetting();
                            statusMessage = "§b🎛 In-Game HUD: " + setting + " ~ Looking gorgeous! ✨";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 3) {
                        // 3. Nametag Music Badge
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean enabled = com.slowbag.pulse.client.audio.MusicBadgeManager.toggleBadgeEnabled();
                            statusMessage = enabled ? "§a✔ Nametag Music Badge: Enabled ~ Shining bright! ✨" : "§7✕ Nametag Music Badge: Disabled 💕";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 4) {
                        // 4. Mono Audio Downmix
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean mono = SettingsManager.getInstance().toggleMonoAudio();
                            statusMessage = mono ? "§e🎧 Mono Audio enabled for accessibility! 🎚" : "§a🎧 Stereo Audio restored! 🎶";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 5) {
                        // 5. Reduced Motion / Waveform Motion
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean red = SettingsManager.getInstance().toggleReducedMotion();
                            statusMessage = red ? "§e⏸ Reduced motion enabled for comfort! ✨" : "§a🌊 Dynamic waveform motion enabled! 🎶";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 6) {
                        // 6. Background Playback
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean bg = SettingsManager.getInstance().toggleBackgroundPlayback();
                            statusMessage = bg ? "§a🖥 Background music playback: Always Play! 🎶" : "§c🔇 Mute music when game is unfocused! 🌙";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 7) {
                        // 7. Network Buffer Cushion
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            int sec = SettingsManager.getInstance().cyclePreloadSeconds();
                            statusMessage = "§a⚡ Network buffer cushion: " + sec + "s preload protection against lag! 🛡";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 8) {
                        // 8. Toast Alerts
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean toast = SettingsManager.getInstance().toggleToastNotifications();
                            statusMessage = toast ? "§a🔔 In-game toast popups enabled! ✨" : "§7🔔 In-game toast popups muted. 🌙";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 9) {
                        // 9. Game Sound Ducking
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean duck = SettingsManager.getInstance().toggleAutoDucking();
                            statusMessage = duck ? "§a🔉 Game sound ducking active during music! 🎧" : "§7🔉 Game sound ducking disabled.";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 10) {
                        // 10. Continuous Playback
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            boolean cont = SettingsManager.getInstance().toggleContinuousPlayback();
                            statusMessage = cont ? "§a♾ Continuous playback active: Never-ending music! 🎶" : "§7♾ Continuous playback disabled.";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 11) {
                        // 11. Clear Cache
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (rowClicked || (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH)) {
                            playClickSound();
                            long freed = SettingsManager.getInstance().clearCache();
                            statusMessage = "§a🧹 Audio cache cleaned! Freed storage. ✨";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }
                    }

                    itemY += rowH;
                }
            }

            // 4. Right Column: Volume Card, Queue Card (Expanded mode only)
            if (twoCol && rightW > 40) {
                int rightX = startX + leftW + 8;
                int volY = heroY;
                int volH = 26;
                int sliderX = rightX + 26;
                int sliderW = rightW - 74;

                // Volume slider
                if (mx >= sliderX - 4 && mx <= sliderX + sliderW + 4 && my >= volY + 4 && my <= volY + 22) {
                    isDraggingVolume = true;
                    float newVol = (float) Math.max(0.0, Math.min(1.0, (mx - sliderX) / (double) sliderW));
                    audioManager.setVolume(newVol);
                    return true;
                }

                // Mute button
                int muteBtnX = rightX + rightW - 18;
                if (mx >= muteBtnX && mx <= muteBtnX + 14 && my >= volY + 4 && my <= volY + 22) {
                    playClickSound();
                    audioManager.setMuted(!audioManager.isMuted());
                    statusMessage = audioManager.isMuted() ? "§e🔇 Shh... Audio muted gently! 🤫" : "§a🔊 Audio restored! Let the music dance ~ 🎵";
                    statusMessageTime = System.currentTimeMillis();
                    return true;
                }

                // Queue Card Clicks
                int queueY = volY + volH + 6;
                int queueH = (browserY + browserH) - queueY;
                List<AudioTrack> upNext = audioManager.getUpcomingQueue();

                // Clear & Shuffle Queue Clicks
                if (!upNext.isEmpty()) {
                    int clearX = rightX + rightW - 40;
                    int shufX = clearX - 46;

                    // Shuffle Queue Click
                    if (mx >= shufX && mx <= shufX + 42 && my >= queueY + 5 && my <= queueY + 17) {
                        playClickSound();
                        if (audioManager.shuffleQueue()) {
                            statusMessage = "§a🔀 Shuffled your queue! 🎶";
                        } else {
                            statusMessage = "§eNeed at least 2 tracks to shuffle queue!";
                        }
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    // Clear Queue Click
                    if (mx >= clearX && mx <= clearX + 36 && my >= queueY + 5 && my <= queueY + 17) {
                        playClickSound();
                        int cleared = upNext.size();
                        audioManager.clearQueue();
                        statusMessage = "§a🗑 Cleared " + cleared + " queued track" + (cleared > 1 ? "s" : "") + "! Playing station ✨";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }
                }

                int visibleRows = Math.max(2, (queueH - 26) / 26);
                int startIdx = Math.min(queueScrollOffset, Math.max(0, upNext.size() - visibleRows));
                int endIdx = Math.min(upNext.size(), startIdx + visibleRows);
                int qItemY = queueY + 24;

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack t = upNext.get(i);
                    int btnW = 15;
                    int btnH = 15;
                    int btnY = qItemY + 4;
                    int gap = 3;

                    int qRemoveX = rightX + rightW - 8 - btnW;
                    int qDownX = qRemoveX - gap - btnW;
                    int qUpX = qDownX - gap - btnW;

                    // Remove from queue
                    if (mx >= qRemoveX && mx <= qRemoveX + btnW && my >= btnY && my <= btnY + btnH) {
                        playClickSound();
                        audioManager.removeFromQueue(i);
                        statusMessage = "§7✕ Removed from queue: " + truncate(t.getTitle(), 18);
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    // Move down in queue
                    if (mx >= qDownX && mx <= qDownX + btnW && my >= btnY && my <= btnY + btnH) {
                        if (i < upNext.size() - 1) {
                            playClickSound();
                            audioManager.moveQueueItemDown(i);
                            statusMessage = "§d▼ Moved down: " + truncate(t.getTitle(), 16);
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }
                    }

                    // Move up in queue
                    if (mx >= qUpX && mx <= qUpX + btnW && my >= btnY && my <= btnY + btnH) {
                        if (i > 0) {
                            playClickSound();
                            audioManager.moveQueueItemUp(i);
                            statusMessage = "§d▲ Moved up: " + truncate(t.getTitle(), 16);
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }
                    }

                    // Click row body to play immediately
                    if (mx >= rightX + 6 && mx < qUpX - 4 && my >= qItemY + 2 && my <= qItemY + 24) {
                        if (notifyIfGuestInRoom()) return true;
                        playClickSound();
                        audioManager.removeFromQueue(i);
                        audioManager.playTrack(t);
                        statusMessage = "§a▶ Now playing: " + truncate(t.getTitle(), 18) + " ~ Enjoy! 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    qItemY += 26;
                }
            }
            } // if (isExpanded)
        }
            return false;
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in handleMouseClicked", t);
            return true;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        try {
            if (handleMouseClicked(normalizeX(event.x()), normalizeY(event.y()), event.button(), isDoubleClick)) {
                return true;
            }
            return super.mouseClicked(event, isDoubleClick);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseClicked", t);
            return true;
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        try {
            if (handleMouseClicked(normalizeX(mouseX), normalizeY(mouseY), button, false)) {
                return true;
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseClicked fallback", t);
        }
        return false;
    }

    private boolean handleMouseReleased(double mx, double my, int btn) {
        if (btn == 0) {
            if (isDraggingScrubber && draggedScrubRatio >= 0) {
                audioManager.seekTo(draggedScrubRatio);
            }
            isDraggingVolume = false;
            isDraggingScrubber = false;
            draggedScrubRatio = -1.0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        try {
            if (handleMouseReleased(normalizeX(event.x()), normalizeY(event.y()), event.button())) {
                return true;
            }
            return super.mouseReleased(event);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseReleased", t);
            return false;
        }
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return handleMouseReleased(normalizeX(mouseX), normalizeY(mouseY), button);
    }

    private boolean handleMouseDragged(double mx, double my, int btn, double dragX, double dragY) {
        if (isDraggingScrubber) {
            int heroX = getHeroX();
            int heroW = getHeroWidth();
            int ctrlAreaW = 118;
            int leftCardW = heroW - ctrlAreaW - 6;
            int artW = getArtWidth();
            int infoX = heroX + 7 + artW + 8;
            int scrubX = infoX + 26;
            int scrubW = (leftCardW - (scrubX - heroX)) - 32;
            draggedScrubRatio = Math.max(0.0, Math.min(1.0, (mx - scrubX) / (double) scrubW));
            return true;
        }
        if (isDraggingVolume && isExpanded && this.width >= 340) {
            int expStartX = getExpStartX();
            int leftW = getExpLeftWidth();
            int rightX = expStartX + leftW + 8;
            int rightW = getExpRightWidth();
            int sliderX = rightX + 26;
            int sliderW = rightW - 74;
            float newVol = (float) Math.max(0.0, Math.min(1.0, (mx - sliderX) / (double) sliderW));
            audioManager.setVolume(newVol);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        try {
            if (handleMouseDragged(normalizeX(event.x()), normalizeY(event.y()), event.button(), dragX, dragY)) {
                return true;
            }
            return super.mouseDragged(event, dragX, dragY);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseDragged", t);
            return false;
        }
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return handleMouseDragged(normalizeX(mouseX), normalizeY(mouseY), button, dragX, dragY);
    }

    private boolean isControlOrSuperDown(int modifiers) {
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || (modifiers & GLFW.GLFW_MOD_SUPER) != 0) {
            return true;
        }
        try {
            if (this.minecraft != null && this.minecraft.getWindow() != null) {
                long win = this.minecraft.getWindow().handle();
                if (win != 0) {
                    return GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS ||
                           GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS ||
                           GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SUPER) == GLFW.GLFW_PRESS ||
                           GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SUPER) == GLFW.GLFW_PRESS;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean handleKeyPressed(int key, int scancode, int modifiers) {
        boolean isPasteKey = (key == GLFW.GLFW_KEY_V && isControlOrSuperDown(modifiers));

        if (key == GLFW.GLFW_KEY_ESCAPE) {
            playClickSound();
            if (passwordPromptRoom != null) {
                passwordPromptRoom = null;
                promptPasswordText = "";
                passwordErrorMsg = "";
                return true;
            }
            if (isSearchFocused) {
                isSearchFocused = false;
                return true;
            }
            if (isUrlFocused) {
                isUrlFocused = false;
                return true;
            }
            if (isRoomNameFocused || isRoomPasswordFocused) {
                isRoomNameFocused = false;
                isRoomPasswordFocused = false;
                return true;
            }
            onClose();
            return true;
        }

        if (passwordPromptRoom != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                playClickSound();
                passwordPromptRoom = null;
                promptPasswordText = "";
                passwordErrorMsg = "";
                return true;
            }
            if (isPasteKey) {
                String clip = getClipboardString();
                if (!clip.isBlank()) {
                    promptPasswordText += clip.trim();
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!promptPasswordText.isEmpty()) {
                    promptPasswordText = promptPasswordText.substring(0, promptPasswordText.length() - 1);
                }
                return true;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                playClickSound();
                submitPasswordPrompt();
                return true;
            }
            return false;
        }

        if (selectedTab == 4 && isCreatingRoom) {
            if (isRoomNameFocused) {
                if (isPasteKey) {
                    String clip = getClipboardString();
                    if (!clip.isBlank()) newRoomName += clip.trim();
                    return true;
                }
                if (key == GLFW.GLFW_KEY_BACKSPACE) {
                    if (!newRoomName.isEmpty()) newRoomName = newRoomName.substring(0, newRoomName.length() - 1);
                    return true;
                } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_TAB) {
                    isRoomNameFocused = false;
                    isRoomPasswordFocused = true;
                    return true;
                }
            } else if (isRoomPasswordFocused) {
                if (isPasteKey) {
                    String clip = getClipboardString();
                    if (!clip.isBlank()) newRoomPassword += clip.trim();
                    return true;
                }
                if (key == GLFW.GLFW_KEY_BACKSPACE) {
                    if (!newRoomPassword.isEmpty()) newRoomPassword = newRoomPassword.substring(0, newRoomPassword.length() - 1);
                    return true;
                } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                    String finalName = newRoomName.trim();
                    if (finalName.isEmpty()) {
                        try {
                            if (Minecraft.getInstance().getUser() != null) {
                                finalName = Minecraft.getInstance().getUser().getName() + "'s Room";
                            }
                        } catch (Throwable ignored) {
                            finalName = "Music Room";
                        }
                    }
                    partyManager.createRoom(finalName, newRoomPassword.trim());
                    isCreatingRoom = false;
                    isRoomNameFocused = false;
                    isRoomPasswordFocused = false;
                    statusMessage = "§a🎉 Created room '" + finalName + "'! Broadcasting music... ✨";
                    statusMessageTime = System.currentTimeMillis();
                    return true;
                }
            }
        }

        // Hotkey recording listener
        com.slowbag.pulse.client.input.KeybindManager km = com.slowbag.pulse.client.input.KeybindManager.getInstance();
        if (km.isListening()) {
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE) {
                km.clearKeybind();
                playClickSound();
                statusMessage = "§c⌨ Hotkey cleared (None)";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }
            boolean isMod = (key == GLFW.GLFW_KEY_LEFT_CONTROL || key == GLFW.GLFW_KEY_RIGHT_CONTROL ||
                             key == GLFW.GLFW_KEY_LEFT_ALT || key == GLFW.GLFW_KEY_RIGHT_ALT ||
                             key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT ||
                             key == GLFW.GLFW_KEY_LEFT_SUPER || key == GLFW.GLFW_KEY_RIGHT_SUPER);
            if (!isMod) {
                boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0 || isControlOrSuperDown(modifiers);
                boolean alt = (modifiers & GLFW.GLFW_MOD_ALT) != 0;
                boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
                km.setKeybind(key, ctrl, alt, shift);
                playClickSound();
                statusMessage = "§a✔ Hotkey saved: §f" + km.getKeybindDisplay() + " ✨";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }
            return true;
        }

        if (isSearchFocused) {
            if (isPasteKey) {
                String clip = getClipboardString();
                if (!clip.isBlank()) {
                    searchQuery += clip;
                    statusMessage = "§a📋 Pasted: " + truncate(clip, 20) + " ✨";
                    statusMessageTime = System.currentTimeMillis();
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    if (searchQuery.isEmpty()) {
                        searchResults.clear();
                        searchResults.addAll(MusicSearchService.getDefaultHits());
                        searchScrollOffset = 0;
                    }
                }
                return true;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                performSearch(searchQuery);
                return true;
            }
        }

        if (selectedTab == 3 || isUrlFocused) {
            if (isPasteKey) {
                playClickSound();
                String clip = getClipboardString();
                if (!clip.isBlank()) {
                    customUrl = cleanPastedUrl(clip);
                    isUrlFocused = true;
                    statusMessage = "§a📋 Pasted link: " + truncate(customUrl, 24) + " ✨";
                    statusMessageTime = System.currentTimeMillis();
                } else {
                    statusMessage = "§cClipboard is empty! Copy a link first (Ctrl+C).";
                    statusMessageTime = System.currentTimeMillis();
                }
                return true;
            }
            if (key == GLFW.GLFW_KEY_BACKSPACE) {
                if (!customUrl.isEmpty()) {
                    customUrl = customUrl.substring(0, customUrl.length() - 1);
                }
                return true;
            } else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                if (!customUrl.isBlank()) {
                    playClickSound();
                    statusMessage = "§e⚡ Resolving YouTube link / playlist with love... 💖";
                    statusMessageTime = System.currentTimeMillis();
                    String toPlay = customUrl.trim();
                    RecentUrlManager.parseUrlOrPlaylistAsync(toPlay, tracks -> {
                        if (!tracks.isEmpty()) {
                            audioManager.setPlaylist(tracks);
                            if (tracks.size() > 1) {
                                statusMessage = "§a🎉 Loaded " + tracks.size() + " tracks from YouTube playlist! 💖";
                            } else {
                                statusMessage = "§a▶ Playing: " + truncate(tracks.get(0).getTitle(), 22) + " ~ Enjoy! 🎶";
                            }
                            statusMessageTime = System.currentTimeMillis();
                        } else {
                            statusMessage = "§c✕ Could not resolve YouTube link or playlist.";
                            statusMessageTime = System.currentTimeMillis();
                        }
                    });
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        try {
            if (handleKeyPressed(event.key(), event.scancode(), event.modifiers())) {
                return true;
            }
            return super.keyPressed(event);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in keyPressed", t);
            return false;
        }
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        try {
            if (handleKeyPressed(keyCode, scanCode, modifiers)) {
                return true;
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in keyPressed fallback", t);
        }
        return false;
    }

    private boolean handleCharTyped(char ch, int modifiers) {
        if (Character.isISOControl(ch) || ch < 32 || ch == 127) {
            return false;
        }

        if (passwordPromptRoom != null) {
            promptPasswordText += ch;
            return true;
        }

        if (selectedTab == 4 && isCreatingRoom) {
            if (isRoomNameFocused) {
                newRoomName += ch;
                return true;
            } else if (isRoomPasswordFocused) {
                newRoomPassword += ch;
                return true;
            }
        }

        if (isSearchFocused) {
            searchQuery += ch;
            return true;
        } else if (isUrlFocused || selectedTab == 3) {
            customUrl += ch;
            isUrlFocused = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        try {
            if (handleCharTyped((char) event.codepoint(), 0)) {
                return true;
            }
            return super.charTyped(event);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in charTyped", t);
            return false;
        }
    }

    public boolean charTyped(char codePoint, int modifiers) {
        return handleCharTyped(codePoint, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // Intermediary overrides for Minecraft 26.1 / 1.21.1 and variants
    public boolean method_25402(double mouseX, double mouseY, int button) {
        return mouseClicked(mouseX, mouseY, button);
    }

    public boolean method_25406(double mouseX, double mouseY, int button) {
        return mouseReleased(mouseX, mouseY, button);
    }

    public boolean method_25403(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    public boolean method_25401(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    public boolean method_25404(int keyCode, int scanCode, int modifiers) {
        return keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean method_16007(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean method_25400(char chr, int modifiers) {
        return charTyped(chr, modifiers);
    }

    private String cleanPastedUrl(String url) {
        if (url == null) return "";
        String s = url.trim();
        if (s.startsWith("<") && s.endsWith(">")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    private String getClipboardString() {
        // 1. Try Minecraft's native KeyboardHandler
        try {
            if (this.minecraft != null && this.minecraft.keyboardHandler != null) {
                String str = this.minecraft.keyboardHandler.getClipboard();
                if (str != null && !str.isBlank()) {
                    return str.trim();
                }
            }
        } catch (Throwable ignored) {}

        // 2. Try LWJGL / GLFW directly via window handle
        try {
            if (this.minecraft != null && this.minecraft.getWindow() != null) {
                long handle = this.minecraft.getWindow().handle();
                if (handle != 0) {
                    String glfwStr = org.lwjgl.glfw.GLFW.glfwGetClipboardString(handle);
                    if (glfwStr != null && !glfwStr.isBlank()) {
                        return glfwStr.trim();
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 3. Try AWT System Clipboard
        try {
            java.awt.datatransfer.Clipboard sys = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            if (sys != null && sys.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                Object data = sys.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (data instanceof String str && !str.isBlank()) {
                    return str.trim();
                }
            }
        } catch (Throwable ignored) {}

        return "";
    }

    private void setClipboardString(String text) {
        if (text == null) text = "";
        try {
            if (this.minecraft != null && this.minecraft.keyboardHandler != null) {
                this.minecraft.keyboardHandler.setClipboard(text);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            if (this.minecraft != null && this.minecraft.getWindow() != null) {
                long handle = this.minecraft.getWindow().handle();
                if (handle != 0) {
                    org.lwjgl.glfw.GLFW.glfwSetClipboardString(handle, text);
                    return;
                }
            }
        } catch (Throwable ignored) {}
        try {
            java.awt.datatransfer.StringSelection selection = new java.awt.datatransfer.StringSelection(text);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
        } catch (Throwable ignored) {}
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars - 2)) + "..";
    }
}
