package com.slowbag.pulse.client.gui;

import com.slowbag.pulse.MusicPlayerMod;
import com.slowbag.pulse.client.audio.AudioManager;
import com.slowbag.pulse.client.audio.AudioTrack;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Modern Sleek Dynamic Island Capsule HUD Overlay.
 * Replaces old circular vinyl disc with a sleek rounded capsule
 * displaying track info, glowing pulse icon, and bouncy dancing equalizer bars.
 */
public class HudOverlayRenderer {

    private static float animProgress = 0.0f;
    private static float compactProgress = 0.0f; // 0.0f = full expanded pill, 1.0f = compact island pill
    private static float smoothPeak = 0.0f;
    private static final float[] smoothBars = new float[6];

    private static AudioTrack lastTrack = null;
    private static boolean wasPlaying = false;
    private static long songStartTime = 0;
    private static final long EXPAND_DURATION_MS = 6000; // 6s expanded capsule before settling to compact pill

    public static void register() {
        try {
            HudElementRegistry.attachElementBefore(
                    VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "hud_overlay"),
                    HudOverlayRenderer::render
            );
            MusicPlayerMod.LOGGER.info("[Music Player] Sleek Dynamic Capsule HUD overlay registered.");
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to register HUD", t);
        }
    }

    public static void triggerSongIntro() {
        songStartTime = System.currentTimeMillis();
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return;

        // Hide overlay while main player screen or HUD move screen is open
        if (client.gui.screen() instanceof MusicPlayerScreen || client.gui.screen() instanceof HudMoveScreen) return;

        HudConfigManager config = HudConfigManager.getInstance();
        if (!config.isHudEnabled()) return;

        AudioManager audio = AudioManager.getInstance();
        AudioTrack track = audio.getCurrentTrack();
        boolean isPlaying = audio.isPlaying();

        // Detect new song start or resume from pause to trigger the expand slide-out
        if (track != null && !track.equals(lastTrack)) {
            lastTrack = track;
            songStartTime = System.currentTimeMillis();
        }
        if (isPlaying && !wasPlaying) {
            songStartTime = System.currentTimeMillis();
        }
        wasPlaying = isPlaying;

        // Fade-in when playing, fade-out when stopped
        float targetAnim = (isPlaying && track != null) ? 1.0f : 0.0f;
        animProgress = AnimationUtil.lerp(animProgress, targetAnim, 0.12f);
        if (animProgress < 0.02f) return;

        float ease = AnimationUtil.easeOutCubic(animProgress);
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();

        // Determine if HUD should be in expanded mode or compact pill
        long timeSinceStart = System.currentTimeMillis() - songStartTime;
        boolean shouldCompact = config.isAutoCompact() && (timeSinceStart > EXPAND_DURATION_MS) && !audio.isPaused();
        float targetCompact = shouldCompact ? 1.0f : 0.0f;
        compactProgress = AnimationUtil.lerp(compactProgress, targetCompact, 0.08f);

        int maxW = 208;
        int minW = 118;
        int hudW = (int) AnimationUtil.lerp(maxW, minW, compactProgress);
        int hudH = 26; // Sleek capsule height

        // Anchor calculation
        int baseX = config.getHudX(screenW, maxW);
        int baseY = config.getHudY(screenH, hudH);

        int posX;
        if (baseX > screenW / 2) {
            // Right-anchored: keep right edge pinned while expanding leftward
            int rightAnchor = baseX + maxW;
            posX = rightAnchor - hudW;
        } else {
            // Left-anchored: keep left edge pinned while expanding rightward
            posX = baseX;
        }
        int posY = baseY;

        int alpha = (int) (235 * ease);
        int bg = (alpha << 24) | 0x141618;

        float peak = audio.getPeakLevel();
        smoothPeak = AnimationUtil.lerp(smoothPeak, peak, 0.35f);

        int borderR = (int) AnimationUtil.lerp(0xD9, 0xFF, smoothPeak);
        int borderG = (int) AnimationUtil.lerp(0x77, 0xA8, smoothPeak);
        int borderB = (int) AnimationUtil.lerp(0x46, 0x60, smoothPeak);
        int borderColor = (alpha << 24) | (borderR << 16) | (borderG << 8) | borderB;

        // Scissor box ensures nothing bleeds outside capsule
        graphics.enableScissor(posX, posY, posX + hudW, posY + hudH);

        // 1. Sleek Curvy Pill Card Background & Amber Border
        drawPillBackground(graphics, posX, posY, hudW, hudH, bg, borderColor);

        // Glass top specular highlight
        int highlightColor = ((int) (alpha * 0.30f) << 24) | 0xFFFFFF;
        graphics.fill(posX + 4, posY + 1, posX + hudW - 4, posY + 2, highlightColor);

        // Ensure album artwork is loading if not already initiated
        if (AlbumArtManager.getInstance().getCurrentUrl() == null && track.getArtworkUrl() != null) {
            AlbumArtManager.getInstance().loadArtworkAsync(track.getArtworkUrl());
        }

        // 2. Left Icon / Mini Artwork Badge
        int iconX = posX + 4;
        int iconY = posY + 4;
        int iconSize = 18;

        Identifier artId = AlbumArtManager.getInstance().getAlbumArtId();
        if (artId != null) {
            graphics.fill(iconX + 1, iconY + 1, iconX + iconSize + 1, iconY + iconSize + 1, 0x50000000);
            graphics.blit(artId, iconX, iconY, iconSize, iconSize, 0.0f, 0.0f, 1.0f, 1.0f);
            graphics.outline(iconX, iconY, iconSize, iconSize, borderColor);
        } else {
            // Sleek glowing music badge with pulse
            int badgeBg = (alpha << 24) | (audio.isPaused() ? 0x2A2620 : 0x2E241E);
            graphics.fill(iconX + 1, iconY + 1, iconX + iconSize - 1, iconY + iconSize - 1, badgeBg);
            graphics.outline(iconX, iconY, iconSize, iconSize, borderColor);

            String noteIcon = audio.isBuffering() ? "§e⏳" : (audio.isPaused() ? "§e❚❚" : "§6♫");
            graphics.centeredText(client.font, Component.literal(noteIcon), iconX + 9, iconY + 5, 0xFFFFFFFF);
        }

        // 3. Middle Text: Song Info
        Font font = client.font;
        int textX = iconX + iconSize + 6;
        int visW = 28; // width reserved for 5 dancing visualizer bars on the right
        int availTextW = (posX + hudW - visW - 4) - textX;

        if (availTextW > 20) {
            if (compactProgress < 0.6f) {
                // Expanded mode: 2 clean lines
                int titleW = Math.max(10, availTextW);
                String safeTitle = truncateText(font, track.getTitle(), titleW);
                graphics.text(font, Component.literal("§f§l" + safeTitle), textX, posY + 4, (alpha << 24) | 0xFFFFFF, false);

                String artistTime = track.getArtist();
                long elapsedSecs = audio.getElapsedSeconds();
                String timeStr = (elapsedSecs / 60) + ":" + String.format("%02d", elapsedSecs % 60);
                String safeArtist = truncateText(font, artistTime + " §8(" + timeStr + ")", availTextW);
                graphics.text(font, Component.literal("§7" + safeArtist), textX, posY + 14, (alpha << 24) | 0xA0988E, false);
            } else {
                // Compact pill mode: 1 centered clean line
                String compactLabel = track.getTitle() + " • " + track.getArtist();
                String safeText = truncateText(font, compactLabel, availTextW);
                graphics.text(font, Component.literal("§f" + safeText), textX, posY + 9, (alpha << 24) | 0xFFFFFF, false);
            }
        }

        // 4. Right: Dynamic 5-Bar Dancing Equalizer (Lively & rhythm responsive)
        int visX = posX + hudW - visW - 2;
        int maxBarH = 14;
        int barBaseY = posY + hudH - 5;
        float[] rawBands = audio.getVisualizerBands();
        long timeMs = System.currentTimeMillis();

        for (int i = 0; i < 5; i++) {
            float audioEnergy = (rawBands != null && (i * 2) < rawBands.length) ? rawBands[i * 2] : 0.0f;
            
            // Lively sine & cosine harmonic wave modulation for energetic rhythmic bounce
            float wavePhase = (float) Math.sin((timeMs / 110.0) + (i * 0.85));
            float waveBonus = isPlaying ? (Math.abs(wavePhase) * 0.45f + (float) Math.cos((timeMs / 180.0) + (i * 1.2)) * 0.25f) : 0.0f;

            float target = (isPlaying && !audio.isPaused())
                    ? Math.max(0.15f, audioEnergy * 2.4f + waveBonus * Math.max(0.35f, audioEnergy + 0.3f))
                    : 0.05f;
            smoothBars[i] = AnimationUtil.lerp(smoothBars[i], target, 0.45f);

            int barH = Math.max(2, Math.min(maxBarH, (int) (smoothBars[i] * maxBarH * 1.5f)));
            int barX = visX + (i * 5);
            int barY = barBaseY - barH;

            // Vibrant warm golden-amber gradient
            int barColor = (alpha << 24) | AnimationUtil.colorLerp(0xD97746, 0xFFA860, (float) i / 4.0f);
            graphics.fill(barX, barY, barX + 3, barBaseY, barColor);
            // Glowing golden peak tip cap
            graphics.fill(barX, barY, barX + 3, barY + 1, (alpha << 24) | 0xFFFFF0D0);
        }

        graphics.disableScissor();
    }

    private static void drawPillBackground(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int bg, int outline) {
        // Rounded pill fill (2px corner chamfers)
        graphics.fill(x + 2, y, x + w - 2, y + h, bg);
        graphics.fill(x, y + 2, x + w, y + h - 2, bg);
        graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, bg);

        // Smooth pill outline
        graphics.fill(x + 2, y, x + w - 2, y + 1, outline);
        graphics.fill(x + 2, y + h - 1, x + w - 2, y + h, outline);
        graphics.fill(x, y + 2, x + 1, y + h - 2, outline);
        graphics.fill(x + w - 1, y + 2, x + w, y + h - 2, outline);

        graphics.fill(x + 1, y + 1, x + 2, y + 2, outline);
        graphics.fill(x + w - 2, y + 1, x + w - 1, y + 2, outline);
        graphics.fill(x + 1, y + h - 2, x + 2, y + h - 1, outline);
        graphics.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, outline);
    }

    private static String truncateText(Font font, String text, int maxWidth) {
        if (text == null) return "";
        if (font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "..";
        int ellipsisW = font.width(ellipsis);
        int targetW = maxWidth - ellipsisW;
        if (targetW <= 0) return ellipsis;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (font.width(sb.toString() + c) > targetW) {
                break;
            }
            sb.append(c);
        }
        return sb.toString().trim() + ellipsis;
    }
}
