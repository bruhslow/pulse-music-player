package com.slowbag.pulse.client.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slowbag.pulse.MusicPlayerMod;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MusicSearchService {

    private static final ExecutorService searchExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MusicPlayer-SearchThread");
        t.setDaemon(true);
        return t;
    });

    private static final List<AudioTrack> DEFAULT_HITS = new ArrayList<>();

    static {
        DEFAULT_HITS.add(new AudioTrack("Worry", "The Fretless", "https://ice1.somafm.com/thistle-128-mp3", true, -1, "Acoustic Folk", "https://somafm.com/img3/thistle120.png"));
        DEFAULT_HITS.add(new AudioTrack("Lofi Hip Hop Chill Beats", "Lofi Girl", "https://ec3.yesstreaming.net:3755/stream", true, -1, "Lofi Chill", "https://somafm.com/img3/groovesalad120.png"));
        DEFAULT_HITS.add(new AudioTrack("Vaporwave & Synthwave", "Nightwave Plaza 24/7", "https://radio.plaza.one/mp3", true, -1, "Synthwave", "https://plaza.one/apple-touch-icon.png"));
        DEFAULT_HITS.add(new AudioTrack("Space Station Ambient", "SomaFM Deep Space", "https://ice1.somafm.com/spacestation-128-mp3", true, -1, "Ambient", "https://somafm.com/img3/spacestation120.png"));
        DEFAULT_HITS.add(new AudioTrack("Smooth Cafe Jazz", "Radio Swiss Jazz", "https://stream.srg-ssr.ch/m/rsj/mp3_128", true, -1, "Jazz", "https://somafm.com/img3/secretagent120.png"));
        DEFAULT_HITS.add(new AudioTrack("Peaceful Classical Piano", "Radio Swiss Classic", "https://stream.srg-ssr.ch/m/rsc_de/mp3_128", true, -1, "Classical", "https://somafm.com/img3/thistle120.png"));
    }

    public static void searchSongsAsync(String query, Consumer<List<AudioTrack>> callback) {
        searchExecutor.submit(() -> {
            List<AudioTrack> results = new ArrayList<>();

            // If empty query, combine local tracks with curated hits
            if (query == null || query.trim().isEmpty()) {
                try {
                    results.addAll(LocalMusicScanner.scanLocalTracks());
                } catch (Throwable ignored) {}
                for (AudioTrack h : DEFAULT_HITS) {
                    if (!results.contains(h)) results.add(h);
                }
                callback.accept(results);
                return;
            }

            String cleanQuery = query.trim();
            String lowerQuery = cleanQuery.toLowerCase(Locale.ROOT);

            // 1. Search Local Files first (0ms instant match)
            try {
                for (AudioTrack local : LocalMusicScanner.scanLocalTracks()) {
                    if (local.getTitle().toLowerCase(Locale.ROOT).contains(lowerQuery)
                            || local.getArtist().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                        results.add(local);
                    }
                }
            } catch (Throwable ignored) {}

            // 2. PRIMARY CATALOG: YouTube / YouTube Music (Keyword, Title, Artist, Any Language)
            try {
                List<AudioTrack> ytResults = YouTubeResolver.search(cleanQuery, 35);
                for (AudioTrack yt : ytResults) {
                    if (!results.contains(yt)) {
                        results.add(yt);
                    }
                }
            } catch (Throwable t) {
                MusicPlayerMod.LOGGER.warn("[Music Player] YouTube search exception: " + t.getMessage());
            }

            // 3. Query Audius Global Music Catalog (Fallback / supplement if needed)
            if (results.size() < 15) {
                try {
                String encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8);
                String audiusUrl = "https://discoveryprovider.audius.co/v1/tracks/search?query=" + encoded + "&limit=50&app_name=PulseMusicPlayer";

                URL url = new URI(audiusUrl).toURL();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(4500);

                if (conn.getResponseCode() == 200) {
                    try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                        if (root.has("data") && root.get("data").isJsonArray()) {
                            JsonArray tracksArray = root.getAsJsonArray("data");
                            for (JsonElement el : tracksArray) {
                                if (!el.isJsonObject()) continue;
                                JsonObject obj = el.getAsJsonObject();

                                String trackId = (obj.has("id") && !obj.get("id").isJsonNull()) ? obj.get("id").getAsString() : null;
                                String title = (obj.has("title") && !obj.get("title").isJsonNull()) ? obj.get("title").getAsString().trim() : "Unknown Title";
                                long duration = (obj.has("duration") && !obj.get("duration").isJsonNull()) ? obj.get("duration").getAsLong() : 180;
                                String genre = (obj.has("genre") && !obj.get("genre").isJsonNull()) ? obj.get("genre").getAsString() : "Music";

                                String artist = "Artist";
                                if (obj.has("user") && obj.get("user").isJsonObject()) {
                                    JsonObject user = obj.getAsJsonObject("user");
                                    if (user.has("name") && !user.get("name").isJsonNull()) {
                                        artist = user.get("name").getAsString().trim();
                                    }
                                }

                                String artwork = null;
                                if (obj.has("artwork") && obj.get("artwork").isJsonObject()) {
                                    JsonObject art = obj.getAsJsonObject("artwork");
                                    if (art.has("150x150") && !art.get("150x150").isJsonNull()) {
                                        artwork = art.get("150x150").getAsString();
                                    } else if (art.has("480x480") && !art.get("480x480").isJsonNull()) {
                                        artwork = art.get("480x480").getAsString();
                                    }
                                }

                                // Prioritize load-balanced canonical discovery stream URL (prevents HTTP 503 node errors)
                                String streamUrl = null;
                                if (trackId != null && !trackId.isBlank()) {
                                    streamUrl = "https://discoveryprovider.audius.co/v1/tracks/" + trackId + "/stream?app_name=PulseMusicPlayer";
                                } else if (obj.has("stream") && obj.get("stream").isJsonObject()) {
                                    JsonObject st = obj.getAsJsonObject("stream");
                                    if (st.has("url") && !st.get("url").isJsonNull()) {
                                        streamUrl = st.get("url").getAsString();
                                    }
                                }

                                if (streamUrl != null) {
                                    AudioTrack t = new AudioTrack(title, artist, streamUrl, false, duration, genre, artwork);
                                    if (!results.contains(t)) {
                                        results.add(t);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                MusicPlayerMod.LOGGER.warn("[Music Player] Audius search exception: " + e.getMessage());
            }
            }

            // 3. Fallback to Radio Browser API if Audius has few results
            if (results.size() < 10) {
                try {
                    String encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8);
                    String radioUrl = "https://de1.api.radio-browser.info/json/stations/byname/" + encoded + "?limit=20&codec=MP3";

                    URL url = new URI(radioUrl).toURL();
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 PulseMusicPlayer/1.0");
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);

                    if (conn.getResponseCode() == 200) {
                        try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                            JsonElement parsed = JsonParser.parseReader(reader);
                            if (parsed.isJsonArray()) {
                                JsonArray array = parsed.getAsJsonArray();
                                for (JsonElement el : array) {
                                    if (!el.isJsonObject()) continue;
                                    JsonObject st = el.getAsJsonObject();

                                    String name = st.has("name") && !st.get("name").isJsonNull() ? st.get("name").getAsString().trim() : "Radio Station";
                                    String stream = st.has("url_resolved") && !st.get("url_resolved").isJsonNull() ? st.get("url_resolved").getAsString().trim() : null;
                                    if (stream == null || stream.isBlank()) {
                                        stream = st.has("url") && !st.get("url").isJsonNull() ? st.get("url").getAsString().trim() : null;
                                    }
                                    if (stream == null || stream.isBlank()) continue;

                                    // Filter out broken / token-expired / non-MP3 streams
                                    String lowerStream = stream.toLowerCase(Locale.ROOT);
                                    if (lowerStream.contains("zeno.fm") || lowerStream.contains("surfernetwork.com") || lowerStream.endsWith(".m3u8") || lowerStream.endsWith(".aac")) {
                                        continue;
                                    }

                                    String tags = st.has("tags") && !st.get("tags").isJsonNull() ? st.get("tags").getAsString() : "Online Radio";
                                    String favicon = st.has("favicon") && !st.get("favicon").isJsonNull() ? st.get("favicon").getAsString() : null;
                                    if (favicon != null && favicon.isBlank()) favicon = null;

                                    AudioTrack rt = new AudioTrack(name, "Radio Stream", stream, true, -1, tags.isEmpty() ? "Radio" : tags.split(",")[0], favicon);
                                    if (!results.contains(rt)) {
                                        results.add(rt);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 4. Fallback to default curated hits if nothing found
            if (results.isEmpty()) {
                for (AudioTrack hit : DEFAULT_HITS) {
                    if (hit.getTitle().toLowerCase(Locale.ROOT).contains(lowerQuery)
                            || hit.getArtist().toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                        results.add(hit);
                    }
                }
            }

            // If still empty, add default hits so results are never empty
            if (results.isEmpty()) {
                results.addAll(DEFAULT_HITS);
            }

            callback.accept(results);
        });
    }
}
