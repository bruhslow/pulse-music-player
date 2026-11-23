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
 * Modern Sleek Top-Bar In-Game HUD matching the user's reference layout.
 * Features:
 * - Line 1: Centered track info (♪ Title • Artist  0:07 / 2:45)
 * - Line 2: Slim floating capsule pill containing:
 *     * Left: Glowing cyan/purple dot
 *     * Center: Song progress stance bar
 *     * Right: Reactive curly audio wave animated to the beat
 */
public class HudOverlayRenderer {

    private static float animProgress = 0.0f;
    private static float smoothPeak = 0.0f;
    private static AudioTrack lastTrack = null;
    private static boolean wasPlaying = false;
    private static long songStartTime = 0;

    public static void register() {
        try {
            HudElementRegistry.attachElementBefore(
                    VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "hud_overlay"),
                    HudOverlayRenderer::render
            );
            MusicPlayerMod.LOGGER.info("[Music Player] Sleek Top-Bar HUD overlay registered.");
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
        animProgress = AnimationUtil.lerp(animProgress, targetAnim, 0.14f);
        if (animProgress < 0.02f) return;

        float ease = AnimationUtil.easeOutCubic(animProgress);
        int screenW = client.getWindow().getGuiScaledWidth();
        int screenH = client.getWindow().getGuiScaledHeight();

        int hudW = 210;
        int hudH = 14;

        int posX = config.getHudX(screenW, hudW);
        int posY = config.getHudY(screenH, hudH);

        // Ensure default top position has room for top title text
        if (posY < 14) posY = 15;

        int alpha = (int) (240 * ease);
        int bg = (alpha << 24) | 0x12101A; // Dark obsidian violet

        float peak = audio.getPeakLevel();
        smoothPeak = AnimationUtil.lerp(smoothPeak, peak, 0.35f);

        int borderR = (int) AnimationUtil.lerp(0xA8, 0xC0, smoothPeak);
        int borderG = (int) AnimationUtil.lerp(0x55, 0x84, smoothPeak);
        int borderB = (int) AnimationUtil.lerp(0xF7, 0xFC, smoothPeak);
        int borderColor = (alpha << 24) | (borderR << 16) | (borderG << 8) | borderB;

        Font font = client.font;

        // 1. Line 1: Centered Song Title, Artist & Time Above Pill
        long elapsedSecs = audio.getElapsedSeconds();
        long durationSecs = (track != null) ? track.getDurationSeconds() : 0;
        String timeStr = (elapsedSecs / 60) + ":" + String.format("%02d", elapsedSecs % 60);
        String totalStr = (durationSecs > 0) ? ((durationSecs / 60) + ":" + String.format("%02d", durationSecs % 60)) : "--:--";

        String title = track != null ? track.getTitle() : "Pulse Music";
        String artist = track != null ? track.getArtist() : "Unknown Artist";

        String formattedHeader = "§d♪ §f" + truncateText(font, title, 100) + " §7• " + truncateText(font, artist, 80) + "  §d" + timeStr + " §8/ §7" + totalStr;
        int textY = posY - 10;
        graphics.centeredText(font, Component.literal(formattedHeader), posX + hudW / 2, textY, (alpha << 24) | 0xFFFFFF);

        // 2. Line 2: Slim Floating Capsule Pill
        drawPillBackground(graphics, posX, posY, hudW, hudH, bg, borderColor);

        // Specular top highlight
        int highlightColor = ((int) (alpha * 0.25f) << 24) | 0xFFFFFF;
        graphics.fill(posX + 4, posY + 1, posX + hudW - 4, posY + 2, highlightColor);

        // 3. Left: Glowing Cyan/Purple Dot
        int dotX = posX + 8;
        int dotY = posY + hudH / 2;
        int dotGlowColor = ((int) (alpha * (0.35f + smoothPeak * 0.40f)) << 24) | 0x38BDF8;
        drawCircle(graphics, dotX, dotY, 3, dotGlowColor);
        drawCircle(graphics, dotX, dotY, 2, (alpha << 24) | 0xFF38BDF8);

        // 4. Middle: Song Progress Stance Bar
        int waveAreaW = 30;
        int trackX = posX + 17;
        int trackW = hudW - 17 - waveAreaW - 6;
        int trackY = posY + (hudH - 3) / 2;
        int trackH = 3;

        // Groove Background
        graphics.fill(trackX, trackY, trackX + trackW, trackY + trackH, (alpha << 24) | 0x30FFFFFF);

        // Progress Fill
        float progress = 0.0f;
        if (durationSecs > 0) {
            progress = Math.min(1.0f, (float) elapsedSecs / (float) durationSecs);
        } else if (track != null && track.isLiveStream()) {
            progress = 1.0f;
        }

        int fillW = (int) (trackW * progress);
        if (fillW > 0) {
            graphics.fill(trackX, trackY, trackX + fillW, trackY + trackH, (alpha << 24) | 0xFFA855F7);
        }

        // Stance Thumb Knob
        int knobX = Math.min(trackX + trackW - 2, trackX + fillW);
        graphics.fill(knobX - 1, trackY - 1, knobX + 2, trackY + trackH + 1, (alpha << 24) | 0xFFFFFFFF);
        graphics.fill(knobX, trackY, knobX + 1, trackY + trackH, (alpha << 24) | 0xFFC084FC);

        // 5. Right: Reactive Curly Audio Wave moving with the beat
        int waveStartX = posX + hudW - waveAreaW;
        int waveEndX = posX + hudW - 8;
        int waveCenterY = posY + hudH / 2;

        if (isPlaying && !audio.isPaused()) {
            double timeMs = System.currentTimeMillis();
            double wavePhase = timeMs * 0.009;
            float amp = 1.4f + smoothPeak * 3.6f;

            int prevWX = waveStartX;
            int prevWY = waveCenterY;

            for (int wx = waveStartX; wx <= waveEndX; wx++) {
                float normX = (float) (wx - waveStartX) / (float) (waveEndX - waveStartX);
                // Curly harmonic sine wave
                double wave = Math.sin(normX * Math.PI * 3.5 + wavePhase) * 0.7 + Math.sin(normX * Math.PI * 7.0 - wavePhase * 1.5) * 0.3;
                int offset = (int) Math.round(wave * amp);
                offset = Math.max(-4, Math.min(4, offset));
                int wy = waveCenterY + offset;

                int minY = Math.min(prevWY, wy);
                int maxY = Math.max(prevWY, wy);
                for (int sy = minY; sy <= maxY; sy++) {
                    graphics.fill(wx, sy, wx + 1, sy + 1, (alpha << 24) | 0xFFC084FC);
                }
                prevWX = wx;
                prevWY = wy;
            }
        } else if (audio.isPaused()) {
            // Little pause bars on the right
            int pauseX = waveStartX + (waveAreaW - 8) / 2;
            graphics.fill(pauseX, posY + 4, pauseX + 2, posY + hudH - 4, (alpha << 24) | 0xFFA855F7);
            graphics.fill(pauseX + 4, posY + 4, pauseX + 6, posY + hudH - 4, (alpha << 24) | 0xFFA855F7);
        } else {
            // Subtle flat baseline
            graphics.fill(waveStartX + 2, waveCenterY, waveEndX - 2, waveCenterY + 1, (alpha << 24) | 0x60A855F7);
        }
    }

