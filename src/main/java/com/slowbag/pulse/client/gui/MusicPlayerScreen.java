package com.slowbag.pulse.client.gui;

import com.slowbag.pulse.MusicPlayerMod;
import com.slowbag.pulse.client.audio.AudioManager;
import com.slowbag.pulse.client.audio.AudioTrack;
import com.slowbag.pulse.client.audio.LocalMusicScanner;
import com.slowbag.pulse.client.audio.MusicSearchService;
import com.slowbag.pulse.client.audio.RecentUrlManager;
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
    private float vinylAngle = 0.0f;
    private final float[] smoothBands = new float[16];
    private final float[] smoothWaveBars = new float[64];

    // Notification toast on refresh / actions
    private String statusMessage = null;
    private long statusMessageTime = 0;

    // Tabs: 0: Search, 1: Local, 2: Starred, 3: URL, 4: Settings
    private int selectedTab = 0;
    private final String[] tabs = {"🔍 Search", "📍 Local", "★ Starred", "🔗 URL", "⚙ Settings"};
    private int settingsScrollOffset = 0;

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

    public MusicPlayerScreen(Screen parentScreen) {
        super(Component.literal("Pulse Music Player"));
        this.parentScreen = parentScreen;
        this.audioManager = AudioManager.getInstance();
    }

    public void setSelectedTab(int tab) {
        this.selectedTab = Math.max(0, Math.min(tabs.length - 1, tab));
    }

    @Override
    protected void init() {
        super.init();
        this.openAnimationProgress = 0.0f;
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
        if (searchResults.isEmpty()) {
            if (!cachedLocalTracks.isEmpty()) {
                searchResults.addAll(cachedLocalTracks);
            }
            performSearch(searchQuery.isEmpty() ? "lofi" : searchQuery);
        }
    }

    private void performSearch(String query) {
        if (query == null || query.trim().isEmpty()) return;
        this.isSearching = true;
        this.searchScrollOffset = 0;
        MusicSearchService.searchSongsAsync(query.trim(), results -> {
            this.isSearching = false;
            if (results != null) {
                this.searchResults.clear();
                this.searchResults.addAll(results);
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
            performSearch(searchQuery.isEmpty() ? "lofi" : searchQuery);
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
    private boolean isTwoColumn() {
        return this.width >= 500;
    }

    private int getTotalWidth() {
        return isTwoColumn() ? Math.min(620, this.width - 20) : Math.min(420, this.width - 20);
    }

    private int getLeftWidth() {
        int totalW = getTotalWidth();
        return isTwoColumn() ? (int) (totalW * 0.65) : totalW;
    }

    private int getRightWidth() {
        return isTwoColumn() ? (getTotalWidth() - getLeftWidth() - 8) : 0;
    }

    private int getStartX() {
        return (this.width - getTotalWidth()) / 2;
    }

    private int getStartY() {
        return Math.max(6, (this.height - 290) / 2);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        try {
            openAnimationProgress = AnimationUtil.lerp(openAnimationProgress, 1.0f, 0.25f);
            float ease = AnimationUtil.easeOutCubic(openAnimationProgress);

            int startX = getStartX();
            int startY = getStartY();
            int leftW = getLeftWidth();
            int rightW = getRightWidth();
            boolean twoCol = isTwoColumn();

            // 1. Dark frosted backdrop
            int backdropAlpha = (int) (165 * ease);
            graphics.fill(0, 0, this.width, this.height, (backdropAlpha << 24));

            // 2. Top Floating Pill Bar ("~ PULSE")
            int pillW = Math.min(270, getTotalWidth());
            int pillX = (this.width - pillW) / 2;
            int pillY = startY;
            int pillH = 22;
            renderHeaderPill(graphics, pillX, pillY, pillW, pillH, mouseX, mouseY);

            // 3. Left Column: Hero Card & Browser Card
            int heroY = pillY + pillH + 6;
            int heroH = 104;
            renderNowPlayingHero(graphics, startX, heroY, leftW, heroH, mouseX, mouseY);

            int browserY = heroY + heroH + 6;
            int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
            renderBrowserCard(graphics, startX, browserY, leftW, browserH, mouseX, mouseY);

            // 4. Right Column: Volume Card & Queue Card (Clean layout, no duplicate beating visualizer on right)
            if (twoCol && rightW > 50) {
                int rightX = startX + leftW + 8;
                int volY = heroY;
                int volH = 32;
                renderVolumeCard(graphics, rightX, volY, rightW, volH, mouseX, mouseY);

                int queueY = volY + volH + 6;
                int queueH = (browserY + browserH) - queueY;
                renderQueueCard(graphics, rightX, queueY, rightW, queueH, mouseX, mouseY);
            }

            // Clear last error message silently
            if (audioManager.getLastErrorMessage() != null) {
                audioManager.clearLastErrorMessage();
            }

            // Status Notification Toast
            if (statusMessage != null && System.currentTimeMillis() - statusMessageTime < 3500) {
                int toastW = this.font.width(statusMessage) + 24;
                int tx = (this.width - toastW) / 2;
                int ty = Math.max(4, startY - 20);
                drawSoftBox(graphics, tx, ty, toastW, 18, 5, 0xF0181A1B, 0xFFD97746);
                graphics.centeredText(this.font, Component.literal(statusMessage), this.width / 2, ty + 5, 0xFFFFFFFF);
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

        // Mini pulse wave icon + title in signature amber orange
        graphics.text(this.font, Component.literal("§6∿ §f§lPULSE"), x + 8, y + 6, 0xFFFFFFFF, true);

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
        boolean setOpen = (selectedTab == 4);
        drawSoftBox(graphics, settingBtnX, iconY, 16, 16, 3, setOpen ? 0x80D97746 : (setHover ? 0x50D97746 : 0x20FFFFFF), setHover ? 0xFFD97746 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal(setOpen || setHover ? "§6⚙" : "§7⚙"), settingBtnX + 4, iconY + 4, 0xFFFFFFFF, false);

        // Pin In-Game HUD
        boolean pinHover = mouseX >= pinBtnX && mouseX <= pinBtnX + 16 && mouseY >= iconY && mouseY <= iconY + 16;
        drawSoftBox(graphics, pinBtnX, iconY, 16, 16, 3, hudActive ? (pinHover ? 0x80D97746 : 0x40D97746) : (pinHover ? 0x60FFFFFF : 0x20FFFFFF), pinHover ? 0xFFD97746 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal(hudActive ? "§6📌" : "§7📌"), pinBtnX + 4, iconY + 4, 0xFFFFFFFF, false);

        // Nametag Badge Toggle
        boolean badgeHover = mouseX >= badgeBtnX && mouseX <= badgeBtnX + 16 && mouseY >= iconY && mouseY <= iconY + 16;
        drawSoftBox(graphics, badgeBtnX, iconY, 16, 16, 3, badgeActive ? (badgeHover ? 0x80D97746 : 0x40D97746) : (badgeHover ? 0x60FFFFFF : 0x20FFFFFF), badgeHover ? 0xFFD97746 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal(badgeActive ? "§6♫" : "§7—"), badgeBtnX + 4, iconY + 4, 0xFFFFFFFF, false);

        // Close button '✕'
        boolean closeHover = mouseX >= closeX && mouseX <= closeX + 16 && mouseY >= iconY && mouseY <= iconY + 16;
        drawSoftBox(graphics, closeX, iconY, 16, 16, 3, closeHover ? 0x90FF3344 : 0x20FFFFFF, closeHover ? 0xFFFF4455 : 0x25FFFFFF);
        graphics.text(this.font, Component.literal("§c✕"), closeX + 4, iconY + 4, 0xFFFFFFFF, false);
    }

    // Now Playing Hero Card (Top-Left)
    private void renderNowPlayingHero(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        // Base dark container with polished warm obsidian glass
        drawGlassCard(graphics, x, y, w, h, 0xEE16181B, 0x30FFFFFF, 0x15FFFFFF);

        AudioTrack track = audioManager.getCurrentTrack();
        boolean isPlaying = audioManager.isPlaying() && !audioManager.isPaused();
        boolean isBuffering = audioManager.isBuffering();

        if (isPlaying && !isBuffering && !SettingsManager.getInstance().isReducedMotion()) {
            vinylAngle = (vinylAngle + 3.0f) % 360.0f;
        }

        int ctrlAreaW = 118;
        int infoAreaW = w - ctrlAreaW - 6;

        // Dedicated Frosted Deep Obsidian Container for Left section
        int leftCardW = infoAreaW;
        drawSoftBox(graphics, x + 3, y + 3, leftCardW, h - 6, 4, 0xE61F2226, 0x25FFFFFF);
        graphics.fill(x + 8, y + 4, x + leftCardW - 2, y + 5, 0x20FFFFFF);

        // 1. Album Art Thumbnail (Left - Wide 66x46 box to fit the full YouTube thumbnail beautifully)
        int artW = 66;
        int artH = 46;
        int artX = x + 8;
        int artY = y + 8;

        Identifier artId = AlbumArtManager.getInstance().getAlbumArtId();
        int borderColor = isBuffering ? 0xFFFFCC00 : (isPlaying ? 0xFFD97746 : 0x50FFFFFF);
        if (artId != null) {
            drawSoftBox(graphics, artX, artY, artW, artH, 4, 0xFF141618, borderColor);
            graphics.blit(artId, artX + 1, artY + 1, artX + artW - 1, artY + artH - 1, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            drawSoftBox(graphics, artX, artY, artW, artH, 4, 0xFF141618, 0x40FFFFFF);
            graphics.blit(AlbumArtManager.getInstance().getDefaultAlbumArtId(), artX + 1, artY + 1, artX + artW - 1, artY + artH - 1, 0.0f, 1.0f, 0.0f, 1.0f);
        }

        // 2. Track Info & Source
        int infoX = artX + artW + 8;
        int maxTextW = leftCardW - artW - 24;
        String title = (track != null) ? track.getTitle() : "No Song Selected";
        String artist = (track != null) ? track.getArtist() : "Search any song below & click play";
        String sourceTag = isBuffering ? "§e⏳ Buffering audio..." : ((track != null) ? "Source: " + track.getCategory() : "Ready to play");

        String titlePrefix = isPlaying ? "§6§l" : "§f§l";
        graphics.text(this.font, Component.literal(titlePrefix + truncate(title, Math.max(10, maxTextW / 6))), infoX, y + 8, 0xFFFFFFFF, true);
        graphics.text(this.font, Component.literal("§7" + truncate(artist, Math.max(10, maxTextW / 5))), infoX, y + 20, 0xFFA0988E, false);
        graphics.text(this.font, Component.literal("§8" + sourceTag), infoX, y + 31, 0xFF888888, false);

        // Heart favorite toggle on Hero card
        int heartX = x + leftCardW - 14;
        int heartY = y + 8;
        boolean isFav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().isFavorite(track);
        boolean heartHover = mouseX >= heartX && mouseX <= heartX + 14 && mouseY >= heartY && mouseY <= heartY + 14;
        String heartIcon = isFav ? "§6♥" : (heartHover ? "§6♡" : "§7♡");
        graphics.text(this.font, Component.literal(heartIcon), heartX, heartY, isFav || heartHover ? 0xFFD97746 : 0xFFFFFFFF, false);

        // 3. Time Display & Progress Scrubber
        int scrubX = infoX + 26;
        int scrubY = y + 46;
        int scrubW = (leftCardW - (scrubX - x)) - 32;
        int scrubH = 3;

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

        // Scrubber bar (soft rounded edges)
        drawSoftBox(graphics, scrubX, scrubY, scrubW, scrubH, 1, 0x35FFFFFF, 0);
        int filledW = (int) (scrubW * ratio);
        if (filledW > 0) {
            drawSoftBox(graphics, scrubX, scrubY, filledW, scrubH, 1, 0xFFD97746, 0);
        }
        // Cream circular handle with amber accent
        drawSoftBox(graphics, scrubX + filledW - 3, scrubY - 2, 7, scrubH + 4, 2, 0xFFF5F0E6, 0xFFD97746);

        graphics.text(this.font, Component.literal("§7" + totalStr), scrubX + scrubW + 4, scrubY - 2, 0xFFA0988E, false);

        // 4. Live Audio Waveform Equalizer (Under scrubber) - Fluid, Dynamic & Rhythmic Dancing Wave
        float[] rawBands = audioManager.getVisualizerBands();
        int waveX = x + 10;
        int waveY = y + h - 8;
        int waveBars = Math.max(16, (leftCardW - 20) / 7);
        int maxWaveH = 18;
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
            int barY = waveY - barH;

            // Warm amber gradient with bright golden caps
            int barColor = AnimationUtil.colorLerp(0xFFD97746, 0xFFFFA070, (float) i / (float) waveBars);
            graphics.fill(barX, barY, barX + 5, waveY, barColor);
            // Dynamic golden tip highlight
            graphics.fill(barX, barY, barX + 5, barY + 1, 0xFFFFD090);
        }

        // 5. Right Section: Integrated Controls Panel
        int ctrlX = x + w - ctrlAreaW;
        graphics.fill(ctrlX, y + 4, ctrlX + 1, y + h - 4, 0x18FFFFFF);

        // Row 1: Previous | Big Play/Pause | Next
        int prevX = ctrlX + 10;
        int playX = ctrlX + 42;
        int nextX = ctrlX + 82;
        int playBtnY = y + 14;

        drawGlassButton(graphics, prevX, playBtnY + 5, 24, 22, "§f⏮", 0x30FFFFFF, mouseX, mouseY);

        // Circular Big Play Button with Amber Gradient and Rim Glow
        boolean playHover = mouseX >= playX && mouseX <= playX + 32 && mouseY >= playBtnY && mouseY <= playBtnY + 32;
        int playOuterGlow = isBuffering ? 0x80FFCC00 : (playHover ? 0x80D97746 : 0x40D97746);
        int playBg = playHover ? 0xFFE0783E : 0xFFD97746;
        drawSoftBox(graphics, playX - 1, playBtnY - 1, 34, 34, 5, playOuterGlow, 0);
        drawSoftBox(graphics, playX, playBtnY, 32, 32, 5, playBg, 0xFFFFA070);
        drawSoftBox(graphics, playX + 1, playBtnY + 1, 30, 30, 4, 0, 0x40FFFFFF);
        String playIcon = isBuffering ? "§e⏳" : (isPlaying ? "§f❚❚" : "§f▶");
        graphics.centeredText(this.font, Component.literal(playIcon), playX + 16, playBtnY + 11, 0xFFFFFFFF);

        drawGlassButton(graphics, nextX, playBtnY + 5, 24, 22, "§f⏭", 0x30FFFFFF, mouseX, mouseY);

        // Row 2: Secondary Controls: Loop & Star (Curvy rounded buttons)
        int row2Y = y + 54;
        boolean loop = audioManager.isLoopCurrent();
        isFav = (track != null) && com.slowbag.pulse.client.audio.FavoritesManager.getInstance().isFavorite(track);
        drawGlassButton(graphics, ctrlX + 8, row2Y, 48, 17, loop ? "§6🔁 Loop" : "§7🔁 Loop", loop ? 0xFFD97746 : 0x25FFFFFF, mouseX, mouseY);
        drawGlassButton(graphics, ctrlX + 60, row2Y, 48, 17, isFav ? "§6★ Star" : "§7☆ Star", isFav ? 0xFFD97746 : 0x25FFFFFF, mouseX, mouseY);

        // Row 3: Utility Controls: Refresh & + Queue (Curvy rounded buttons, text fits cleanly)
        int row3Y = y + 76;
        drawGlassButton(graphics, ctrlX + 8, row3Y, 48, 16, "§7🔄 Refresh", 0x20FFFFFF, mouseX, mouseY);
        drawGlassButton(graphics, ctrlX + 60, row3Y, 48, 16, "§7➕ Queue", 0x20FFFFFF, mouseX, mouseY);
    }

    // Browser Card (Bottom-Left)
    private void renderBrowserCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0xEE16181B, 0x30FFFFFF, 0x15FFFFFF);

        // Top Tab Bar
        int tabCount = tabs.length;
        int actionIconsW = 38;
        int tabW = (w - 16 - actionIconsW) / tabCount;
        int tabY = y + 5;

        for (int i = 0; i < tabCount; i++) {
            int tx = x + 8 + (i * tabW);
            boolean isSel = (selectedTab == i);
            boolean isHov = mouseX >= tx && mouseX <= tx + tabW - 4 && mouseY >= tabY && mouseY <= tabY + 18;

            int bg = isSel ? 0x50D97746 : (isHov ? 0x25D97746 : 0x15FFFFFF);
            int outline = isSel ? 0xFFD97746 : (isHov ? 0x40D97746 : 0x20FFFFFF);
            drawSoftBox(graphics, tx, tabY, tabW - 4, 18, 3, bg, outline);
            if (isSel) {
                graphics.fill(tx + 4, tabY + 15, tx + tabW - 8, tabY + 17, 0xFFD97746);
            }
            String tabLabel = tabs[i];
            if (i == 2) {
                tabLabel = "§6★ §fStarred";
            }
            graphics.centeredText(this.font, Component.literal(tabLabel), tx + (tabW - 4) / 2, tabY + 5, isSel ? 0xFFFFFFFF : 0xFFA0988E);
        }

        // Action icons on right of Tab Bar: 🔔 Notifications & ⚙ Settings
        int bellX = x + w - 34;
        int gearX = x + w - 18;
        boolean bellHov = mouseX >= bellX && mouseX <= bellX + 14 && mouseY >= tabY && mouseY <= tabY + 18;
        boolean gearHov = mouseX >= gearX && mouseX <= gearX + 14 && mouseY >= tabY && mouseY <= tabY + 18;
        graphics.text(this.font, Component.literal(bellHov ? "§6🔔" : "§7🔔"), bellX, tabY + 4, 0xFFFFFFFF, false);
        graphics.text(this.font, Component.literal(gearHov || selectedTab == 4 ? "§6⚙" : "§7⚙"), gearX, tabY + 4, 0xFFFFFFFF, false);

        // Tab Content
        renderTabContent(graphics, x, y + 26, w, h - 28, mouseX, mouseY);
    }

    private void renderTabContent(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        if (selectedTab == 0) {
            // Tab 0: Direct Online Song Search (Plentiful Songs with Smooth Scrolling)
            int searchX = x + 10;
            int searchY = y + 2;
            int searchW = w - 20;
            int searchH = 18;

            drawSoftBox(graphics, searchX, searchY, searchW, searchH, 3, 0x60000000, isSearchFocused ? 0xFFD97746 : 0x35FFFFFF);

            String displayText = searchQuery.isEmpty() ? (isSearchFocused ? "" : "§8🔍 Search songs, artists or keywords...") : searchQuery;
            graphics.text(this.font, Component.literal(displayText), searchX + 6, searchY + 5, isSearchFocused ? 0xFFFFFFFF : 0xFFA0988E, false);

            if (!searchQuery.isEmpty()) {
                graphics.text(this.font, Component.literal("§7✕"), searchX + searchW - 14, searchY + 5, 0xFFAAAAAA, false);
            }

            int itemY = searchY + 22;
            if (isSearching) {
                graphics.centeredText(this.font, Component.literal("§6⚡ Searching music catalog..."), x + w / 2, itemY + 20, 0xFFE0E0E0);
            } else if (searchResults.isEmpty()) {
                graphics.centeredText(this.font, Component.literal("§7Type a song title above and hit Enter! ✨"), x + w / 2, itemY + 20, 0xFFAAAAAA);
            } else {
                int totalResults = searchResults.size();
                int visibleRows = Math.max(3, (h - 26) / 24);
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
            drawSoftBox(graphics, openX, headerY, 96, 16, 3, openHover ? 0xFFE0783E : 0x40FFFFFF, openHover ? 0xFFFFA070 : 0x30FFFFFF);
            graphics.centeredText(this.font, Component.literal("§f📂 Open Folder"), openX + 48, headerY + 4, 0xFFFFFFFF);

            if (cachedLocalTracks.isEmpty()) {
                cachedLocalTracks = LocalMusicScanner.scanLocalTracks();
            }
            List<AudioTrack> locals = cachedLocalTracks;
            int itemY = headerY + 22;

            if (locals.isEmpty()) {
                graphics.centeredText(this.font, Component.literal("§7No local .mp3/.wav/.m4a files found."), x + w / 2, itemY + 20, 0xFFAAAAAA);
                graphics.centeredText(this.font, Component.literal("§6Click 'Open Folder' above to drop songs in!"), x + w / 2, itemY + 34, 0xFFD97746);
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
            graphics.text(this.font, Component.literal("§6§l★ Starred Tracks & Favorites"), headerX, headerY + 4, 0xFFD97746, true);

            int shufX = x + w - 110;
            boolean shufHover = mouseX >= shufX && mouseX <= shufX + 96 && mouseY >= headerY && mouseY <= headerY + 16;
            drawSoftBox(graphics, shufX, headerY, 96, 16, 3, shufHover ? 0xFFE0783E : 0x40FFFFFF, shufHover ? 0xFFFFA070 : 0x30FFFFFF);
            graphics.centeredText(this.font, Component.literal("§f🔀 Shuffle Starred"), shufX + 48, headerY + 4, 0xFFFFFFFF);

            List<AudioTrack> favs = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().getFavorites();
            int itemY = headerY + 22;

            if (favs.isEmpty()) {
                graphics.centeredText(this.font, Component.literal("§7No starred tracks yet!"), x + w / 2, itemY + 20, 0xFFAAAAAA);
                graphics.centeredText(this.font, Component.literal("§6Click the ★ button on any track to star it! 💕"), x + w / 2, itemY + 34, 0xFFD97746);
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
            drawSoftBox(graphics, inputX, inputY, inputW, inputH, 3, 0x60000000, isUrlFocused ? 0xFFD97746 : 0x35FFFFFF);

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
            drawSoftBox(graphics, pasteBtnX, btnRowY, btnW, btnH, 3, pasteHover ? 0x80D97746 : 0x25FFFFFF, pasteHover ? 0xFFD97746 : 0x40FFFFFF);
            drawCleanCenteredText(graphics, "📋 Paste", pasteBtnX + btnW / 2, btnRowY + 5, 0xFFFFFFFF);

            // "▶ Play" Button (Warm Amber / Orange highlight)
            boolean playHover = mouseX >= playBtnX && mouseX <= playBtnX + btnW && mouseY >= btnRowY && mouseY <= btnRowY + btnH;
            drawSoftBox(graphics, playBtnX, btnRowY, btnW, btnH, 3, playHover ? 0xFFE0783E : 0xFFD97746, playHover ? 0xFFFFA070 : 0x60FFA070);
            drawCleanCenteredText(graphics, "▶ Play", playBtnX + btnW / 2, btnRowY + 5, 0xFFFFFFFF);

            // "+Q" Queue Button
            boolean qHover = mouseX >= qBtnX && mouseX <= qBtnX + btnW && mouseY >= btnRowY && mouseY <= btnRowY + btnH;
            drawSoftBox(graphics, qBtnX, btnRowY, btnW, btnH, 3, qHover ? 0x80D97746 : 0x25FFFFFF, qHover ? 0xFFD97746 : 0x40FFFFFF);
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
                    String badge = isPl ? "§6[Playlist] " : (isYt ? "§c[YouTube] " : "§b[Stream] ");

                    int rowW = w - 20;
                    int rowH = 18;
                    int curRowY = recentListY + (i * 21);

                    boolean rHover = mouseX >= inputX && mouseX <= inputX + rowW && mouseY >= curRowY && mouseY <= curRowY + rowH;
                    drawSoftBox(graphics, inputX, curRowY, rowW, rowH, 3, rHover ? 0x30D97746 : 0x15FFFFFF, rHover ? 0x40D97746 : 0x20FFFFFF);

                    // Row action buttons on right: +Q (w: 24) and ▶ Play (w: 36)
                    int rowPlayW = 36;
                    int rowQW = 24;
                    int rowPlayX = inputX + rowW - rowPlayW - 4;
                    int rowQX = rowPlayX - rowQW - 4;

                    // Text bounded so it NEVER overlaps buttons:
                    int maxTextW = rowQX - (inputX + 6) - 4;
                    graphics.text(this.font, Component.literal(badge + "§f" + truncateToWidth(rUrl, maxTextW)), inputX + 6, curRowY + 5, 0xFFFFFFFF, false);

                    // Row +Q button
                    boolean rowQHover = mouseX >= rowQX && mouseX <= rowQX + rowQW && mouseY >= curRowY + 2 && mouseY <= curRowY + 16;
                    drawSoftBox(graphics, rowQX, curRowY + 2, rowQW, 14, 3, rowQHover ? 0x80D97746 : 0x25FFFFFF, rowQHover ? 0xFFD97746 : 0x30FFFFFF);
                    drawCleanCenteredText(graphics, "+Q", rowQX + rowQW / 2, curRowY + 3, 0xFFFFFFFF);

                    // Row Play button
                    boolean rowPlayHover = mouseX >= rowPlayX && mouseX <= rowPlayX + rowPlayW && mouseY >= curRowY + 2 && mouseY <= curRowY + 16;
                    drawSoftBox(graphics, rowPlayX, curRowY + 2, rowPlayW, 14, 3, rowPlayHover ? 0xFFE0783E : 0xFFD97746, 0);
                    drawCleanCenteredText(graphics, "▶ Play", rowPlayX + rowPlayW / 2, curRowY + 3, 0xFFFFFFFF);
                }
            }

        } else if (selectedTab == 4) {
            // Tab 4: Settings & Accessibility
            SettingsManager sm = SettingsManager.getInstance();
            int curY = y + 2;

            graphics.text(this.font, Component.literal("§f§l⚙ Settings §8& §6Accessibility"), x + 10, curY, 0xFFFFFFFF, false);
            graphics.text(this.font, Component.literal("§7Choose audio output streams, devices & accessibility options."), x + 10, curY + 12, 0xFFA0988E, false);

            int listStartY = curY + 28;
            int rowH = 36;
            int totalSettings = 11;
            int visibleSettings = Math.max(2, (h - 56) / rowH);
            int startIdx = Math.min(settingsScrollOffset, Math.max(0, totalSettings - visibleSettings));
            int endIdx = Math.min(totalSettings, startIdx + visibleSettings);

            int itemY = listStartY;
            for (int i = startIdx; i < endIdx; i++) {
                int rowBg = 0x18FFFFFF;
                drawSoftBox(graphics, x + 8, itemY, w - 16, rowH - 4, 3, rowBg, 0x25FFFFFF);

                if (i == 0) {
                    // 1. Output Stream / Primary Audio Device
                    String curDev = sm.getOutputDevice();
                    int arrowBtnW = 18;
                    int labelW = Math.max(65, Math.min(135, w - 215));
                    int rightEdge = x + w - 14;
                    int nextBtnX = rightEdge - arrowBtnW;
                    int labelBoxX = nextBtnX - labelW - 4;
                    int prevBtnX = labelBoxX - arrowBtnW - 4;
                    int btnY = itemY + 8;
                    int btnH = 18;

                    int maxDescW = prevBtnX - (x + 14) - 6;
                    boolean isVoiceChat = curDev.equalsIgnoreCase(com.slowbag.pulse.client.audio.SettingsManager.VOICE_CHAT_DEVICE);
                    String header = isVoiceChat ? "§f🎤 Voice Chat Microphone:" : "§f🔊 Primary Audio Output:";
                    String sub = isVoiceChat ? "§6Direct in-game mic broadcast to nearby players" : "§8Route sound to speakers or headphones";
                    graphics.text(this.font, Component.literal(truncateToWidth(header, maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth(sub, maxDescW)), x + 14, itemY + 17, isVoiceChat ? 0xFFFFA070 : 0xFF888888, false);

                    // Prev ◀
                    boolean prevHov = mouseX >= prevBtnX && mouseX <= prevBtnX + arrowBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, prevBtnX, btnY, arrowBtnW, btnH, 3, prevHov ? 0x80D97746 : 0x30FFFFFF, prevHov ? 0xFFD97746 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, "◀", prevBtnX + arrowBtnW / 2, btnY + 5, 0xFFFFFFFF);

                    // Device Name Box
                    boolean devHov = mouseX >= labelBoxX && mouseX <= labelBoxX + labelW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, labelBoxX, btnY, labelW, btnH, 3, devHov ? 0x40D97746 : 0x25000000, devHov ? 0xFFD97746 : 0x50D97746);
                    drawCleanCenteredText(graphics, truncateToWidth("§6" + curDev, labelW - 4), labelBoxX + labelW / 2, btnY + 5, 0xFFFFFFFF);

                    // Next ▶
                    boolean nextHov = mouseX >= nextBtnX && mouseX <= nextBtnX + arrowBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, nextBtnX, btnY, arrowBtnW, btnH, 3, nextHov ? 0x80D97746 : 0x30FFFFFF, nextHov ? 0xFFD97746 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, "▶", nextBtnX + arrowBtnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 1) {
                    // 2. Mono Audio Downmix (Accessibility)
                    boolean mono = sm.isMonoAudio();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f🎧 Mono Audio Downmix:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Combines left & right channels for hearing", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, mono ? (bHov ? 0xFFE0783E : 0xFFD97746) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFFFA070 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, mono ? "✔ Mono (On)" : "Stereo (Off)", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 2) {
                    // 3. Reduced Motion / Visualizer (Accessibility)
                    boolean reduced = sm.isReducedMotion();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f🌊 Waveform Motion:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Pauses or softens dancing animations", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, reduced ? (bHov ? 0xFFFFA070 : 0xFFD97746) : (bHov ? 0xFFE0783E : 0xFFD97746), bHov ? 0xFFFFFFFF : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, reduced ? "Reduced" : "Dynamic", btnX + btnW / 2, btnY + 5, reduced ? 0xFF000000 : 0xFFFFFFFF);

                } else if (i == 3) {
                    // 4. In-Game HUD
                    HudConfigManager hudCfg = HudConfigManager.getInstance();
                    int moveBtnW = 44;
                    int moveBtnX = x + w - moveBtnW - 14;
                    int modeBtnW = 54;
                    int modeBtnX = moveBtnX - modeBtnW - 6;
                    int btnH = 18;
                    int btnY = itemY + 8;

                    int maxDescW = modeBtnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f🎛 In-Game HUD Overlay:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Floating now-playing HUD widget in game", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    String hudText = !hudCfg.isHudEnabled() ? "Disabled" : hudCfg.getPosition();
                    boolean modeHov = mouseX >= modeBtnX && mouseX <= modeBtnX + modeBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, modeBtnX, btnY, modeBtnW, btnH, 3, hudCfg.isHudEnabled() ? (modeHov ? 0x80D97746 : 0x40D97746) : (modeHov ? 0x50FFFFFF : 0x25FFFFFF), modeHov ? 0xFFFFFFFF : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, hudText, modeBtnX + modeBtnW / 2, btnY + 5, 0xFFFFFFFF);

                    boolean moveHov = mouseX >= moveBtnX && mouseX <= moveBtnX + moveBtnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, moveBtnX, btnY, moveBtnW, btnH, 3, moveHov ? 0x8038BDF8 : 0x4038BDF8, moveHov ? 0xFFFFFFFF : 0x5038BDF8);
                    drawCleanCenteredText(graphics, "✥ Move", moveBtnX + moveBtnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 4) {
                    // 5. Nametag Music Badge
                    boolean badge = com.slowbag.pulse.client.audio.MusicBadgeManager.isBadgeEnabled();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f♫ Player Nametag Badge:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Animated music note above nametag", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, badge ? (bHov ? 0xFFE0783E : 0xFFD97746) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFFFA070 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, badge ? "♫ Active" : "✕ Hidden", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 5) {
                    // 6. Background Playback
                    boolean bg = sm.isBackgroundPlayback();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f🖥 Background Audio Playback:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Keep songs playing when unfocused", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, bg ? (bHov ? 0xFFE0783E : 0xFFD97746) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFFFA070 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, bg ? "Always Play" : "Mute Focus", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 6) {
                    // 7. Network Lag Buffer Cushion
                    int preloadSec = sm.getPreloadSeconds();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f⚡ Network Lag Buffer Shield:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Preload audio to prevent lag stutter", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    String bufLabel = (preloadSec == 2) ? "2s Fast" : ((preloadSec == 5) ? "✔ 5s Shield" : "🛡 10s Max");
                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, bHov ? 0x80D97746 : 0x35FFFFFF, bHov ? 0xFFD97746 : 0x40FFFFFF);
                    drawCleanCenteredText(graphics, bufLabel, btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 7) {
                    // 8. Toast Alerts & Notifications
                    boolean toast = sm.isToastNotifications();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f🔔 In-Game Toast Alerts:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Display popups on track changes", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, toast ? (bHov ? 0xFFE0783E : 0xFFD97746) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFFFA070 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, toast ? "✔ Enabled" : "✕ Muted", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 8) {
                    // 9. Game Sound Auto-Ducking
                    boolean duck = sm.isAutoDucking();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f🔉 Game Sound Auto-Ducking:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Lower ambient sounds during music", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, duck ? (bHov ? 0xFFE0783E : 0xFFD97746) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFFFA070 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, duck ? "✔ Ducking" : "✕ Normal", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 9) {
                    // 10. Continuous Playback
                    boolean cont = sm.isContinuousPlayback();
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f♾ Continuous Playback:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Keep playing similar songs at end", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, cont ? (bHov ? 0xFFE0783E : 0xFFD97746) : (bHov ? 0x50FFFFFF : 0x25FFFFFF), bHov ? 0xFFFFA070 : 0x35FFFFFF);
                    drawCleanCenteredText(graphics, cont ? "✔ Endless" : "✕ Stop", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);

                } else if (i == 10) {
                    // 11. Audio Cache Storage
                    int btnW = 86;
                    int btnH = 18;
                    int btnX = x + w - btnW - 14;
                    int btnY = itemY + 8;

                    int maxDescW = btnX - (x + 14) - 6;
                    graphics.text(this.font, Component.literal(truncateToWidth("§f🧹 Audio Stream Cache:", maxDescW)), x + 14, itemY + 4, 0xFFFFFFFF, false);
                    graphics.text(this.font, Component.literal(truncateToWidth("§8Clear temp audio chunks on disk", maxDescW)), x + 14, itemY + 17, 0xFF888888, false);

                    boolean bHov = mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH;
                    drawSoftBox(graphics, btnX, btnY, btnW, btnH, 3, bHov ? 0x90FF3344 : 0x40FF3344, bHov ? 0xFFFF4455 : 0x60FF4455);
                    drawCleanCenteredText(graphics, "🗑 Clear", btnX + btnW / 2, btnY + 5, 0xFFFFFFFF);
                }

                itemY += rowH;
            }
        }
    }

    // Single Song Row Component matching reference layout
    private void renderTrackRow(GuiGraphicsExtractor graphics, int rx, int ry, int rw, AudioTrack t, int mouseX, int mouseY) {
        boolean rowHover = mouseX >= rx && mouseX <= rx + rw && mouseY >= ry && mouseY <= ry + 22;
        boolean isCurrent = (audioManager.getCurrentTrack() != null && audioManager.getCurrentTrack().getTitle().equals(t.getTitle()));
        boolean isFav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().isFavorite(t);

        // Warm Obsidian pill with amber glow for Selected/Current track, subtle dark glass for others
        if (isCurrent) {
            drawSoftBox(graphics, rx, ry, rw, 22, 3, 0xF025211D, 0xFFD97746);
            graphics.fill(rx + 2, ry + 4, rx + 4, ry + 18, 0xFFFF9944); // Left accent indicator
        } else {
            int bg = rowHover ? 0x25FFFFFF : 0x14FFFFFF;
            int outline = rowHover ? 0x30FFFFFF : 0;
            drawSoftBox(graphics, rx, ry, rw, 22, 3, bg, outline);
        }

        // Mini thumbnail / icon
        int thumbX = rx + 6;
        int thumbY = ry + 3;
        drawSoftBox(graphics, thumbX, thumbY, 16, 16, 3, isCurrent ? 0xFF181A1B : 0xFF16181B, isCurrent ? 0xFFD97746 : 0x35FFFFFF);
        if (isCurrent && AlbumArtManager.getInstance().getAlbumArtId() != null) {
            graphics.blit(AlbumArtManager.getInstance().getAlbumArtId(), thumbX + 1, thumbY + 1, thumbX + 15, thumbY + 15, 0.0f, 1.0f, 0.0f, 1.0f);
        } else if (isCurrent) {
            graphics.blit(AlbumArtManager.getInstance().getDefaultAlbumArtId(), thumbX + 1, thumbY + 1, thumbX + 15, thumbY + 15, 0.0f, 1.0f, 0.0f, 1.0f);
        } else {
            graphics.text(this.font, Component.literal("§7♫"), thumbX + 4, thumbY + 4, 0xFFA0988E, false);
        }

        // Title and Subtitle: Razor-sharp clean rendering without drop shadows or blurry double layers
        int textX = thumbX + 22;
        int maxTitleW = rw - 110;
        String titlePrefix = isCurrent ? "§6§l" : "§f";
        String artistPrefix = isCurrent ? "§e" : "§7";
        graphics.text(this.font, Component.literal(titlePrefix + truncate(t.getTitle(), maxTitleW / 6)), textX, ry + 3, isCurrent ? 0xFFFFD090 : 0xFFFFFFFF, false);
        graphics.text(this.font, Component.literal(artistPrefix + truncate(t.getArtist() + " • " + t.getCategory(), maxTitleW / 5)), textX, ry + 13, isCurrent ? 0xFFFFA070 : 0xFFA0988E, false);

        // Heart Icon: Amber heart
        int heartX = rx + rw - 86;
        int heartY = ry + 4;
        boolean heartHover = mouseX >= heartX && mouseX <= heartX + 14 && mouseY >= heartY && mouseY <= heartY + 14;
        String heartStr = isFav ? "§6♥" : (heartHover ? "§6♡" : (isCurrent ? "§6♡" : "§7♡"));
        graphics.text(this.font, Component.literal(heartStr), heartX + 2, heartY + 2, isFav || heartHover ? 0xFFD97746 : 0xFF888888, false);

        // "+Q" Queue Next Button
        int qBtnX = rx + rw - 69;
        int qBtnY = ry + 3;
        boolean qBtnHover = mouseX >= qBtnX && mouseX <= qBtnX + 22 && mouseY >= qBtnY && mouseY <= qBtnY + 16;
        int qBg = qBtnHover ? 0x80D97746 : (isCurrent ? 0x40D97746 : 0x20FFFFFF);
        drawSoftBox(graphics, qBtnX, qBtnY, 22, 16, 3, qBg, qBtnHover ? 0xFFD97746 : 0x30FFFFFF);
        graphics.centeredText(this.font, Component.literal("§6+Q"), qBtnX + 11, qBtnY + 4, 0xFFFFFFFF);

        // "▶ Play" Pill Button
        int btnX = rx + rw - 44;
        int btnY = ry + 3;
        boolean btnHover = mouseX >= btnX && mouseX <= btnX + 40 && mouseY >= btnY && mouseY <= btnY + 16;
        int btnBg = btnHover ? 0xFFE0783E : (isCurrent ? 0x90D97746 : 0x35FFFFFF);
        drawSoftBox(graphics, btnX, btnY, 40, 16, 3, btnBg, btnHover ? 0xFFFFA070 : (isCurrent ? 0xFFD97746 : 0x40FFFFFF));
        graphics.centeredText(this.font, Component.literal("§f▶ Play"), btnX + 20, btnY + 4, 0xFFFFFFFF);
    }

    // Volume Card (Top-Right)
    private void renderVolumeCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0xEE16181B, 0x30FFFFFF, 0x15FFFFFF);

        float vol = audioManager.getVolume();
        boolean muted = audioManager.isMuted();

        String volIcon = (muted || vol == 0) ? "§c🔇" : (vol < 0.4f ? "§7🔈" : (vol < 0.8f ? "§f🔉" : "§6🔊"));
        graphics.text(this.font, Component.literal(volIcon), x + 8, y + 10, 0xFFFFFFFF, false);

        int sliderX = x + 28;
        int sliderY = y + 14;
        int sliderW = w - 78;
        int sliderH = 4;

        // Background groove (rounded soft edges)
        drawSoftBox(graphics, sliderX, sliderY, sliderW, sliderH, 1, 0x35FFFFFF, 0);
        int filled = (int) (sliderW * vol);
        // Filled warm amber bar
        if (filled > 0) {
            drawSoftBox(graphics, sliderX, sliderY, filled, sliderH, 1, 0xFFD97746, 0);
        }
        // Cream circular handle with amber accent
        drawSoftBox(graphics, sliderX + filled - 3, sliderY - 3, 7, sliderH + 6, 2, 0xFFF5F0E6, 0xFFD97746);

        String volText = (muted) ? "§c0%" : ((int) (vol * 100) + "%");
        graphics.text(this.font, Component.literal("§7" + volText), sliderX + sliderW + 6, y + 10, 0xFFA0988E, false);

        // Mute button toggle on far right
        int muteBtnX = x + w - 18;
        boolean muteHover = mouseX >= muteBtnX && mouseX <= muteBtnX + 14 && mouseY >= y + 8 && mouseY <= y + 22;
        graphics.text(this.font, Component.literal(muted ? "§6🔊" : "§7🔇"), muteBtnX, y + 10, 0xFFFFFFFF, false);
    }

    // Mini Waveform Card (Middle-Right)
    private void renderMiniWaveformCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0xEE16181B, 0x30FFFFFF, 0x15FFFFFF);

        graphics.text(this.font, Component.literal("§6🔊"), x + 8, y + 12, 0xFFD97746, false);

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
            int color = AnimationUtil.colorLerp(0xFFD97746, 0xFFFFA070, (float) i / (float) bars);
            graphics.fill(bx, by, bx + 3, waveY, color);
        }

        // Right three dots menu icon matching reference
        graphics.text(this.font, Component.literal("§8•••"), x + w - dotsW, y + 12, 0xFF8A8278, false);
    }

    // Queue Card (Bottom-Right)
    private void renderQueueCard(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int mouseX, int mouseY) {
        drawGlassCard(graphics, x, y, w, h, 0xEE16181B, 0x30FFFFFF, 0x15FFFFFF);

        List<AudioTrack> upNext = audioManager.getUpcomingQueue();
        int upNextCount = upNext.size();

        String queueHeader = (upNextCount > 0) ? ("§f§lQueue §7- " + upNextCount + (upNextCount == 1 ? " track" : " tracks")) : "§f§lQueue §7(Empty)";
        graphics.text(this.font, Component.literal(queueHeader), x + 8, y + 8, 0xFFFFFFFF, true);

        // Clear & Shuffle queue buttons
        if (upNextCount > 0) {
            int clearX = x + w - 38;
            int shufX = clearX - 44;

            // Shuffle queue button (🔀 Shuf)
            boolean shufHov = mouseX >= shufX && mouseX <= shufX + 40 && mouseY >= y + 5 && mouseY <= y + 17;
            drawSoftBox(graphics, shufX, y + 5, 40, 12, 3, shufHov ? 0x80D97746 : 0x20FFFFFF, shufHov ? 0xFFFFA070 : 0x40FFFFFF);
            graphics.centeredText(this.font, Component.literal("§6🔀 Shuf"), shufX + 20, y + 6, 0xFFFFFFFF);

            // Clear queue button
            boolean clearHov = mouseX >= clearX && mouseX <= clearX + 34 && mouseY >= y + 5 && mouseY <= y + 17;
            drawSoftBox(graphics, clearX, y + 5, 34, 12, 3, clearHov ? 0x80FF3344 : 0x20FFFFFF, clearHov ? 0xFFFF4455 : 0x40FFFFFF);
            graphics.centeredText(this.font, Component.literal("§cClear"), clearX + 17, y + 6, 0xFFFFFFFF);
        }

        int itemY = y + 24;
        if (upNext.isEmpty()) {
            graphics.centeredText(this.font, Component.literal("§7Queue is empty."), x + w / 2, itemY + 20, 0xFFA0988E);
            graphics.centeredText(this.font, Component.literal("§8Click +Q on any song to queue it!"), x + w / 2, itemY + 34, 0xFF706860);
        } else {
            int visibleRows = Math.max(2, (h - 28) / 24);
            int startIdx = Math.min(queueScrollOffset, Math.max(0, upNext.size() - visibleRows));
            int endIdx = Math.min(upNext.size(), startIdx + visibleRows);

            for (int i = startIdx; i < endIdx; i++) {
                AudioTrack t = upNext.get(i);
                boolean rHover = mouseX >= x + 6 && mouseX <= x + w - 6 && mouseY >= itemY && mouseY <= itemY + 22;

                int rowBg = (i == 0) ? 0x25D97746 : (rHover ? 0x25FFFFFF : 0x10FFFFFF);
                int rowOutline = (i == 0) ? 0x50D97746 : (rHover ? 0x30FFFFFF : 0);
                drawSoftBox(graphics, x + 6, itemY, w - 12, 22, 3, rowBg, rowOutline);

                // Queue position badge
                int thumbX = x + 8;
                int thumbY = itemY + 3;
                drawSoftBox(graphics, thumbX, thumbY, 16, 16, 3, 0xFF181A1B, 0x30FFFFFF);
                graphics.centeredText(this.font, Component.literal("§6#" + (i + 1)), thumbX + 8, thumbY + 4, 0xFFFFFFFF);

                // Action buttons on right: [▲] [▼] [▶] [✕]
                int removeX = x + w - 16;
                int playX = removeX - 16;
                int downX = playX - 16;
                int upX = downX - 16;

                // Move Up button
                boolean canMoveUp = (i > 0);
                boolean upHover = canMoveUp && mouseX >= upX && mouseX <= upX + 12 && mouseY >= itemY + 3 && mouseY <= itemY + 19;
                String upColor = !canMoveUp ? "§8" : (upHover ? "§6" : "§7");
                graphics.text(this.font, Component.literal(upColor + "▲"), upX + 2, itemY + 6, 0xFFFFFFFF, false);

                // Move Down button
                boolean canMoveDown = (i < upNext.size() - 1);
                boolean downHover = canMoveDown && mouseX >= downX && mouseX <= downX + 12 && mouseY >= itemY + 3 && mouseY <= itemY + 19;
                String downColor = !canMoveDown ? "§8" : (downHover ? "§6" : "§7");
                graphics.text(this.font, Component.literal(downColor + "▼"), downX + 2, itemY + 6, 0xFFFFFFFF, false);

                // Play triangle
                boolean playHover = mouseX >= playX && mouseX <= playX + 12 && mouseY >= itemY + 3 && mouseY <= itemY + 19;
                graphics.text(this.font, Component.literal(playHover ? "§6▶" : "§7▶"), playX + 2, itemY + 6, playHover ? 0xFFD97746 : 0xFFA0988E, false);

                // Remove X button
                boolean remHover = mouseX >= removeX && mouseX <= removeX + 12 && mouseY >= itemY + 3 && mouseY <= itemY + 19;
                graphics.text(this.font, Component.literal(remHover ? "§c✕" : "§8✕"), removeX + 2, itemY + 6, 0xFFFFFFFF, false);

                // Song Title and Subtitle - Bounded so it NEVER overlaps the buttons
                int textX = thumbX + 20;
                int textW = upX - textX - 4;
                graphics.text(this.font, Component.literal("§f§l" + truncateToWidth(t.getTitle(), textW)), textX, itemY + 3, 0xFFFFFFFF, false);
                String sub = (i == 0 ? "§6Plays Next" : (i == 1 ? "§ePlays Next Next" : "§7Queued")) + " • " + truncateToWidth(t.getArtist(), Math.max(20, textW - 55));
                graphics.text(this.font, Component.literal(sub), textX, itemY + 12, 0xFFA0988E, false);

                itemY += 24;
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
        if (text == null || text.isEmpty()) return "";
        if (maxWidth <= 20) return "";
        String clean = text.replaceAll("§[0-9a-fk-or]", "");
        if (this.font.width(clean) <= maxWidth) return text;
        int dotsW = this.font.width("..");
        String trimmed = this.font.plainSubstrByWidth(clean, Math.max(10, maxWidth - dotsW));
        return trimmed + "..";
    }

    private void drawGlassButton(GuiGraphicsExtractor graphics, int bx, int by, int bw, int bh, String text, int accent, int mx, int my) {
        boolean hover = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        boolean isActive = (accent == 0xFFD97746 || accent == 0xFFE0783E);
        int bg;
        int outline;
        if (isActive) {
            bg = hover ? 0xFFE0783E : 0xCCD97746;
            outline = 0xFFFFA070;
        } else {
            bg = hover ? 0x50FFFFFF : 0x22FFFFFF;
            outline = hover ? 0xFFD97746 : 0x35FFFFFF;
        }

        drawSoftBox(graphics, bx, by, bw, bh, 3, bg, outline);
        drawCleanCenteredText(graphics, text, bx + bw / 2, by + (bh - 8) / 2, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        try {
            int startX = getStartX();
            int leftW = getLeftWidth();
            int rightW = getRightWidth();
            boolean twoCol = isTwoColumn();

            // Right column queue scroll
            if (twoCol && mouseX >= startX + leftW + 8 && mouseX <= startX + leftW + 8 + rightW) {
                List<AudioTrack> upNext = audioManager.getUpcomingQueue();
                int visibleQ = Math.max(2, 6);
                if (verticalAmount > 0) {
                    queueScrollOffset = Math.max(0, queueScrollOffset - 1);
                } else if (verticalAmount < 0) {
                    int max = Math.max(0, upNext.size() - visibleQ);
                    queueScrollOffset = Math.min(max, queueScrollOffset + 1);
                }
                return true;
            }

            // Left column tab content scroll
            int heroY = getStartY() + 22 + 6;
            int browserY = heroY + 104 + 6;
            int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
            int visibleRows = Math.max(3, (browserH - 54) / 24);
            if (mouseY >= browserY + 24) {
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
                    int totalSettings = 11;
                    int maxScroll = Math.max(0, totalSettings - Math.max(2, (browserH - 56) / 36));
                    if (verticalAmount > 0) {
                        settingsScrollOffset = Math.max(0, settingsScrollOffset - 1);
                    } else if (verticalAmount < 0) {
                        settingsScrollOffset = Math.min(maxScroll, settingsScrollOffset + 1);
                    }
                    return true;
                }
            }
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseScrolled", t);
            return false;
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        try {
            double mx = event.x();
            double my = event.y();
            int btn = event.button();

        if (btn == 0) { // Left click
            int startX = getStartX();
            int startY = getStartY();
            int leftW = getLeftWidth();
            int rightW = getRightWidth();
            boolean twoCol = isTwoColumn();

            // 1. Top Floating Header Pill
            int pillW = Math.min(270, getTotalWidth());
            int pillX = (this.width - pillW) / 2;
            int pillY = startY;
            int iconY = pillY + 3;
            int closeX = pillX + pillW - 20;
            int badgeBtnX = closeX - 22;
            int pinBtnX = badgeBtnX - 22;
            int settingBtnX = pinBtnX - 22;

            // Close button
            if (mx >= closeX && mx <= closeX + 16 && my >= iconY && my <= iconY + 16) {
                playClickSound();
                this.minecraft.gui.setScreen(parentScreen);
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
                selectedTab = (selectedTab == 4) ? 0 : 4;
                isSearchFocused = false;
                isUrlFocused = false;
                return true;
            }

            // 2. Now Playing Hero Card
            int heroY = pillY + 22 + 6;
            int heroH = 104;
            int ctrlAreaW = 118;
            int infoAreaW = leftW - ctrlAreaW - 12;

            // Heart favorite on Hero
            int heartX = startX + infoAreaW - 6;
            int heartY = heroY + 10;
            if (mx >= heartX && mx <= heartX + 14 && my >= heartY && my <= heartY + 14) {
                if (audioManager.getCurrentTrack() != null) {
                    playClickSound();
                    boolean fav = com.slowbag.pulse.client.audio.FavoritesManager.getInstance().toggleFavorite(audioManager.getCurrentTrack());
                    statusMessage = fav ? "§a★ Starred song added with love! ✨" : "§7☆ Removed from your favorites. We'll miss it! 💕";
                    statusMessageTime = System.currentTimeMillis();
                    return true;
                }
            }

            // Timeline Progress Scrubber
            int artW = 66;
            int infoX = startX + 8 + artW + 8;
            int scrubX = infoX + 26;
            int scrubY = heroY + 46;
            int scrubW = (leftW - (scrubX - startX)) - 32;
            int scrubH = 4;

            if (mx >= scrubX - 4 && mx <= scrubX + scrubW + 4 && my >= scrubY - 4 && my <= scrubY + scrubH + 4) {
                if (audioManager.getCurrentTrack() != null && !audioManager.getCurrentTrack().isLiveStream() && audioManager.getCurrentTrack().getDurationSeconds() > 0) {
                    playClickSound();
                    isDraggingScrubber = true;
                    draggedScrubRatio = Math.max(0.0, Math.min(1.0, (mx - scrubX) / (double) scrubW));
                    audioManager.seekTo(draggedScrubRatio);
                    return true;
                }
            }

            // Controls on Hero Card
            int ctrlX = startX + leftW - ctrlAreaW;
            int prevX = ctrlX + 10;
            int playX = ctrlX + 42;
            int nextX = ctrlX + 82;
            int playBtnY = heroY + 14;

            if (mx >= prevX && mx <= prevX + 24 && my >= playBtnY + 5 && my <= playBtnY + 27) {
                playClickSound();
                audioManager.previous();
                return true;
            }

            if (mx >= playX && mx <= playX + 32 && my >= playBtnY && my <= playBtnY + 32) {
                playClickSound();
                if (audioManager.isBuffering()) return true;
                if (audioManager.getCurrentTrack() == null && !searchResults.isEmpty()) {
                    audioManager.playTrack(searchResults.get(0));
                } else {
                    audioManager.togglePause();
                }
                return true;
            }

            if (mx >= nextX && mx <= nextX + 24 && my >= playBtnY + 5 && my <= playBtnY + 27) {
                playClickSound();
                audioManager.next();
                return true;
            }

            int row2Y = heroY + 54;
            // 1. Loop Button
            if (mx >= ctrlX + 8 && mx <= ctrlX + 56 && my >= row2Y && my <= row2Y + 17) {
                playClickSound();
                audioManager.toggleLoop();
                statusMessage = audioManager.isLoopCurrent() ? "§a🔁 Loop: On (Repeating track)" : "§7🔁 Loop: Off";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }

            // 2. Star / Favorite Button
            if (mx >= ctrlX + 60 && mx <= ctrlX + 108 && my >= row2Y && my <= row2Y + 17) {
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

            int row3Y = heroY + 76;
            // 3. Refresh Button
            if (mx >= ctrlX + 8 && mx <= ctrlX + 56 && my >= row3Y && my <= row3Y + 16) {
                triggerRefresh();
                return true;
            }

            // 4. + Queue Button
            if (mx >= ctrlX + 60 && mx <= ctrlX + 108 && my >= row3Y && my <= row3Y + 16) {
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

            // 3. Browser Card (Tabs)
            int browserY = heroY + heroH + 6;
            int tabCount = tabs.length;
            int actionIconsW = 38;
            int tabW = (leftW - 16 - actionIconsW) / tabCount;
            int tabY = browserY + 5;

            for (int i = 0; i < tabCount; i++) {
                int tx = startX + 8 + (i * tabW);
                if (mx >= tx && mx <= tx + tabW - 4 && my >= tabY && my <= tabY + 18) {
                    playClickSound();
                    selectedTab = i;
                    isSearchFocused = (i == 0);
                    isUrlFocused = (i == 3);
                    return true;
                }
            }

            int bellX = startX + leftW - 34;
            int gearX = startX + leftW - 18;
            if (mx >= bellX && mx <= bellX + 14 && my >= tabY && my <= tabY + 18) {
                playClickSound();
                statusMessage = "§a🔔 No new notifications ~ Listening smoothly!";
                statusMessageTime = System.currentTimeMillis();
                return true;
            }
            if (mx >= gearX && mx <= gearX + 14 && my >= tabY && my <= tabY + 18) {
                playClickSound();
                selectedTab = (selectedTab == 4) ? 0 : 4;
                isSearchFocused = false;
                isUrlFocused = false;
                return true;
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
                    return true;
                }

                int totalResults = searchResults.size();
                int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
                int visibleRows = Math.max(3, (browserH - 54) / 24);
                int startIdx = Math.min(searchScrollOffset, Math.max(0, totalResults - visibleRows));
                int endIdx = Math.min(totalResults, startIdx + visibleRows);
                int itemY = searchY + 22;

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
                        audioManager.enqueueTrack(track);
                        int qPos = audioManager.getQueueSize();
                        String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
                        statusMessage = "§a➕ Queued: " + truncate(track.getTitle(), 18) + " ~ " + ord + " 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    if ((mx >= playBtnX - 2 && mx <= playBtnX + 42 && my >= itemY && my <= itemY + 22) ||
                        (mx >= rx && mx <= heartBtnX - 4 && my >= itemY && my <= itemY + 22)) {
                        playClickSound();
                        audioManager.playTrack(track);
                        statusMessage = "§a▶ Playing: " + truncate(track.getTitle(), 22) + " 💖";
                        statusMessageTime = System.currentTimeMillis();
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
                int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
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
                        audioManager.enqueueTrack(track);
                        int qPos = audioManager.getQueueSize();
                        String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
                        statusMessage = "§a➕ Queued: " + truncate(track.getTitle(), 18) + " ~ " + ord + " 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    if ((mx >= playBtnX - 2 && mx <= playBtnX + 42 && my >= itemY && my <= itemY + 22) ||
                        (mx >= rx && mx <= heartBtnX - 4 && my >= itemY && my <= itemY + 22)) {
                        playClickSound();
                        audioManager.playTrack(track);
                        statusMessage = "§a▶ Playing: " + truncate(track.getTitle(), 22) + " 💖";
                        statusMessageTime = System.currentTimeMillis();
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
                int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
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
                        audioManager.enqueueTrack(track);
                        int qPos = audioManager.getQueueSize();
                        String ord = (qPos == 1) ? "Plays next!" : ((qPos == 2) ? "Plays next next!" : "(#" + qPos + " in queue)");
                        statusMessage = "§a➕ Queued: " + truncate(track.getTitle(), 18) + " ~ " + ord + " 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    if ((mx >= playBtnX - 2 && mx <= playBtnX + 42 && my >= itemY && my <= itemY + 22) ||
                        (mx >= rx && mx <= heartBtnX - 4 && my >= itemY && my <= itemY + 22)) {
                        playClickSound();
                        audioManager.playTrack(track);
                        statusMessage = "§a▶ Playing: " + truncate(track.getTitle(), 22) + " 💖";
                        statusMessageTime = System.currentTimeMillis();
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
                        RecentUrlManager.parseUrlOrPlaylistAsync(toPlay, tracks -> {
                            if (!tracks.isEmpty()) {
                                audioManager.setPlaylist(tracks);
                                if (tracks.size() > 1) {
                                    statusMessage = "§a🎉 Loaded " + tracks.size() + " tracks from playlist!";
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

                // "+Q" Queue Next Button
                if (mx >= qBtnX && mx <= qBtnX + btnW && my >= btnRowY && my <= btnRowY + btnH) {
                    playClickSound();
                    if (!customUrl.isBlank()) {
                        statusMessage = "§e⚡ Resolving link / playlist to queue... 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        String toQueue = customUrl.trim();
                        RecentUrlManager.parseUrlOrPlaylistAsync(toQueue, tracks -> {
                            if (!tracks.isEmpty()) {
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
                int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
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

            // Tab 4: Settings & Accessibility
            if (selectedTab == 4) {
                int curY = browserY + 28;
                int listStartY = curY + 28;
                int rowH = 36;
                int totalSettings = 11;
                int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
                int visibleSettings = Math.max(2, (browserH - 56) / rowH);
                int startIdx = Math.min(settingsScrollOffset, Math.max(0, totalSettings - visibleSettings));
                int endIdx = Math.min(totalSettings, startIdx + visibleSettings);

                int itemY = listStartY;
                for (int i = startIdx; i < endIdx; i++) {
                    int btnY = itemY + 8;
                    int btnH = 18;

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
                        // Mono Audio Downmix
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            boolean mono = SettingsManager.getInstance().toggleMonoAudio();
                            statusMessage = mono ? "§e🎧 Mono Audio enabled for accessibility! 🎚" : "§a🎧 Stereo Audio restored! 🎶";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 2) {
                        // Reduced Motion
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            boolean red = SettingsManager.getInstance().toggleReducedMotion();
                            statusMessage = red ? "§e⏸ Reduced motion enabled for comfort! ✨" : "§a🌊 Dynamic waveform motion enabled! 🎶";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 3) {
                        // In-Game HUD: [Mode] and [✥ Move]
                        int moveBtnW = 44;
                        int moveBtnX = startX + leftW - moveBtnW - 14;
                        int modeBtnW = 54;
                        int modeBtnX = moveBtnX - modeBtnW - 6;

                        if (mx >= moveBtnX && mx <= moveBtnX + moveBtnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            Minecraft.getInstance().gui.setScreen(new HudMoveScreen(this));
                            return true;
                        }

                        if (mx >= modeBtnX && mx <= modeBtnX + modeBtnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            String setting = HudConfigManager.getInstance().cycleSetting();
                            statusMessage = "§b🎛 In-Game HUD: " + setting + " ~ Looking gorgeous! ✨";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 4) {
                        // Nametag Music Badge
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            boolean enabled = com.slowbag.pulse.client.audio.MusicBadgeManager.toggleBadgeEnabled();
                            statusMessage = enabled ? "§a✔ Nametag Music Badge: Enabled ~ Shining bright! ✨" : "§7✕ Nametag Music Badge: Disabled 💕";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 5) {
                        // Background Playback
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            boolean bg = SettingsManager.getInstance().toggleBackgroundPlayback();
                            statusMessage = bg ? "§a🖥 Background music playback: Always Play! 🎶" : "§c🔇 Mute music when game is unfocused! 🌙";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 6) {
                        // Network Buffer Cushion
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            int sec = SettingsManager.getInstance().cyclePreloadSeconds();
                            statusMessage = "§a⚡ Network buffer cushion: " + sec + "s preload protection against lag! 🛡";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 7) {
                        // Toast Alerts
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            boolean toast = SettingsManager.getInstance().toggleToastNotifications();
                            statusMessage = toast ? "§a🔔 In-game toast popups enabled! ✨" : "§7🔔 In-game toast popups muted. 🌙";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 8) {
                        // Game Sound Ducking
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            boolean duck = SettingsManager.getInstance().toggleAutoDucking();
                            statusMessage = duck ? "§a🔉 Game sound ducking active during music! 🎧" : "§7🔉 Game sound ducking disabled.";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 9) {
                        // Continuous Playback
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
                            playClickSound();
                            boolean cont = SettingsManager.getInstance().toggleContinuousPlayback();
                            statusMessage = cont ? "§a♾ Continuous playback active: Never-ending music! 🎶" : "§7♾ Continuous playback disabled.";
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }

                    } else if (i == 10) {
                        // Clear Cache
                        int btnW = 86;
                        int btnX = startX + leftW - btnW - 14;
                        if (mx >= btnX && mx <= btnX + btnW && my >= btnY && my <= btnY + btnH) {
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

            // 4. Right Column: Volume Card, Queue Card
            if (twoCol && rightW > 50) {
                int rightX = startX + leftW + 8;
                int volY = heroY;
                int sliderX = rightX + 28;
                int sliderW = rightW - 80;

                // Volume slider
                if (mx >= sliderX && mx <= sliderX + sliderW && my >= volY + 6 && my <= volY + 26) {
                    isDraggingVolume = true;
                    float newVol = (float) (mx - sliderX) / sliderW;
                    audioManager.setVolume(newVol);
                    return true;
                }

                // Mute button
                int muteBtnX = rightX + rightW - 20;
                if (mx >= muteBtnX && mx <= muteBtnX + 16 && my >= volY + 6 && my <= volY + 26) {
                    playClickSound();
                    audioManager.setMuted(!audioManager.isMuted());
                    statusMessage = audioManager.isMuted() ? "§e🔇 Shh... Audio muted gently! 🤫" : "§a🔊 Audio restored! Let the music dance ~ 🎵";
                    statusMessageTime = System.currentTimeMillis();
                    return true;
                }

                // Queue Card Clicks
                int queueY = volY + 32 + 6;
                int browserH = Math.max(145, Math.min(185, this.height - browserY - 8));
                int queueH = (browserY + browserH) - queueY;
                List<AudioTrack> upNext = audioManager.getUpcomingQueue();
                List<AudioTrack> displayList = audioManager.getUnifiedQueue();

                // Clear & Shuffle Queue Clicks
                if (!upNext.isEmpty()) {
                    int clearX = rightX + rightW - 38;
                    int shufX = clearX - 44;

                    // Shuffle Queue Click
                    if (mx >= shufX && mx <= shufX + 40 && my >= queueY + 5 && my <= queueY + 17) {
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
                    if (mx >= clearX && mx <= clearX + 34 && my >= queueY + 5 && my <= queueY + 17) {
                        playClickSound();
                        int cleared = upNext.size();
                        audioManager.clearQueue();
                        statusMessage = "§a🗑 Cleared " + cleared + " queued track" + (cleared > 1 ? "s" : "") + "! Playing station ✨";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }
                }

                int visibleRows = Math.max(2, (queueH - 28) / 24);
                int startIdx = Math.min(queueScrollOffset, Math.max(0, upNext.size() - visibleRows));
                int endIdx = Math.min(upNext.size(), startIdx + visibleRows);
                int qItemY = queueY + 24;

                for (int i = startIdx; i < endIdx; i++) {
                    AudioTrack t = upNext.get(i);
                    int qRemoveX = rightX + rightW - 16;
                    int qPlayX = qRemoveX - 16;
                    int qDownX = qPlayX - 16;
                    int qUpX = qDownX - 16;

                    // Remove from queue
                    if (mx >= qRemoveX - 2 && mx <= qRemoveX + 14 && my >= qItemY + 2 && my <= qItemY + 20) {
                        playClickSound();
                        audioManager.removeFromQueue(i);
                        statusMessage = "§7✕ Removed from queue: " + truncate(t.getTitle(), 18);
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    // Play immediately
                    if (mx >= qPlayX - 2 && mx <= qPlayX + 14 && my >= qItemY + 2 && my <= qItemY + 20) {
                        playClickSound();
                        audioManager.removeFromQueue(i);
                        audioManager.playTrack(t);
                        statusMessage = "§a▶ Now playing: " + truncate(t.getTitle(), 18) + " ~ Enjoy! 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    // Move down in queue
                    if (mx >= qDownX - 2 && mx <= qDownX + 14 && my >= qItemY + 2 && my <= qItemY + 20) {
                        if (i < upNext.size() - 1) {
                            playClickSound();
                            audioManager.moveQueueItemDown(i);
                            statusMessage = "§6▼ Moved down: " + truncate(t.getTitle(), 16);
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }
                    }

                    // Move up in queue
                    if (mx >= qUpX - 2 && mx <= qUpX + 14 && my >= qItemY + 2 && my <= qItemY + 20) {
                        if (i > 0) {
                            playClickSound();
                            audioManager.moveQueueItemUp(i);
                            statusMessage = "§6▲ Moved up: " + truncate(t.getTitle(), 16);
                            statusMessageTime = System.currentTimeMillis();
                            return true;
                        }
                    }

                    // Click row body to play
                    if (mx >= rightX + 6 && mx <= qUpX - 4 && my >= qItemY && my <= qItemY + 22) {
                        playClickSound();
                        audioManager.removeFromQueue(i);
                        audioManager.playTrack(t);
                        statusMessage = "§a▶ Now playing: " + truncate(t.getTitle(), 18) + " ~ Enjoy! 🎶";
                        statusMessageTime = System.currentTimeMillis();
                        return true;
                    }

                    qItemY += 24;
                }
            }
        }

            return super.mouseClicked(event, isDoubleClick);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseClicked", t);
            return true;
        }
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        try {
            if (event.button() == 0) {
                if (isDraggingScrubber && draggedScrubRatio >= 0) {
                    audioManager.seekTo(draggedScrubRatio);
                }
                isDraggingVolume = false;
                isDraggingScrubber = false;
                draggedScrubRatio = -1.0;
            }
            return super.mouseReleased(event);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseReleased", t);
            return false;
        }
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        try {
            if (isDraggingScrubber) {
                int startX = getStartX();
                int leftW = getLeftWidth();
                int ctrlAreaW = 116;
                int infoAreaW = leftW - ctrlAreaW - 12;
                int artW = 66;
                int infoX = startX + 8 + artW + 8;
                int scrubX = infoX + 26;
                int scrubW = (leftW - (scrubX - startX)) - 32;
                draggedScrubRatio = Math.max(0.0, Math.min(1.0, (event.x() - scrubX) / (double) scrubW));
                return true;
            }
            if (isDraggingVolume && isTwoColumn()) {
                int rightX = getStartX() + getLeftWidth() + 8;
                int rightW = getRightWidth();
                int sliderX = rightX + 28;
                int sliderW = rightW - 80;
                float newVol = (float) (event.x() - sliderX) / sliderW;
                audioManager.setVolume(newVol);
                return true;
            }
            return super.mouseDragged(event, dragX, dragY);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in mouseDragged", t);
            return false;
        }
    }

    private boolean isControlOrSuperDown(KeyEvent event) {
        if ((event.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0 || (event.modifiers() & GLFW.GLFW_MOD_SUPER) != 0) {
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

    @Override
    public boolean keyPressed(KeyEvent event) {
        try {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            playClickSound();
            this.minecraft.gui.setScreen(parentScreen);
            return true;
        }

        boolean isPasteKey = (event.key() == GLFW.GLFW_KEY_V && isControlOrSuperDown(event));

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
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!searchQuery.isEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                }
                return true;
            } else if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
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
            if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
                if (!customUrl.isEmpty()) {
                    customUrl = customUrl.substring(0, customUrl.length() - 1);
                }
                return true;
            } else if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
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

        return super.keyPressed(event);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in keyPressed", t);
            return false;
        }
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        try {
            char ch = (char) event.codepoint();
            if (Character.isISOControl(ch) || ch < 32 || ch == 127) {
                return false;
            }

            if (isSearchFocused) {
                searchQuery += ch;
                return true;
            } else if (isUrlFocused || selectedTab == 3) {
                customUrl += ch;
                isUrlFocused = true;
                return true;
            }

            return super.charTyped(event);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Error in charTyped", t);
            return false;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
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
