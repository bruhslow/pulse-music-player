package com.slowbag.pulse.client.audio;

import com.slowbag.pulse.MusicPlayerMod;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class AacDashAudioStream {

    public interface PlaybackController {
        boolean isPlaying();
        boolean isPaused();
        int getSessionId();
    }

    public static void play(InputStream input, VisualizerAudioDevice device, PlaybackController controller, int targetSessionId, long seekSeconds, File cacheFile) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(input, 1024 * 1024));
        FileOutputStream cacheOut = null;
        if (cacheFile != null) {
            try {
                File parent = cacheFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                cacheOut = new FileOutputStream(cacheFile);
            } catch (Throwable t) {
                MusicPlayerMod.LOGGER.debug("[AAC] Could not create cache file: " + t.getMessage());
            }
        }

        byte[] asc = new byte[]{0x12, 0x10}; // 44.1kHz Stereo AAC-LC default
        Decoder decoder = null;
        SampleBuffer sampleBuffer = new SampleBuffer();
        boolean deviceOpened = false;

        long currentStreamOffset = 0;
        int samplesToSkip = (int) (seekSeconds * (44100.0 / 1024.0));

        try {
            while (controller.isPlaying() && controller.getSessionId() == targetSessionId) {
                // Read 4-byte atom size
                int size;
                try {
                    size = in.readInt();
                } catch (Exception eof) {
                    break; // Reached end of stream
                }
                currentStreamOffset += 4;
                if (cacheOut != null) {
                    cacheOut.write((size >>> 24) & 0xFF);
                    cacheOut.write((size >>> 16) & 0xFF);
                    cacheOut.write((size >>> 8) & 0xFF);
                    cacheOut.write(size & 0xFF);
                }

                if (size < 8) break;

                // Read 4-byte atom type
                byte[] typeBytes = new byte[4];
                in.readFully(typeBytes);
                currentStreamOffset += 4;
                if (cacheOut != null) cacheOut.write(typeBytes);

                String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
                int payloadSize = size - 8;

                if ("ftyp".equals(type) || "sidx".equals(type)) {
                    copyOrSkip(in, payloadSize, cacheOut);
                    currentStreamOffset += payloadSize;
                } else if ("moov".equals(type)) {
                    byte[] moovData = new byte[payloadSize];
                    in.readFully(moovData);
                    currentStreamOffset += payloadSize;
                    if (cacheOut != null) cacheOut.write(moovData);

                    // Check if this is a Progressive MP4 (itag 18) with a 'soun' audio track
                    AudioTrakInfo progAudio = parseProgressiveAudio(moovData);
                    if (progAudio != null && progAudio.sampleSizes != null && progAudio.sampleSizes.length > 0 && progAudio.chunkOffsets != null) {
                        System.out.println("[Music Player] Detected Progressive MP4 with " + progAudio.sampleSizes.length + " AAC audio frames.");
                        playProgressiveMp4(in, device, controller, targetSessionId, seekSeconds, cacheOut, progAudio, currentStreamOffset);
                        return;
                    }
                    System.out.println("[Music Player] Not a progressive MP4 track or progressive parsing fell back; trying DASH moof atoms...");

                    // Fragmented DASH fallback: extract ASC and wait for moof
                    byte[] foundAsc = extractAscFromMoov(moovData);
                    if (foundAsc != null) {
                        asc = foundAsc;
                    }
                    decoder = new Decoder(asc);
                } else if ("moof".equals(type)) {
                    byte[] moofData = new byte[payloadSize];
                    in.readFully(moofData);
                    currentStreamOffset += payloadSize;
                    if (cacheOut != null) cacheOut.write(moofData);

                    // Parse 'trun' box inside 'moof'
                    TrunInfo trun = parseTrun(moofData);

                    // Expect 'mdat' immediately after 'moof'
                    int mdatSize = in.readInt();
                    currentStreamOffset += 4;
                    if (cacheOut != null) {
                        cacheOut.write((mdatSize >>> 24) & 0xFF);
                        cacheOut.write((mdatSize >>> 16) & 0xFF);
                        cacheOut.write((mdatSize >>> 8) & 0xFF);
                        cacheOut.write(mdatSize & 0xFF);
                    }
                    byte[] mdatType = new byte[4];
                    in.readFully(mdatType);
                    currentStreamOffset += 4;
                    if (cacheOut != null) cacheOut.write(mdatType);

                    int mdatPayload = mdatSize - 8;

                    if (decoder == null) {
                        decoder = new Decoder(asc);
                    }

                    if (trun == null || trun.sampleSizes == null || trun.sampleSizes.length == 0) {
                        copyOrSkip(in, mdatPayload, cacheOut);
                        currentStreamOffset += mdatPayload;
                        continue;
                    }

                    // Check if entire fragment can be skipped for seeking
                    if (samplesToSkip >= trun.sampleSizes.length) {
                        samplesToSkip -= trun.sampleSizes.length;
                        copyOrSkip(in, mdatPayload, cacheOut);
                        currentStreamOffset += mdatPayload;
                        continue;
                    }

                    for (int s = 0; s < trun.sampleSizes.length; s++) {
                        int sampleLen = trun.sampleSizes[s];
                        byte[] frame = new byte[sampleLen];
                        try {
                            in.readFully(frame);
                        } catch (Exception eof) {
                            break;
                        }
                        currentStreamOffset += sampleLen;
                        if (cacheOut != null) cacheOut.write(frame);

                        if (samplesToSkip > 0) {
                            samplesToSkip--;
                            continue;
                        }

                        while (controller.isPaused() && controller.isPlaying() && controller.getSessionId() == targetSessionId) {
                            Thread.sleep(30);
                        }
                        if (!controller.isPlaying() || controller.getSessionId() != targetSessionId) {
                            break;
                        }

                        try {
                            decoder.decodeFrame(frame, sampleBuffer);
                            sampleBuffer.setBigEndian(false);
                            byte[] pcm = sampleBuffer.getData();

                            if (!deviceOpened) {
                                int sampleRate = sampleBuffer.getSampleRate();
                                int channels = sampleBuffer.getChannels();
                                AudioFormat format = new AudioFormat(sampleRate > 0 ? sampleRate : 44100, 16, channels > 0 ? channels : 2, true, false);
                                System.out.println("[Music Player] DASH audio device opened: " + format);
                                device.openDirect(format);
                                deviceOpened = true;
                            }

                            if (pcm != null && pcm.length > 0) {
                                device.writeDirectPcm(pcm, 0, pcm.length);
                                AudioManager.getInstance().notifyAudioStarted(seekSeconds);
                            }
                        } catch (Throwable frameErr) {
                            // Do NOT re-instantiate decoder: resetting wipes out MDCT overlap-add history and causes severe audio crackles/shuddering
                        }
                    }
                } else {
                    // Unknown box, skip safely
                    if (payloadSize > 0) {
                        copyOrSkip(in, payloadSize, cacheOut);
                        currentStreamOffset += payloadSize;
                    }
                }
            }
        } finally {
            if (!controller.isPlaying() || controller.getSessionId() != targetSessionId) {
                device.flushDirect();
            } else {
                device.startDirectIfPrebuffering();
                device.drainDirect();
            }
            if (cacheOut != null) {
                try { cacheOut.flush(); cacheOut.close(); } catch (Throwable ignored) {}
            }
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    private static void playProgressiveMp4(DataInputStream in, VisualizerAudioDevice device, PlaybackController controller, int targetSessionId, long seekSeconds, FileOutputStream cacheOut, AudioTrakInfo info, long initialOffset) throws Exception {
        byte[] asc = info.asc != null ? info.asc : new byte[]{0x12, 0x10};
        Decoder decoder;
        try {
            decoder = new Decoder(asc);
        } catch (Throwable t) {
            decoder = new Decoder(new byte[]{0x12, 0x10});
        }
        SampleBuffer sampleBuffer = new SampleBuffer();
        boolean deviceOpened = false;

        long currentStreamOffset = initialOffset;
        int targetSample = (int) (seekSeconds * (44100.0 / 1024.0));
        int curSampleIdx = 0;
        int stscPtr = 0;

        for (int c = 0; c < info.chunkOffsets.length && controller.isPlaying() && controller.getSessionId() == targetSessionId; c++) {
            int chunkNum = c + 1;
            while (stscPtr + 1 < info.stsc.length && chunkNum >= info.stsc[stscPtr + 1][0]) {
                stscPtr++;
            }
            int spc = info.stsc[stscPtr][1];

            long targetOffset = info.chunkOffsets[c];
            long gap = targetOffset - currentStreamOffset;
            if (gap > 0) {
                try {
                    copyOrSkip(in, gap, null); // Skip video gap directly without blocking disk writes
                } catch (Exception eof) {
                    break;
                }
                currentStreamOffset += gap;
            }

            for (int s = 0; s < spc && controller.isPlaying() && controller.getSessionId() == targetSessionId; s++) {
                if (curSampleIdx >= info.sampleSizes.length) break;
                int frameSize = info.sampleSizes[curSampleIdx];
                byte[] frame = new byte[frameSize];
                try {
                    in.readFully(frame);
                } catch (Exception eof) {
                    break;
                }
                if (cacheOut != null) cacheOut.write(frame);
                currentStreamOffset += frameSize;

                if (curSampleIdx < targetSample) {
                    curSampleIdx++;
                    continue;
                }
                curSampleIdx++;

                while (controller.isPaused() && controller.isPlaying() && controller.getSessionId() == targetSessionId) {
                    Thread.sleep(30);
                }
                if (!controller.isPlaying() || controller.getSessionId() != targetSessionId) {
                    break;
                }

                try {
                    decoder.decodeFrame(frame, sampleBuffer);
                    sampleBuffer.setBigEndian(false);
                    byte[] pcm = sampleBuffer.getData();

                    if (!deviceOpened) {
                        int sampleRate = sampleBuffer.getSampleRate();
                        int channels = sampleBuffer.getChannels();
                        AudioFormat format = new AudioFormat(sampleRate > 0 ? sampleRate : 44100, 16, channels > 0 ? channels : 2, true, false);
                        System.out.println("[Music Player] Progressive audio device opened: " + format);
                        device.openDirect(format);
                        deviceOpened = true;
                    }

                    if (pcm != null && pcm.length > 0) {
                        device.writeDirectPcm(pcm, 0, pcm.length);
                        AudioManager.getInstance().notifyAudioStarted(seekSeconds);
                    }
                } catch (Throwable frameErr) {
                    // Do NOT re-instantiate decoder: resetting wipes out MDCT overlap-add history and causes severe audio crackles/shuddering
                }
            }
        }

        if (!controller.isPlaying() || controller.getSessionId() != targetSessionId) {
            device.flushDirect();
        } else {
            device.startDirectIfPrebuffering();
            device.drainDirect();
        }
    }

    private static class AudioTrakInfo {
        byte[] asc;
        int[] sampleSizes;
        long[] chunkOffsets;
        int[][] stsc;
    }

    private static AudioTrakInfo parseProgressiveAudio(byte[] moovData) {
        try {
            // Find 'soun' handler box inside moov
            int sounIdx = -1;
            for (int i = 0; i < moovData.length - 4; i++) {
                if (moovData[i] == 's' && moovData[i+1] == 'o' && moovData[i+2] == 'u' && moovData[i+3] == 'n') {
                    sounIdx = i;
                    break;
                }
            }
            if (sounIdx == -1) return null;

            // Find enclosing 'trak' box
            int trakPos = -1;
            for (int i = sounIdx; i >= 4; i--) {
                if (moovData[i] == 't' && moovData[i+1] == 'r' && moovData[i+2] == 'a' && moovData[i+3] == 'k') {
                    trakPos = i - 4;
                    break;
                }
            }
            if (trakPos == -1) return null;

            int trakSize = ((moovData[trakPos] & 0xFF) << 24) | ((moovData[trakPos+1] & 0xFF) << 16) | ((moovData[trakPos+2] & 0xFF) << 8) | (moovData[trakPos+3] & 0xFF);
            if (trakSize <= 0 || trakPos + trakSize > moovData.length) return null;

            byte[] trak = new byte[trakSize];
            System.arraycopy(moovData, trakPos, trak, 0, trakSize);

            // Find stsz (sample sizes)
            int stszIdx = -1;
            for (int i = 0; i < trak.length - 4; i++) {
                if (trak[i] == 's' && trak[i+1] == 't' && trak[i+2] == 's' && trak[i+3] == 'z') {
                    stszIdx = i - 4;
                    break;
                }
            }
            if (stszIdx == -1) return null;

            int sampleCount = ((trak[stszIdx+16] & 0xFF) << 24) | ((trak[stszIdx+17] & 0xFF) << 16) | ((trak[stszIdx+18] & 0xFF) << 8) | (trak[stszIdx+19] & 0xFF);
            if (sampleCount <= 0 || sampleCount > 5000000) return null;

            int[] sampleSizes = new int[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                int off = stszIdx + 20 + (i * 4);
                if (off + 4 > trak.length) break;
                sampleSizes[i] = ((trak[off] & 0xFF) << 24) | ((trak[off+1] & 0xFF) << 16) | ((trak[off+2] & 0xFF) << 8) | (trak[off+3] & 0xFF);
            }

            // Find stco or co64 (chunk offsets)
            int stcoIdx = -1;
            boolean isCo64 = false;
            for (int i = 0; i < trak.length - 4; i++) {
                if (trak[i] == 's' && trak[i+1] == 't' && trak[i+2] == 'c' && trak[i+3] == 'o') {
                    stcoIdx = i - 4;
                    break;
                }
            }
            if (stcoIdx == -1) {
                for (int i = 0; i < trak.length - 4; i++) {
                    if (trak[i] == 'c' && trak[i+1] == 'o' && trak[i+2] == '6' && trak[i+3] == '4') {
                        stcoIdx = i - 4;
                        isCo64 = true;
                        break;
                    }
                }
            }
            if (stcoIdx == -1) return null;

            int chunkCount;
            long[] chunkOffsets;
            if (!isCo64) {
                chunkCount = ((trak[stcoIdx+12] & 0xFF) << 24) | ((trak[stcoIdx+13] & 0xFF) << 16) | ((trak[stcoIdx+14] & 0xFF) << 8) | (trak[stcoIdx+15] & 0xFF);
                if (chunkCount <= 0 || chunkCount > 2000000) return null;

                chunkOffsets = new long[chunkCount];
                for (int i = 0; i < chunkCount; i++) {
                    int off = stcoIdx + 16 + (i * 4);
                    if (off + 4 > trak.length) break;
                    chunkOffsets[i] = (((long)(trak[off] & 0xFF)) << 24) | (((long)(trak[off+1] & 0xFF)) << 16) | (((long)(trak[off+2] & 0xFF)) << 8) | ((long)(trak[off+3] & 0xFF));
                }
            } else {
                chunkCount = ((trak[stcoIdx+12] & 0xFF) << 24) | ((trak[stcoIdx+13] & 0xFF) << 16) | ((trak[stcoIdx+14] & 0xFF) << 8) | (trak[stcoIdx+15] & 0xFF);
                if (chunkCount <= 0 || chunkCount > 2000000) return null;

                chunkOffsets = new long[chunkCount];
                for (int i = 0; i < chunkCount; i++) {
                    int off = stcoIdx + 16 + (i * 8);
                    if (off + 8 > trak.length) break;
                    chunkOffsets[i] = (((long)(trak[off] & 0xFF)) << 56) | (((long)(trak[off+1] & 0xFF)) << 48) | (((long)(trak[off+2] & 0xFF)) << 40) | (((long)(trak[off+3] & 0xFF)) << 32)
                                    | (((long)(trak[off+4] & 0xFF)) << 24) | (((long)(trak[off+5] & 0xFF)) << 16) | (((long)(trak[off+6] & 0xFF)) << 8) | ((long)(trak[off+7] & 0xFF));
                }
            }

            // Find stsc (samples to chunk table)
            int stscIdx = -1;
            for (int i = 0; i < trak.length - 4; i++) {
                if (trak[i] == 's' && trak[i+1] == 't' && trak[i+2] == 's' && trak[i+3] == 'c') {
                    stscIdx = i - 4;
                    break;
                }
            }
            if (stscIdx == -1) return null;

            int stscCount = ((trak[stscIdx+12] & 0xFF) << 24) | ((trak[stscIdx+13] & 0xFF) << 16) | ((trak[stscIdx+14] & 0xFF) << 8) | (trak[stscIdx+15] & 0xFF);
            if (stscCount <= 0 || stscCount > 200000) return null;

            int[][] stsc = new int[stscCount][2];
            for (int i = 0; i < stscCount; i++) {
                int off = stscIdx + 16 + (i * 12);
                if (off + 8 > trak.length) break;
                stsc[i][0] = ((trak[off] & 0xFF) << 24) | ((trak[off+1] & 0xFF) << 16) | ((trak[off+2] & 0xFF) << 8) | (trak[off+3] & 0xFF);
                stsc[i][1] = ((trak[off+4] & 0xFF) << 24) | ((trak[off+5] & 0xFF) << 16) | ((trak[off+6] & 0xFF) << 8) | (trak[off+7] & 0xFF);
            }

            AudioTrakInfo info = new AudioTrakInfo();
            info.asc = extractAscFromMoov(trak);
            info.sampleSizes = sampleSizes;
            info.chunkOffsets = chunkOffsets;
            info.stsc = stsc;
            return info;
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[AAC] Progressive MP4 parse error: " + t.getMessage());
        }
        return null;
    }

    private static byte[] extractAscFromMoov(byte[] data) {
        try {
            for (int i = 0; i < data.length - 8; i++) {
                if (data[i] == 'e' && data[i+1] == 's' && data[i+2] == 'd' && data[i+3] == 's') {
                    int maxScan = Math.min(data.length, i + 64);
                    for (int j = i + 4; j < maxScan - 3; j++) {
                        if (data[j] == 0x05) {
                            int len = data[j+1] & 0xFF;
                            if (len >= 2 && j + 2 + len <= data.length) {
                                int actualLen = len;
                                while (actualLen > 2 && data[j + 2 + actualLen - 1] == 0) {
                                    actualLen--;
                                }
                                if (actualLen > 5) actualLen = 2;
                                byte[] asc = new byte[actualLen];
                                System.arraycopy(data, j + 2, asc, 0, actualLen);
                                return asc;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static class TrunInfo {
        int[] sampleSizes;
    }

    private static TrunInfo parseTrun(byte[] moofData) {
        try {
            for (int i = 0; i < moofData.length - 16; i++) {
                if (moofData[i] == 't' && moofData[i+1] == 'r' && moofData[i+2] == 'u' && moofData[i+3] == 'n') {
                    int flags = ((moofData[i+5] & 0xFF) << 16) | ((moofData[i+6] & 0xFF) << 8) | (moofData[i+7] & 0xFF);
                    int sampleCount = ((moofData[i+8] & 0xFF) << 24) | ((moofData[i+9] & 0xFF) << 16) | ((moofData[i+10] & 0xFF) << 8) | (moofData[i+11] & 0xFF);

                    if (sampleCount <= 0 || sampleCount > 50000) return null;

                    int offset = i + 12;
                    if ((flags & 0x01) != 0) offset += 4;
                    if ((flags & 0x04) != 0) offset += 4;

                    boolean hasDuration = (flags & 0x0100) != 0;
                    boolean hasSize = (flags & 0x0200) != 0;
                    boolean hasFlags = (flags & 0x0400) != 0;
                    boolean hasCompTime = (flags & 0x0800) != 0;

                    int bytesPerEntry = (hasDuration ? 4 : 0) + (hasSize ? 4 : 0) + (hasFlags ? 4 : 0) + (hasCompTime ? 4 : 0);
                    if (bytesPerEntry == 0) bytesPerEntry = 4;

                    TrunInfo info = new TrunInfo();
                    info.sampleSizes = new int[sampleCount];

                    for (int s = 0; s < sampleCount; s++) {
                        int entryOffset = offset + (s * bytesPerEntry);
                        if (entryOffset + 4 > moofData.length) break;

                        int sizeOffset = entryOffset;
                        if (hasDuration) sizeOffset += 4;

                        int sampleSize = ((moofData[sizeOffset] & 0xFF) << 24) | ((moofData[sizeOffset+1] & 0xFF) << 16) | ((moofData[sizeOffset+2] & 0xFF) << 8) | (moofData[sizeOffset+3] & 0xFF);
                        info.sampleSizes[s] = sampleSize;
                    }
                    return info;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void copyOrSkip(DataInputStream in, long totalBytes, FileOutputStream cacheOut) throws IOException {
        if (cacheOut == null) {
            long remaining = totalBytes;
            while (remaining > 0) {
                long skipped = in.skip(remaining);
                if (skipped <= 0) {
                    byte[] buf = new byte[(int) Math.min(remaining, 16384)];
                    int r = in.read(buf);
                    if (r == -1) throw new java.io.EOFException("Unexpected EOF while skipping " + remaining + " bytes");
                    remaining -= r;
                } else {
                    remaining -= skipped;
                }
            }
            return;
        }
        byte[] buf = new byte[16384];
        long remaining = totalBytes;
        while (remaining > 0) {
            int toRead = (int) Math.min(remaining, buf.length);
            in.readFully(buf, 0, toRead);
            cacheOut.write(buf, 0, toRead);
            remaining -= toRead;
        }
    }
}
