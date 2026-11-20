package com.slowbag.pulse.client.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RecentUrlManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> recentUrls = new ArrayList<>();
    private static boolean loaded = false;

    private static File getConfigFile() {
        File configDir = new File(Minecraft.getInstance().gameDirectory, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "musicplayer_recents.json");
    }

    public static synchronized List<String> getRecentUrls() {
        if (!loaded) {
            load();
        }
        return new ArrayList<>(recentUrls);
    }

    public static synchronized void addRecentUrl(String url) {
        if (url == null || url.isBlank()) return;
        String clean = url.trim();
        recentUrls.remove(clean);
        recentUrls.add(0, clean);
        if (recentUrls.size() > 15) {
            recentUrls.remove(recentUrls.size() - 1);
        }
        save();
    }

    public static synchronized void removeRecentUrl(String url) {
        if (url == null) return;
        recentUrls.remove(url);
        save();
    }

    private static void load() {
        loaded = true;
        File file = getConfigFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<String> list = GSON.fromJson(reader, new TypeToken<List<String>>(){}.getType());
            if (list != null) {
                recentUrls.clear();
                recentUrls.addAll(list);
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to load recent URLs", e);
        }
    }

    private static void save() {
        File file = getConfigFile();
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(recentUrls, writer);
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to save recent URLs", e);
        }
    }

    private static final java.util.concurrent.ExecutorService urlExecutor = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MusicPlayer-UrlParserThread");
        t.setDaemon(true);
        return t;
    });

    /**
     * Resolves a URL asynchronously in background and returns the result on the main thread.
     */
    public static void parseUrlOrPlaylistAsync(String inputUrl, java.util.function.Consumer<List<AudioTrack>> callback) {
        if (inputUrl == null || inputUrl.isBlank()) {
            callback.accept(new ArrayList<>());
            return;
        }
        urlExecutor.submit(() -> {
            List<AudioTrack> tracks = parseUrlOrPlaylist(inputUrl);
            Minecraft.getInstance().execute(() -> callback.accept(tracks));
        });
    }

    /**
     * Parses a URL, checking if it is a YouTube Playlist, YouTube Video, .m3u/.m3u8 playlist, or a direct stream.
     */
    public static List<AudioTrack> parseUrlOrPlaylist(String inputUrl) {
        List<AudioTrack> tracks = new ArrayList<>();
        if (inputUrl == null || inputUrl.isBlank()) return tracks;

        String clean = inputUrl.trim();

        // 1. Check if it's a YouTube Playlist
        String playlistId = YouTubeResolver.extractPlaylistId(clean);
        if (playlistId != null) {
            System.out.println("[Music Player] Resolving YouTube Playlist ID: " + playlistId);
            List<AudioTrack> plTracks = YouTubeResolver.resolvePlaylist(clean, 100);
            if (!plTracks.isEmpty()) {
                addRecentUrl(clean);
                return plTracks;
            }
        }

        // 2. Check if it's a YouTube Video
        if (YouTubeResolver.isYouTubeUrl(clean)) {
            String videoId = YouTubeResolver.extractVideoId(clean);
            if (videoId != null) {
                System.out.println("[Music Player] Resolving YouTube Video: " + videoId);
                AudioTrack ytTrack = YouTubeResolver.resolveVideoTrack(videoId);
                if (ytTrack != null) {
                    tracks.add(ytTrack);
                    addRecentUrl(clean);
                    return tracks;
                }
            }
        }

        // 3. Check if it is an .m3u or .m3u8 playlist
        if (clean.toLowerCase().endsWith(".m3u") || clean.toLowerCase().endsWith(".m3u8")) {
            try {
                URL url = new URI(clean).toURL();
                URLConnection conn = url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(6000);

                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    String pendingTitle = null;

                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        if (line.startsWith("#EXTINF:")) {
                            int commaIdx = line.indexOf(',');
                            if (commaIdx != -1 && commaIdx + 1 < line.length()) {
                                pendingTitle = line.substring(commaIdx + 1).trim();
                            }
                        } else if (!line.startsWith("#")) {
                            String trackTitle = pendingTitle != null ? pendingTitle : "Playlist Track";
                            tracks.add(new AudioTrack(trackTitle, "Playlist Stream", line, "Playlist"));
                            pendingTitle = null;
                        }
                    }
                }
            } catch (Exception e) {
                MusicPlayerMod.LOGGER.error("[Music Player] Failed to parse playlist: " + clean, e);
            }
        }

        if (tracks.isEmpty()) {
            tracks.add(new AudioTrack("Custom Stream", "Web Audio", clean, "Custom URL"));
        }

        addRecentUrl(clean);
        return tracks;
    }
}
