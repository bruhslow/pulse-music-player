package com.slowbag.pulse.client.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages player nametag badges and presence.
 * Shows an animated beating music note [♫] when listening to music,
 * or an indicator badge [●] when a player has Pulse installed.
 * Visibility is client-controlled: if the viewer enables badges, they can see
 * who has the mod or is listening, regardless of other players' local toggles.
 */
public class MusicBadgeManager {

    private static final MusicBadgeManager INSTANCE = new MusicBadgeManager();
    public static MusicBadgeManager getInstance() {
        return INSTANCE;
    }

    private static volatile boolean badgeEnabled = true;
    private static final Map<String, Long> activeModUsers = new ConcurrentHashMap<>();
    private static final Map<String, Long> activeListeners = new ConcurrentHashMap<>();

    private final ExecutorService presenceExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Pulse-PresenceThread");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean presenceListening = false;
    private volatile long lastPresenceEventTime = 0;
    private volatile long lastHeartbeatSent = 0;

    private MusicBadgeManager() {
        loadConfig();
        startPresenceBroadcaster();
        startPresenceListener();
    }

    public static boolean isBadgeEnabled() {
        return badgeEnabled;
    }

    public static boolean toggleBadgeEnabled() {
        badgeEnabled = !badgeEnabled;
        saveConfig();
        return badgeEnabled;
    }

    public static void setBadgeEnabled(boolean enabled) {
        badgeEnabled = enabled;
        saveConfig();
    }

    public static boolean isModUser(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String lower = playerName.toLowerCase();

        // 1. Local player
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            if (playerName.equalsIgnoreCase(client.player.getName().getString())) {
                return true;
            }
        }

        // 2. Active presence within last 25 seconds
        Long lastSeen = activeModUsers.get(lower);
        if (lastSeen != null && (System.currentTimeMillis() - lastSeen < 25000)) {
            return true;
        }

        // 3. Server party mod users
        PartyManager pm = PartyManager.getInstance();
        if (pm.getServerModUsers().contains(playerName) || pm.getMembers().contains(playerName)) {
            return true;
        }

