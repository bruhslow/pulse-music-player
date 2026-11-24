package com.slowbag.pulse.client.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;

public class LastTrackManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final LastTrackManager INSTANCE = new LastTrackManager();
    private AudioTrack lastTrack = null;
    private boolean loaded = false;

    public static LastTrackManager getInstance() {
        return INSTANCE;
    }

    private File getConfigFile() {
        File configDir = new File(Minecraft.getInstance().gameDirectory, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "musicplayer_last_track.json");
    }

    public synchronized AudioTrack getLastTrack() {
        if (!loaded) {
            load();
        }
        return lastTrack;
    }

    public synchronized void setLastTrack(AudioTrack track) {
        if (track == null) return;
        this.lastTrack = track;
        save();
    }

    private synchronized void load() {
        loaded = true;
        File file = getConfigFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            TrackData data = GSON.fromJson(reader, TrackData.class);
            if (data != null && data.title != null && data.url != null && !data.url.isBlank()) {
                this.lastTrack = new AudioTrack(data.title, data.artist, data.url, data.isLiveStream, data.durationSeconds, data.category, data.artworkUrl);
            }
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[Music Player] Failed to load last track: " + t.getMessage());
        }
    }

    private synchronized void save() {
        if (lastTrack == null) return;
        File file = getConfigFile();
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            TrackData data = new TrackData();
            data.title = lastTrack.getTitle();
            data.artist = lastTrack.getArtist();
            data.url = lastTrack.getUrl();
            data.isLiveStream = lastTrack.isLiveStream();
            data.durationSeconds = lastTrack.getDurationSeconds();
            data.category = lastTrack.getCategory();
            data.artworkUrl = lastTrack.getArtworkUrl();
            GSON.toJson(data, writer);
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[Music Player] Failed to save last track: " + t.getMessage());
        }
    }

    private static class TrackData {
        String title;
        String artist;
        String url;
        boolean isLiveStream;
        long durationSeconds;
        String category;
        String artworkUrl;
    }
}
