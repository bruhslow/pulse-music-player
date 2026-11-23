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

    private static final File CONFIG_FILE = new File(Minecraft.getInstance().gameDirectory, "config/pulse_keybind.json");

    private KeybindManager() {
        loadConfig();
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
        saveConfig();
    }

    public void clearKeybind() {
        this.keyCode = GLFW.GLFW_KEY_UNKNOWN;
        this.ctrl = false;
        this.alt = false;
        this.shift = false;
        this.isListening = false;
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

        String name = null;
        try {
            name = GLFW.glfwGetKeyName(keyCode, 0);
        } catch (Throwable ignored) {}

        if (name != null && !name.isBlank()) {
            sb.append(name.toUpperCase());
        } else {
            switch (keyCode) {
                case GLFW.GLFW_KEY_TAB: sb.append("Tab"); break;
                case GLFW.GLFW_KEY_CAPS_LOCK: sb.append("Caps Lock"); break;
                case GLFW.GLFW_KEY_SPACE: sb.append("Space"); break;
                case GLFW.GLFW_KEY_ENTER: sb.append("Enter"); break;
                case GLFW.GLFW_KEY_F1: sb.append("F1"); break;
                case GLFW.GLFW_KEY_F2: sb.append("F2"); break;
                case GLFW.GLFW_KEY_F3: sb.append("F3"); break;
                case GLFW.GLFW_KEY_F4: sb.append("F4"); break;
                case GLFW.GLFW_KEY_F5: sb.append("F5"); break;
                case GLFW.GLFW_KEY_F6: sb.append("F6"); break;
                case GLFW.GLFW_KEY_F7: sb.append("F7"); break;
                case GLFW.GLFW_KEY_F8: sb.append("F8"); break;
                case GLFW.GLFW_KEY_F9: sb.append("F9"); break;
                case GLFW.GLFW_KEY_F10: sb.append("F10"); break;
                case GLFW.GLFW_KEY_F11: sb.append("F11"); break;
                case GLFW.GLFW_KEY_F12: sb.append("F12"); break;
                case GLFW.GLFW_KEY_GRAVE_ACCENT: sb.append("~"); break;
                case GLFW.GLFW_KEY_MINUS: sb.append("-"); break;
                case GLFW.GLFW_KEY_EQUAL: sb.append("="); break;
                case GLFW.GLFW_KEY_LEFT_BRACKET: sb.append("["); break;
                case GLFW.GLFW_KEY_RIGHT_BRACKET: sb.append("]"); break;
                case GLFW.GLFW_KEY_BACKSLASH: sb.append("\\"); break;
                case GLFW.GLFW_KEY_SEMICOLON: sb.append(";"); break;
                case GLFW.GLFW_KEY_APOSTROPHE: sb.append("'"); break;
                case GLFW.GLFW_KEY_COMMA: sb.append(","); break;
                case GLFW.GLFW_KEY_PERIOD: sb.append("."); break;
                case GLFW.GLFW_KEY_SLASH: sb.append("/"); break;
                default: sb.append("Key #").append(keyCode); break;
            }
        }
        return sb.toString();
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
        if (!CONFIG_FILE.exists()) return;
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.has("keyCode")) this.keyCode = json.get("keyCode").getAsInt();
            if (json.has("ctrl")) this.ctrl = json.get("ctrl").getAsBoolean();
            if (json.has("alt")) this.alt = json.get("alt").getAsBoolean();
            if (json.has("shift")) this.shift = json.get("shift").getAsBoolean();
        } catch (Throwable ignored) {}
    }

    private void saveConfig() {
        try {
            if (!CONFIG_FILE.getParentFile().exists()) {
                CONFIG_FILE.getParentFile().mkdirs();
            }
            JsonObject json = new JsonObject();
            json.addProperty("keyCode", keyCode);
            json.addProperty("ctrl", ctrl);
            json.addProperty("alt", alt);
            json.addProperty("shift", shift);
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                writer.write(json.toString());
            }
        } catch (Throwable ignored) {}
    }
}