        return false;
    }

    public static boolean isPlayerListening(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        String lower = playerName.toLowerCase();

        // 1. Local player audio playback
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.player != null) {
            if (playerName.equalsIgnoreCase(client.player.getName().getString())) {
                return AudioManager.getInstance().isPlaying() && !AudioManager.getInstance().isPaused();
            }
        }

        // 2. Remote player active listening heartbeat within last 18 seconds
        Long lastAudio = activeListeners.get(lower);
        if (lastAudio != null && (System.currentTimeMillis() - lastAudio < 18000)) {
            return true;
        }

        // 3. Shared party room playback
        PartyManager pm = PartyManager.getInstance();
        if (pm.isInRoom() && pm.getMembers().contains(playerName) && AudioManager.getInstance().isPlaying() && !AudioManager.getInstance().isPaused()) {
            return true;
        }

        return false;
    }

    public static String getBeatingMusicPrefix() {
        long now = System.currentTimeMillis();
        // 600ms full cycle, 150ms per beat step
        int step = (int) ((now / 150) % 4);
        String color;
        String note;
        switch (step) {
            case 0:
                color = "§6"; // Warm Amber
                note = "♫";
                break;
            case 1:
                color = "§e"; // Bright Gold
                note = "♪";
                break;
            case 2:
                color = "§f"; // Pure White Flash
                note = "♫";
                break;
            case 3:
                color = "§6"; // Pulse Gold
                note = "♪";
                break;
            default:
                color = "§6";
                note = "♫";
                break;
        }
        return "§8[" + color + note + "§8] ";
    }

    public static Component decorateNametag(Player player, Component original) {
        if (!badgeEnabled || player == null || original == null) {
            return original;
        }

        String playerName = player.getName().getString();

        if (isPlayerListening(playerName)) {
            return Component.literal(getBeatingMusicPrefix())
                    .append(original)
                    .append(Component.literal(" §e🎶"));
        } else if (isModUser(playerName)) {
            return Component.literal("§8[§e⚡§8] ").append(original);
        }

        return original;
    }

    // ------------------------------------------------------------------------
    // Global Presence Broadcasting & Listening
    // ------------------------------------------------------------------------

    private void startPresenceBroadcaster() {
        presenceExecutor.submit(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                    Minecraft client = Minecraft.getInstance();
                    if (client == null || client.player == null) continue;

                    String myName = client.player.getName().getString();
                    if ("Player".equalsIgnoreCase(myName) || myName.isBlank()) continue;

                    boolean isPlaying = AudioManager.getInstance().isPlaying() && !AudioManager.getInstance().isPaused();
                    long now = System.currentTimeMillis();

                    if (now - lastHeartbeatSent > 5000) {
                        lastHeartbeatSent = now;
                        JsonObject payload = new JsonObject();
                        payload.addProperty("player", myName);
                        payload.addProperty("hasMod", true);
                        payload.addProperty("status", isPlaying ? "playing" : "idle");
                        payload.addProperty("time", now);
                        sendPresence(payload.toString());
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {}
            }
        });
    }

    private void sendPresence(String json) {
        presenceExecutor.submit(() -> {
            try {
                URL url = new URI("https://ntfy.sh/pulse_presence_global").toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.5");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(3000);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(json.getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
            } catch (Throwable ignored) {}
        });
    }

    private void startPresenceListener() {
        if (presenceListening) return;
        this.presenceListening = true;
        this.lastPresenceEventTime = System.currentTimeMillis() / 1000 - 10;

        presenceExecutor.submit(() -> {
            while (presenceListening) {
                try {
                    URL url = new URI("https://ntfy.sh/pulse_presence_global/json?since=" + lastPresenceEventTime + "&poll=1").toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "PulseMusicPlayer/2.6");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    if (conn.getResponseCode() == 200) {
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.isBlank()) continue;
                                try {
                                    JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                                    if (event.has("time")) {
                                        lastPresenceEventTime = Math.max(lastPresenceEventTime, event.get("time").getAsLong());
                                    }
                                    if (event.has("message")) {
                                        handlePresencePayload(event.get("message").getAsString());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ignored2) {
                        break;
                    }
                }
            }
        });
    }

    private void handlePresencePayload(String rawJson) {
        try {
            JsonObject obj = JsonParser.parseString(rawJson).getAsJsonObject();
            if (obj.has("player")) {
                String player = obj.get("player").getAsString().toLowerCase();
                long now = System.currentTimeMillis();
                activeModUsers.put(player, now);

                String status = obj.has("status") ? obj.get("status").getAsString() : "idle";
                if ("playing".equalsIgnoreCase(status)) {
                    activeListeners.put(player, now);
                } else {
                    activeListeners.remove(player);
                }
            }
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------------
    // Config Persistence
    // ------------------------------------------------------------------------

    private static File getConfigFile() {
        try {
            List<File> dirs = LocalMusicScanner.getMusicDirectories();
            if (!dirs.isEmpty()) {
                return new File(dirs.get(0), "badge_config.json");
            }
        } catch (Throwable ignored) {}
        return new File("badge_config.json");
    }

    private static void loadConfig() {
        try {
            File f = getConfigFile();
            if (f.exists()) {
                try (FileReader reader = new FileReader(f, StandardCharsets.UTF_8)) {
                    JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                    if (obj.has("badgeEnabled")) {
                        badgeEnabled = obj.get("badgeEnabled").getAsBoolean();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void saveConfig() {
        try {
            File f = getConfigFile();
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            JsonObject obj = new JsonObject();
            obj.addProperty("badgeEnabled", badgeEnabled);
            try (FileWriter writer = new FileWriter(f, StandardCharsets.UTF_8)) {
                writer.write(obj.toString());
            }
        } catch (Throwable ignored) {}
    }
}
