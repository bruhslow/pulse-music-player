package com.slowbag.pulse.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.slowbag.pulse.MusicPlayerMod;
import com.slowbag.pulse.client.audio.AudioManager;
import com.slowbag.pulse.client.audio.PartyManager;
import com.slowbag.pulse.client.command.MusicPlayerCommand;
import com.slowbag.pulse.client.gui.HudOverlayRenderer;
import com.slowbag.pulse.client.gui.MusicPlayerScreen;
import com.slowbag.pulse.client.gui.ScreenCompat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class MusicPlayerClientMod implements ClientModInitializer {

    public static KeyMapping pulseKeyMapping;
    private static boolean wasHotkeyDown = false;

    @Override
    public void onInitializeClient() {
        // 1. Register Client Commands (/mp and /musicplayer)
        try {
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                try {
                    MusicPlayerCommand.register(dispatcher);
                } catch (Throwable t) {
                    MusicPlayerMod.LOGGER.error("[Music Player] Error registering command dispatcher", t);
                }
            });
            MusicPlayerMod.LOGGER.info("[Music Player] Registered /mp and /musicplayer commands.");
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to register commands", t);
        }

        // 2. Register Native KeyMapping for cross-platform hotkey (Linux Wayland/X11, macOS, Windows SDL/GLFW)
        try {
            com.slowbag.pulse.client.input.KeybindManager km = com.slowbag.pulse.client.input.KeybindManager.getInstance();
            int initialKey = (km != null && km.isConfigured()) ? km.getKeyCode() : GLFW.GLFW_KEY_J;
            int mcKey = com.slowbag.pulse.client.input.KeybindManager.getMinecraftKeyCode(initialKey);
            pulseKeyMapping = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                    "key.musicplayer.open",
                    InputConstants.Type.KEYSYM,
                    mcKey,
                    KeyMapping.Category.MISC
            ));
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to register key mapping", t);
        }

        // 3. Register HUD Overlay
        try {
            HudOverlayRenderer.register();
            com.slowbag.pulse.client.gui.HudConfigManager.getInstance().setHudEnabled(true);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to register HUD overlay", t);
        }

        // 4. Initialize Rooms & Group System
        try {
            com.slowbag.pulse.client.audio.PartyManager.getInstance().init();
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[Music Player] Failed to init party manager", t);
        }

        // 5. Initialize Nametag Music Badge System
        try {
            com.slowbag.pulse.client.audio.MusicBadgeManager.getInstance();
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[Music Player] Failed to init badge manager", t);
        }

        // 6. Clean up rooms on server disconnect or client stopping, and query rooms on join
        try {
            ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
                try {
                    PartyManager.getInstance().refreshRooms();
                    com.slowbag.pulse.client.audio.MusicBadgeManager.getInstance().broadcastPresenceNow();
                } catch (Throwable ignored) {}
            });
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                PartyManager.getInstance().leaveRoom();
            });
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
                PartyManager.getInstance().leaveRoom();
                AudioManager.getInstance().stop();
            });
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    PartyManager.getInstance().leaveRoom();
                    AudioManager.getInstance().stop();
                } catch (Throwable ignored) {}
            }, "MusicPlayer-ShutdownHook"));
        } catch (Throwable ignored) {}

        // 7. Tick listener for hotkey triggers (works natively across Linux, Windows, macOS, SDL and GLFW)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.gui == null) {
                wasHotkeyDown = false;
                return;
            }

            try {
                // Auto-suppress vanilla ambient music when custom music is actively playing
                if (AudioManager.getInstance().isPlaying() && !AudioManager.getInstance().isPaused()) {
                    if (client.getMusicManager() != null) {
                        client.getMusicManager().stopPlaying();
                    }
                }

                boolean trigger = false;

                com.slowbag.pulse.client.input.KeybindManager km = com.slowbag.pulse.client.input.KeybindManager.getInstance();
                boolean ctrlDown = isCtrlDown(client);
                boolean altDown = isAltDown(client);
                boolean shiftDown = isShiftDown(client);
                boolean superDown = isSuperDown(client);

                boolean modifiersMatch = (km == null) || (ctrlDown == km.isCtrl() && altDown == km.isAlt() && shiftDown == km.isShift() && superDown == km.isSuper());

                // Native Minecraft KeyMapping click check (100% reliable on Linux Wayland/X11 and SDL)
                if (pulseKeyMapping != null) {
                    while (pulseKeyMapping.consumeClick()) {
                        if (modifiersMatch) {
                            trigger = true;
                        }
                    }
                }

                // Configurable combo check with modifiers (Ctrl, Alt, Shift, Super/Cmd)
                try {
                    if (km != null && km.isConfigured()) {
                        boolean isKeyDown = isKeyPhysicallyDown(client, km.getKeyCode());
                        boolean match = isKeyDown && modifiersMatch;
                        if (match && !wasHotkeyDown) {
                            trigger = true;
                        }
                        wasHotkeyDown = match;
                    } else {
                        wasHotkeyDown = false;
                    }
                } catch (Throwable ignored) {
                    wasHotkeyDown = false;
                }

                if (trigger) {
                    wasHotkeyDown = true;
                    net.minecraft.client.gui.screens.Screen current = ScreenCompat.getCurrentScreen(client);
                    if (current == null) {
                        ScreenCompat.setScreen(client, new MusicPlayerScreen(null));
                    }
                }
            } catch (Throwable ignored) {}
        });

        MusicPlayerMod.LOGGER.info("[Music Player] Client v2.0 initialized successfully.");
    }

    public static boolean isKeyPhysicallyDown(Minecraft client, int keyCode) {
        if (keyCode <= 0) return false;
        int sdlCode = com.slowbag.pulse.client.input.KeybindManager.getSdlScancodeFromGlfw(keyCode);
        try {
            // Minecraft 26.3 (SDL): InputConstants.isKeyDown(int)
            java.lang.reflect.Method m1 = InputConstants.class.getMethod("isKeyDown", int.class);
            if (sdlCode != 0 && (boolean) m1.invoke(null, sdlCode)) return true;
            if (sdlCode != keyCode && (boolean) m1.invoke(null, keyCode)) return true;
        } catch (Throwable t1) {
            try {
                // Minecraft 26.1 / 26.2 (GLFW): InputConstants.isKeyDown(long, int)
                if (client != null && client.getWindow() != null) {
                    long handle = client.getWindow().handle();
                    if (handle != 0) {
                        java.lang.reflect.Method m2 = InputConstants.class.getMethod("isKeyDown", long.class, int.class);
                        return (boolean) m2.invoke(null, handle, keyCode);
                    }
                }
            } catch (Throwable t2) {
                try {
                    if (client != null && client.getWindow() != null) {
                        long handle = client.getWindow().handle();
                        if (handle != 0) {
                            return GLFW.glfwGetKey(handle, keyCode) == GLFW.GLFW_PRESS;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    public static boolean isCtrlDown(Minecraft client) {
        return isKeyPhysicallyDown(client, GLFW.GLFW_KEY_LEFT_CONTROL) || isKeyPhysicallyDown(client, GLFW.GLFW_KEY_RIGHT_CONTROL);
    }

    public static boolean isAltDown(Minecraft client) {
        return isKeyPhysicallyDown(client, GLFW.GLFW_KEY_LEFT_ALT) || isKeyPhysicallyDown(client, GLFW.GLFW_KEY_RIGHT_ALT);
    }

    public static boolean isShiftDown(Minecraft client) {
        return isKeyPhysicallyDown(client, GLFW.GLFW_KEY_LEFT_SHIFT) || isKeyPhysicallyDown(client, GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    public static boolean isSuperDown(Minecraft client) {
        return isKeyPhysicallyDown(client, GLFW.GLFW_KEY_LEFT_SUPER) || isKeyPhysicallyDown(client, GLFW.GLFW_KEY_RIGHT_SUPER);
    }
}
