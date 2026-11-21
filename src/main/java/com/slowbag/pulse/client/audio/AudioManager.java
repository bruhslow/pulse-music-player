package com.slowbag.pulse.client.audio;

import javazoom.jl.player.Player;
import com.slowbag.pulse.MusicPlayerMod;
import com.slowbag.pulse.client.gui.AlbumArtManager;
import net.minecraft.client.sounds.JOrbisAudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class AudioManager {

    private static final AudioManager INSTANCE = new AudioManager();
    public static AudioManager getInstance() {
        return INSTANCE;
    }

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MusicPlayer-AudioThread");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        return t;
    });

    private final List<AudioTrack> playlist = new ArrayList<>();
    private final List<AudioTrack> upcomingQueue = new ArrayList<>();
    private int currentIndex = -1;

    private volatile AudioTrack currentTrack = null;
    private volatile boolean isPlaying = false;
    private volatile boolean isPaused = false;
    private volatile boolean isBuffering = false;
    private volatile String lastErrorMessage = null;
    private volatile float volume = 0.75f;
    private volatile long playStartTime = 0;
    private volatile long accumulatedPauseTime = 0;
    private volatile long lastPauseStart = 0;

    private final AtomicInteger sessionCounter = new AtomicInteger(0);

    private volatile Player activePlayer = null;
    private volatile VisualizerAudioDevice activeDevice = null;
    private volatile InputStream activeStream = null;
    private volatile SourceDataLine wavLine = null;
    private volatile java.util.concurrent.Future<?> currentPlaybackFuture = null;

    private boolean loopCurrent = false;
    private boolean shuffle = false;
    private int consecutiveTrackErrors = 0;
    private long lastTrackErrorTime = 0;

    private final List<TrackChangeListener> listeners = new ArrayList<>();

    public interface TrackChangeListener {
        void onTrackChanged(AudioTrack track);
        void onPlaybackStateChanged(boolean isPlaying, boolean isPaused);
    }

    private AudioManager() {
        // Pre-populate with curated stations
        playlist.addAll(PresetStations.getStations());
        if (!playlist.isEmpty()) {
            this.currentTrack = playlist.get(0);
        }
    }

    public void addListener(TrackChangeListener listener) {
        listeners.add(listener);
    }

    public synchronized void reloadEngine() {
        stop();
        AlbumArtManager.getInstance().clearTexture();
        System.gc();
    }

    public synchronized void playTrack(AudioTrack track) {
        playTrackInternal(track, true, 0, false);
    }

    public synchronized void playTrackInternal(AudioTrack track, boolean broadcastParty) {
        playTrackInternal(track, broadcastParty, 0, false);
    }

    public synchronized void playTrackInternal(AudioTrack track, boolean broadcastParty, long seekSeconds) {
        playTrackInternal(track, broadcastParty, seekSeconds, seekSeconds > 0);
    }

    public boolean checkHostPermission(String action) {
        PartyManager pm = PartyManager.getInstance();
        if (pm.isInRoom() && !pm.isHost()) {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client != null) {
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§e[Pulse] You are in " + pm.getCurrentHost() + "'s room! Only the host controls playback. You can add songs to Queue ~ 🎵"));
                    }
                });
            }
            return false;
        }
        return true;
    }

    public synchronized void playTrackInternal(AudioTrack track, boolean broadcastParty, long seekSeconds, boolean isSeek) {
        if (track == null) return;

        // In a room/party, only the host can start, change or play tracks!
        if (broadcastParty && !checkHostPermission("play songs")) {
            return;
        }

        // If this exact track is already buffering or playing and we aren't seeking, ignore redundant clicks
        if (!isSeek && track.equals(this.currentTrack) && this.isPlaying && !this.isPaused) {
            return;
        }

        // 1. Terminate current decoder/stream immediately so previous loop yields
        stopInternal();

        final int sessionId = sessionCounter.incrementAndGet();

        this.currentTrack = track;
        this.isPlaying = true;
        this.isPaused = false;
        this.isBuffering = true;
        this.lastErrorMessage = null;
        this.playStartTime = 0; // Recorded when audio output actually begins
        this.accumulatedPauseTime = 0;
        this.upcomingQueue.remove(track);

        int idx = playlist.indexOf(track);
        if (idx != -1) {
            this.currentIndex = idx;
        } else {
            playlist.add(track);
            this.currentIndex = playlist.size() - 1;
        }

        if (!track.getUrl().startsWith("file:/")) {
            RecentUrlManager.addRecentUrl(track.getUrl());
        }

        // Trigger dynamic album art loading
        AlbumArtManager.getInstance().loadArtworkAsync(track.getArtworkUrl());

        // Notify Listen Together party host broadcast
        if (broadcastParty) {
            try {
                PartyManager.getInstance().broadcastTrackIfHost(track);
            } catch (Throwable ignored) {}
        }

        for (TrackChangeListener l : listeners) {
            try {
                l.onTrackChanged(track);
                l.onPlaybackStateChanged(true, false);
            } catch (Throwable ignored) {}
        }

        currentPlaybackFuture = executor.submit(() -> startPlayback(track, sessionId, seekSeconds));
    }

    public synchronized void seekTo(double ratio) {
        if (currentTrack == null || currentTrack.isLiveStream() || currentTrack.getDurationSeconds() <= 0) return;
        if (!checkHostPermission("seek tracks")) return;
        double clamped = Math.max(0.0, Math.min(0.98, ratio));
        long targetSeconds = (long) (clamped * currentTrack.getDurationSeconds());
        playTrackInternal(currentTrack, false, targetSeconds, true);
        try {
            PartyManager.getInstance().broadcastSeekIfHost(clamped);
        } catch (Throwable ignored) {}
    }

    public synchronized void switchOutputDevice(String device) {
        SettingsManager.getInstance().setOutputDevice(device);
        if (isPlaying && currentTrack != null) {
            long currentSeconds = getElapsedSeconds();
            playTrackInternal(currentTrack, false, currentSeconds, true);
        }
    }

    public synchronized void seekToRemote(double ratio) {
        if (currentTrack == null || currentTrack.isLiveStream() || currentTrack.getDurationSeconds() <= 0) return;
        double clamped = Math.max(0.0, Math.min(0.98, ratio));
        long targetSeconds = (long) (clamped * currentTrack.getDurationSeconds());
        long currentSeconds = getElapsedSeconds();
        if (Math.abs(currentSeconds - targetSeconds) <= 3) {
            // In sync within 3 seconds, do not re-seek or cause audio stutter
            return;
        }
        playTrackInternal(currentTrack, false, targetSeconds, true);
    }

    public synchronized void togglePauseRemote(boolean targetPaused) {
        if (!isPlaying || currentTrack == null) return;
        if (this.isPaused == targetPaused) return;

        this.isPaused = targetPaused;
        if (targetPaused) {
            lastPauseStart = System.currentTimeMillis();
            if (activeDevice != null) {
                activeDevice.pause();
            }
        } else {
            if (lastPauseStart > 0) {
                accumulatedPauseTime += (System.currentTimeMillis() - lastPauseStart);
            }
            if (activeDevice != null) {
                activeDevice.resume();
            }
        }

        for (TrackChangeListener l : listeners) {
            try {
                l.onPlaybackStateChanged(isPlaying, isPaused);
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void setPaused(boolean paused) {
        togglePauseRemote(paused);
    }

    private InputStream openHttpStreamWithRedirects(String urlStr, long byteOffset) throws Exception {
        try {
            return openSingleHttpStream(urlStr, byteOffset);
        } catch (IOException e) {
            // Audius discovery failover: try api.audius.co mirror if discoveryprovider.audius.co failed
            if (urlStr.contains("discoveryprovider.audius.co")) {
                String mirror = urlStr.replace("discoveryprovider.audius.co", "api.audius.co");
                System.out.println("[Music Player] Retrying Audius stream via mirror: " + mirror);
                return openSingleHttpStream(mirror, byteOffset);
            }
            throw e;
        }
    }

    private InputStream openSingleHttpStream(String urlStr, long byteOffset) throws Exception {
        URL url = new URI(urlStr).toURL();
        for (int redirect = 0; redirect < 6; redirect++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "keep-alive");
            if (byteOffset > 0) {
                conn.setRequestProperty("Range", "bytes=" + byteOffset + "-");
            }
            conn.setRequestProperty("Icy-MetaData", "0");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            if (code == 416 && byteOffset > 0) {
                // Live streams (Icecast/Shoutcast) do not support byte offsets; reconnect to live stream directly
                return openSingleHttpStream(urlStr, 0);
            }
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && !loc.isBlank()) {
                    try {
                        url = url.toURI().resolve(loc).toURL();
                    } catch (Throwable t) {
                        url = new URI(loc).toURL();
                    }
                    continue;
                }
            }

            if (code >= 200 && code < 300) {
                return conn.getInputStream();
            }

            throw new IOException("HTTP code " + code + " from audio server (" + url + ")");
        }
        throw new IOException("Too many redirects");
    }

    private void startPlayback(AudioTrack track, int sessionId, long seekSeconds) {
        if (sessionId != sessionCounter.get()) return;

        try {
            String urlStr = track.getUrl();
            System.out.println("[Music Player] Starting: " + track.getTitle() + " -> " + urlStr + (seekSeconds > 0 ? " (seek: " + seekSeconds + "s)" : ""));

            if (YouTubeResolver.isYouTubeUrl(urlStr)) {
                String videoId = YouTubeResolver.extractVideoId(urlStr);
                String directUrl = YouTubeResolver.resolveAudioStreamUrl(videoId);
                if (directUrl == null) {
                    throw new IOException("Unable to resolve audio stream for YouTube track: " + track.getTitle());
                }
                System.out.println("[Music Player] Streaming direct YouTube audio stream with 16MB auto-reconnecting jitter cushion for: " + track.getTitle());
                InputStream rawHttp = openDirectAudioStream(directUrl, 0);
                InputStream bufferedHttp = new AsyncAudioBufferStream(rawHttp, offset -> openDirectAudioStream(directUrl, offset));
                activeStream = bufferedHttp;

                if (sessionId != sessionCounter.get()) {
                    bufferedHttp.close();
                    return;
                }

                playAacDash(bufferedHttp, sessionId, track, seekSeconds, null);
            } else {
                // 1. Obtain raw buffered stream
                InputStream rawStream;
                if (urlStr.startsWith("file:/")) {
                    File file = new File(new URI(urlStr));
                    if (!file.exists()) {
                        throw new IOException("Local file does not exist: " + file.getAbsolutePath());
                    }
                    rawStream = new BufferedInputStream(new FileInputStream(file), 128 * 1024);
                } else {
                    InputStream asyncBuffer = new AsyncAudioBufferStream(openHttpStreamWithRedirects(urlStr, 0), offset -> openHttpStreamWithRedirects(urlStr, offset));
                    rawStream = new BufferedInputStream(asyncBuffer, 8192);
                }

                if (sessionId != sessionCounter.get()) {
                    rawStream.close();
                    return;
                }
                activeStream = rawStream;

                // 2. Inspect first 8 bytes to detect audio container format
                rawStream.mark(16);
                byte[] magic = new byte[8];
                int readBytes = rawStream.read(magic, 0, 8);
                rawStream.reset();

                boolean isOgg = (readBytes >= 4 && magic[0] == 0x4F && magic[1] == 0x67 && magic[2] == 0x67 && magic[3] == 0x53); // "OggS"
                boolean isWav = (readBytes >= 4 && magic[0] == 0x52 && magic[1] == 0x49 && magic[2] == 0x46 && magic[3] == 0x46); // "RIFF"
                boolean isMp4 = (readBytes >= 8 && magic[4] == 'f' && magic[5] == 't' && magic[6] == 'y' && magic[7] == 'p'); // "ftyp"

                if (isOgg) {
                    System.out.println("[Music Player] Detected Ogg Vorbis format for: " + track.getTitle());
                    playVorbis(rawStream, sessionId, track, seekSeconds);
                } else if (isWav) {
                    System.out.println("[Music Player] Detected WAV format for: " + track.getTitle());
                    playWav(rawStream, sessionId, track, seekSeconds);
                } else if (isMp4) {
                    System.out.println("[Music Player] Detected MP4/M4A AAC format for: " + track.getTitle());
                    playAacDash(rawStream, sessionId, track, seekSeconds, null);
                } else {
                    System.out.println("[Music Player] Detected MP3/MPEG format for: " + track.getTitle());
                    playMp3(rawStream, sessionId, track, seekSeconds);
                }
            }

            // 3. Post-song transition handling
            long durationPlayed = playStartTime > 0 ? (System.currentTimeMillis() - playStartTime) : 0;
            if (sessionId == sessionCounter.get() && isPlaying && !isPaused) {
                // If it aborted with 0 actual playback or in less than 1.5 seconds, it was an error or unsupported codec - avoid skip loops
                if (playStartTime == 0 || durationPlayed < 1500) {
                    System.err.println("[Music Player] Track ended abnormally fast (" + durationPlayed + "ms): " + track.getTitle());
                    long now = System.currentTimeMillis();
                    if (now - lastTrackErrorTime < 8000) {
                        consecutiveTrackErrors++;
                    } else {
                        consecutiveTrackErrors = 1;
                    }
                    lastTrackErrorTime = now;

                    if (consecutiveTrackErrors >= 3) {
                        System.err.println("[Music Player] Halting skip cascade: 3 consecutive fast track terminations.");
                        this.lastErrorMessage = "Playback halted: multiple playback errors";
                        stop();
                        return;
                    }

                    if (!upcomingQueue.isEmpty()) {
                        System.out.println("[Music Player] Skipping unplayable queued track, advancing to next in queue...");
                        executor.submit(this::next);
                    } else {
                        stop();
                    }
                } else if (loopCurrent) {
                    executor.submit(() -> playTrack(track));
                } else if (!upcomingQueue.isEmpty() || !playlist.isEmpty()) {
                    executor.submit(this::next);
                } else {
                    stop();
                }
            }

        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            if (sessionId == sessionCounter.get()) {
                long durationPlayed = playStartTime > 0 ? (System.currentTimeMillis() - playStartTime) : 0;
                // If track played for over 4 seconds, end of stream is natural end!
                if (playStartTime > 0 && durationPlayed >= 4000) {
                    System.out.println("[Music Player] Stream ended naturally after " + durationPlayed + "ms.");
                    consecutiveTrackErrors = 0;
                    if (loopCurrent) {
                        System.out.println("[Music Player] 🔁 Looping current track: " + track.getTitle());
                        executor.submit(() -> playTrack(track));
                        return;
                    }
                    if (!upcomingQueue.isEmpty() || !playlist.isEmpty()) {
                        executor.submit(this::next);
                        return;
                    } else {
                        stop();
                        return;
                    }
                }
                System.err.println("[Music Player] Playback exception on: " + track.getTitle() + " - " + e.getMessage());
                this.isBuffering = false;
                this.lastErrorMessage = "Stream error: " + (e.getMessage() != null ? e.getMessage() : "Offline / Unreachable");

                long now = System.currentTimeMillis();
                if (now - lastTrackErrorTime < 8000) {
                    consecutiveTrackErrors++;
                } else {
                    consecutiveTrackErrors = 1;
                }
                lastTrackErrorTime = now;

                if (consecutiveTrackErrors >= 3) {
                    System.err.println("[Music Player] Halting skip cascade: 3 consecutive track failures.");
                    stop();
                    return;
                }

                if (loopCurrent && durationPlayed >= 1500) {
                    System.out.println("[Music Player] 🔁 Retrying loop on current track after exception: " + track.getTitle());
                    executor.submit(() -> playTrack(track));
                } else if (!upcomingQueue.isEmpty()) {
                    System.out.println("[Music Player] Error on track, advancing to next queued track...");
                    executor.submit(this::next);
                } else {
                    stop();
                }
            }
        } finally {
            if (sessionId == sessionCounter.get()) {
                stopInternal();
            }
        }
    }

    private File getCacheDir() {
        try {
            List<File> dirs = LocalMusicScanner.getMusicDirectories();
            if (!dirs.isEmpty()) {
                File cache = new File(dirs.get(0), "cache");
                if (!cache.exists()) cache.mkdirs();
                return cache;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private InputStream openDirectAudioStream(String urlStr, long byteOffset) throws Exception {
        URL url = new URI(urlStr).toURL();
        for (int redirect = 0; redirect < 6; redirect++) {
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip");
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Range", "bytes=" + byteOffset + "-");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);

            int code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                if (loc != null && !loc.isBlank()) {
                    try {
                        url = url.toURI().resolve(loc).toURL();
                    } catch (Throwable t) {
                        url = new URI(loc).toURL();
                    }
                    continue;
                }
            }

            if (code >= 200 && code < 300) {
                return conn.getInputStream();
            }

            throw new IOException("HTTP code " + code + " from YouTube audio server");
        }
        throw new IOException("Too many redirects from YouTube server");
    }

    private void playAacDash(InputStream stream, int sessionId, AudioTrack track, long seekSeconds, File cacheFile) throws Exception {
        VisualizerAudioDevice dev = new VisualizerAudioDevice();
        dev.setVolume(this.volume);
        activeDevice = dev;

        System.out.println("[Music Player] AAC DASH stream initialized for: " + track.getTitle());
        this.isBuffering = false;

        try {
            AacDashAudioStream.play(stream, dev, new AacDashAudioStream.PlaybackController() {
                @Override
                public boolean isPlaying() {
                    return AudioManager.this.isPlaying && AudioManager.this.sessionCounter.get() == sessionId;
                }

                @Override
                public boolean isPaused() {
                    return AudioManager.this.isPaused;
                }

                @Override
                public int getSessionId() {
                    return sessionId;
                }
            }, sessionId, seekSeconds, cacheFile);
        } finally {
            dev.close();
            if (activeDevice == dev) activeDevice = null;
        }
    }

    private void playVorbis(InputStream rawStream, int sessionId, AudioTrack track, long seekSeconds) throws Exception {
        VisualizerAudioDevice dev = new VisualizerAudioDevice();
        dev.setVolume(this.volume);
        activeDevice = dev;

        try (JOrbisAudioStream jorbis = new JOrbisAudioStream(rawStream)) {
            AudioFormat format = jorbis.getFormat();
            dev.openDirect(format);

            System.out.println("[Music Player] Vorbis stream initialized (" + format + ") for: " + track.getTitle());
            this.isBuffering = false;

            if (seekSeconds > 0) {
                int skipChunks = (int) (seekSeconds * 22);
                for (int i = 0; i < skipChunks && isPlaying && sessionId == sessionCounter.get(); i++) {
                    ByteBuffer b = jorbis.read(8192);
                    if (b == null || b.remaining() == 0) break;
                }
            }

            while (isPlaying && sessionId == sessionCounter.get()) {
                if (isPaused) {
                    Thread.sleep(40);
                    continue;
                }
                ByteBuffer buf = jorbis.read(8192);
                if (buf == null || buf.remaining() == 0) {
                    break;
                }
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);
                dev.writeDirectPcm(chunk, 0, chunk.length);
                notifyAudioStarted(seekSeconds);
            }
            if (!isPlaying || sessionId != sessionCounter.get()) {
                dev.flushDirect();
            } else {
                dev.drainDirect();
            }
        } finally {
            dev.close();
            if (activeDevice == dev) activeDevice = null;
        }
    }

    private void playWav(InputStream rawStream, int sessionId, AudioTrack track, long seekSeconds) throws Exception {
        VisualizerAudioDevice dev = new VisualizerAudioDevice();
        dev.setVolume(this.volume);
        activeDevice = dev;

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(rawStream)) {
            AudioFormat format = ais.getFormat();
            dev.openDirect(format);

            System.out.println("[Music Player] WAV stream initialized (" + format + ") for: " + track.getTitle());
            this.isBuffering = false;

            if (seekSeconds > 0) {
                long bytesToSkip = (long) (seekSeconds * format.getSampleRate() * format.getFrameSize());
                ais.skip(bytesToSkip);
            }

            byte[] buffer = new byte[8192];
            int read;
            while (isPlaying && sessionId == sessionCounter.get() && (read = ais.read(buffer, 0, buffer.length)) != -1) {
                if (isPaused) {
                    Thread.sleep(40);
                    continue;
                }
                dev.writeDirectPcm(buffer, 0, read);
                notifyAudioStarted(seekSeconds);
            }
            if (!isPlaying || sessionId != sessionCounter.get()) {
                dev.flushDirect();
            } else {
                dev.drainDirect();
            }
        } finally {
            dev.close();
            if (activeDevice == dev) activeDevice = null;
        }
    }

    private void playMp3(InputStream rawStream, int sessionId, AudioTrack track, long seekSeconds) throws Exception {
        // For local files with known duration, skip bytes directly on the raw file stream instantly
        if (seekSeconds > 0 && track.getUrl().startsWith("file:/") && track.getDurationSeconds() > 0) {
            try {
                File file = new File(new URI(track.getUrl()));
                long fileLength = file.length();
                if (fileLength > 0) {
                    long bytesToSkip = (long) (((double) seekSeconds / track.getDurationSeconds()) * fileLength);
                    bytesToSkip = Math.max(0, Math.min(fileLength - 8192, bytesToSkip));
                    long skipped = rawStream.skip(bytesToSkip);
                    System.out.println("[Music Player] Fast seek skipped " + skipped + " bytes for MP3 (" + seekSeconds + "s)");
                    seekSeconds = 0; // Handled directly
                }
            } catch (Throwable t) {
                System.err.println("[Music Player] Fast seek skip failed: " + t.getMessage());
            }
        }

        InputStream stream = rawStream;
        if (sessionId != sessionCounter.get()) {
            stream.close();
            return;
        }

        VisualizerAudioDevice dev = new VisualizerAudioDevice();
        dev.setVolume(this.volume);
        activeDevice = dev;
        activeStream = stream;

        try {
            activePlayer = new Player(activeStream, dev);

            System.out.println("[Music Player] MP3 decoder running for: " + track.getTitle());
            this.isBuffering = false;

            if (seekSeconds > 0 && activePlayer != null) {
                dev.setMuted(true);
                int skipFrames = (int) (seekSeconds * 38.28);
                for (int i = 0; i < skipFrames && isPlaying && sessionId == sessionCounter.get() && activePlayer != null; i++) {
                    try {
                        activePlayer.play(1);
                    } catch (Throwable ignored) {}
                }
                dev.setMuted(false);
            }

            int consecutiveErrors = 0;
            while (isPlaying && sessionId == sessionCounter.get() && activePlayer != null) {
                if (isPaused) {
                    Thread.sleep(40);
                    continue;
                }

                boolean hasMore;
                try {
                    hasMore = activePlayer.play(1);
                    consecutiveErrors = 0;
                } catch (Throwable frameEx) {
                    consecutiveErrors++;
                    if (consecutiveErrors > 25) {
                        throw frameEx;
                    }
                    continue;
                }
                notifyAudioStarted(seekSeconds);
                if (!hasMore) {
                    break;
                }
            }
            if (!isPlaying || sessionId != sessionCounter.get()) {
                dev.flushDirect();
            } else {
                dev.drainDirect();
            }
        } finally {
            if (activePlayer != null) {
                try { activePlayer.close(); } catch (Throwable ignored) {}
                activePlayer = null;
            }
            dev.close();
            if (activeDevice == dev) activeDevice = null;
        }
    }

    private synchronized void stopInternal() {
        this.isBuffering = false;
        if (currentPlaybackFuture != null) {
            currentPlaybackFuture.cancel(true);
            currentPlaybackFuture = null;
        }
        if (activePlayer != null) {
            try {
                activePlayer.close();
            } catch (Exception ignored) {}
            activePlayer = null;
        }
        if (activeStream != null) {
            try {
                activeStream.close();
            } catch (Exception ignored) {}
            activeStream = null;
        }
        if (activeDevice != null) {
            try {
                activeDevice.close();
            } catch (Exception ignored) {}
            activeDevice = null;
        }
        if (wavLine != null) {
            try {
                wavLine.stop();
                wavLine.close();
            } catch (Exception ignored) {}
            wavLine = null;
        }
    }

    public synchronized void togglePause() {
        if (!checkHostPermission("pause or resume songs")) return;
        if (!isPlaying || currentTrack == null) {
            if (currentTrack != null) {
                playTrack(currentTrack);
            } else if (!playlist.isEmpty()) {
                playTrack(playlist.get(0));
            } else {
                List<AudioTrack> hits = PresetStations.getStations();
                if (!hits.isEmpty()) {
                    playTrack(hits.get(0));
                }
            }
            return;
        }

        if (isPaused) {
            isPaused = false;
            if (lastPauseStart > 0) {
                accumulatedPauseTime += (System.currentTimeMillis() - lastPauseStart);
            }
            if (activeDevice != null) {
                activeDevice.resume();
            }
        } else {
            isPaused = true;
            lastPauseStart = System.currentTimeMillis();
            if (activeDevice != null) {
                activeDevice.pause();
            }
        }

        for (TrackChangeListener l : listeners) {
            try {
                l.onPlaybackStateChanged(isPlaying, isPaused);
            } catch (Throwable ignored) {}
        }

        try {
            PartyManager.getInstance().broadcastPauseIfHost(isPaused);
        } catch (Throwable ignored) {}
    }

    public synchronized void stop() {
        if (!checkHostPermission("stop playback")) return;
        sessionCounter.incrementAndGet();
        this.isPlaying = false;
        this.isPaused = false;
        stopInternal();

        for (TrackChangeListener l : listeners) {
            try {
                l.onPlaybackStateChanged(false, false);
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void next() {
        if (!checkHostPermission("skip songs")) return;
        if (!upcomingQueue.isEmpty()) {
            AudioTrack nextTrack = upcomingQueue.remove(0);
            playTrack(nextTrack);
            return;
        }
        if (playlist.isEmpty()) return;
        if (shuffle) {
            currentIndex = (int) (Math.random() * playlist.size());
        } else {
            currentIndex = (currentIndex + 1) % playlist.size();
        }
        playTrack(playlist.get(currentIndex));
    }

    public synchronized void previous() {
        if (playlist.isEmpty()) return;
        if (!checkHostPermission("skip songs")) return;
        if (shuffle) {
            currentIndex = (int) (Math.random() * playlist.size());
        } else {
            currentIndex = (currentIndex - 1 + playlist.size()) % playlist.size();
        }
        playTrack(playlist.get(currentIndex));
    }

    private volatile boolean muted = false;

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (activeDevice != null) {
            activeDevice.setMuted(muted);
        }
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        if (activeDevice != null) {
            activeDevice.setVolume(this.volume);
        }
        if (wavLine != null && wavLine.isOpen()) {
            try {
                if (wavLine.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) wavLine.getControl(FloatControl.Type.MASTER_GAIN);
                    float dB = (float) (Math.log10(Math.max(0.001f, this.volume)) * 20.0);
                    gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), dB)));
                }
            } catch (Throwable ignored) {}
        }
    }

    public float getVolume() {
        return volume;
    }

    public boolean isPlaying() {
        return isPlaying;
    }

    public boolean isPaused() {
        return isPaused;
    }

    public AudioTrack getCurrentTrack() {
        return currentTrack;
    }

    public List<AudioTrack> getPlaylist() {
        return Collections.unmodifiableList(playlist);
    }

    public synchronized void enqueueTrack(AudioTrack track) {
        if (track == null) return;
        PartyManager pm = PartyManager.getInstance();
        if (pm.isInRoom() && !pm.isHost()) {
            pm.addTrackToRoomQueue(track);
            return;
        }
        if (!isPlaying || currentTrack == null) {
            playTrack(track);
            return;
        }
        if (!upcomingQueue.contains(track)) {
            upcomingQueue.add(track);
        }
        if (pm.isInRoom() && pm.isHost()) {
            pm.publishHostRoomState();
        }
    }

    public synchronized void addToQueue(AudioTrack track) {
        enqueueTrack(track);
    }

    public synchronized void enqueueTrackNext(AudioTrack track) {
        if (track == null) return;
        PartyManager pm = PartyManager.getInstance();
        if (pm.isInRoom() && !pm.isHost()) {
            pm.addTrackToRoomQueue(track);
            return;
        }
        if (!isPlaying || currentTrack == null) {
            playTrack(track);
            return;
        }
        upcomingQueue.add(0, track);
        if (pm.isInRoom() && pm.isHost()) {
            pm.publishHostRoomState();
        }
    }

    public synchronized void enqueueTrackLast(AudioTrack track) {
        enqueueTrack(track);
    }

    public synchronized void enqueueTrackDirect(AudioTrack track) {
        if (track == null) return;
        if (!upcomingQueue.contains(track)) {
            upcomingQueue.add(track);
        }
    }

    public synchronized void syncGuestQueue(List<AudioTrack> queue) {
        upcomingQueue.clear();
        if (queue != null) {
            upcomingQueue.addAll(queue);
        }
    }

    public synchronized List<AudioTrack> getUpcomingQueue() {
        return Collections.unmodifiableList(new ArrayList<>(upcomingQueue));
    }

    public synchronized int getQueueSize() {
        return upcomingQueue.size();
    }

    public synchronized List<AudioTrack> getUnifiedQueue() {
        List<AudioTrack> list = new ArrayList<>();
        list.addAll(upcomingQueue);
        list.addAll(playlist);
        return list;
    }

    public synchronized void removeFromQueue(int index) {
        if (!checkHostPermission("manage the queue")) return;
        if (index >= 0 && index < upcomingQueue.size()) {
            upcomingQueue.remove(index);
            return;
        }
        int playlistIdx = index - upcomingQueue.size();
        if (playlistIdx >= 0 && playlistIdx < playlist.size()) {
            if (playlistIdx == currentIndex) {
                next();
            } else {
                if (playlistIdx < currentIndex) {
                    currentIndex--;
                }
                playlist.remove(playlistIdx);
            }
        }
    }

    public synchronized void clearQueue() {
        if (!checkHostPermission("clear the queue")) return;
        upcomingQueue.clear();
    }

    public void setPlaylist(List<AudioTrack> newPlaylist) {
        if (!checkHostPermission("load playlists")) return;
        setPlaylistAndPlay(newPlaylist, 0);
    }

    public void setPlaylistAndPlay(List<AudioTrack> newPlaylist, int startIndex) {
        if (!checkHostPermission("load playlists")) return;
        this.playlist.clear();
        this.playlist.addAll(newPlaylist);
        if (!this.playlist.isEmpty()) {
            int validIndex = Math.max(0, Math.min(this.playlist.size() - 1, startIndex));
            this.currentIndex = validIndex;
            playTrack(this.playlist.get(validIndex));
        }
    }

    public void notifyAudioStarted(long seekSeconds) {
        if (this.playStartTime == 0) {
            this.playStartTime = System.currentTimeMillis() - (seekSeconds * 1000L);
        }
        this.isBuffering = false;
        this.consecutiveTrackErrors = 0;
    }

    public boolean isLoopCurrent() {
        return loopCurrent;
    }

    public void toggleLoop() {
        if (!checkHostPermission("toggle loop")) return;
        this.loopCurrent = !this.loopCurrent;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    public void toggleShuffle() {
        if (!checkHostPermission("toggle shuffle")) return;
        this.shuffle = !this.shuffle;
    }

    public long getElapsedSeconds() {
        if (!isPlaying || playStartTime == 0) return 0;
        long elapsed = System.currentTimeMillis() - playStartTime - accumulatedPauseTime;
        if (isPaused && lastPauseStart > 0) {
            elapsed -= (System.currentTimeMillis() - lastPauseStart);
        }
        return Math.max(0, elapsed / 1000);
    }

    public float[] getVisualizerBands() {
        if (activeDevice != null && isPlaying && !isPaused) {
            return activeDevice.getBandLevels();
        }
        return null;
    }

    public float getPeakLevel() {
        if (activeDevice != null && isPlaying && !isPaused) {
            return activeDevice.getPeakLevel();
        }
        return 0.0f;
    }

    public boolean isBuffering() {
        return isBuffering && isPlaying && !isPaused;
    }

    public void setBuffering(boolean buffering) {
        this.isBuffering = buffering;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void clearLastErrorMessage() {
        this.lastErrorMessage = null;
    }
}
