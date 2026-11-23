package com.slowbag.pulse.client.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;

public class HudConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HudConfigManager INSTANCE = new HudConfigManager();

    private boolean hudEnabled = true;
    private String position = "TOP"; // "TOP", "RIGHT", "LEFT", "CUSTOM"
    private int customX = -1;
    private int customY = -1;
    private boolean autoCompact = true; // Auto-compact to beating circle after song intro
    private boolean loaded = false;

    private HudConfigManager() {
        load();
    }

    public static HudConfigManager getInstance() {
        return INSTANCE;
    }

    private File getConfigFile() {
        File configDir = new File(Minecraft.getInstance().gameDirectory, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "musicplayer_hud.json");
    }

    public synchronized boolean isHudEnabled() {
        if (!loaded) load();
        return hudEnabled;
    }

    public synchronized void setHudEnabled(boolean enabled) {
        this.hudEnabled = enabled;
        save();
    }

    public synchronized boolean toggleEnabled() {
        this.hudEnabled = !this.hudEnabled;
        save();
        return this.hudEnabled;
    }

    public synchronized String getPosition() {
        if (!loaded) load();
        return position != null ? position : "TOP";
    }

    public synchronized void setPosition(String position) {
        this.position = position;
        save();
    }

    public synchronized int getCustomX() {
        if (!loaded) load();
        return customX;
    }

    public synchronized int getCustomY() {
        if (!loaded) load();
        return customY;
    }

    public synchronized void setCustomPosition(int x, int y) {
        this.customX = x;
        this.customY = y;
        this.position = "CUSTOM";
        save();
    }

    public synchronized void resetToDefault() {
        this.position = "TOP";
        this.customX = -1;
        this.customY = -1;
        save();
    }

    public synchronized void resetToDefault(boolean right) {
        resetToDefault();
    }

    public synchronized boolean isAutoCompact() {
        if (!loaded) load();
        return autoCompact;
    }

    public synchronized void setAutoCompact(boolean autoCompact) {
        this.autoCompact = autoCompact;
        save();
    }

    public synchronized boolean toggleAutoCompact() {
        this.autoCompact = !this.autoCompact;
        save();
        return this.autoCompact;
    }

    public synchronized int getHudX(int screenW, int currentW) {
        if (!loaded) load();
        if ("CUSTOM".equalsIgnoreCase(position) && customX >= 0) {
            return Math.max(4, Math.min(screenW - currentW - 4, customX));
        }
        if ("LEFT".equalsIgnoreCase(position)) {
            return 12;
        }
        if ("RIGHT".equalsIgnoreCase(position)) {
            return screenW - currentW - 12;
        }
        // Default: TOP (Centered)
        return (screenW - currentW) / 2;
    }

    public synchronized int getHudY(int screenH, int currentH) {
        if (!loaded) load();
        if ("CUSTOM".equalsIgnoreCase(position) && customY >= 0) {
            return Math.max(4, Math.min(screenH - currentH - 4, customY));
        }
        return 16;
    }

    /**
     * Cycles through: Top -> Right -> Left -> Custom -> Disabled -> Top
     */
    public synchronized String cycleSetting() {
        if (!hudEnabled) {
            hudEnabled = true;
            position = "TOP";
            save();
            return "Top Bar";
        } else if ("TOP".equalsIgnoreCase(position)) {
            position = "RIGHT";
            save();
            return "Right";
        } else if ("RIGHT".equalsIgnoreCase(position)) {
            position = "LEFT";
            save();
            return "Left";
        } else if ("LEFT".equalsIgnoreCase(position)) {
            if (customX >= 0 && customY >= 0) {
                position = "CUSTOM";
                save();
                return "Custom";
            } else {
                hudEnabled = false;
                save();
                return "Disabled";
            }
        } else if ("CUSTOM".equalsIgnoreCase(position)) {
            hudEnabled = false;
            save();
            return "Disabled";
        } else {
            hudEnabled = true;
            position = "TOP";
            save();
            return "Top Bar";
        }
    }

    private synchronized void load() {
        loaded = true;
        File file = getConfigFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            HudConfigData data = GSON.fromJson(reader, HudConfigData.class);
            if (data != null) {
                this.hudEnabled = data.hudEnabled;
                this.position = (data.position != null) ? data.position : "TOP";
                if ("RIGHT".equalsIgnoreCase(this.position) && data.customX < 0 && data.customY < 0) {
                    this.position = "TOP";
                }
                this.customX = data.customX;
                this.customY = data.customY;
                this.autoCompact = data.autoCompact;
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to load HUD config", e);
        }
    }

    private synchronized void save() {
        File file = getConfigFile();
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            HudConfigData data = new HudConfigData();
            data.hudEnabled = this.hudEnabled;
            data.position = this.position;
            data.customX = this.customX;
            data.customY = this.customY;
            data.autoCompact = this.autoCompact;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to save HUD config", e);
        }
    }

    private static class HudConfigData {
        boolean hudEnabled = true;
        String position = "TOP";
        int customX = -1;
        int customY = -1;
        boolean autoCompact = true;
    }
}
