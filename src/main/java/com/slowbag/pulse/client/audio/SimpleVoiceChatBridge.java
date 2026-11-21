package com.slowbag.pulse.client.audio;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleVoiceChatBridge {

    private static final SimpleVoiceChatBridge INSTANCE = new SimpleVoiceChatBridge();
    public static SimpleVoiceChatBridge getInstance() {
        return INSTANCE;
    }

    private static final int TARGET_SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 960; // 20ms at 48kHz
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2; // 16-bit mono

    private final ExecutorService transmissionExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Pulse-VoiceChatMicStreamer");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

    private final ConcurrentLinkedQueue<short[]> pcmQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean isStreaming = new AtomicBoolean(false);
    private volatile boolean notifiedModStatus = false;
    private volatile SourceDataLine virtualMicLine = null;
    private volatile long lastMergeHandledTime = 0;
    private final java.util.concurrent.atomic.AtomicLong sequenceNumber = new java.util.concurrent.atomic.AtomicLong(1);
    private Object cachedEncoder = null;

    private SimpleVoiceChatBridge() {
        startTransmissionLoop();
    }

    public void markMergeHandled() {
        this.lastMergeHandledTime = System.currentTimeMillis();
    }

    public boolean isVoiceChatModPresent() {
        try {
            Class.forName("de.maxhenkel.voicechat.VoicechatClient");
            return true;
        } catch (Throwable t1) {
            try {
                Class.forName("de.maxhenkel.voicechat.Voicechat");
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    public void notifyModeEnabled() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            if (isVoiceChatModPresent()) {
                mc.player.sendSystemMessage(Component.literal("§6[Pulse] 🎤 Simple Voice Chat microphone output selected! Music will stream directly through your in-game mic to nearby players."));
            } else {
                mc.player.sendSystemMessage(Component.literal("§e[Pulse] 🎤 Voice Chat mode active! Note: Simple Voice Chat mod or VB-Audio Cable was not found. Install Simple Voice Chat or a virtual mic to broadcast to friends in-game."));
            }
        }
    }

    public void injectAudio(short[] samples, int offset, int length, int sampleRate, int channels) {
        if (samples == null || length <= 0) return;
        short[] mono48k = resampleTo48kMono(samples, offset, length, sampleRate, channels);
        if (mono48k != null && mono48k.length > 0) {
            // Buffer up to ~2 seconds of audio (100 frames) to prevent memory buildup
            if (pcmQueue.size() < 100) {
                pcmQueue.offer(mono48k);
            }
        }
    }

    public void injectAudio(byte[] pcmBytes, int offset, int length, int sampleRate, int channels) {
        if (pcmBytes == null || length < 2) return;
        int sampleCount = length / 2;
        short[] samples = new short[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            int byteIdx = offset + i * 2;
            samples[i] = (short) ((pcmBytes[byteIdx] & 0xFF) | (pcmBytes[byteIdx + 1] << 8));
        }
        injectAudio(samples, 0, sampleCount, sampleRate, channels);
    }

    private short[] resampleTo48kMono(short[] src, int offset, int len, int srcRate, int channels) {
        if (srcRate <= 0) srcRate = 44100;
        if (channels <= 0) channels = 2;

        int frameCount = len / channels;
        if (frameCount <= 0) return null;

        // 1. Downmix to mono
        short[] monoSrc = new short[frameCount];
        if (channels == 1) {
            System.arraycopy(src, offset, monoSrc, 0, frameCount);
        } else {
            for (int i = 0; i < frameCount; i++) {
                int base = offset + i * channels;
                int sum = 0;
                for (int c = 0; c < channels; c++) {
                    sum += src[base + c];
                }
                monoSrc[i] = (short) (sum / channels);
            }
        }

        // 2. Resample to 48kHz if needed
        if (srcRate == TARGET_SAMPLE_RATE) {
            return monoSrc;
        }

        double ratio = (double) TARGET_SAMPLE_RATE / srcRate;
        int outLength = (int) Math.round(frameCount * ratio);
        short[] out = new short[outLength];

        for (int i = 0; i < outLength; i++) {
            double srcIdx = i / ratio;
            int idxFloor = (int) Math.floor(srcIdx);
            int idxCeil = Math.min(frameCount - 1, idxFloor + 1);
            double frac = srcIdx - idxFloor;

            if (idxFloor >= frameCount) {
                out[i] = monoSrc[frameCount - 1];
            } else {
                double val = monoSrc[idxFloor] * (1.0 - frac) + monoSrc[idxCeil] * frac;
                out[i] = (short) Math.max(-32768, Math.min(32767, Math.round(val)));
            }
        }
        return out;
    }

    private final Object frameLock = new Object();
    private short[] remainder = new short[0];

    public short[] pollFrame() {
        synchronized (frameLock) {
            if (pcmQueue.isEmpty() && remainder.length == 0) {
                return null;
            }

            short[] frame = new short[FRAME_SAMPLES];
            int collected = 0;

            if (remainder.length > 0) {
                int take = Math.min(remainder.length, FRAME_SAMPLES);
                System.arraycopy(remainder, 0, frame, 0, take);
                collected += take;
                if (remainder.length > take) {
                    short[] newRem = new short[remainder.length - take];
                    System.arraycopy(remainder, take, newRem, 0, newRem.length);
                    remainder = newRem;
                } else {
                    remainder = new short[0];
                }
            }

            while (collected < FRAME_SAMPLES) {
                short[] nextChunk = pcmQueue.poll();
                if (nextChunk == null) break;

                int needed = FRAME_SAMPLES - collected;
                if (nextChunk.length <= needed) {
                    System.arraycopy(nextChunk, 0, frame, collected, nextChunk.length);
                    collected += nextChunk.length;
                } else {
                    System.arraycopy(nextChunk, 0, frame, collected, needed);
                    collected += needed;
                    int leftOver = nextChunk.length - needed;
                    remainder = new short[leftOver];
                    System.arraycopy(nextChunk, needed, remainder, 0, leftOver);
                }
            }

            if (collected == 0) {
                return null;
            }

            // Zero-pad remainder of frame
            for (int i = collected; i < FRAME_SAMPLES; i++) {
                frame[i] = 0;
            }

            // Mirror to virtual audio cable if active
            if (virtualMicLine != null && virtualMicLine.isOpen()) {
                byte[] b = new byte[FRAME_BYTES];
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    b[i * 2] = (byte) (frame[i] & 0xFF);
                    b[i * 2 + 1] = (byte) ((frame[i] >> 8) & 0xFF);
                }
                try { virtualMicLine.write(b, 0, b.length); } catch (Throwable ignored) {}
            }

            return frame;
        }
    }

    private void startTransmissionLoop() {
        transmissionExecutor.submit(() -> {
            while (true) {
                try {
                    boolean voiceChatActive = SettingsManager.getInstance().isVoiceChatOutput();
                    if (!voiceChatActive) {
                        Thread.sleep(50);
                        continue;
                    }

                    // If Simple Voice Chat's MergeClientSoundEvent is actively pulling audio (within last 100ms),
                    // let it handle merging so we don't double-transmit.
                    if (System.currentTimeMillis() - lastMergeHandledTime < 100) {
                        Thread.sleep(15);
                        continue;
                    }

                    // Otherwise (player mic is idle, muted, or push-to-talk not held), transmit directly
                    short[] frame = pollFrame();
                    if (frame == null) {
                        Thread.sleep(15);
                        continue;
                    }

                    dispatchFrameDirect(frame);
                    Thread.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    try { Thread.sleep(20); } catch (Throwable ignored) {}
                }
            }
        });
    }

    private void dispatchFrameDirect(short[] frame) {
        // 1. Send directly through Simple Voice Chat client network connection if active
        if (isVoiceChatModPresent()) {
            try {
                if (cachedEncoder == null && PulseVoicechatPlugin.getClientApi() != null) {
                    cachedEncoder = PulseVoicechatPlugin.getClientApi().createEncoder();
                }
                if (cachedEncoder != null) {
                    byte[] opusData = (byte[]) cachedEncoder.getClass().getMethod("encode", short[].class).invoke(cachedEncoder, (Object) frame);
                    if (opusData != null && opusData.length > 0) {
                        Class<?> clientManagerClass = Class.forName("de.maxhenkel.voicechat.voice.client.ClientManager");
                        Object client = clientManagerClass.getMethod("getClient").invoke(null);
                        if (client != null) {
                            Object conn = client.getClass().getMethod("getConnection").invoke(client);
                            if (conn != null) {
                                boolean isInit = (boolean) conn.getClass().getMethod("isInitialized").invoke(conn);
                                if (isInit) {
                                    Class<?> micPacketClass = Class.forName("de.maxhenkel.voicechat.voice.common.MicPacket");
                                    Object micPacket = micPacketClass.getConstructor(byte[].class, boolean.class, long.class)
                                            .newInstance(opusData, false, sequenceNumber.getAndIncrement());

                                    Class<?> netMsgClass = Class.forName("de.maxhenkel.voicechat.voice.common.NetworkMessage");
                                    Object netMsg = netMsgClass.getConstructor(Class.forName("de.maxhenkel.voicechat.voice.common.Packet"))
                                            .newInstance(micPacket);

                                    conn.getClass().getMethod("sendToServer", netMsgClass).invoke(conn, netMsg);
                                    isStreaming.set(true);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 2. Mirror to Virtual Audio Cable if open
        if (virtualMicLine == null) {
            initVirtualMicLine();
        }
        if (virtualMicLine != null && virtualMicLine.isOpen()) {
            byte[] frameBytes = new byte[FRAME_BYTES];
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                frameBytes[i * 2] = (byte) (frame[i] & 0xFF);
                frameBytes[i * 2 + 1] = (byte) ((frame[i] >> 8) & 0xFF);
            }
            try {
                virtualMicLine.write(frameBytes, 0, frameBytes.length);
            } catch (Throwable ignored) {}
        }
    }

    private void initVirtualMicLine() {
        try {
            AudioFormat format = new AudioFormat(TARGET_SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

            for (Mixer.Info mInfo : AudioSystem.getMixerInfo()) {
                String name = mInfo.getName().toLowerCase();
                if (name.contains("cable") || name.contains("voicemeeter") || name.contains("virtual") || name.contains("line in")) {
                    Mixer m = AudioSystem.getMixer(mInfo);
                    if (m.isLineSupported(info)) {
                        virtualMicLine = (SourceDataLine) m.getLine(info);
                        virtualMicLine.open(format, 19200);
                        virtualMicLine.start();
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public void clearBuffer() {
        pcmQueue.clear();
    }
}
