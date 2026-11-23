package com.slowbag.pulse.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.slowbag.pulse.MusicPlayerMod;
import com.slowbag.pulse.client.audio.AudioManager;
import com.slowbag.pulse.client.audio.PartyManager;
import com.slowbag.pulse.client.command.MusicPlayerCommand;
import com.slowbag.pulse.client.gui.HudOverlayRenderer;
import com.slowbag.pulse.client.gui.MusicPlayerScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.lwjgl.glfw.GLFW;

public class MusicPlayerClientMod implements ClientModInitializer {

    private static boolean wasHotkeyDown = false;

    @Override
    public void onInitializeClient() {
        // 1. Register Client Commands (/mp and /musicplayer)
        try {
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
                MusicPlayerCommand.register(dispatcher);
            });
            MusicPlayerMod.LOGGER.info("[Music Player] Registered /mp and /musicplayer commands.");
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to register commands", t);
        }

        // 2. Register HUD Overlay
        try {
            HudOverlayRenderer.register();
            com.slowbag.pulse.client.gui.HudConfigManager.getInstance().setHudEnabled(true);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to register HUD overlay", t);
        }

        // 3. Initialize Rooms & Group System
        try {
            com.slowbag.pulse.client.audio.PartyManager.getInstance().init();
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[Music Player] Failed to init party manager", t);
        }

        // 4. Initialize Nametag Music Badge System
        try {
            com.slowbag.pulse.client.audio.MusicBadgeManager.getInstance();
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[Music Player] Failed to init badge manager", t);
        }

        // 5. Clean up rooms on server disconnect or client stopping, and query rooms on join
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

        // 6. Register Configurable Hotkey for Opening GUI (default: 'J', modifiers supported)
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

                // If user pressed configured hotkey combo
                try {
                    com.slowbag.pulse.client.input.KeybindManager km = com.slowbag.pulse.client.input.KeybindManager.getInstance();
                    if (km != null && km.isConfigured() && client.getWindow() != null) {
                        long win = client.getWindow().handle();
                        boolean isKeyDown = GLFW.glfwGetKey(win, km.getKeyCode()) == GLFW.GLFW_PRESS;
                        boolean ctrlDown = (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS);
                        boolean altDown = (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS);
                        boolean shiftDown = (GLFW.glfwGetKey(win, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(win, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS);

                        boolean match = isKeyDown && (ctrlDown == km.isCtrl()) && (altDown == km.isAlt()) && (shiftDown == km.isShift());
                        if (match && !wasHotkeyDown) {
                            if (client.gui.screen() == null) {
                                client.gui.setScreen(new MusicPlayerScreen(null));
                            } else if (client.gui.screen() instanceof MusicPlayerScreen) {
                                // If screen is open and not listening to keybind recording, close screen
                                if (!km.isListening()) {
                                    client.gui.setScreen(null);
                                }
                            }
                        }
                        wasHotkeyDown = match;
                    } else {
                        wasHotkeyDown = false;
                    }
                } catch (Throwable ignored) {
                    wasHotkeyDown = false;
                }
            } catch (Throwable ignored) {}
        });

        // 4. Auto-stop audio when quitting/disconnecting from a world or closing Minecraft
        try {
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
                MusicPlayerMod.LOGGER.info("[Music Player] Game stopping. Stopping audio playback.");
                AudioManager.getInstance().stop();
                PartyManager.getInstance().leaveRoom();
            });

            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
                MusicPlayerMod.LOGGER.info("[Music Player] Disconnected from server. Stopping audio playback.");
                AudioManager.getInstance().stop();
                PartyManager.getInstance().leaveRoom();
            });

            // JVM Shutdown Hook safety
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                AudioManager.getInstance().stop();
            }, "MusicPlayer-ShutdownHook"));
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[Music Player] Failed to hook lifecycle events", t);
        }

        MusicPlayerMod.LOGGER.info("[Music Player] Client v2.0 initialized successfully.");
    }
}
