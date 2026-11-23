package com.slowbag.pulse.client.gui;

import com.slowbag.pulse.client.audio.AudioManager;
import com.slowbag.pulse.client.audio.AudioTrack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public class HudMoveScreen extends Screen {

    private final Screen parentScreen;
    private boolean isDragging = false;
    private int dragOffsetX = 0;
    private int dragOffsetY = 0;

    private int previewX = 100;
    private int previewY = 16;
    private final int hudW = 210;
    private final int hudH = 14;

    public HudMoveScreen(Screen parentScreen) {
        super(Component.literal("Move HUD Overlay"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        HudConfigManager cfg = HudConfigManager.getInstance();
        if (cfg.getCustomX() >= 0 && cfg.getCustomY() >= 0) {
            previewX = cfg.getHudX(this.width, hudW);
            previewY = cfg.getHudY(this.height, hudH);
        } else {
            previewX = (this.width - hudW) / 2;
            previewY = 16;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // Darkened glass backdrop to see HUD clearly
        graphics.fill(0, 0, this.width, this.height, 0x90000000);

        // Grid guidelines
        int gridColor = 0x15FFFFFF;
        for (int x = 40; x < this.width; x += 40) {
            graphics.fill(x, 0, x + 1, this.height, gridColor);
        }
        for (int y = 40; y < this.height; y += 40) {
            graphics.fill(0, y, this.width, y + 1, gridColor);
        }

        // Top Header Banner
        int headerW = Math.min(380, this.width - 24);
        int headerH = 32;
        int headerX = (this.width - headerW) / 2;
        int headerY = 10;
        MusicPlayerScreen.drawSoftBox(graphics, headerX, headerY, headerW, headerH, 5, 0xF0181A1B, 0xFFA855F7);
        graphics.centeredText(this.font, Component.literal("§d§l✦ DRAG HUD ANYWHERE ON SCREEN ✦"), this.width / 2, headerY + 5, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("§7Click & drag the HUD pill • Snap buttons below • Release to save"), this.width / 2, headerY + 18, 0xFFA0988E);

        // Get track details or demo track
        AudioManager audio = AudioManager.getInstance();
        AudioTrack track = audio.getCurrentTrack();
        String title = track != null ? track.getTitle() : "Locked Out of Heaven";
        String artist = track != null ? track.getArtist() : "Bruno Mars";

        long elapsedSecs = audio.isPlaying() ? audio.getElapsedSeconds() : 34;
        long durationSecs = (track != null && track.getDurationSeconds() > 0) ? track.getDurationSeconds() : 233;
        String timeStr = (elapsedSecs / 60) + ":" + String.format("%02d", elapsedSecs % 60);
        String totalStr = (durationSecs / 60) + ":" + String.format("%02d", durationSecs % 60);

        // 1. Line 1: Centered Song Title, Artist & Time Above Pill
        String formattedHeader = "§d♪ §f" + truncate(title, 20) + " §7• " + truncate(artist, 18) + "  §d" + timeStr + " §8/ §7" + totalStr;
        graphics.centeredText(this.font, Component.literal(formattedHeader), previewX + hudW / 2, previewY - 10, 0xFFFFFFFF);

        // 2. Line 2: Slim Floating Capsule Pill
        int cardBg = isDragging ? 0xF0221A2B : 0xF012101A;
        int borderCol = isDragging ? 0xFFC084FC : 0xFFA855F7;
        HudOverlayRenderer.drawPillBackground(graphics, previewX, previewY, hudW, hudH, cardBg, borderCol);

        // Specular top highlight
        graphics.fill(previewX + 4, previewY + 1, previewX + hudW - 4, previewY + 2, 0x40FFFFFF);

        // 3. Left: Glowing Cyan Dot
        int dotX = previewX + 8;
        int dotY = previewY + hudH / 2;
        HudOverlayRenderer.drawCircle(graphics, dotX, dotY, 3, 0x6038BDF8);
        HudOverlayRenderer.drawCircle(graphics, dotX, dotY, 2, 0xFF38BDF8);

        // 4. Middle: Song Progress Stance Bar
        int waveAreaW = 30;
        int trackX = previewX + 17;
        int trackW = hudW - 17 - waveAreaW - 6;
        int trackY = previewY + (hudH - 3) / 2;
        int trackH = 3;

        // Groove Background
        graphics.fill(trackX, trackY, trackX + trackW, trackY + trackH, 0x30FFFFFF);

        // Progress Fill (~35% or live)
        float progress = 0.35f;
        if (durationSecs > 0 && elapsedSecs > 0) {
            progress = Math.min(1.0f, (float) elapsedSecs / (float) durationSecs);
        }
        int fillW = (int) (trackW * progress);
        if (fillW > 0) {
            graphics.fill(trackX, trackY, trackX + fillW, trackY + trackH, 0xFFA855F7);
        }

        // Stance Thumb Knob
        int knobX = Math.min(trackX + trackW - 2, trackX + fillW);
        graphics.fill(knobX - 1, trackY - 1, knobX + 2, trackY + trackH + 1, 0xFFFFFFFF);
        graphics.fill(knobX, trackY, knobX + 1, trackY + trackH, 0xFFC084FC);

        // 5. Right: Reactive Curly Audio Wave
        int waveStartX = previewX + hudW - waveAreaW;
        int waveEndX = previewX + hudW - 8;
        int waveCenterY = previewY + hudH / 2;

        double timeMs = System.currentTimeMillis();
        double wavePhase = timeMs * 0.009;
        float amp = 2.8f;

        int prevWX = waveStartX;
        int prevWY = waveCenterY;
        for (int wx = waveStartX; wx <= waveEndX; wx++) {
            float normX = (float) (wx - waveStartX) / (float) (waveEndX - waveStartX);
            double wave = Math.sin(normX * Math.PI * 3.5 + wavePhase) * 0.7 + Math.sin(normX * Math.PI * 7.0 - wavePhase * 1.5) * 0.3;
            int offset = (int) Math.round(wave * amp);
            offset = Math.max(-4, Math.min(4, offset));
            int wy = waveCenterY + offset;

            int minY = Math.min(prevWY, wy);
            int maxY = Math.max(prevWY, wy);
            for (int sy = minY; sy <= maxY; sy++) {
                graphics.fill(wx, sy, wx + 1, sy + 1, 0xFFC084FC);
            }
            prevWX = wx;
            prevWY = wy;
        }

        // Coordinates & Drag Hint Below Pill
        graphics.centeredText(this.font, Component.literal("§7Position: §dX=" + previewX + ", Y=" + previewY), previewX + hudW / 2, previewY + hudH + 4, 0xFFFFFFFF);

        // Bottom Controls: Snap Presets & Save Done
        int bottomY = this.height - 30;
        int btnH = 20;
        int btnW = 74;

        // 1. Top-Center Snap (Default)
        drawSnapButton(graphics, 12, bottomY, btnW, btnH, "Top-Center", mouseX, mouseY, true);

        // 2. Top-Left Snap
        drawSnapButton(graphics, 12 + btnW + 6, bottomY, btnW, btnH, "Top-Left", mouseX, mouseY, false);

        // 3. Top-Right Snap
        drawSnapButton(graphics, 12 + (btnW + 6) * 2, bottomY, btnW, btnH, "Top-Right", mouseX, mouseY, false);

        // 4. Bottom-Center Snap
        drawSnapButton(graphics, 12 + (btnW + 6) * 3, bottomY, btnW, btnH, "Btm-Center", mouseX, mouseY, false);

        // 5. Reset Default
        int resetW = 60;
        int resetX = 12 + (btnW + 6) * 4;
        boolean resetHov = mouseX >= resetX && mouseX <= resetX + resetW && mouseY >= bottomY && mouseY <= bottomY + btnH;
        MusicPlayerScreen.drawSoftBox(graphics, resetX, bottomY, resetW, btnH, 3, resetHov ? 0x80FF3344 : 0x25FFFFFF, resetHov ? 0xFFFF4455 : 0x30FFFFFF);
        graphics.centeredText(this.font, Component.literal("§cReset"), resetX + resetW / 2, bottomY + 6, 0xFFFFFFFF);

        // Save & Done Button (Green/Purple pill on far right)
        int doneW = 86;
        int doneX = this.width - doneW - 12;
        boolean doneHov = mouseX >= doneX && mouseX <= doneX + doneW && mouseY >= bottomY && mouseY <= bottomY + btnH;
        MusicPlayerScreen.drawSoftBox(graphics, doneX, bottomY, doneW, btnH, 3, doneHov ? 0xFFC084FC : 0xFFA855F7, doneHov ? 0xFFFFFFFF : 0x50FFFFFF);
        graphics.centeredText(this.font, Component.literal("§f✔ Save & Done"), doneX + doneW / 2, bottomY + 6, 0xFFFFFFFF);
    }

    private void drawSnapButton(GuiGraphicsExtractor graphics, int bx, int by, int bw, int bh, String label, int mx, int my, boolean highlight) {
        boolean hov = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        int bg = highlight ? (hov ? 0xFFC084FC : 0xFFA855F7) : (hov ? 0x80A855F7 : 0x25FFFFFF);
        int border = highlight ? 0xFFE9D5FF : (hov ? 0xFFA855F7 : 0x30FFFFFF);
        MusicPlayerScreen.drawSoftBox(graphics, bx, by, bw, bh, 3, bg, border);
        graphics.centeredText(this.font, Component.literal("§f" + label), bx + bw / 2, by + 6, 0xFFFFFFFF);
    }

    private boolean handleMouseClicked(double mx, double my, int button) {
        if (button == 0) { // Left click
            // Check HUD card click for dragging (includes text above pill and pill)
            if (mx >= previewX - 4 && mx <= previewX + hudW + 4 && my >= previewY - 14 && my <= previewY + hudH + 12) {
                isDragging = true;
                dragOffsetX = (int) mx - previewX;
                dragOffsetY = (int) my - previewY;
                return true;
            }

            int bottomY = this.height - 30;
            int btnH = 20;
            int btnW = 74;
            HudConfigManager cfg = HudConfigManager.getInstance();

            // 1. Top-Center Snap (Default)
            if (mx >= 12 && mx <= 12 + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = (this.width - hudW) / 2;
                previewY = 16;
                cfg.resetToDefault();
                return true;
            }

            // 2. Top-Left Snap
            if (mx >= 12 + btnW + 6 && mx <= 12 + (btnW + 6) + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = 12;
                previewY = 16;
                cfg.setCustomPosition(previewX, previewY);
                return true;
            }

            // 3. Top-Right Snap
            if (mx >= 12 + (btnW + 6) * 2 && mx <= 12 + (btnW + 6) * 2 + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = this.width - hudW - 12;
                previewY = 16;
                cfg.setCustomPosition(previewX, previewY);
                return true;
            }

            // 4. Bottom-Center Snap
            if (mx >= 12 + (btnW + 6) * 3 && mx <= 12 + (btnW + 6) * 3 + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = (this.width - hudW) / 2;
                previewY = this.height - hudH - 12;
                cfg.setCustomPosition(previewX, previewY);
                return true;
            }

            // 5. Reset Default
            int resetW = 60;
            int resetX = 12 + (btnW + 6) * 4;
            if (mx >= resetX && mx <= resetX + resetW && my >= bottomY && my <= bottomY + btnH) {
                cfg.resetToDefault();
                previewX = (this.width - hudW) / 2;
                previewY = 16;
                return true;
            }

            // Done Button
            int doneW = 86;
            int doneX = this.width - doneW - 12;
            if (mx >= doneX && mx <= doneX + doneW && my >= bottomY && my <= bottomY + btnH) {
                close();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        if (handleMouseClicked(event.x(), event.y(), event.button())) {
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (handleMouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            if (isDragging) {
                isDragging = false;
                applySavedPosition();
            }
        }
        return super.mouseReleased(event);
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && isDragging) {
            isDragging = false;
            applySavedPosition();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (isDragging) {
            previewX = (int) event.x() - dragOffsetX;
            previewY = (int) event.y() - dragOffsetY;
            previewX = Math.max(4, Math.min(this.width - hudW - 4, previewX));
            previewY = Math.max(14, Math.min(this.height - hudH - 14, previewY));
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (isDragging) {
            previewX = (int) mouseX - dragOffsetX;
            previewY = (int) mouseY - dragOffsetY;
            previewX = Math.max(4, Math.min(this.width - hudW - 4, previewX));
            previewY = Math.max(14, Math.min(this.height - hudH - 14, previewY));
            return true;
        }
        return false;
    }

    private void applySavedPosition() {
        // If dropped very close to top-center default, snap directly to default TOP
        int defaultX = (this.width - hudW) / 2;
        int defaultY = 16;
        if (Math.abs(previewX - defaultX) < 16 && Math.abs(previewY - defaultY) < 8) {
            previewX = defaultX;
            previewY = defaultY;
            HudConfigManager.getInstance().resetToDefault();
        } else {
            HudConfigManager.getInstance().setCustomPosition(previewX, previewY);
        }
    }

    @Override
    public void onClose() {
        close();
    }

    private void close() {
        applySavedPosition();
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parentScreen);
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, Math.max(0, maxChars - 2)) + "..";
    }
}
