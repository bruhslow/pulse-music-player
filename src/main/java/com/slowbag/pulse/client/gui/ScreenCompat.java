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

        // 2. Try client.screen field (Minecraft 26.1.x / standard Fabric)
        try {
            Field f = Minecraft.class.getDeclaredField("screen");
            f.setAccessible(true);
            return (Screen) f.get(client);
        } catch (Throwable ignored) {}

        // 3. Try client.getScreen() method
        try {
            Method m = Minecraft.class.getMethod("getScreen");
            return (Screen) m.invoke(client);
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

        // 1. Try standard Minecraft.setScreen(Screen)
        try {
            Method m = Minecraft.class.getMethod("setScreen", Screen.class);
            m.invoke(c, newScreen);
            return;
        } catch (Throwable ignored) {}

        // 2. Try Minecraft.setScreenAndShow(Screen)
        try {
            Method m = Minecraft.class.getMethod("setScreenAndShow", Screen.class);
            m.invoke(c, newScreen);
            return;
        } catch (Throwable ignored) {}

        // 3. Try client.gui.setScreen(Screen)
        try {
            if (c.gui != null) {
                Method m = c.gui.getClass().getMethod("setScreen", Screen.class);
                m.invoke(c.gui, newScreen);
                return;
            }
        } catch (Throwable ignored) {}

        // 4. Direct field assignment fallback as last resort
        try {
            Field f = Minecraft.class.getDeclaredField("screen");
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
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Pulse] Failed to setScreen", t);
        }
    }
}
