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
    private int previewY = 100;
    private final int hudW = 220;
    private final int hudH = 32;

    public HudMoveScreen(Screen parentScreen) {
        super(Component.literal("Move HUD Overlay"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();
        HudConfigManager cfg = HudConfigManager.getInstance();
        previewX = cfg.getHudX(this.width, hudW);
        previewY = cfg.getHudY(this.height, hudH);
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
        int headerH = 34;
        int headerX = (this.width - headerW) / 2;
        int headerY = 10;
        MusicPlayerScreen.drawSoftBox(graphics, headerX, headerY, headerW, headerH, 5, 0xF0181A1B, 0xFFD97746);
        graphics.centeredText(this.font, Component.literal("§6§l✦ DRAG HUD ANYWHERE ON SCREEN ✦"), this.width / 2, headerY + 6, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.literal("§7Click and hold the music card to drag • Release to save position"), this.width / 2, headerY + 19, 0xFFA0988E);

        // Render HUD Card Preview
        HudConfigManager cfg = HudConfigManager.getInstance();
        AudioManager audio = AudioManager.getInstance();
        AudioTrack track = audio.getCurrentTrack();
        String title = track != null ? track.getTitle() : "Pulse Lo-Fi Beats & Chill";
        String artist = track != null ? track.getArtist() : "Lofi Girl Records";

        // HUD Capsule Body (Rounded pill with soft 5px curved corners)
        int cardBg = isDragging ? 0xF02A241F : 0xEE141618;
        int borderCol = isDragging ? 0xFFFFD700 : 0xFFD97746;

        graphics.fill(previewX + 5, previewY, previewX + hudW - 5, previewY + 1, cardBg);
        graphics.fill(previewX + 3, previewY + 1, previewX + hudW - 3, previewY + 2, cardBg);
        graphics.fill(previewX + 2, previewY + 2, previewX + hudW - 2, previewY + 3, cardBg);
        graphics.fill(previewX + 1, previewY + 3, previewX + hudW - 1, previewY + 5, cardBg);
        graphics.fill(previewX, previewY + 5, previewX + hudW, previewY + hudH - 5, cardBg);
        graphics.fill(previewX + 1, previewY + hudH - 5, previewX + hudW - 1, previewY + hudH - 3, cardBg);
        graphics.fill(previewX + 2, previewY + hudH - 3, previewX + hudW - 2, previewY + hudH - 2, cardBg);
        graphics.fill(previewX + 3, previewY + hudH - 2, previewX + hudW - 3, previewY + hudH - 1, cardBg);
        graphics.fill(previewX + 5, previewY + hudH - 1, previewX + hudW - 5, previewY + hudH, cardBg);

        graphics.fill(previewX + 5, previewY, previewX + hudW - 5, previewY + 1, borderCol);
        graphics.fill(previewX + 5, previewY + hudH - 1, previewX + hudW - 5, previewY + hudH, borderCol);
        graphics.fill(previewX, previewY + 5, previewX + 1, previewY + hudH - 5, borderCol);
        graphics.fill(previewX + hudW - 1, previewY + 5, previewX + hudW, previewY + hudH - 5, borderCol);

        graphics.fill(previewX + 3, previewY + 1, previewX + 5, previewY + 2, borderCol);
        graphics.fill(previewX + 2, previewY + 2, previewX + 3, previewY + 3, borderCol);
        graphics.fill(previewX + 1, previewY + 3, previewX + 2, previewY + 5, borderCol);

        graphics.fill(previewX + hudW - 5, previewY + 1, previewX + hudW - 3, previewY + 2, borderCol);
        graphics.fill(previewX + hudW - 3, previewY + 2, previewX + hudW - 2, previewY + 3, borderCol);
        graphics.fill(previewX + hudW - 2, previewY + 3, previewX + hudW - 1, previewY + 5, borderCol);

        graphics.fill(previewX + 1, previewY + hudH - 5, previewX + 2, previewY + hudH - 3, borderCol);
        graphics.fill(previewX + 2, previewY + hudH - 3, previewX + 3, previewY + hudH - 2, borderCol);
        graphics.fill(previewX + 3, previewY + hudH - 2, previewX + 5, previewY + hudH - 1, borderCol);

        graphics.fill(previewX + hudW - 2, previewY + hudH - 5, previewX + hudW - 1, previewY + hudH - 3, borderCol);
        graphics.fill(previewX + hudW - 3, previewY + hudH - 3, previewX + hudW - 2, previewY + hudH - 2, borderCol);
        graphics.fill(previewX + hudW - 5, previewY + hudH - 2, previewX + hudW - 3, previewY + hudH - 1, borderCol);

        graphics.fill(previewX + 5, previewY + 1, previewX + hudW - 5, previewY + 2, 0x40FFFFFF);

        // Left circle with moving animated wave line inside
        int cx = previewX + 16;
        int cy = previewY + 16;
        int circleRadius = 11;

        // Dark circle interior & border ring
        HudOverlayRenderer.drawCircle(graphics, cx, cy, circleRadius, 0xFF141518);
        HudOverlayRenderer.drawCircleRing(graphics, cx, cy, circleRadius, borderCol);

        // Wave line preview
        double moveTime = System.currentTimeMillis() * 0.007;
        for (int px = -8; px <= 8; px++) {
            int curX = cx + px;
            double normX = px / 8.0;
            double wave = Math.sin(normX * 4.2 + moveTime) * 0.7;
            int curY = cy + (int) Math.round(wave * 3.5);
            graphics.fill(curX, curY - 1, curX + 1, curY, 0x60D97746);
            graphics.fill(curX, curY, curX + 1, curY + 1, 0xFFFFF0D0);
            graphics.fill(curX, curY + 1, curX + 1, curY + 2, 0x60D97746);
        }
        graphics.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

        // Info Text
        graphics.text(this.font, Component.literal("§f§l" + truncate(title, 20)), previewX + 32, previewY + 5, 0xFFFFFFFF, false);
        graphics.text(this.font, Component.literal("§7" + truncate(artist, 22)), previewX + 32, previewY + 17, 0xFFA0988E, false);

        // Mini dancing visualizer bars on right
        int visX = previewX + hudW - 34;
        int barBaseY = previewY + hudH - 7;
        for (int i = 0; i < 6; i++) {
            int bh = 4 + (i * 3 % 11);
            graphics.fill(visX + (i * 5), barBaseY - bh, visX + (i * 5) + 3, barBaseY, 0xFFD97746);
            graphics.fill(visX + (i * 5), barBaseY - bh, visX + (i * 5) + 3, barBaseY - bh + 1, 0xFFFFF0D0);
        }

        // Bottom Controls: Snap Presets & Save Done
        int bottomY = this.height - 32;
        int btnH = 20;
        int btnW = 74;

        // Top-Left Snap
        drawSnapButton(graphics, 12, bottomY, btnW, btnH, "Top-Left", mouseX, mouseY, () -> {
            previewX = 12;
            previewY = 12;
            cfg.setCustomPosition(previewX, previewY);
        });

        // Top-Right Snap
        drawSnapButton(graphics, 12 + btnW + 6, bottomY, btnW, btnH, "Top-Right", mouseX, mouseY, () -> {
            previewX = this.width - hudW - 12;
            previewY = 12;
            cfg.setCustomPosition(previewX, previewY);
        });

        // Bottom-Left Snap
        drawSnapButton(graphics, 12 + (btnW + 6) * 2, bottomY, btnW, btnH, "Btm-Left", mouseX, mouseY, () -> {
            previewX = 12;
            previewY = this.height - hudH - 12;
            cfg.setCustomPosition(previewX, previewY);
        });

        // Bottom-Right Snap
        drawSnapButton(graphics, 12 + (btnW + 6) * 3, bottomY, btnW, btnH, "Btm-Right", mouseX, mouseY, () -> {
            previewX = this.width - hudW - 12;
            previewY = this.height - hudH - 12;
            cfg.setCustomPosition(previewX, previewY);
        });

        // Auto-Compact Toggle Button
        boolean compactOn = cfg.isAutoCompact();
        int compBtnW = 110;
        int compBtnX = 12 + (btnW + 6) * 4;
        boolean compHov = mouseX >= compBtnX && mouseX <= compBtnX + compBtnW && mouseY >= bottomY && mouseY <= bottomY + btnH;
        MusicPlayerScreen.drawSoftBox(graphics, compBtnX, bottomY, compBtnW, btnH, 3, compactOn ? (compHov ? 0xFFE0783E : 0xFFD97746) : (compHov ? 0x60FFFFFF : 0x25FFFFFF), compHov ? 0xFFFFFFFF : 0x35FFFFFF);
        graphics.centeredText(this.font, Component.literal(compactOn ? "§fAuto-Compact: §aON" : "§7Auto-Compact: §cOFF"), compBtnX + compBtnW / 2, bottomY + 6, 0xFFFFFFFF);

        // Save & Done Button (Green pill on far right)
        int doneW = 86;
        int doneX = this.width - doneW - 12;
        boolean doneHov = mouseX >= doneX && mouseX <= doneX + doneW && mouseY >= bottomY && mouseY <= bottomY + btnH;
        MusicPlayerScreen.drawSoftBox(graphics, doneX, bottomY, doneW, btnH, 3, doneHov ? 0xFF22C55E : 0xFF16A34A, doneHov ? 0xFFFFFFFF : 0x50FFFFFF);
        graphics.centeredText(this.font, Component.literal("§f✔ Save & Done"), doneX + doneW / 2, bottomY + 6, 0xFFFFFFFF);

        // Coordinates display
        graphics.text(this.font, Component.literal("§7Position: §6X=" + previewX + ", Y=" + previewY), previewX, previewY - 10, 0xFFFFFFFF, false);
    }

    private void drawSnapButton(GuiGraphicsExtractor graphics, int bx, int by, int bw, int bh, String label, int mx, int my, Runnable action) {
        boolean hov = mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        MusicPlayerScreen.drawSoftBox(graphics, bx, by, bw, bh, 3, hov ? 0x80D97746 : 0x25FFFFFF, hov ? 0xFFD97746 : 0x30FFFFFF);
        graphics.centeredText(this.font, Component.literal("§f" + label), bx + bw / 2, by + 6, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x();
        double my = event.y();

        if (event.button() == 0) { // Left click
            // Check HUD card click for dragging
            if (mx >= previewX && mx <= previewX + hudW && my >= previewY && my <= previewY + hudH) {
                isDragging = true;
                dragOffsetX = (int) mx - previewX;
                dragOffsetY = (int) my - previewY;
                return true;
            }

            int bottomY = this.height - 32;
            int btnH = 20;
            int btnW = 74;
            HudConfigManager cfg = HudConfigManager.getInstance();

            // Top-Left Snap
            if (mx >= 12 && mx <= 12 + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = 12;
                previewY = 12;
                cfg.setCustomPosition(previewX, previewY);
                return true;
            }

            // Top-Right Snap
            if (mx >= 12 + btnW + 6 && mx <= 12 + (btnW + 6) + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = this.width - hudW - 12;
                previewY = 12;
                cfg.setCustomPosition(previewX, previewY);
                return true;
            }

            // Bottom-Left Snap
            if (mx >= 12 + (btnW + 6) * 2 && mx <= 12 + (btnW + 6) * 2 + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = 12;
                previewY = this.height - hudH - 12;
                cfg.setCustomPosition(previewX, previewY);
                return true;
            }

            // Bottom-Right Snap
            if (mx >= 12 + (btnW + 6) * 3 && mx <= 12 + (btnW + 6) * 3 + btnW && my >= bottomY && my <= bottomY + btnH) {
                previewX = this.width - hudW - 12;
                previewY = this.height - hudH - 12;
                cfg.setCustomPosition(previewX, previewY);
                return true;
            }

            // Auto-compact toggle
            int compBtnW = 110;
            int compBtnX = 12 + (btnW + 6) * 4;
            if (mx >= compBtnX && mx <= compBtnX + compBtnW && my >= bottomY && my <= bottomY + btnH) {
                cfg.toggleAutoCompact();
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

        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0) {
            if (isDragging) {
                isDragging = false;
                HudConfigManager.getInstance().setCustomPosition(previewX, previewY);
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        if (isDragging) {
            previewX = (int) event.x() - dragOffsetX;
            previewY = (int) event.y() - dragOffsetY;
            previewX = Math.max(4, Math.min(this.width - hudW - 4, previewX));
            previewY = Math.max(4, Math.min(this.height - hudH - 4, previewY));
            HudConfigManager.getInstance().setCustomPosition(previewX, previewY);
            return true;
        }
        return super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public void onClose() {
        close();
    }

    private void close() {
        HudConfigManager.getInstance().setCustomPosition(previewX, previewY);
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parentScreen);
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null) return "";
        return text.length() <= maxChars ? text : text.substring(0, Math.max(0, maxChars - 2)) + "..";
    }
}
