package com.slowbag.pulse.client.audio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.slowbag.pulse.MusicPlayerMod;

import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YouTubeResolver {

    private static final String SEARCH_ENDPOINT = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String BROWSE_ENDPOINT = "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w";

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("(?:v=|\\/|youtu\\.be\\/|embed\\/|shorts\\/)([a-zA-Z0-9_-]{11})");
    private static final Pattern PLAYLIST_ID_PATTERN = Pattern.compile("(?:list=|p=)([a-zA-Z0-9_-]+)");

    public static String extractVideoId(String urlOrId) {
        if (urlOrId == null || urlOrId.trim().isEmpty()) return null;
        String trimmed = urlOrId.trim();
        if (trimmed.length() == 11 && trimmed.matches("^[a-zA-Z0-9_-]{11}$")) {
            return trimmed;
        }
        Matcher matcher = VIDEO_ID_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    public static String extractPlaylistId(String urlOrId) {
        if (urlOrId == null || urlOrId.trim().isEmpty()) return null;
        String trimmed = urlOrId.trim();
        Matcher matcher = PLAYLIST_ID_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        if (trimmed.startsWith("PL") || trimmed.startsWith("OLAK5uy_") || trimmed.startsWith("RDMM") || trimmed.startsWith("VL")) {
            return trimmed.startsWith("VL") ? trimmed.substring(2) : trimmed;
        }
        return null;
    }

    public static boolean isYouTubeUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.startsWith("yt:") || lower.contains("music.youtube.com");
    }

    public static AudioTrack resolveVideoTrack(String videoIdOrUrl) {
        String videoId = extractVideoId(videoIdOrUrl);
        if (videoId == null) return null;

        try {
            JsonObject context = new JsonObject();
            JsonObject client = new JsonObject();
            client.addProperty("clientName", "ANDROID");
            client.addProperty("clientVersion", "20.10.38");
            client.addProperty("androidSdkVersion", 30);
            client.addProperty("hl", "en");
            client.addProperty("gl", "US");
            context.add("client", client);

            JsonObject payload = new JsonObject();
            payload.add("context", context);
            payload.addProperty("videoId", videoId);
            payload.addProperty("racyCheckOk", true);
            payload.addProperty("contentCheckOk", true);

            byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URI(PLAYER_ENDPOINT).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(6000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            if (conn.getResponseCode() == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("videoDetails")) {
                        JsonObject vd = root.getAsJsonObject("videoDetails");
                        String title = vd.has("title") ? vd.get("title").getAsString() : "YouTube Track";
                        String author = vd.has("author") ? vd.get("author").getAsString() : "YouTube";
                        long duration = 180;
                        if (vd.has("lengthSeconds")) {
                            try {
                                duration = Long.parseLong(vd.get("lengthSeconds").getAsString());
                            } catch (Throwable ignored) {}
                        }
                        String thumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                        if (vd.has("thumbnail")) {
                            JsonObject th = vd.getAsJsonObject("thumbnail");
                            if (th.has("thumbnails")) {
                                JsonArray arr = th.getAsJsonArray("thumbnails");
                                if (arr.size() > 0) {
                                    thumb = arr.get(arr.size() - 1).getAsJsonObject().get("url").getAsString();
                                }
                            }
                        }
                        return new AudioTrack(title, author, "https://www.youtube.com/watch?v=" + videoId, false, duration, "YouTube", thumb);
                    }
                }
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[YouTube] Error resolving video details: " + t.getMessage());
        }

        return new AudioTrack("YouTube Video", "YouTube", "https://www.youtube.com/watch?v=" + videoId, false, 180, "YouTube", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
    }

    public static List<AudioTrack> resolvePlaylist(String playlistIdOrUrl, int limit) {
        List<AudioTrack> tracks = new ArrayList<>();
        String playlistId = extractPlaylistId(playlistIdOrUrl);
        if (playlistId == null) return tracks;

        try {
            JsonObject context = new JsonObject();
            JsonObject client = new JsonObject();
            client.addProperty("clientName", "WEB");
            client.addProperty("clientVersion", "2.20240101.00.00");
            client.addProperty("hl", "en");
            client.addProperty("gl", "US");
            context.add("client", client);

            JsonObject payload = new JsonObject();
            payload.add("context", context);
            payload.addProperty("browseId", playlistId.startsWith("VL") ? playlistId : ("VL" + playlistId));

            byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URI(BROWSE_ENDPOINT).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(7000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            if (conn.getResponseCode() == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    collectPlaylistVideos(root, tracks, limit);
                }
            } else {
                MusicPlayerMod.LOGGER.warn("[YouTube] Playlist browse HTTP response code: " + conn.getResponseCode());
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[YouTube] Resolve playlist error: " + t.getMessage());
        }

        return tracks;
    }

    private static void collectPlaylistVideos(JsonElement element, List<AudioTrack> tracks, int limit) {
        if (element == null || tracks.size() >= limit) return;
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("playlistVideoRenderer")) {
                JsonObject pvr = obj.getAsJsonObject("playlistVideoRenderer");
                if (pvr.has("videoId")) {
                    String videoId = pvr.get("videoId").getAsString();
                    String title = "YouTube Track";
                    if (pvr.has("title")) {
                        JsonObject tObj = pvr.getAsJsonObject("title");
                        if (tObj.has("runs") && tObj.getAsJsonArray("runs").size() > 0) {
                            title = tObj.getAsJsonArray("runs").get(0).getAsJsonObject().get("text").getAsString();
                        } else if (tObj.has("simpleText")) {
                            title = tObj.get("simpleText").getAsString();
                        }
                    }

                    String artist = "YouTube";
                    if (pvr.has("shortBylineText")) {
                        JsonObject bObj = pvr.getAsJsonObject("shortBylineText");
                        if (bObj.has("runs") && bObj.getAsJsonArray("runs").size() > 0) {
                            artist = bObj.getAsJsonArray("runs").get(0).getAsJsonObject().get("text").getAsString();
                        } else if (bObj.has("simpleText")) {
                            artist = bObj.get("simpleText").getAsString();
                        }
                    }

                    long duration = 180;
                    if (pvr.has("lengthSeconds")) {
                        try {
                            duration = Long.parseLong(pvr.get("lengthSeconds").getAsString());
                        } catch (Throwable ignored) {
                            try { duration = pvr.get("lengthSeconds").getAsLong(); } catch (Throwable ignored2) {}
                        }
                    }

                    String thumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                    if (pvr.has("thumbnail")) {
                        JsonObject thObj = pvr.getAsJsonObject("thumbnail");
                        if (thObj.has("thumbnails")) {
                            JsonArray thArr = thObj.getAsJsonArray("thumbnails");
                            if (thArr.size() > 0) {
                                thumb = thArr.get(thArr.size() - 1).getAsJsonObject().get("url").getAsString();
                            }
                        }
                    }

                    tracks.add(new AudioTrack(title, artist, "https://www.youtube.com/watch?v=" + videoId, false, duration, "YouTube Playlist", thumb));
                    return;
                }
            }
            for (String key : obj.keySet()) {
                collectPlaylistVideos(obj.get(key), tracks, limit);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectPlaylistVideos(item, tracks, limit);
            }
        }
    }

    public static List<AudioTrack> search(String query, int limit) {
        List<AudioTrack> results = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return results;

        try {
            JsonObject context = new JsonObject();
            JsonObject client = new JsonObject();
            client.addProperty("clientName", "WEB");
            client.addProperty("clientVersion", "2.20240101.00.00");
            client.addProperty("hl", "en");
            client.addProperty("gl", "US");
            context.add("client", client);

            JsonObject payload = new JsonObject();
            payload.add("context", context);
            payload.addProperty("query", query.trim());

            byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URI(SEARCH_ENDPOINT).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(5500);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            if (conn.getResponseCode() == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    parseSearchResults(root, results, limit);
                }
            } else {
                MusicPlayerMod.LOGGER.warn("[YouTube] Search HTTP response code: " + conn.getResponseCode());
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[YouTube] Search error: " + t.getMessage());
        }

        return results;
    }

    private static void parseSearchResults(JsonObject root, List<AudioTrack> results, int limit) {
        try {
            JsonObject twoColumn = root.getAsJsonObject("contents")
                    .getAsJsonObject("twoColumnSearchResultsRenderer");
            JsonObject primary = twoColumn.getAsJsonObject("primaryContents");
            JsonArray sectionContents = primary.getAsJsonObject("sectionListRenderer")
                    .getAsJsonArray("contents");

            for (JsonElement sectionEl : sectionContents) {
                if (!sectionEl.isJsonObject()) continue;
                JsonObject sectionObj = sectionEl.getAsJsonObject();
                if (!sectionObj.has("itemSectionRenderer")) continue;

                JsonArray items = sectionObj.getAsJsonObject("itemSectionRenderer")
                        .getAsJsonArray("contents");

                for (JsonElement itemEl : items) {
                    if (results.size() >= limit) return;
                    if (!itemEl.isJsonObject()) continue;
                    JsonObject itemObj = itemEl.getAsJsonObject();
                    if (!itemObj.has("videoRenderer")) continue;

                    JsonObject vr = itemObj.getAsJsonObject("videoRenderer");
                    if (!vr.has("videoId")) continue;

                    // Exclude live streams and premiere badges
                    boolean isLive = !vr.has("lengthText") || vr.toString().contains("\"LIVE\"") || vr.toString().contains("\"UPCOMING\"");
                    if (isLive) continue;

                    String videoId = vr.get("videoId").getAsString();
                    String title = "Unknown Song";
                    if (vr.has("title") && vr.getAsJsonObject("title").has("runs")) {
                        JsonArray runs = vr.getAsJsonObject("title").getAsJsonArray("runs");
                        if (runs.size() > 0) {
                            title = runs.get(0).getAsJsonObject().get("text").getAsString();
                        }
                    }

                    String channel = "YouTube";
                    if (vr.has("ownerText") && vr.getAsJsonObject("ownerText").has("runs")) {
                        JsonArray runs = vr.getAsJsonObject("ownerText").getAsJsonArray("runs");
                        if (runs.size() > 0) {
                            channel = runs.get(0).getAsJsonObject().get("text").getAsString();
                        }
                    }

                    long durationSeconds = 180;
                    if (vr.has("lengthText") && vr.getAsJsonObject("lengthText").has("simpleText")) {
                        String durStr = vr.getAsJsonObject("lengthText").get("simpleText").getAsString();
                        durationSeconds = parseDurationToSeconds(durStr);
                    }

                    // Songs are generally between 30 seconds and 12 minutes (720s) - reject multi-hour videos/podcasts
                    if (durationSeconds > 720 || durationSeconds < 30) {
                        continue;
                    }

                    // Music Tag & Channel verification (Official Artist Channels, Topic channels, or music keywords)
                    boolean isMusicTrack = channel.endsWith(" - Topic")
                            || vr.toString().contains("OFFICIAL_ARTIST_CHANNEL")
                            || vr.toString().contains("BADGE_STYLE_TYPE_VERIFIED_ARTIST")
                            || title.toLowerCase().matches(".*\\b(audio|official audio|lyrics|lyric video|music video|official video|song|soundtrack|ost|remix|ft\\.|feat\\.|prod\\.|album|track|cover)\\b.*")
                            || channel.toLowerCase().contains("records") || channel.toLowerCase().contains("music") || channel.toLowerCase().contains("vevo");

                    // If not obviously non-music, keep it but mark verified music tracks prominently
                    String category = isMusicTrack ? "YouTube Music" : "YouTube";

                    String artwork = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                    String trackUrl = "https://www.youtube.com/watch?v=" + videoId;

                    AudioTrack track = new AudioTrack(title, channel, trackUrl, false, durationSeconds, category, artwork);
                    if (!results.contains(track)) {
                        results.add(track);
                    }
                }
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[YouTube] Error parsing search JSON: " + t.getMessage());
        }
    }

    public static String resolveAudioStreamUrl(String videoIdOrUrl) {
        String videoId = extractVideoId(videoIdOrUrl);
        if (videoId == null) return null;

        try {
            JsonObject context = new JsonObject();
            JsonObject client = new JsonObject();
            client.addProperty("clientName", "ANDROID");
            client.addProperty("clientVersion", "20.10.38");
            client.addProperty("androidSdkVersion", 30);
            client.addProperty("hl", "en");
            client.addProperty("gl", "US");
            context.add("client", client);

            JsonObject payload = new JsonObject();
            payload.add("context", context);
            payload.addProperty("videoId", videoId);
            payload.addProperty("racyCheckOk", true);
            payload.addProperty("contentCheckOk", true);

            byte[] postData = payload.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URI(PLAYER_ENDPOINT).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip");
            conn.setDoOutput(true);
            conn.setConnectTimeout(4500);
            conn.setReadTimeout(6000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData);
            }

            if (conn.getResponseCode() == 200) {
                try (InputStreamReader reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    if (root.has("streamingData")) {
                        JsonObject sd = root.getAsJsonObject("streamingData");

                        // 1. Prioritize Progressive Formats (itag 18 - 360p MP4 with AAC audio)
                        // Itag 18 has NO 1MB cutoff, no Range restrictions, and downloads full songs reliably with code 200
                        if (sd.has("formats") && sd.get("formats").isJsonArray()) {
                            JsonArray muxed = sd.getAsJsonArray("formats");
                            for (JsonElement el : muxed) {
                                if (!el.isJsonObject()) continue;
                                JsonObject f = el.getAsJsonObject();
                                if (f.has("url")) {
                                    int itag = f.has("itag") ? f.get("itag").getAsInt() : 0;
                                    String mime = f.has("mimeType") ? f.get("mimeType").getAsString() : "";
                                    if (itag == 18 || (mime.contains("mp4") && mime.contains("mp4a"))) {
                                        return f.get("url").getAsString();
                                    }
                                }
                            }
                        }

                        // 2. Fallback to adaptiveFormats (itag 140)
                        if (sd.has("adaptiveFormats") && sd.get("adaptiveFormats").isJsonArray()) {
                            JsonArray formats = sd.getAsJsonArray("adaptiveFormats");
                            String fallbackAudioUrl = null;
                            for (JsonElement el : formats) {
                                if (!el.isJsonObject()) continue;
                                JsonObject f = el.getAsJsonObject();
                                if (f.has("url")) {
                                    int itag = f.has("itag") ? f.get("itag").getAsInt() : 0;
                                    String mime = f.has("mimeType") ? f.get("mimeType").getAsString() : "";
                                    if (itag == 140) {
                                        return f.get("url").getAsString();
                                    }
                                    if (mime.startsWith("audio/mp4") || mime.contains("mp4a")) {
                                        fallbackAudioUrl = f.get("url").getAsString();
                                    } else if (fallbackAudioUrl == null && mime.startsWith("audio/")) {
                                        fallbackAudioUrl = f.get("url").getAsString();
                                    }
                                }
                            }
                            if (fallbackAudioUrl != null) {
                                return fallbackAudioUrl;
                            }
                        }
                    }
                }
            } else {
                MusicPlayerMod.LOGGER.warn("[YouTube] Player HTTP response code: " + conn.getResponseCode());
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.warn("[YouTube] Resolve audio error: " + t.getMessage());
        }

        return null;
    }

    public static long parseDurationToSeconds(String durStr) {
        if (durStr == null || durStr.trim().isEmpty()) return 180;
        try {
            String[] parts = durStr.trim().split(":");
            if (parts.length == 1) {
                return Long.parseLong(parts[0]);
            } else if (parts.length == 2) {
                return Long.parseLong(parts[0]) * 60 + Long.parseLong(parts[1]);
            } else if (parts.length == 3) {
                return Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60 + Long.parseLong(parts[2]);
            }
        } catch (Throwable ignored) {}
        return 180;
    }
}
