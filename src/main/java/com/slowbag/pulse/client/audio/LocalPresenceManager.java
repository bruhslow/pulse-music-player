package com.slowbag.pulse.client.audio;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slowbag.pulse.client.audio.PartyManager.RoomEntry;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Handles ultra-low-latency local IPC presence and rooms.
 * Uses a lightweight file-backed directory in the system temp directory
 * so multiple Minecraft clients on the same machine (e.g. main and alt accounts)
 * or LAN instances discover each other with 0ms delay, independent of external servers.
 */
public class LocalPresenceManager {

    private static final File PRESENCE_DIR = new File(System.getProperty("java.io.tmpdir"), "pulse_presence");
    private static final File ROOMS_DIR = new File(PRESENCE_DIR, "rooms");

    static {
        try {
            PRESENCE_DIR.mkdirs();
            ROOMS_DIR.mkdirs();
        } catch (Throwable ignored) {}
    }

    public static void updatePresence(String playerName, boolean isPlaying, String trackTitle) {
        if (playerName == null || playerName.isBlank() || "Player".equalsIgnoreCase(playerName)) return;
        try {
            if (!PRESENCE_DIR.exists()) PRESENCE_DIR.mkdirs();
            File file = new File(PRESENCE_DIR, playerName.toLowerCase().trim() + ".json");
            JsonObject obj = new JsonObject();
            obj.addProperty("player", playerName);
            obj.addProperty("time", System.currentTimeMillis());
            obj.addProperty("playing", isPlaying);
            if (trackTitle != null) {
                obj.addProperty("track", trackTitle);
            }
            Files.writeString(file.toPath(), obj.toString(), StandardCharsets.UTF_8);
            file.deleteOnExit();
        } catch (Throwable ignored) {}
    }

    public static boolean isLocalModUser(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        try {
            File file = new File(PRESENCE_DIR, playerName.toLowerCase().trim() + ".json");
            if (file.exists()) {
                long diff = System.currentTimeMillis() - file.lastModified();
                if (diff < 45000) { // Active within 45 seconds
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static boolean isLocalPlayerListening(String playerName) {
        if (playerName == null || playerName.isBlank()) return false;
        try {
            File file = new File(PRESENCE_DIR, playerName.toLowerCase().trim() + ".json");
            if (file.exists() && (System.currentTimeMillis() - file.lastModified() < 45000)) {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                return obj.has("playing") && obj.get("playing").getAsBoolean();
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String getLocalPlayerTrack(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        try {
            File file = new File(PRESENCE_DIR, playerName.toLowerCase().trim() + ".json");
            if (file.exists() && (System.currentTimeMillis() - file.lastModified() < 45000)) {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                if (obj.has("track")) {
                    return obj.get("track").getAsString();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static Set<String> getActiveLocalUsers() {
        Set<String> set = new HashSet<>();
        try {
            File[] files = PRESENCE_DIR.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                long now = System.currentTimeMillis();
                for (File f : files) {
                    if (now - f.lastModified() < 45000) {
                        try {
                            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                            if (obj.has("player")) {
                                set.add(obj.get("player").getAsString());
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return set;
    }

    public static void registerRoom(String roomName, String hostName, boolean isLocked, String password, int members) {
        if (roomName == null || hostName == null) return;
        try {
            if (!ROOMS_DIR.exists()) ROOMS_DIR.mkdirs();
            File file = new File(ROOMS_DIR, roomName.toLowerCase().replaceAll("[^a-z0-9_-]", "_") + ".json");
            JsonObject obj = new JsonObject();
            obj.addProperty("name", roomName);
            obj.addProperty("host", hostName);
            obj.addProperty("isLocked", isLocked);
            obj.addProperty("password", password != null ? password : "");
            obj.addProperty("members", members);
            obj.addProperty("time", System.currentTimeMillis());
            Files.writeString(file.toPath(), obj.toString(), StandardCharsets.UTF_8);
            file.deleteOnExit();
        } catch (Throwable ignored) {}
    }

    public static void removeRoom(String roomName) {
        if (roomName == null) return;
        try {
            File file = new File(ROOMS_DIR, roomName.toLowerCase().replaceAll("[^a-z0-9_-]", "_") + ".json");
            if (file.exists()) file.delete();
        } catch (Throwable ignored) {}
    }

    public static List<RoomEntry> getActiveRooms() {
        List<RoomEntry> list = new ArrayList<>();
        try {
            File[] files = ROOMS_DIR.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                long now = System.currentTimeMillis();
                for (File f : files) {
                    if (now - f.lastModified() < 30000) {
                        try {
                            String content = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                            JsonObject obj = JsonParser.parseString(content).getAsJsonObject();
                            String name = obj.get("name").getAsString();
                            String host = obj.get("host").getAsString();
                            boolean locked = obj.has("isLocked") && obj.get("isLocked").getAsBoolean();
                            String pass = obj.has("password") ? obj.get("password").getAsString() : "";
                            int members = obj.has("members") ? obj.get("members").getAsInt() : 1;
                            list.add(new RoomEntry(name, host, locked, pass, members));
                        } catch (Throwable ignored) {}
                    } else {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }
}
