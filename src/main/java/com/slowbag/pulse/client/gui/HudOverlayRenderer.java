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

        // 2. Circle on the Left with Animated Audio Wave Line Inside
        int cx = posX + 18;
        int cy = posY + 16;
        int circleRadius = 13;

        // Bass pulse aura glow around the enlarged circle
        int pulseRadius = circleRadius + (int) (smoothPeak * 2.0f);
        int glowColor = ((int) (alpha * (0.22f + smoothPeak * 0.40f)) << 24) | (borderR << 16) | (borderG << 8) | borderB;
        drawCircle(graphics, cx, cy, pulseRadius + 1, glowColor);

        // Dark glass circle interior
        int discColor = (alpha << 24) | 0x141518;
        drawCircle(graphics, cx, cy, circleRadius, discColor);

        // Subtle concentric groove accent inside circle
        drawCircleRing(graphics, cx, cy, circleRadius - 3, ((int) (alpha * 0.20f) << 24) | 0x40FFFFFF);

        // Circular ring border
        drawCircleRing(graphics, cx, cy, circleRadius, borderColor);

        // Moving animated audio line inside the circle
        double timeMs = System.currentTimeMillis();
        double wavePhase = timeMs * 0.007;
        float amp = (isPlaying && !audio.isPaused()) ? (2.0f + smoothPeak * 5.0f) : 0.0f;
        int waveLineColor = (alpha << 24) | 0xFFFFF0D0;
        int waveGlowColor = ((int) (alpha * 0.45f) << 24) | (borderR << 16) | (borderG << 8) | borderB;

        int prevX = cx - circleRadius + 2;
        int prevY = cy;

        for (int px = -circleRadius + 2; px <= circleRadius - 2; px++) {
            int curX = cx + px;
            float normX = (float) px / (float) (circleRadius - 2);
            float window = (float) Math.cos(normX * (Math.PI / 2.0));
            float maxH = (float) Math.sqrt(Math.max(0, (circleRadius - 1) * (circleRadius - 1) - px * px)) - 1.5f;

            double wave = Math.sin(normX * 4.2 + wavePhase) * 0.65 + Math.sin(normX * 8.5 - wavePhase * 1.4) * 0.35;
            int offset = (int) Math.round(wave * amp * window);
            offset = Math.max(-(int) maxH, Math.min((int) maxH, offset));
            int curY = cy + offset;

            // Connect previous point to current point for a continuous, smooth wave line
            int minY = Math.min(prevY, curY);
            int maxY = Math.max(prevY, curY);
            for (int yStep = minY; yStep <= maxY; yStep++) {
                // Soft glow halo
                graphics.fill(curX, yStep - 1, curX + 1, yStep, waveGlowColor);
                graphics.fill(curX, yStep + 1, curX + 1, yStep + 2, waveGlowColor);
                // Crisp bright core wave line
                graphics.fill(curX, yStep, curX + 1, yStep + 1, waveLineColor);
            }

            prevX = curX;
            prevY = curY;
        }

        // Center beacon dot on the moving wave
        if (isPlaying && !audio.isPaused()) {
            graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, (alpha << 24) | 0xFFFFFFFF);
        } else if (audio.isPaused()) {
            // Little pause pause glyph in center
            graphics.fill(cx - 2, cy - 3, cx - 1, cy + 4, (alpha << 24) | 0xFFD97746);
            graphics.fill(cx + 1, cy - 3, cx + 2, cy + 4, (alpha << 24) | 0xFFD97746);
        }

        // 3. Middle Text: Song Info (Expands smoothly across entire pill width without bars crowding)
        Font font = client.font;
        int textX = cx + circleRadius + 8;
        int availTextW = (posX + hudW - 10) - textX;

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

        graphics.disableScissor();
    }

    private static void drawPillBackground(GuiGraphicsExtractor graphics, int x, int y, int w, int h, int bg, int outline) {
        // Soft rounded capsule fill (5px smooth corner fillets)
        graphics.fill(x + 5, y, x + w - 5, y + 1, bg);
        graphics.fill(x + 3, y + 1, x + w - 3, y + 2, bg);
        graphics.fill(x + 2, y + 2, x + w - 2, y + 3, bg);
        graphics.fill(x + 1, y + 3, x + w - 1, y + 5, bg);
        graphics.fill(x, y + 5, x + w, y + h - 5, bg);
        graphics.fill(x + 1, y + h - 5, x + w - 1, y + h - 3, bg);
        graphics.fill(x + 2, y + h - 3, x + w - 2, y + h - 2, bg);
        graphics.fill(x + 3, y + h - 2, x + w - 3, y + h - 1, bg);
        graphics.fill(x + 5, y + h - 1, x + w - 5, y + h, bg);

        // Smooth continuous curved outline (no sharp edges)
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
