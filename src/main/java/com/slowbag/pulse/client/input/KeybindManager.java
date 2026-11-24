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
    private boolean superKey = false;
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

    public boolean isSuper() {
        return superKey;
    }

    public boolean isListening() {
        return isListening;
    }

    public void setListening(boolean listening) {
        this.isListening = listening;
    }

    public static int getMinecraftKeyCode(int glfwKey) {
        try {
            if (com.slowbag.pulse.client.gui.MusicPlayerScreen.IS_SDL) {
                return getSdlScancodeFromGlfw(glfwKey);
            }
        } catch (Throwable ignored) {}
        return glfwKey;
    }

    public void setKeybind(int key, boolean ctrl, boolean alt, boolean shift) {
        setKeybind(key, ctrl, alt, shift, false);
    }

    public void setKeybind(int key, boolean ctrl, boolean alt, boolean shift, boolean superKey) {
        this.keyCode = key;
        this.ctrl = ctrl;
        this.alt = alt;
        this.shift = shift;
        this.superKey = superKey;
        this.isListening = false;
        try {
            if (com.slowbag.pulse.client.MusicPlayerClientMod.pulseKeyMapping != null) {
                int mcKey = getMinecraftKeyCode(key);
                com.slowbag.pulse.client.MusicPlayerClientMod.pulseKeyMapping.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(mcKey));
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
        this.superKey = false;
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
        if (superKey) sb.append("Cmd + ");
        if (alt) sb.append("Alt + ");
        if (shift) sb.append("Shift + ");
        sb.append(getKeyName(keyCode));
        return sb.toString();
    }

    public static int normalizeToGlfwKeyCode(int rawKey, int scancode) {
        int code = rawKey;
        if (code <= 0 && scancode > 0) {
            code = scancode;
        }

        // Already GLFW standard key?
        if (code >= GLFW.GLFW_KEY_A && code <= GLFW.GLFW_KEY_Z) {
            return code;
        }
        if (code >= GLFW.GLFW_KEY_0 && code <= GLFW.GLFW_KEY_9) {
            return code;
        }
        if (code >= GLFW.GLFW_KEY_ESCAPE && code <= GLFW.GLFW_KEY_MENU) {
            return code;
        }
        if (code == GLFW.GLFW_KEY_SPACE) {
            return code;
        }

        // SDL Scancodes (USB HID codes used by SDL in Minecraft 26.3):
        if (code >= 4 && code <= 29) {
            // A-Z: 4 -> 65 (A), 13 -> 74 (J), 14 -> 75 (K), 29 -> 90 (Z)
            return GLFW.GLFW_KEY_A + (code - 4);
        }
        if (code >= 30 && code <= 38) {
            // 1-9: 30 -> 49 ('1')
            return GLFW.GLFW_KEY_1 + (code - 30);
        }
        if (code == 39) return GLFW.GLFW_KEY_0; // '0'
        if (code == 40) return GLFW.GLFW_KEY_ENTER;
        if (code == 41) return GLFW.GLFW_KEY_ESCAPE;
        if (code == 42) return GLFW.GLFW_KEY_BACKSPACE;
        if (code == 43) return GLFW.GLFW_KEY_TAB;
        if (code == 44) return GLFW.GLFW_KEY_SPACE;
        if (code == 45) return GLFW.GLFW_KEY_MINUS;
        if (code == 46) return GLFW.GLFW_KEY_EQUAL;
        if (code == 47) return GLFW.GLFW_KEY_LEFT_BRACKET;
        if (code == 48) return GLFW.GLFW_KEY_RIGHT_BRACKET;
        if (code == 49) return GLFW.GLFW_KEY_BACKSLASH;
        if (code == 51) return GLFW.GLFW_KEY_SEMICOLON;
        if (code == 52) return GLFW.GLFW_KEY_APOSTROPHE;
        if (code == 53) return GLFW.GLFW_KEY_GRAVE_ACCENT;
        if (code == 54) return GLFW.GLFW_KEY_COMMA;
        if (code == 55) return GLFW.GLFW_KEY_PERIOD;
        if (code == 56) return GLFW.GLFW_KEY_SLASH;
        if (code == 57) return GLFW.GLFW_KEY_CAPS_LOCK;
        if (code >= 58 && code <= 69) {
            // F1-F12
            return GLFW.GLFW_KEY_F1 + (code - 58);
        }
        if (code == 70) return GLFW.GLFW_KEY_PRINT_SCREEN;
        if (code == 71) return GLFW.GLFW_KEY_SCROLL_LOCK;
        if (code == 72) return GLFW.GLFW_KEY_PAUSE;
        if (code == 73) return GLFW.GLFW_KEY_INSERT;
        if (code == 74) return GLFW.GLFW_KEY_HOME;
        if (code == 75) return GLFW.GLFW_KEY_PAGE_UP;
        if (code == 76) return GLFW.GLFW_KEY_DELETE;
        if (code == 77) return GLFW.GLFW_KEY_END;
        if (code == 78) return GLFW.GLFW_KEY_PAGE_DOWN;
        if (code == 79) return GLFW.GLFW_KEY_RIGHT;
        if (code == 80) return GLFW.GLFW_KEY_LEFT;
        if (code == 81) return GLFW.GLFW_KEY_DOWN;
        if (code == 82) return GLFW.GLFW_KEY_UP;

        if (code == 83) return GLFW.GLFW_KEY_NUM_LOCK;
        if (code == 84) return GLFW.GLFW_KEY_KP_DIVIDE;
        if (code == 85) return GLFW.GLFW_KEY_KP_MULTIPLY;
        if (code == 86) return GLFW.GLFW_KEY_KP_SUBTRACT;
        if (code == 87) return GLFW.GLFW_KEY_KP_ADD;
        if (code == 88) return GLFW.GLFW_KEY_KP_ENTER;
        if (code >= 89 && code <= 97) {
            return GLFW.GLFW_KEY_KP_1 + (code - 89);
        }
        if (code == 98) return GLFW.GLFW_KEY_KP_0;
        if (code == 99) return GLFW.GLFW_KEY_KP_DECIMAL;

        // SDL modifier scancodes (224-231)
        if (code == 224) return GLFW.GLFW_KEY_LEFT_CONTROL;
        if (code == 225) return GLFW.GLFW_KEY_LEFT_SHIFT;
        if (code == 226) return GLFW.GLFW_KEY_LEFT_ALT;
        if (code == 227) return GLFW.GLFW_KEY_LEFT_SUPER;
        if (code == 228) return GLFW.GLFW_KEY_RIGHT_CONTROL;
        if (code == 229) return GLFW.GLFW_KEY_RIGHT_SHIFT;
        if (code == 230) return GLFW.GLFW_KEY_RIGHT_ALT;
        if (code == 231) return GLFW.GLFW_KEY_RIGHT_SUPER;

        return code;
    }

    public static boolean isModifierKey(int key) {
        int normalized = normalizeToGlfwKeyCode(key, 0);
        if (normalized == GLFW.GLFW_KEY_LEFT_CONTROL || normalized == GLFW.GLFW_KEY_RIGHT_CONTROL ||
            normalized == GLFW.GLFW_KEY_LEFT_ALT || normalized == GLFW.GLFW_KEY_RIGHT_ALT ||
            normalized == GLFW.GLFW_KEY_LEFT_SHIFT || normalized == GLFW.GLFW_KEY_RIGHT_SHIFT ||
            normalized == GLFW.GLFW_KEY_LEFT_SUPER || normalized == GLFW.GLFW_KEY_RIGHT_SUPER) {
            return true;
        }
        return (key >= 224 && key <= 231) || (key >= 340 && key <= 347);
    }

    public static int getSdlScancodeFromGlfw(int glfwKey) {
        if (glfwKey >= GLFW.GLFW_KEY_A && glfwKey <= GLFW.GLFW_KEY_Z) {
            return 4 + (glfwKey - GLFW.GLFW_KEY_A);
        }
        if (glfwKey >= GLFW.GLFW_KEY_1 && glfwKey <= GLFW.GLFW_KEY_9) {
            return 30 + (glfwKey - GLFW.GLFW_KEY_1);
        }
        if (glfwKey == GLFW.GLFW_KEY_0) return 39;
        if (glfwKey == GLFW.GLFW_KEY_ENTER) return 40;
        if (glfwKey == GLFW.GLFW_KEY_ESCAPE) return 41;
        if (glfwKey == GLFW.GLFW_KEY_BACKSPACE) return 42;
        if (glfwKey == GLFW.GLFW_KEY_TAB) return 43;
        if (glfwKey == GLFW.GLFW_KEY_SPACE) return 44;
        if (glfwKey == GLFW.GLFW_KEY_LEFT_CONTROL) return 224;
        if (glfwKey == GLFW.GLFW_KEY_LEFT_SHIFT) return 225;
        if (glfwKey == GLFW.GLFW_KEY_LEFT_ALT) return 226;
        if (glfwKey == GLFW.GLFW_KEY_LEFT_SUPER) return 227;
        if (glfwKey == GLFW.GLFW_KEY_RIGHT_CONTROL) return 228;
        if (glfwKey == GLFW.GLFW_KEY_RIGHT_SHIFT) return 229;
        if (glfwKey == GLFW.GLFW_KEY_RIGHT_ALT) return 230;
        if (glfwKey == GLFW.GLFW_KEY_RIGHT_SUPER) return 231;
        return glfwKey;
    }

    public static String getKeyName(int keyCode) {
        if (keyCode <= 0 || keyCode == GLFW.GLFW_KEY_UNKNOWN) return "None";

        // Normalize SDL scancode to canonical GLFW key
        keyCode = normalizeToGlfwKeyCode(keyCode, 0);

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
        return matches(pressedKey, hasCtrl, hasAlt, hasShift, false);
    }

    public boolean matches(int pressedKey, boolean hasCtrl, boolean hasAlt, boolean hasShift, boolean hasSuper) {
        if (!isConfigured()) return false;
        if (pressedKey != this.keyCode) return false;
        if (this.ctrl != hasCtrl) return false;
        if (this.alt != hasAlt) return false;
        if (this.shift != hasShift) return false;
        if (this.superKey != hasSuper) return false;
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
            if (json.has("super")) this.superKey = json.get("super").getAsBoolean();
            if (json.has("cmd")) this.superKey = json.get("cmd").getAsBoolean();

            if (isModifierKey(this.keyCode)) {
                this.keyCode = GLFW.GLFW_KEY_J;
                this.ctrl = false;
                this.superKey = false;
                saveConfig();
            } else {
                this.keyCode = normalizeToGlfwKeyCode(this.keyCode, 0);
            }
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
            json.addProperty("super", superKey);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(json.toString());
            }
        } catch (Throwable ignored) {}
    }
}
