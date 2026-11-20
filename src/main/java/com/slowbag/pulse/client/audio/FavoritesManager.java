package com.slowbag.pulse.client.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class FavoritesManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final FavoritesManager INSTANCE = new FavoritesManager();

    private final CopyOnWriteArrayList<AudioTrack> favorites = new CopyOnWriteArrayList<>();
    private boolean loaded = false;

    private FavoritesManager() {
        load();
    }

    public static FavoritesManager getInstance() {
        return INSTANCE;
    }

    private File getConfigFile() {
        File configDir = new File(Minecraft.getInstance().gameDirectory, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "musicplayer_favorites.json");
    }

    public synchronized List<AudioTrack> getFavorites() {
        if (!loaded) {
            load();
        }
        return new ArrayList<>(favorites);
    }

    public synchronized boolean isFavorite(AudioTrack track) {
        if (track == null || track.getUrl() == null) return false;
        return isFavorite(track.getUrl());
    }

    public synchronized boolean isFavorite(String url) {
        if (url == null || url.isBlank()) return false;
        String clean = url.trim();
        for (AudioTrack fav : favorites) {
            if (fav != null && clean.equalsIgnoreCase(fav.getUrl())) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean toggleFavorite(AudioTrack track) {
        if (track == null) return false;
        if (isFavorite(track)) {
            removeFavorite(track);
            return false;
        } else {
            addFavorite(track);
            return true;
        }
    }

    public synchronized void addFavorite(AudioTrack track) {
        if (track == null || track.getUrl() == null || track.getUrl().isBlank()) return;
        removeFavorite(track); // Avoid duplicates
        favorites.add(0, track);
        save();
    }

    public synchronized void removeFavorite(AudioTrack track) {
        if (track == null || track.getUrl() == null) return;
        removeFavorite(track.getUrl());
    }

    public synchronized void removeFavorite(String url) {
        if (url == null || url.isBlank()) return;
        String clean = url.trim();
        favorites.removeIf(fav -> fav == null || clean.equalsIgnoreCase(fav.getUrl()));
        save();
    }

    public synchronized void clearFavorites() {
        favorites.clear();
        save();
    }

    private synchronized void load() {
        loaded = true;
        File file = getConfigFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            List<FavoriteEntry> list = GSON.fromJson(reader, new TypeToken<List<FavoriteEntry>>(){}.getType());
            if (list != null) {
                favorites.clear();
                for (FavoriteEntry entry : list) {
                    if (entry != null && entry.url != null && !entry.url.isBlank()) {
                        favorites.add(new AudioTrack(
                                entry.title,
                                entry.artist,
                                entry.url,
                                entry.liveStream,
                                entry.durationSeconds,
                                entry.category,
                                entry.artworkUrl
                        ));
                    }
                }
            }
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to load favorites", e);
        }
    }

    private synchronized void save() {
        File file = getConfigFile();
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            List<FavoriteEntry> list = new ArrayList<>();
            for (AudioTrack track : favorites) {
                if (track != null) {
                    FavoriteEntry entry = new FavoriteEntry();
                    entry.title = track.getTitle();
                    entry.artist = track.getArtist();
                    entry.url = track.getUrl();
                    entry.liveStream = track.isLiveStream();
                    entry.durationSeconds = track.getDurationSeconds();
                    entry.category = track.getCategory();
                    entry.artworkUrl = track.getArtworkUrl();
                    list.add(entry);
                }
            }
            GSON.toJson(list, writer);
        } catch (Exception e) {
            MusicPlayerMod.LOGGER.error("[Music Player] Failed to save favorites", e);
        }
    }

    private static class FavoriteEntry {
        String title;
        String artist;
        String url;
        boolean liveStream;
        long durationSeconds;
        String category;
        String artworkUrl;
    }
}
