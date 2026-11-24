package com.slowbag.pulse.client.input;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Manages configurable key combination hotkey to open Pulse Music Player GUI.
 * Defaults to 'J' with support for modifiers (Ctrl, Alt, Shift) or 'None'.
 */
public class KeybindManager {

    private static final KeybindManager INSTANCE = new KeybindManager();
    public static KeybindManager getInstance() {
        return INSTANCE;
    }

    private int keyCode = GLFW.GLFW_KEY_J; // Default: 'J'
    private boolean ctrl = false;
    private boolean alt = false;
    private boolean shift = false;
    private boolean isListening = false;

    private static File getConfigFile() {
        File configDir = null;
        try {
            configDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().toFile();
        } catch (Throwable ignored) {}
        if (configDir == null) {
            try {
                if (Minecraft.getInstance() != null && Minecraft.getInstance().gameDirectory != null) {
                    configDir = new File(Minecraft.getInstance().gameDirectory, "config");
                }
            } catch (Throwable ignored) {}
        }
        if (configDir == null) {
            configDir = new File("config");
        }
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "pulse_keybind.json");
    }

    private KeybindManager() {
        try {
            loadConfig();
        } catch (Throwable ignored) {}
    }

    public int getKeyCode() {
        return keyCode;
    }

    public boolean isCtrl() {
        return ctrl;
    }

    public boolean isAlt() {
        return alt;
    }

    public boolean isShift() {
        return shift;
    }

    public boolean isListening() {
        return isListening;
    }

    public void setListening(boolean listening) {
        this.isListening = listening;
    }

    public void setKeybind(int key, boolean ctrl, boolean alt, boolean shift) {
        this.keyCode = key;
        this.ctrl = ctrl;
        this.alt = alt;
        this.shift = shift;
        this.isListening = false;
        try {
            if (com.slowbag.pulse.client.MusicPlayerClientMod.pulseKeyMapping != null) {
                com.slowbag.pulse.client.MusicPlayerClientMod.pulseKeyMapping.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(key));
                net.minecraft.client.KeyMapping.resetMapping();
            }
        } catch (Throwable ignored) {}
        saveConfig();
    }

    public void clearKeybind() {
        this.keyCode = GLFW.GLFW_KEY_UNKNOWN;
        this.ctrl = false;
        this.alt = false;
        this.shift = false;
        this.isListening = false;
        try {
            if (com.slowbag.pulse.client.MusicPlayerClientMod.pulseKeyMapping != null) {
                com.slowbag.pulse.client.MusicPlayerClientMod.pulseKeyMapping.setKey(com.mojang.blaze3d.platform.InputConstants.UNKNOWN);
                net.minecraft.client.KeyMapping.resetMapping();
            }
        } catch (Throwable ignored) {}
        saveConfig();
    }

    public boolean isConfigured() {
        return keyCode > 0 && keyCode != GLFW.GLFW_KEY_UNKNOWN;
    }

    public String getKeybindDisplay() {
        if (!isConfigured()) return "None";
        StringBuilder sb = new StringBuilder();
        if (ctrl) sb.append("Ctrl + ");
        if (alt) sb.append("Alt + ");
        if (shift) sb.append("Shift + ");
        sb.append(getKeyName(keyCode));
        return sb.toString();
    }

    public static String getKeyName(int keyCode) {
        if (keyCode <= 0 || keyCode == GLFW.GLFW_KEY_UNKNOWN) return "None";

        // 1. Letters A-Z (Standard Latin on any OS/Linux/Windows)
        if (keyCode >= GLFW.GLFW_KEY_A && keyCode <= GLFW.GLFW_KEY_Z) {
            return String.valueOf((char) keyCode);
        }

        // 2. Numbers 0-9
        if (keyCode >= GLFW.GLFW_KEY_0 && keyCode <= GLFW.GLFW_KEY_9) {
            return String.valueOf((char) keyCode);
        }

        // 3. Keypad numbers 0-9
        if (keyCode >= GLFW.GLFW_KEY_KP_0 && keyCode <= GLFW.GLFW_KEY_KP_9) {
            return "Num " + (keyCode - GLFW.GLFW_KEY_KP_0);
        }

        // 4. Try Minecraft's native InputConstants translation (handles OS locale, layout, Linux X11/Wayland, SDL)
        try {
            var key = com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(keyCode);
            if (key != null) {
                String disp = key.getDisplayName().getString();
                if (disp != null && !disp.isBlank() && !disp.toLowerCase().startsWith("key.keyboard")) {
                    return disp.toUpperCase();
                }
            }
        } catch (Throwable ignored) {}

        // 5. Try GLFW key name
        try {
            String name = GLFW.glfwGetKeyName(keyCode, 0);
            if (name != null && !name.isBlank()) {
                return name.toUpperCase();
            }
        } catch (Throwable ignored) {}

        // 6. Comprehensive fallback dictionary
        return switch (keyCode) {
            case GLFW.GLFW_KEY_TAB -> "Tab";
            case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps Lock";
            case GLFW.GLFW_KEY_SPACE -> "Space";
            case GLFW.GLFW_KEY_ENTER -> "Enter";
            case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
            case GLFW.GLFW_KEY_ESCAPE -> "Esc";
            case GLFW.GLFW_KEY_INSERT -> "Insert";
            case GLFW.GLFW_KEY_DELETE -> "Delete";
            case GLFW.GLFW_KEY_RIGHT -> "Right Arrow";
            case GLFW.GLFW_KEY_LEFT -> "Left Arrow";
            case GLFW.GLFW_KEY_DOWN -> "Down Arrow";
            case GLFW.GLFW_KEY_UP -> "Up Arrow";
            case GLFW.GLFW_KEY_PAGE_UP -> "Page Up";
            case GLFW.GLFW_KEY_PAGE_DOWN -> "Page Down";
            case GLFW.GLFW_KEY_HOME -> "Home";
            case GLFW.GLFW_KEY_END -> "End";
            case GLFW.GLFW_KEY_SCROLL_LOCK -> "Scroll Lock";
            case GLFW.GLFW_KEY_NUM_LOCK -> "Num Lock";
            case GLFW.GLFW_KEY_PRINT_SCREEN -> "Print Screen";
            case GLFW.GLFW_KEY_PAUSE -> "Pause";
            case GLFW.GLFW_KEY_F1 -> "F1";
            case GLFW.GLFW_KEY_F2 -> "F2";
            case GLFW.GLFW_KEY_F3 -> "F3";
            case GLFW.GLFW_KEY_F4 -> "F4";
            case GLFW.GLFW_KEY_F5 -> "F5";
            case GLFW.GLFW_KEY_F6 -> "F6";
            case GLFW.GLFW_KEY_F7 -> "F7";
            case GLFW.GLFW_KEY_F8 -> "F8";
            case GLFW.GLFW_KEY_F9 -> "F9";
            case GLFW.GLFW_KEY_F10 -> "F10";
            case GLFW.GLFW_KEY_F11 -> "F11";
            case GLFW.GLFW_KEY_F12 -> "F12";
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> "~";
            case GLFW.GLFW_KEY_MINUS -> "-";
            case GLFW.GLFW_KEY_EQUAL -> "=";
            case GLFW.GLFW_KEY_LEFT_BRACKET -> "[";
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> "]";
            case GLFW.GLFW_KEY_BACKSLASH -> "\\";
            case GLFW.GLFW_KEY_SEMICOLON -> ";";
            case GLFW.GLFW_KEY_APOSTROPHE -> "'";
            case GLFW.GLFW_KEY_COMMA -> ",";
            case GLFW.GLFW_KEY_PERIOD -> ".";
            case GLFW.GLFW_KEY_SLASH -> "/";
            case GLFW.GLFW_KEY_KP_DECIMAL -> "Num .";
            case GLFW.GLFW_KEY_KP_DIVIDE -> "Num /";
            case GLFW.GLFW_KEY_KP_MULTIPLY -> "Num *";
            case GLFW.GLFW_KEY_KP_SUBTRACT -> "Num -";
            case GLFW.GLFW_KEY_KP_ADD -> "Num +";
            case GLFW.GLFW_KEY_KP_ENTER -> "Num Enter";
            case GLFW.GLFW_KEY_KP_EQUAL -> "Num =";
            default -> "Key #" + keyCode;
        };
    }

    public boolean matches(int pressedKey, boolean hasCtrl, boolean hasAlt, boolean hasShift) {
        if (!isConfigured()) return false;
        if (pressedKey != this.keyCode) return false;
        if (this.ctrl != hasCtrl) return false;
        if (this.alt != hasAlt) return false;
        if (this.shift != hasShift) return false;
        return true;
    }

    private void loadConfig() {
        File file = getConfigFile();
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("keyCode")) this.keyCode = json.get("keyCode").getAsInt();
            if (json.has("ctrl")) this.ctrl = json.get("ctrl").getAsBoolean();
            if (json.has("alt")) this.alt = json.get("alt").getAsBoolean();
            if (json.has("shift")) this.shift = json.get("shift").getAsBoolean();
        } catch (Throwable ignored) {}
    }

    private void saveConfig() {
        try {
            File file = getConfigFile();
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            JsonObject json = new JsonObject();
            json.addProperty("keyCode", keyCode);
            json.addProperty("ctrl", ctrl);
            json.addProperty("alt", alt);
            json.addProperty("shift", shift);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json.toString());
            }
        } catch (Throwable ignored) {}
    }
}
