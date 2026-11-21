package com.slowbag.pulse.client.audio;

import java.io.IOException;
import java.io.InputStream;

/**
 * High-capacity asynchronous circular jitter buffer stream with auto-reconnection.
 * Decouples network I/O from audio frame decoding to completely eliminate audio stuttering,
 * buffer underruns, and playback freezes on progressive MP4 streams.
 */
public class AsyncAudioBufferStream extends InputStream {

    @FunctionalInterface
    public interface StreamOpener {
        InputStream open(long byteOffset) throws Exception;
    }

    private static final int BUFFER_CAPACITY = 16 * 1024 * 1024; // 16 MB ring buffer (~2 to 3 minutes of progressive MP4 / ~15 mins AAC)
    private static final int READ_CHUNK_SIZE = 65536;            // 64 KB network read chunks

    private InputStream source;
    private final StreamOpener opener;
    private final byte[] ringBuffer = new byte[BUFFER_CAPACITY];
    private int writePos = 0;
    private int readPos = 0;
    private int availableBytes = 0;
    private long totalBytesDownloaded = 0;

    private volatile boolean sourceEof = false;
    private volatile boolean closed = false;
    private volatile IOException readerException = null;

    private final Thread readerThread;

    public AsyncAudioBufferStream(InputStream source) {
        this(source, null);
    }

    public AsyncAudioBufferStream(InputStream source, StreamOpener opener) {
        this.source = source;
        this.opener = opener;
        this.readerThread = new Thread(this::fillBufferLoop, "MusicPlayer-JitterBuffer");
        this.readerThread.setDaemon(true);
        this.readerThread.setPriority(Thread.NORM_PRIORITY);
        this.readerThread.start();

        // Pre-buffer initial audio frames so playback starts completely uninterrupted
        waitForInitialData();
    }

    private void fillBufferLoop() {
        byte[] chunk = new byte[READ_CHUNK_SIZE];
        while (!closed) {
            int space;
            synchronized (this) {
                space = BUFFER_CAPACITY - availableBytes;
                while (space <= chunk.length && !closed) {
                    try {
                        wait(150);
                    } catch (InterruptedException e) {
                        return;
                    }
                    space = BUFFER_CAPACITY - availableBytes;
                }
            }

            if (closed) break;

            int readCount = -1;
            try {
                readCount = source.read(chunk, 0, Math.min(chunk.length, space));
            } catch (IOException e) {
                if (closed) break;

                // Connection dropped, timed out, or reset by CDN/remote server
                boolean reconnected = false;
                if (opener != null) {
                    for (int attempt = 1; attempt <= 4 && !closed; attempt++) {
                        try {
                            try { source.close(); } catch (Throwable ignored) {}
                            Thread.sleep(attempt * 250L);
                            source = opener.open(totalBytesDownloaded);
                            reconnected = true;
                            System.out.println("[Music Player] Auto-reconnected audio stream at offset " + totalBytesDownloaded);
                            break;
                        } catch (Throwable retryEx) {
                            // Back off and try next attempt
                        }
                    }
                }

                if (reconnected) {
                    continue;
                }

                synchronized (this) {
                    if (!closed) {
                        readerException = e;
                        notifyAll();
                    }
                }
                break;
            }

            if (readCount == -1) {
                synchronized (this) {
                    sourceEof = true;
                    notifyAll();
                }
                break;
            }

            totalBytesDownloaded += readCount;

            synchronized (this) {
                int firstChunk = Math.min(readCount, BUFFER_CAPACITY - writePos);
                System.arraycopy(chunk, 0, ringBuffer, writePos, firstChunk);
                if (readCount > firstChunk) {
                    System.arraycopy(chunk, firstChunk, ringBuffer, 0, readCount - firstChunk);
                }
                writePos = (writePos + readCount) % BUFFER_CAPACITY;
                availableBytes += readCount;
                notifyAll();
            }
        }
    }

