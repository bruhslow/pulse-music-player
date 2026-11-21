package com.slowbag.pulse.client.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class SettingsManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SettingsManager INSTANCE = new SettingsManager();

    public static final String VOICE_CHAT_DEVICE = "Simple Voice Chat (Mic)";
    private String outputDevice = "Default";
    private int preloadSeconds = 5;
    private boolean monoAudio = false;
    private boolean reducedMotion = false;
    private boolean backgroundPlayback = true;
    private boolean toastNotifications = true;
    private boolean autoDucking = false;
    private boolean continuousPlayback = true;
    private boolean autoConnectServerParty = true;
    private boolean loaded = false;

    private SettingsManager() {
        load();
    }

    public static SettingsManager getInstance() {
        return INSTANCE;
    }

    public synchronized String getOutputDevice() {
        if (!loaded) load();
        return outputDevice != null ? outputDevice : "Default";
    }

    public synchronized boolean isVoiceChatOutput() {
        if (!loaded) load();
        return VOICE_CHAT_DEVICE.equalsIgnoreCase(outputDevice);
    }

    public synchronized void setOutputDevice(String device) {
        this.outputDevice = (device != null && !device.isBlank()) ? device : "Default";
        save();
        if (isVoiceChatOutput()) {
            SimpleVoiceChatBridge.getInstance().notifyModeEnabled();
        }
    }

    public synchronized List<String> getAvailableOutputDevices() {
        List<String> list = new ArrayList<>();
        list.add("Default");
        list.add(VOICE_CHAT_DEVICE);
        Line.Info lineInfo = new Line.Info(SourceDataLine.class);
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            try {
                Mixer m = AudioSystem.getMixer(info);
                if ((m.getSourceLineInfo() != null && m.getSourceLineInfo().length > 0) || m.isLineSupported(lineInfo)) {
                    String name = info.getName();
                    if (name != null && !name.isBlank() && !list.contains(name)) {
                        list.add(name);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return list;
    }

    public synchronized String cycleOutputDevice(boolean forward) {
        List<String> devices = getAvailableOutputDevices();
        int currentIdx = 0;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).equalsIgnoreCase(outputDevice)) {
                currentIdx = i;
                break;
            }
        }
        int nextIdx;
        if (forward) {
            nextIdx = (currentIdx + 1) % devices.size();
        } else {
            nextIdx = (currentIdx - 1 + devices.size()) % devices.size();
        }
        outputDevice = devices.get(nextIdx);
        save();
        return outputDevice;
    }

    public synchronized int getPreloadSeconds() {
        if (!loaded) load();
        return preloadSeconds > 0 ? preloadSeconds : 5;
    }

    public synchronized int cyclePreloadSeconds() {
        if (!loaded) load();
        if (preloadSeconds == 2) {
            preloadSeconds = 5;
        } else if (preloadSeconds == 5) {
            preloadSeconds = 10;
        } else {
            preloadSeconds = 2;
        }
        save();
        return preloadSeconds;
    }

    public synchronized void setPreloadSeconds(int sec) {
        this.preloadSeconds = Math.max(1, Math.min(15, sec));
        save();
    }

    public synchronized boolean isMonoAudio() {
        if (!loaded) load();
        return monoAudio;
    }

    public synchronized boolean toggleMonoAudio() {
        this.monoAudio = !this.monoAudio;
        save();
        return this.monoAudio;
    }

    public synchronized boolean isReducedMotion() {
        if (!loaded) load();
        return reducedMotion;
    }

    public synchronized boolean toggleReducedMotion() {
        this.reducedMotion = !this.reducedMotion;
        save();
        return this.reducedMotion;
    }

    public synchronized boolean isBackgroundPlayback() {
        if (!loaded) load();
        return backgroundPlayback;
    }

    public synchronized boolean toggleBackgroundPlayback() {
        this.backgroundPlayback = !this.backgroundPlayback;
        save();
        return this.backgroundPlayback;
    }

    public synchronized boolean isToastNotifications() {
        if (!loaded) load();
        return toastNotifications;
    }

    public synchronized boolean toggleToastNotifications() {
        this.toastNotifications = !this.toastNotifications;
        save();
        return this.toastNotifications;
    }

    public synchronized boolean isAutoDucking() {
        if (!loaded) load();
        return autoDucking;
    }

    public synchronized boolean toggleAutoDucking() {
        this.autoDucking = !this.autoDucking;
        save();
        return this.autoDucking;
    }

    public synchronized boolean isContinuousPlayback() {
        if (!loaded) load();
        return continuousPlayback;
    }

    public synchronized boolean toggleContinuousPlayback() {
        this.continuousPlayback = !this.continuousPlayback;
        save();
        return this.continuousPlayback;
    }

    public synchronized long clearCache() {
        long freedBytes = 0;
        try {
            File cacheDir = new File(Minecraft.getInstance().gameDirectory, "pulse_cache");
            if (cacheDir.exists() && cacheDir.isDirectory()) {
                File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        freedBytes += f.length();
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return freedBytes;
    }

    public synchronized boolean isAutoConnectServerParty() {
        if (!loaded) load();
        return autoConnectServerParty;
    }

    public synchronized boolean toggleAutoConnectServerParty() {
        this.autoConnectServerParty = !this.autoConnectServerParty;
        save();
        return this.autoConnectServerParty;
    }

    public static SourceDataLine openLineForDevice(DataLine.Info info) throws LineUnavailableException {
        String device = getInstance().getOutputDevice();
        if (device != null && !device.equalsIgnoreCase("Default") && !device.isBlank()) {
            if (device.equalsIgnoreCase(VOICE_CHAT_DEVICE)) {
                return (SourceDataLine) AudioSystem.getLine(info);
            }
            for (Mixer.Info mInfo : AudioSystem.getMixerInfo()) {
                if (mInfo.getName().equalsIgnoreCase(device)) {
                    try {
                        Mixer mixer = AudioSystem.getMixer(mInfo);
                        if (mixer.isLineSupported(info)) {
                            return (SourceDataLine) mixer.getLine(info);
                        }
                    } catch (Throwable ignored) {}
                }
            }
        }
        return (SourceDataLine) AudioSystem.getLine(info);
    }

    private File getConfigFile() {
        File configDir = new File(Minecraft.getInstance().gameDirectory, "config");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, "musicplayer_settings.json");
    }

    private synchronized void load() {
        loaded = true;
        File file = getConfigFile();
        if (!file.exists()) return;

        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            SettingsData data = GSON.fromJson(reader, SettingsData.class);
            if (data != null) {
                if (data.outputDevice != null) this.outputDevice = data.outputDevice;
                if (data.preloadSeconds > 0) this.preloadSeconds = data.preloadSeconds;
                this.monoAudio = data.monoAudio;
                this.reducedMotion = data.reducedMotion;
                this.backgroundPlayback = data.backgroundPlayback;
                this.toastNotifications = data.toastNotifications;
                this.autoDucking = data.autoDucking;
                this.continuousPlayback = data.continuousPlayback;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized void save() {
        File file = getConfigFile();
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            SettingsData data = new SettingsData();
            data.outputDevice = this.outputDevice;
            data.preloadSeconds = this.preloadSeconds;
            data.monoAudio = this.monoAudio;
            data.reducedMotion = this.reducedMotion;
            data.backgroundPlayback = this.backgroundPlayback;
            data.toastNotifications = this.toastNotifications;
            data.autoDucking = this.autoDucking;
            data.continuousPlayback = this.continuousPlayback;
            GSON.toJson(data, writer);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class SettingsData {
        String outputDevice = "Default";
        int preloadSeconds = 5;
        boolean monoAudio = false;
        boolean reducedMotion = false;
        boolean backgroundPlayback = true;
        boolean toastNotifications = true;
        boolean autoDucking = false;
        boolean continuousPlayback = true;
    }
}