    private static void drawPillBackground(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int bg, int outline) {
        // Soft rounded capsule fill
        graphics.fill(x + 5, y, x + w - 5, y + 1, bg);
        graphics.fill(x + 3, y + 1, x + w - 3, y + 2, bg);
        graphics.fill(x + 2, y + 2, x + w - 2, y + 3, bg);
        graphics.fill(x + 1, y + 3, x + w - 1, y + 5, bg);
        graphics.fill(x, y + 5, x + w, y + h - 5, bg);
        graphics.fill(x + 1, y + h - 5, x + w - 1, y + h - 3, bg);
        graphics.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, bg);
        graphics.fill(x + 3, y + h - 2, x + w - 3, y + h - 1, bg);
        graphics.fill(x + 5, y + h - 1, x + w - 5, y + h, bg);

        // Smooth continuous curved outline
        graphics.fill(x + 5, y, x + w - 5, y + 1, outline);
        graphics.fill(x + 5, y + h - 1, x + w - 5, y + h, outline);
        graphics.fill(x, y + 5, x + 1, y + h - 5, outline);
        graphics.fill(x + w - 1, y + 5, x + w, y + h - 5, outline);

        // Top-Left curve
        graphics.fill(x + 3, y + 1, x + 5, y + 2, outline);
        graphics.fill(x + 2, y + 2, x + 3, y + 3, outline);
        graphics.fill(x + 1, y + 3, x + 2, y + 5, outline);

        // Top-Right curve
        graphics.fill(x + w - 5, y + 1, x + w - 3, y + 2, outline);
        graphics.fill(x + w - 3, y + 2, x + w - 2, y + 3, outline);
        graphics.fill(x + w - 2, y + 3, x + w - 1, y + 5, outline);

        // Bottom-Left curve
        graphics.fill(x + 1, y + h - 5, x + 2, y + h - 3, outline);
        graphics.fill(x + 2, y + h - 3, x + 3, y + h - 2, outline);
        graphics.fill(x + 3, y + h - 2, x + 5, y + h - 1, outline);

        // Bottom-Right curve
        graphics.fill(x + w - 2, y + h - 5, x + w - 1, y + h - 3, outline);
        graphics.fill(x + w - 3, y + h - 3, x + w - 2, y + h - 2, outline);
        graphics.fill(x + w - 5, y + h - 2, x + w - 3, y + h - 1, outline);
    }

    public static void drawCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.sqrt(radius * radius - dy * dy);
            graphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    public static void drawCircleRing(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
        int r2 = radius * radius;
        int innerR2 = (radius - 1) * (radius - 1);
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int d2 = dx * dx + dy * dy;
                if (d2 <= r2 && d2 >= innerR2) {
                    graphics.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
                }
            }
        }
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