    private synchronized void waitForInitialData() {
        long start = System.currentTimeMillis();
        int preloadSec = SettingsManager.getInstance().getPreloadSeconds();
        // 48KB - 96KB initial data is plenty for MP4 headers/atoms and begins instant playback
        int targetPreload = Math.max(32768, Math.min(98304, preloadSec * 16 * 1024));
        long timeoutMs = Math.max(800, preloadSec * 200L);

        while (availableBytes < targetPreload && !sourceEof && !closed && readerException == null) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                break;
            }
            try {
                wait(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private synchronized void awaitBufferData(int minRequired) throws IOException {
        long start = System.currentTimeMillis();
        long timeoutMs = 8000;
        boolean announcedBuffering = false;

        while (availableBytes < minRequired && !closed && !sourceEof && readerException == null) {
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed > 200 && !announcedBuffering) {
                announcedBuffering = true;
                try {
                    AudioManager.getInstance().setBuffering(true);
                } catch (Throwable ignored) {}
            }
            if (elapsed > timeoutMs) {
                break;
            }
            try {
                wait(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        if (announcedBuffering) {
            try {
                AudioManager.getInstance().setBuffering(false);
            } catch (Throwable ignored) {}
        }

        if (readerException != null && availableBytes < minRequired) {
            String msg = readerException.getMessage();
            if (msg != null && (msg.contains("reset") || msg.contains("closed") || msg.contains("Premature") ||
                    msg.contains("end of file") || msg.contains("EOF") || msg.contains("timed out") || msg.contains("Timeout"))) {
                sourceEof = true;
                return;
            }
            throw readerException;
        }
    }

    @Override
    public synchronized int read() throws IOException {
        while (availableBytes == 0) {
            if (closed || sourceEof) return -1;
            if (readerException != null) {
                String msg = readerException.getMessage();
                if (msg != null && (msg.contains("reset") || msg.contains("closed") || msg.contains("Premature") ||
                        msg.contains("end of file") || msg.contains("EOF") || msg.contains("timed out") || msg.contains("Timeout"))) {
                    sourceEof = true;
                    return -1;
                }
                throw readerException;
            }
            awaitBufferData(1);
            if (availableBytes == 0 && (closed || sourceEof)) return -1;
        }

        int b = ringBuffer[readPos] & 0xFF;
        readPos = (readPos + 1) % BUFFER_CAPACITY;
        availableBytes--;
        notifyAll();
        return b;
    }

    @Override
    public synchronized int read(byte[] b, int off, int len) throws IOException {
        if (b == null) throw new NullPointerException();
        if (off < 0 || len < 0 || len > b.length - off) throw new IndexOutOfBoundsException();
        if (len == 0) return 0;

        while (availableBytes == 0) {
            if (closed || sourceEof) return -1;
            if (readerException != null) {
                String msg = readerException.getMessage();
                if (msg != null && (msg.contains("reset") || msg.contains("closed") || msg.contains("Premature") ||
                        msg.contains("end of file") || msg.contains("EOF") || msg.contains("timed out") || msg.contains("Timeout"))) {
                    sourceEof = true;
                    return -1;
                }
                throw readerException;
            }
            awaitBufferData(Math.min(len, 4096));
            if (availableBytes == 0 && (closed || sourceEof)) return -1;
        }

        int toRead = Math.min(len, availableBytes);
        int firstChunk = Math.min(toRead, BUFFER_CAPACITY - readPos);
        System.arraycopy(ringBuffer, readPos, b, off, firstChunk);
        if (toRead > firstChunk) {
            System.arraycopy(ringBuffer, 0, b, off + firstChunk, toRead - firstChunk);
        }
        readPos = (readPos + toRead) % BUFFER_CAPACITY;
        availableBytes -= toRead;
        notifyAll();
        return toRead;
    }

    @Override
    public synchronized long skip(long n) throws IOException {
        if (n <= 0) return 0;
        long totalSkipped = 0;
        while (totalSkipped < n) {
            while (availableBytes == 0) {
                if (closed || sourceEof) return totalSkipped;
                if (readerException != null) {
                    sourceEof = true;
                    return totalSkipped;
                }
                awaitBufferData(1);
                if (availableBytes == 0 && (closed || sourceEof)) return totalSkipped;
            }
            int toSkip = (int) Math.min(n - totalSkipped, availableBytes);
            readPos = (readPos + toSkip) % BUFFER_CAPACITY;
            availableBytes -= toSkip;
            totalSkipped += toSkip;
            notifyAll();
        }
        return totalSkipped;
    }

    @Override
    public synchronized int available() throws IOException {
        return availableBytes;
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        notifyAll();
        readerThread.interrupt();
        try {
            source.close();
        } catch (Throwable ignored) {}
    }
}
