package com.slowbag.pulse.client.audio;

import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.AudioDeviceBase;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

public class VisualizerAudioDevice extends AudioDeviceBase {

    private SourceDataLine line = null;
    private byte[] byteBuf = new byte[16384];
    private volatile AudioFormat currentDirectFormat = null;

    private volatile float volume = 0.75f;
    private volatile boolean muted = false;
    private final float[] bandLevels = new float[16];
    private volatile float peakLevel = 0.0f;

    private volatile boolean prebuffering = true;
    private volatile int prebufferBytes = 0;
    private static final int PREBUFFER_BYTES_TARGET = 16384; // ~0.09s instant start cushion without latency

    public void setMuted(boolean muted) {
        this.muted = muted;
        updateHardwareVolume();
    }

    public boolean isMuted() {
        return muted;
    }

    public void setVolume(float volume) {
        this.volume = Math.max(0.0f, Math.min(1.0f, volume));
        updateHardwareVolume();
    }

    private void updateHardwareVolume() {
        if (line != null && line.isOpen()) {
            try {
                if (line.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
                    float min = gain.getMinimum();
                    float max = gain.getMaximum();
                    float effVol = muted ? 0.0f : volume;
                    if (effVol <= 0.001f) {
                        gain.setValue(min);
                    } else {
                        float dB = (float) (Math.log10(effVol) * 20.0);
                        gain.setValue(Math.max(min, Math.min(max, dB)));
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public float getVolume() {
        return volume;
    }

    public float[] getBandLevels() {
        return bandLevels;
    }

    public float getPeakLevel() {
        return peakLevel;
    }

    @Override
    protected void openImpl() throws JavaLayerException {
        // Line initialization is deferred to writeImpl so dec.getOutputFrequency() is known
    }

    public synchronized void openDirect(AudioFormat format) {
        closeImpl();
        this.currentDirectFormat = format;
        try {
            prebuffering = true;
            prebufferBytes = 0;
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            int bufferSize = Math.max(24576, (int) (format.getSampleRate() * format.getFrameSize() * 0.14));

            SourceDataLine openedLine = null;
            Exception lastEx = null;
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    openedLine = SettingsManager.openLineForDevice(info);
                    try {
                        openedLine.open(format, bufferSize);
                    } catch (Throwable fallback) {
                        openedLine.open(format);
                    }
                    lastEx = null;
                    break;
                } catch (Exception e) {
                    lastEx = e;
                    if (openedLine != null) {
                        try { openedLine.close(); } catch (Throwable ignored) {}
                        openedLine = null;
                    }
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (openedLine != null) {
                line = openedLine;
                updateHardwareVolume();
                System.out.println("[Music Player] Direct audio line opened (" + line.getBufferSize() + " byte cushion) on: " + SettingsManager.getInstance().getOutputDevice());
            } else if (lastEx != null) {
                System.err.println("[Music Player] Failed to open direct audio line after retries: " + lastEx.getMessage());
            }
        } catch (Exception e) {
            System.err.println("[Music Player] Failed to open direct audio line: " + e.getMessage());
        }
    }

    public synchronized void drainDirect() {
        if (line != null && line.isOpen() && line.isActive()) {
            try {
                line.drain();
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void flushDirect() {
        if (line != null && line.isOpen()) {
            try {
                line.flush();
                prebuffering = true;
                prebufferBytes = 0;
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void startDirectIfPrebuffering() {
        if (line != null && line.isOpen() && !line.isActive()) {
            try {
                line.start();
            } catch (Throwable ignored) {}
        }
        prebuffering = false;
    }

    public synchronized void pause() {
        if (line != null && line.isOpen()) {
            try {
                line.stop();
            } catch (Throwable ignored) {}
        }
        resetLevels();
    }

    public synchronized void resume() {
        if (line != null && line.isOpen()) {
            try {
                line.start();
            } catch (Throwable ignored) {}
        }
    }

    public void resetLevels() {
        java.util.Arrays.fill(bandLevels, 0.0f);
        this.peakLevel = 0.0f;
    }

    public void writeDirectPcm(byte[] buffer, int offset, int length) {
        if (length <= 0) return;

        // 1. Analyze 16-band energy & peak for visualizer and HUD beat-reactivity
        calculateVisualizerEnergy(buffer, offset, length);

        // Forward to Simple Voice Chat microphone streamer if enabled
        if (SettingsManager.getInstance().isVoiceChatOutput()) {
            int rate = currentDirectFormat != null ? (int) currentDirectFormat.getSampleRate() : 44100;
            int ch = currentDirectFormat != null ? currentDirectFormat.getChannels() : 2;
            SimpleVoiceChatBridge.getInstance().injectAudio(buffer, offset, length, rate, ch);
        }

        if (line == null || !line.isOpen()) return;

        if (muted) {
            byte[] silence = new byte[length];
            try {
                line.write(silence, 0, length);
            } catch (Throwable ignored) {}
            return;
        }

        // Never allow line.write to deadlock if prebuffer reaches near buffer capacity
        if (prebuffering && (prebufferBytes + length >= line.getBufferSize() - 16384)) {
            try {
                line.start();
            } catch (Throwable ignored) {}
            prebuffering = false;
        }

        boolean useSoftwareGain = !line.isControlSupported(FloatControl.Type.MASTER_GAIN);
        float softGain = useSoftwareGain ? (volume * volume) : 1.0f;

        byte[] pcmToWrite = buffer;
        int pcmOffset = offset;
        boolean mono = SettingsManager.getInstance().isMonoAudio();

        if (mono && length >= 4) {
            byte[] monoBuf = new byte[length];
            for (int i = 0; i < length - 3; i += 4) {
                int idx = offset + i;
                short left = (short) ((buffer[idx] & 0xFF) | (buffer[idx + 1] << 8));
                short right = (short) ((buffer[idx + 2] & 0xFF) | (buffer[idx + 3] << 8));
                short avg = (short) ((left + right) / 2);
                monoBuf[i] = (byte) (avg & 0xFF);
                monoBuf[i + 1] = (byte) ((avg >> 8) & 0xFF);
                monoBuf[i + 2] = (byte) (avg & 0xFF);
                monoBuf[i + 3] = (byte) ((avg >> 8) & 0xFF);
            }
            pcmToWrite = monoBuf;
            pcmOffset = 0;
        }

        if (useSoftwareGain && softGain < 0.999f) {
            byte[] modBuf = new byte[length];
            int samplesCount = length / 2;
            for (int i = 0; i < samplesCount; i++) {
                int idx = pcmOffset + i * 2;
                short s = (short) ((pcmToWrite[idx] & 0xFF) | (pcmToWrite[idx + 1] << 8));
                int val = Math.round(s * softGain);
                val = Math.max(-32768, Math.min(32767, val));
                modBuf[i * 2] = (byte) (val & 0xFF);
                modBuf[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
            }
            line.write(modBuf, 0, length);
        } else {
            line.write(pcmToWrite, pcmOffset, length);
        }

        if (prebuffering) {
            prebufferBytes += length;
            int startThreshold = Math.min(PREBUFFER_BYTES_TARGET, Math.max(16384, line.getBufferSize() / 3));
            if (prebufferBytes >= startThreshold) {
                try {
                    line.start();
                } catch (Throwable ignored) {}
                prebuffering = false;
            }
        }
    }

    private void calculateVisualizerEnergy(byte[] buffer, int offset, int length) {
        int samplesCount = length / 2;
        if (samplesCount <= 0) return;

        // Fast frequency-domain energy estimation across 16 responsive bands:
        // Bands 0-3: Sub-bass & Bass kick (strongest punch)
        // Bands 4-8: Low mids & Vocals / rhythm
        // Bands 9-12: High mids & Snare / brass
        // Bands 13-15: Treble, Cymbals & Hi-hats
        float currentPeak = 0.0f;
        int step = Math.max(1, samplesCount / 128); // downsample for ultra-fast calculation
        int evaluatedSamples = samplesCount / step;

        for (int b = 0; b < 16; b++) {
            // Target frequency center for this band
            double freqTarget = 40.0 * Math.pow(16000.0 / 40.0, (double) b / 15.0);
            double omega = 2.0 * Math.PI * freqTarget / 44100.0;
            double cosW = Math.cos(omega * step);
            double sinW = Math.sin(omega * step);

            double real = 0.0;
            double imag = 0.0;
            double curCos = 1.0;
            double curSin = 0.0;
            long rawSumSq = 0;
            int count = 0;

            for (int s = 0; s < evaluatedSamples; s++) {
                int sampleIdx = s * step;
                int byteIdx = offset + sampleIdx * 2;
                if (byteIdx + 1 < offset + length) {
                    short val = (short) ((buffer[byteIdx] & 0xFF) | (buffer[byteIdx + 1] << 8));
                    rawSumSq += (long) val * val;
                    real += val * curCos;
                    imag += val * curSin;
                    count++;

                    // Rotate cosine / sine
                    double nextCos = curCos * cosW - curSin * sinW;
                    double nextSin = curSin * cosW + curCos * sinW;
                    curCos = nextCos;
                    curSin = nextSin;
                }
            }

            float bandEnergy = 0.0f;
            if (count > 0) {
                double mag = Math.sqrt(real * real + imag * imag) / (count * 16384.0);
                double rms = Math.sqrt((double) rawSumSq / count) / 32768.0;
                // Combine frequency magnitude with base RMS + logarithmic perceptual curve
                double combined = (mag * 2.8) + (rms * 0.7);
                bandEnergy = (float) Math.min(1.0, Math.pow(combined * 3.5, 0.75));
            }

            float prev = bandLevels[b];
            if (bandEnergy > prev) {
                // Snappy, energetic attack so bars jump instantly to beats
                bandLevels[b] = prev * 0.15f + bandEnergy * 0.85f;
            } else {
                // Smooth, bouncy decay
                bandLevels[b] = prev * 0.78f;
            }

            if (bandLevels[b] > currentPeak) {
                currentPeak = bandLevels[b];
            }
        }
        this.peakLevel = currentPeak;
    }

    private void ensureLineOpen() throws JavaLayerException {
        if (line != null && line.isOpen()) return;

        Decoder dec = getDecoder();
        int sampleRate = (dec != null && dec.getOutputFrequency() > 0) ? dec.getOutputFrequency() : 44100;
        int channels = (dec != null && dec.getOutputChannels() > 0) ? dec.getOutputChannels() : 2;

        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
        );

        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        int bufferSize = Math.max(24576, (int) (format.getSampleRate() * format.getFrameSize() * 0.14));

        SourceDataLine openedLine = null;
        Exception lastEx = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                openedLine = SettingsManager.openLineForDevice(info);
                try {
                    openedLine.open(format, bufferSize);
                } catch (Throwable fallback) {
                    openedLine.open(format);
                }
                lastEx = null;
                break;
            } catch (Exception e) {
                lastEx = e;
                if (openedLine != null) {
                    try { openedLine.close(); } catch (Throwable ignored) {}
                    openedLine = null;
                }
                try {
                    Thread.sleep(40);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (openedLine != null) {
            line = openedLine;
            prebuffering = true;
            prebufferBytes = 0;
            updateHardwareVolume();
        } else {
            throw new JavaLayerException("Failed to open audio line after retries: " + (lastEx != null ? lastEx.getMessage() : "unavailable"), lastEx);
        }
    }

    @Override
    protected void writeImpl(short[] samples, int offs, int len) throws JavaLayerException {
        if (len <= 0) return;

        // Forward to Simple Voice Chat microphone streamer if enabled
        if (SettingsManager.getInstance().isVoiceChatOutput()) {
            Decoder dec = getDecoder();
            int rate = (dec != null && dec.getOutputFrequency() > 0) ? dec.getOutputFrequency() : 44100;
            int ch = (dec != null && dec.getOutputChannels() > 0) ? dec.getOutputChannels() : 2;
            SimpleVoiceChatBridge.getInstance().injectAudio(samples, offs, len, rate, ch);
        }

        ensureLineOpen();
        if (line == null || !line.isOpen()) return;

        // 1. Analyze sample energy across 16 bands for visualizer
        int bucketSize = Math.max(1, len / 16);
        float currentPeak = 0.0f;

        for (int b = 0; b < 16; b++) {
            int start = offs + b * bucketSize;
            int end = Math.min(offs + len, start + bucketSize);
            long sumSquare = 0;
            int count = 0;

            for (int i = start; i < end; i++) {
                short s = samples[i];
                sumSquare += (long) s * s;
                count++;
            }

            float rms = 0.0f;
            if (count > 0) {
                rms = (float) Math.sqrt((double) sumSquare / count) / 32768.0f;
            }

            float prev = bandLevels[b];
            if (rms > prev) {
                bandLevels[b] = prev * 0.25f + rms * 0.75f;
            } else {
                bandLevels[b] = prev * 0.88f;
            }

            if (bandLevels[b] > currentPeak) {
                currentPeak = bandLevels[b];
            }
        }
        this.peakLevel = currentPeak;

        if (muted) {
            int byteCount = len * 2;
            byte[] silence = new byte[byteCount];
            try {
                line.write(silence, 0, byteCount);
            } catch (Throwable ignored) {}
            return;
        }

        // 2. Prepare byte buffer (little-endian) with software gain & mono support
        int byteCount = len * 2;
        if (byteBuf.length < byteCount) {
            byteBuf = new byte[byteCount];
        }

        boolean useSoftwareGain = !line.isControlSupported(FloatControl.Type.MASTER_GAIN);
        float softGain = useSoftwareGain ? (volume * volume) : 1.0f;
        boolean mono = SettingsManager.getInstance().isMonoAudio();

        int byteIndex = 0;
        if (mono && len >= 2) {
            for (int i = offs; i < offs + len - 1; i += 2) {
                int left = samples[i];
                int right = samples[i + 1];
                int avg = (left + right) / 2;
                if (useSoftwareGain && softGain < 0.999f) {
                    avg = Math.round(avg * softGain);
                }
                avg = Math.max(-32768, Math.min(32767, avg));
                byte b0 = (byte) (avg & 0xFF);
                byte b1 = (byte) ((avg >> 8) & 0xFF);
                byteBuf[byteIndex++] = b0;
                byteBuf[byteIndex++] = b1;
                byteBuf[byteIndex++] = b0;
                byteBuf[byteIndex++] = b1;
            }
        } else {
            for (int i = offs; i < offs + len; i++) {
                int val = samples[i];
                if (useSoftwareGain && softGain < 0.999f) {
                    val = Math.round(val * softGain);
                }
                val = Math.max(-32768, Math.min(32767, val));

                byteBuf[byteIndex++] = (byte) (val & 0xFF);
                byteBuf[byteIndex++] = (byte) ((val >> 8) & 0xFF);
            }
        }

        // 3. Output to audio hardware safely with pre-buffer cushion
        try {
            line.write(byteBuf, 0, byteCount);
        } catch (Throwable ignored) {}

        if (prebuffering) {
            prebufferBytes += byteCount;
            if (prebufferBytes >= PREBUFFER_BYTES_TARGET || (line.isOpen() && prebufferBytes >= line.getBufferSize() - 16384)) {
                try {
                    line.start();
                } catch (Throwable ignored) {}
                prebuffering = false;
            }
        }
    }

    @Override
    protected void flushImpl() {
        if (line != null && line.isOpen()) {
            line.flush();
            prebuffering = true;
            prebufferBytes = 0;
        }
    }

    @Override
    protected synchronized void closeImpl() {
        if (line != null) {
            try {
                prebuffering = false;
                line.stop();
                line.flush();
                line.close();
            } catch (Throwable ignored) {}
            line = null;
        }
        resetLevels();
    }

    public int getPosition() {
        if (line != null && line.isOpen()) {
            return (int) (line.getMicrosecondPosition() / 1000);
        }
        return 0;
    }
}
