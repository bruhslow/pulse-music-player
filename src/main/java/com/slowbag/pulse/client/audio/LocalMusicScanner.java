package com.slowbag.pulse.client.audio;

import net.minecraft.client.Minecraft;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class LocalMusicScanner {

    public static List<File> getMusicDirectories() {
        List<File> dirs = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        // 1. Lunar Client main profile music folder
        File lunarProfileMusic = new File(userHome, ".lunarclient/profiles/main/music");
        if (lunarProfileMusic.exists() && lunarProfileMusic.isDirectory()) {
            dirs.add(lunarProfileMusic);
        }

        // 2. Minecraft game directory music folder
        try {
            File gameDir = Minecraft.getInstance().gameDirectory;
            if (gameDir != null) {
                File gMusic = new File(gameDir, "music");
                if (gMusic.exists() && gMusic.isDirectory()) {
                    dirs.add(gMusic);
                }
            }
        } catch (Throwable ignored) {}

        // 3. Standard .minecraft/music
        File dotMcMusic = new File(userHome, "AppData/Roaming/.minecraft/music");
        if (dotMcMusic.exists() && dotMcMusic.isDirectory()) {
            dirs.add(dotMcMusic);
        }

        // 4. Windows user Music folder
        File winMusic = new File(userHome, "Music");
        if (winMusic.exists() && winMusic.isDirectory()) {
            dirs.add(winMusic);
        }

        // Default: ensure at least one folder exists
        if (dirs.isEmpty()) {
            if (!lunarProfileMusic.exists()) {
                lunarProfileMusic.mkdirs();
            }
            dirs.add(lunarProfileMusic);
        }

        return dirs;
    }

    public static List<AudioTrack> scanLocalTracks() {
        List<AudioTrack> tracks = new ArrayList<>();
        Set<String> seenNames = new LinkedHashSet<>();

        for (File musicFolder : getMusicDirectories()) {
            if (!musicFolder.exists() || !musicFolder.isDirectory()) continue;

            File[] files = musicFolder.listFiles((dir, name) -> {
                String lower = name.toLowerCase();
                return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg");
            });

            if (files != null) {
                for (File file : files) {
                    String fileName = file.getName();
                    String cleanName = fileName.replaceFirst("[.][^.]+$", "");
                    String lowerName = cleanName.toLowerCase();
                    if (seenNames.contains(lowerName)) continue;
                    seenNames.add(lowerName);

                    String artist = "Local File";
                    String title = cleanName;

                    if (cleanName.contains(" - ")) {
                        String[] parts = cleanName.split(" - ", 2);
                        artist = parts[0].trim();
                        title = parts[1].trim();
                    }

                    // Look for local companion album artwork (.png, .jpg)
                    String artworkUrl = null;
                    File[] potentialArts = new File[] {
                            new File(musicFolder, cleanName + ".png"),
                            new File(musicFolder, cleanName + ".jpg"),
                            new File(musicFolder, cleanName + ".jpeg"),
                            new File(musicFolder, "cover.png"),
                            new File(musicFolder, "cover.jpg"),
                            new File(musicFolder, "albumart.png"),
                            new File(musicFolder, "albumart.jpg")
                    };
                    for (File art : potentialArts) {
                        if (art.exists() && art.isFile()) {
                            artworkUrl = art.toURI().toString();
                            break;
                        }
                    }

                    long duration = -1;
                    if (file.length() > 0) {
                        // Standard ~160kbps estimate (20 KB/sec) for MP3/Vorbis tracks
                        duration = Math.max(15, file.length() / 20000L);
                    }

                    tracks.add(new AudioTrack(
                            title,
                            artist,
                            file.toURI().toString(),
                            false,
                            duration,
                            "Local",
                            artworkUrl
                    ));
                }
            }
        }

        return tracks;
    }

    public static void openMusicFolder() {
        List<File> dirs = getMusicDirectories();
        File folder = dirs.isEmpty() ? new File(System.getProperty("user.home"), ".lunarclient/profiles/main/music") : dirs.get(0);
        if (!folder.exists()) {
            folder.mkdirs();
        }

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(folder);
            } else {
                new ProcessBuilder("explorer.exe", folder.getAbsolutePath()).start();
            }
        } catch (Throwable ignored) {
            try {
                new ProcessBuilder("explorer.exe", folder.getAbsolutePath()).start();
            } catch (Throwable ignored2) {}
        }
    }
}
