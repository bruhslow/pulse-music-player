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

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("(?:v=|youtu\\.be\\/|embed\\/|shorts\\/|live\\/|\\/)([a-zA-Z0-9_-]{11})");
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
                        boolean isLive = (vd.has("isLive") && vd.get("isLive").getAsBoolean())
                                || (vd.has("isLiveContent") && vd.get("isLiveContent").getAsBoolean());
                        long duration = isLive ? -1 : 180;
                        if (!isLive && vd.has("lengthSeconds")) {
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
                        return new AudioTrack(title, author, "https://www.youtube.com/watch?v=" + videoId, isLive, duration, isLive ? "YouTube Live" : "YouTube", thumb);
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
            if (obj.has("lockupViewModel")) {
                AudioTrack track = parseLockupViewModel(obj.getAsJsonObject("lockupViewModel"), "YouTube Playlist");
                if (track != null) {
                    tracks.add(track);
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

    public static AudioTrack parseLockupViewModel(JsonObject lvm, String category) {
        if (lvm == null) return null;

        if (lvm.has("contentType")) {
            String ct = lvm.get("contentType").getAsString();
            if (!"LOCKUP_CONTENT_TYPE_VIDEO".equalsIgnoreCase(ct)) {
                return null;
            }
        }

        String videoId = null;
        if (lvm.has("contentId") && !lvm.get("contentId").isJsonNull()) {
            videoId = lvm.get("contentId").getAsString();
        }
        if ((videoId == null || videoId.isEmpty()) && lvm.has("rendererContext")) {
            try {
                videoId = lvm.getAsJsonObject("rendererContext")
                             .getAsJsonObject("commandContext")
                             .getAsJsonObject("onTap")
                             .getAsJsonObject("innertubeCommand")
                             .getAsJsonObject("watchEndpoint")
                             .get("videoId").getAsString();
            } catch (Throwable ignored) {}
        }

        if (videoId == null || !videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
            return null;
        }

        String title = "YouTube Track";
        String artist = "YouTube";

        if (lvm.has("metadata")) {
            JsonObject md = lvm.getAsJsonObject("metadata");
            if (md.has("lockupMetadataViewModel")) {
                JsonObject lmd = md.getAsJsonObject("lockupMetadataViewModel");
                if (lmd.has("title") && lmd.getAsJsonObject("title").has("content")) {
                    title = lmd.getAsJsonObject("title").get("content").getAsString();
                }
                if (lmd.has("metadata")) {
                    JsonObject cmd = lmd.getAsJsonObject("metadata");
                    if (cmd.has("contentMetadataViewModel")) {
                        JsonObject cmvm = cmd.getAsJsonObject("contentMetadataViewModel");
                        if (cmvm.has("metadataRows")) {
                            JsonArray rows = cmvm.getAsJsonArray("metadataRows");
                            if (rows.size() > 0 && rows.get(0).isJsonObject()) {
                                JsonObject r0 = rows.get(0).getAsJsonObject();
                                if (r0.has("metadataParts")) {
                                    JsonArray parts = r0.getAsJsonArray("metadataParts");
                                    if (parts.size() > 0 && parts.get(0).isJsonObject()) {
                                        JsonObject p0 = parts.get(0).getAsJsonObject();
                                        if (p0.has("text") && p0.getAsJsonObject("text").has("content")) {
                                            artist = p0.getAsJsonObject("text").get("content").getAsString();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if ("YouTube Track".equals(title) && lvm.has("rendererContext")) {
            try {
                JsonObject a11y = lvm.getAsJsonObject("rendererContext").getAsJsonObject("accessibilityContext");
                if (a11y.has("label")) {
                    String lbl = a11y.get("label").getAsString();
                    if (lbl != null && !lbl.isBlank()) {
                        title = lbl;
                    }
                }
            } catch (Throwable ignored) {}
        }

        long duration = 180;
        String thumb = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";

        if (lvm.has("contentImage")) {
            JsonObject ci = lvm.getAsJsonObject("contentImage");
            if (ci.has("thumbnailViewModel")) {
                JsonObject tvm = ci.getAsJsonObject("thumbnailViewModel");
                if (tvm.has("overlays")) {
                    JsonArray overlays = tvm.getAsJsonArray("overlays");
                    for (JsonElement ovEl : overlays) {
                        if (ovEl.isJsonObject()) {
                            JsonObject ov = ovEl.getAsJsonObject();
                            if (ov.has("thumbnailBottomOverlayViewModel")) {
                                JsonObject tbovm = ov.getAsJsonObject("thumbnailBottomOverlayViewModel");
                                if (tbovm.has("badges")) {
                                    JsonArray badges = tbovm.getAsJsonArray("badges");
                                    for (JsonElement bEl : badges) {
                                        if (bEl.isJsonObject()) {
                                            JsonObject b = bEl.getAsJsonObject();
                                            if (b.has("thumbnailBadgeViewModel")) {
                                                JsonObject tbvm = b.getAsJsonObject("thumbnailBadgeViewModel");
                                                if (tbvm.has("text")) {
                                                    String t = tbvm.get("text").getAsString();
                                                    if (t != null && t.contains(":")) {
                                                        duration = parseDurationToSeconds(t);
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return new AudioTrack(title, artist, "https://www.youtube.com/watch?v=" + videoId, false, duration, category, thumb);
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

                    if (itemObj.has("lockupViewModel")) {
                        AudioTrack track = parseLockupViewModel(itemObj.getAsJsonObject("lockupViewModel"), "YouTube");
                        if (track != null) {
                            if (track.getDurationSeconds() >= 30 && track.getDurationSeconds() <= 720) {
                                results.add(track);
                            }
                        }
                        continue;
                    }

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

        for (int attempt = 1; attempt <= 4; attempt++) {
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
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);

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

                            // 3. Fallback for Live Streams: Extract from HLS Manifest
                            if (sd.has("hlsManifestUrl")) {
                                String hlsManifest = sd.get("hlsManifestUrl").getAsString();
                                String liveAudio = extractHlsAudioStream(hlsManifest);
                                if (liveAudio != null) {
                                    return liveAudio;
                                }
                                return hlsManifest;
                            }
                        }
                    }
                } else {
                    MusicPlayerMod.LOGGER.warn("[YouTube] Player HTTP response code on attempt " + attempt + ": " + conn.getResponseCode());
                }
            } catch (Throwable t) {
                MusicPlayerMod.LOGGER.warn("[YouTube] Stream resolve attempt " + attempt + " error: " + t.getMessage());
            }

            if (attempt < 4) {
                try {
                    Thread.sleep(attempt * 400L);
                } catch (InterruptedException ignored) {}
            }
        }

        return null;
    }

    private static String extractHlsAudioStream(String hlsManifestUrl) {
        try {
            URL u = new URI(hlsManifestUrl).toURL();
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    String lastUrl = null;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("http://") || line.startsWith("https://")) {
                            lastUrl = line;
                            if (line.contains("audio") || line.contains("itag/140") || line.contains("itag/91")) {
                                return line;
                            }
                        }
                    }
                    if (lastUrl != null) return lastUrl;
                }
            }
        } catch (Throwable ignored) {}
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
