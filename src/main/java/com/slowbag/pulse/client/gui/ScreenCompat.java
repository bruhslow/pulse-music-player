package com.slowbag.pulse.client.gui;

import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Robust cross-version compatibility bridge for Minecraft 26.x series
 * (26.1, 26.1.1, 26.1.2, 26.2, 26.3, 26.4).
 * Handles differences in Screen navigation, setScreen, and GUI access safely.
 */
public final class ScreenCompat {

    private ScreenCompat() {}

    /**
     * Safely obtains the currently active Screen on any 26.x version.
     */
    public static Screen getCurrentScreen(Minecraft client) {
        if (client == null) return null;

        // 1. Try client.gui.screen() (Minecraft 26.2+)
        try {
            if (client.gui != null) {
                return client.gui.screen();
            }
        } catch (Throwable ignored) {}

        // 2. Try any field on Minecraft of type Screen (Minecraft 26.1.x / standard Fabric)
        try {
            for (Field f : Minecraft.class.getDeclaredFields()) {
                if (Screen.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Object s = f.get(client);
                    if (s != null) return (Screen) s;
                }
            }
        } catch (Throwable ignored) {}

        // 3. Try any method on Minecraft returning Screen with 0 parameters
        try {
            for (Method m : Minecraft.class.getMethods()) {
                if (m.getParameterCount() == 0 && Screen.class.isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    Object s = m.invoke(client);
                    if (s != null) return (Screen) s;
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Safely transitions to a new screen or closes current screen (when newScreen is null)
     * across all Minecraft 26.x versions without throwing NoSuchMethodError.
     */
    public static void setScreen(Minecraft client, Screen newScreen) {
        if (client == null) {
            try {
                client = Minecraft.getInstance();
            } catch (Throwable ignored) {}
        }
        if (client == null) return;

        final Minecraft c = client;

        // Ensure this runs on the render/game thread
        if (!c.isSameThread()) {
            c.execute(() -> setScreen(c, newScreen));
            return;
        }

        // 1. Direct call to c.gui.setScreen (26.2+)
        try {
            if (c.gui != null) {
                c.gui.setScreen(newScreen);
                handleMouseRelease(c, newScreen);
                return;
            }
        } catch (Throwable ignored) {}

        // 2. Search for any method on Minecraft taking (Screen) and returning void (26.1 / standard MC)
        try {
            for (Method m : Minecraft.class.getMethods()) {
                if (m.getParameterCount() == 1 && Screen.class.isAssignableFrom(m.getParameterTypes()[0]) && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    m.invoke(c, newScreen);
                    handleMouseRelease(c, newScreen);
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // 3. Direct field assignment fallback as last resort
        try {
            for (Field f : Minecraft.class.getDeclaredFields()) {
                if (Screen.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    Screen old = (Screen) f.get(c);
                    if (old != null) {
                        try { old.removed(); } catch (Throwable ignored) {}
                    }
                    f.set(c, newScreen);
                    if (newScreen != null) {
                        try {
                            int w = c.getWindow().getGuiScaledWidth();
                            int h = c.getWindow().getGuiScaledHeight();
                            newScreen.init(w, h);
                        } catch (Throwable ignored) {}
                    }
                    handleMouseRelease(c, newScreen);
                    return;
                }
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Failed to setScreen", t);
        }
    }

    public static void handleMouseRelease(Minecraft c, Screen newScreen) {
        if (c == null) return;
        try {
            if (newScreen != null) {
                if (c.mouseHandler != null) {
                    c.mouseHandler.releaseMouse();
                }
                if (c.getWindow() != null) {
                    long win = c.getWindow().handle();
                    if (win != 0) {
                        org.lwjgl.glfw.GLFW.glfwSetInputMode(win, org.lwjgl.glfw.GLFW.GLFW_CURSOR, org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL);
                    }
                }
            } else {
                if (c.level != null && c.mouseHandler != null) {
                    c.mouseHandler.grabMouse();
                }
            }
        } catch (Throwable ignored) {}
    }
}
