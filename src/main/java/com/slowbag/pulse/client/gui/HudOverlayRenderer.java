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
 * Modern Sleek Dynamic HUD Overlay with Rotating Turntable Disc and Fluid Dancing Waveform Bars.
 */
public class HudOverlayRenderer {

    private static float animProgress = 0.0f;
    private static float compactProgress = 0.0f;
    private static float smoothPeak = 0.0f;
    private static float vinylAngle = 0.0f;
    private static final float[] smoothBars = new float[6];

    private static AudioTrack lastTrack = null;
    private static boolean wasPlaying = false;
    private static long songStartTime = 0;
    private static final long EXPAND_DURATION_MS = 6500;

    public static void register() {
        try {
            HudElementRegistry.attachElementBefore(
                    VanillaHudElements.CHAT,
                    Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "hud_overlay"),
                    HudOverlayRenderer::render
            );
            MusicPlayerMod.LOGGER.info("[Music Player] Sleek Turntable Circle HUD overlay registered.");
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

        int maxW = 220;
        int minW = 124;
        int hudW = (int) AnimationUtil.lerp(maxW, minW, compactProgress);
        int hudH = 32; // Generous height for circular turntable and legible text

        // Anchor calculation
        int baseX = config.getHudX(screenW, maxW);
        int baseY = config.getHudY(screenH, hudH);

        int posX;
        if (baseX > screenW / 2) {
            int rightAnchor = baseX + maxW;
            posX = rightAnchor - hudW;
        } else {
            posX = baseX;
        }
        int posY = baseY;

        int alpha = (int) (240 * ease);
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

        // 2. Circle on the Left with Animation Inside (Turntable vinyl disc with grooved rings and spinning arm)
        int cx = posX + 16;
        int cy = posY + 16;
        int circleRadius = 11;

        if (isPlaying && !audio.isPaused()) {
            vinylAngle += 0.07f + (smoothPeak * 0.12f);
            if (vinylAngle > 6.28318f) vinylAngle -= 6.28318f;
        }

        // Bass pulse aura glow around the circle
        int pulseRadius = circleRadius + (int) (smoothPeak * 2.5f);
        int glowColor = ((int) (alpha * (0.25f + smoothPeak * 0.45f)) << 24) | (borderR << 16) | (borderG << 8) | borderB;
        drawCircle(graphics, cx, cy, pulseRadius + 1, glowColor);

        Identifier circleArtId = AlbumArtManager.getInstance().getCircleAlbumArtId();
        if (circleArtId != null) {
            // Render circle album artwork
            drawCircle(graphics, cx, cy, circleRadius, (alpha << 24) | 0x1A1816);
            graphics.blit(circleArtId, cx - circleRadius, cy - circleRadius, circleRadius * 2, circleRadius * 2, 0.0f, 0.0f, 1.0f, 1.0f);
            drawCircleRing(graphics, cx, cy, circleRadius, borderColor);

            // Center spindle
            drawCircle(graphics, cx, cy, 2, (alpha << 24) | 0xFFD97746);
            drawCircle(graphics, cx, cy, 1, (alpha << 24) | 0x141312);
        } else {
            // Dark grooved vinyl record disc
            int discColor = (alpha << 24) | 0x1C1A18;
            drawCircle(graphics, cx, cy, circleRadius, discColor);
            drawCircleRing(graphics, cx, cy, circleRadius, borderColor);

            // Concentric vinyl grooves
            drawCircleRing(graphics, cx, cy, circleRadius - 3, (alpha << 24) | 0x30FFFFFF);
            drawCircleRing(graphics, cx, cy, circleRadius - 6, (alpha << 24) | 0x18FFFFFF);

            // Center bronze/gold turntable hub
            int coreColor = (alpha << 24) | (audio.isPaused() ? 0xFFCC00 : 0xFFD97746);
            drawCircle(graphics, cx, cy, 4, coreColor);
            drawCircle(graphics, cx, cy, 2, (alpha << 24) | 0x141312);
        }

        // Rotating turntable needle / stylus animation inside the circle
        if (isPlaying && !audio.isPaused()) {
            float needleX = cx + (float) Math.cos(vinylAngle) * 6.5f;
            float needleY = cy + (float) Math.sin(vinylAngle) * 6.5f;
            graphics.fill((int) needleX, (int) needleY, (int) needleX + 2, (int) needleY + 2, (alpha << 24) | 0xFFFFF0D0);

            float oppX = cx - (float) Math.cos(vinylAngle) * 4.5f;
            float oppY = cy - (float) Math.sin(vinylAngle) * 4.5f;
            graphics.fill((int) oppX, (int) oppY, (int) oppX + 1, (int) oppY + 1, (alpha << 24) | 0x60FFFFFF);
        } else if (audio.isPaused()) {
            // Little pause pause glyph in center
            graphics.fill(cx - 2, cy - 2, cx - 1, cy + 3, (alpha << 24) | 0xFFFFFF);
            graphics.fill(cx + 1, cy - 2, cx + 2, cy + 3, (alpha << 24) | 0xFFFFFF);
        }

        // 3. Middle Text: Song Info
        Font font = client.font;
        int textX = cx + circleRadius + 7;
        int visW = 34; // width reserved for 6 dancing visualizer bars on the right
        int availTextW = (posX + hudW - visW - 4) - textX;

        if (availTextW > 20) {
            if (compactProgress < 0.6f) {
                // Expanded mode: 2 clean lines
                int titleW = Math.max(10, availTextW);
                String safeTitle = truncateText(font, track.getTitle(), titleW);
                graphics.text(font, Component.literal("§f§l" + safeTitle), textX, posY + 5, (alpha << 24) | 0xFFFFFF, false);

                String artistTime = track.getArtist();
                long elapsedSecs = audio.getElapsedSeconds();
                String timeStr = (elapsedSecs / 60) + ":" + String.format("%02d", elapsedSecs % 60);
                String safeArtist = truncateText(font, artistTime + " §8• §6" + timeStr, availTextW);
                graphics.text(font, Component.literal("§7" + safeArtist), textX, posY + 17, (alpha << 24) | 0xA0988E, false);
            } else {
                // Compact pill mode: 1 centered clean line
                String compactLabel = track.getTitle() + " • " + track.getArtist();
                String safeText = truncateText(font, compactLabel, availTextW);
                graphics.text(font, Component.literal("§f" + safeText), textX, posY + 12, (alpha << 24) | 0xFFFFFF, false);
            }
        }

        // 4. Right: Dynamic 6-Bar Dancing Equalizer (Fluid moving wave animation)
        int visX = posX + hudW - visW - 2;
        int maxBarH = 16;
        int barBaseY = posY + hudH - 7;
        float[] rawBands = audio.getVisualizerBands();
        long timeMs = System.currentTimeMillis();
        double t = timeMs / 1000.0;

        for (int i = 0; i < 6; i++) {
            float audioEnergy = (rawBands != null && (i * 2) < rawBands.length) ? rawBands[i * 2] : 0.0f;
            audioEnergy = Math.max(0.0f, Math.min(1.0f, audioEnergy));

            // Fluid sinusoidal multi-frequency wave motion so each bar has a distinct moving phase
            double w1 = Math.sin(t * 4.0 - i * 0.65);
            double w2 = Math.sin(t * 6.5 + i * 0.85) * 0.35;
            double wave = 0.50 + (0.35 * w1) + w2;
            wave = Math.max(0.08, Math.min(0.92, wave));

            float target;
            if (isPlaying && !audio.isPaused()) {
                double energyMod = 0.32 + (0.40 * audioEnergy) + (0.28 * smoothPeak);
                target = (float) (0.15 + (wave * energyMod));
                target = Math.max(0.12f, Math.min(0.88f, target));
            } else {
                target = 0.06f; // resting baseline when stopped or paused
            }

            smoothBars[i] = AnimationUtil.lerp(smoothBars[i], target, 0.35f);
            int barH = Math.max(2, Math.min(maxBarH, (int) (smoothBars[i] * maxBarH)));
            int barX = visX + (i * 5);
            int barY = barBaseY - barH;

            // Vibrant warm golden-amber gradient
            int barColor = (alpha << 24) | AnimationUtil.colorLerp(0xD97746, 0xFFA860, (float) i / 5.0f);
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

    private static void drawCircle(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.sqrt(radius * radius - dy * dy);
            graphics.fill(cx - dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
        }
    }

    private static void drawCircleRing(GuiGraphicsExtractor graphics, int cx, int cy, int radius, int color) {
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
