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

    private static boolean wasNextPressed = false;
    private static boolean wasPrevPressed = false;
    private static boolean wasPPressed = false;
    private static boolean wasVolUpPressed = false;
    private static boolean wasVolDownPressed = false;

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

        // 5. Clean up rooms on server disconnect or client stopping
        try {
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

        // 6. Register In-Game Media Hotkeys (Instant responsiveness, Zero FPS overhead)
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client == null || client.player == null || client.gui == null) {
                wasNextPressed = false;
                wasPrevPressed = false;
                wasPPressed = false;
                wasVolUpPressed = false;
                wasVolDownPressed = false;
                return;
            }

            try {
                // Auto-suppress vanilla ambient music when custom music is actively playing
                if (AudioManager.getInstance().isPlaying() && !AudioManager.getInstance().isPaused()) {
                    if (client.getMusicManager() != null) {
                        client.getMusicManager().stopPlaying();
                    }
                }

                // If GUI is completely closed, allow quick in-game media controls
                if (client.gui.screen() == null) {
                    // ]: Next Track
                    boolean isNextDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_RIGHT_BRACKET);
                    if (isNextDown && !wasNextPressed) {
                        AudioManager.getInstance().next();
                    }
                    wasNextPressed = isNextDown;

                    // [: Previous Track
                    boolean isPrevDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_LEFT_BRACKET);
                    if (isPrevDown && !wasPrevPressed) {
                        AudioManager.getInstance().previous();
                    }
                    wasPrevPressed = isPrevDown;

                    // P: Toggle Play / Pause
                    boolean isPDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_P);
                    if (isPDown && !wasPPressed) {
                        AudioManager.getInstance().togglePause();
                    }
                    wasPPressed = isPDown;

                    // . (Period): Volume Up (+5%)
                    boolean isVolUpDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_PERIOD);
                    if (isVolUpDown && !wasVolUpPressed) {
                        AudioManager.getInstance().setVolume(AudioManager.getInstance().getVolume() + 0.05f);
                    }
                    wasVolUpPressed = isVolUpDown;

                    // , (Comma): Volume Down (-5%)
                    boolean isVolDownDown = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_COMMA);
                    if (isVolDownDown && !wasVolDownPressed) {
                        AudioManager.getInstance().setVolume(AudioManager.getInstance().getVolume() - 0.05f);
                    }
                    wasVolDownPressed = isVolDownDown;

                } else {
                    wasNextPressed = false;
                    wasPrevPressed = false;
                    wasPPressed = false;
                    wasVolUpPressed = false;
                    wasVolDownPressed = false;
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
