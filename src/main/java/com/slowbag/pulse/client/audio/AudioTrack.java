package com.slowbag.pulse.client.audio;

import java.util.Objects;

public class AudioTrack {
    private final String title;
    private final String artist;
    private final String url;
    private final boolean liveStream;
    private volatile long durationSeconds;
    private final String category;
    private final String artworkUrl;

    public AudioTrack(String title, String artist, String url, boolean liveStream, long durationSeconds, String category, String artworkUrl) {
        this.title = title != null ? title : "Unknown Title";
        this.artist = artist != null ? artist : "Unknown Artist";
        this.url = url != null ? url : "";
        this.liveStream = liveStream;
        this.durationSeconds = durationSeconds;
        this.category = category != null ? category : "Music";
        this.artworkUrl = artworkUrl;
    }

    public AudioTrack(String title, String artist, String url, boolean liveStream, long durationSeconds, String category) {
        this(title, artist, url, liveStream, durationSeconds, category, null);
    }

    public AudioTrack(String title, String artist, String url, String category) {
        this(title, artist, url, true, -1, category, null);
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getUrl() {
        return url;
    }

    public boolean isLiveStream() {
        return liveStream;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public String getCategory() {
        return category;
    }

    public String getArtworkUrl() {
        if (artworkUrl != null && !artworkUrl.isBlank()) {
            return artworkUrl;
        }
        if (url != null) {
            String u = url.toLowerCase();
            if (u.contains("groovesalad")) return "https://somafm.com/img3/groovesalad120.png";
            if (u.contains("spacestation")) return "https://somafm.com/img3/spacestation120.png";
            if (u.contains("defcon")) return "https://somafm.com/img3/defcon120.png";
            if (u.contains("suburbsofgoa")) return "https://somafm.com/img3/suburbsofgoa120.png";
            if (u.contains("lush")) return "https://somafm.com/img3/lush120.png";
            if (u.contains("thistle")) return "https://somafm.com/img3/thistle120.png";
            if (u.contains("secretagent")) return "https://somafm.com/img3/secretagent120.png";
            if (u.contains("dronezone")) return "https://somafm.com/img3/dronezone120.png";
            if (u.contains("indiepop")) return "https://somafm.com/img3/indiepop120.png";
            if (u.contains("beatblender")) return "https://somafm.com/img3/beatblender120.png";
            if (u.contains("plaza.one")) return "https://plaza.one/apple-touch-icon.png";
            if (u.contains("rsj") || u.contains("swissjazz")) return "https://somafm.com/img3/secretagent120.png";
            if (u.contains("rsc") || u.contains("swissclassic")) return "https://somafm.com/img3/thistle120.png";
            if (u.contains("youtube.com") || u.contains("youtu.be") || u.startsWith("yt:") || (category != null && category.toLowerCase().contains("youtube"))) {
                String vid = YouTubeResolver.extractVideoId(url);
                if (vid != null && !vid.isBlank()) {
                    return "https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg";
                }
            }
        }
        return null;
    }

    public String getFormattedDuration() {
        if (liveStream || durationSeconds < 0) {
            return "LIVE";
        }
        long minutes = durationSeconds / 60;
        long seconds = durationSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AudioTrack that = (AudioTrack) o;
        return Objects.equals(url, that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }
}
