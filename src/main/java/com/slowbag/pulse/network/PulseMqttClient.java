package com.slowbag.pulse.network;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

/**
 * Ultra-lightweight, pure-Java zero-dependency MQTT 3.1.1 client.
 * Provides real-time, low-latency, bidirectional publish-subscribe communication
 * across Minecraft players over public brokers (HiveMQ and EMQX fallback).
 */
public class PulseMqttClient {

    private static final String[] BROKERS = {"broker.hivemq.com", "broker.emqx.io"};
    private static final int PORT = 1883;

    private static final PulseMqttClient INSTANCE = new PulseMqttClient();
    public static PulseMqttClient getInstance() {
        return INSTANCE;
    }

    private final String clientId = "pulse_" + UUID.randomUUID().toString().substring(0, 8);
    private final Set<String> subscribedTopics = ConcurrentHashMap.newKeySet();
    private final List<BiConsumer<String, String>> messageListeners = new CopyOnWriteArrayList<>();

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Pulse-MQTT-Thread");
        t.setDaemon(true);
        return t;
    });

    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile InputStream in;
    private volatile boolean connected = false;
    private volatile boolean running = true;
    private int currentBrokerIndex = 0;
    private int nextPacketId = 1;

    private PulseMqttClient() {
        startConnectionLoop();
        startPingLoop();
    }

    public void addListener(BiConsumer<String, String> listener) {
        messageListeners.add(listener);
    }

    public synchronized void subscribe(String topic) {
        if (topic == null || topic.isBlank()) return;
        subscribedTopics.add(topic);
        if (connected) {
            sendSubscribe(topic);
        }
    }

    public synchronized void unsubscribe(String topic) {
        if (topic == null) return;
        subscribedTopics.remove(topic);
    }

    public void publish(String topic, String message) {
        if (topic == null || message == null) return;
        executor.submit(() -> {
            try {
                if (!connected) return;
                byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
                byte[] payloadBytes = message.getBytes(StandardCharsets.UTF_8);
                int remLen = 2 + topicBytes.length + payloadBytes.length;

                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                buf.write(0x30); // PUBLISH QoS 0
                writeVariableLength(buf, remLen);
                buf.write((topicBytes.length >> 8) & 0xFF);
                buf.write(topicBytes.length & 0xFF);
                buf.write(topicBytes);
                buf.write(payloadBytes);

                synchronized (this) {
                    if (out != null) {
                        out.write(buf.toByteArray());
                        out.flush();
                    }
                }
            } catch (Throwable t) {
                closeConnection();
            }
        });
    }

    private void startConnectionLoop() {
        executor.submit(() -> {
            while (running) {
                if (!connected) {
                    tryConnect();
                }
                try {
                    Thread.sleep(connected ? 3000 : 5000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        });
    }

    private void tryConnect() {
        String broker = BROKERS[currentBrokerIndex % BROKERS.length];
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(broker, PORT), 4000);
            s.setSoTimeout(35000);

            OutputStream o = s.getOutputStream();
            InputStream i = s.getInputStream();

            // Send CONNECT packet
            byte[] idBytes = clientId.getBytes(StandardCharsets.UTF_8);
            int remLen = 10 + 2 + idBytes.length;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(0x10); // CONNECT
            writeVariableLength(buf, remLen);
            // Protocol Name
            buf.write(0x00);
            buf.write(0x04);
            buf.write('M');
            buf.write('Q');
            buf.write('T');
            buf.write('T');
            // Protocol Level
            buf.write(0x04);
            // Connect Flags (Clean Session = 0x02)
            buf.write(0x02);
            // Keep Alive (60s)
            buf.write(0x00);
            buf.write(0x3C);
            // Client ID
            buf.write((idBytes.length >> 8) & 0xFF);
            buf.write(idBytes.length & 0xFF);
            buf.write(idBytes);

            o.write(buf.toByteArray());
            o.flush();

            // Read CONNACK
            int b1 = i.read();
            int b2 = i.read();
            if (b1 == 0x20 && b2 == 0x02) {
                i.read(); // ack flags
                int rc = i.read(); // return code
                if (rc == 0) {
                    this.socket = s;
                    this.out = o;
                    this.in = i;
                    this.connected = true;

                    // Re-subscribe to all active topics
                    for (String t : subscribedTopics) {
                        sendSubscribe(t);
                    }

                    // Start reader loop on this socket
                    startReaderLoop(s, i);
                    return;
                }
            }
            s.close();
        } catch (Throwable t) {
            currentBrokerIndex++;
        }
    }

    private synchronized void sendSubscribe(String topic) {
        try {
            if (out == null) return;
            byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
            int pId = nextPacketId++;
            int remLen = 2 + 2 + topicBytes.length + 1; // packetId(2) + topicLen(2) + topic + qos(1)

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(0x82); // SUBSCRIBE QoS 1
            writeVariableLength(buf, remLen);
            buf.write((pId >> 8) & 0xFF);
            buf.write(pId & 0xFF);
            buf.write((topicBytes.length >> 8) & 0xFF);
            buf.write(topicBytes.length & 0xFF);
            buf.write(topicBytes);
            buf.write(0x00); // requested QoS 0

            out.write(buf.toByteArray());
            out.flush();
        } catch (Throwable t) {
            closeConnection();
        }
    }

    private void startReaderLoop(Socket s, InputStream i) {
        executor.submit(() -> {
            try {
                while (connected && !s.isClosed()) {
                    int header = i.read();
                    if (header == -1) break;

                    int type = (header >> 4) & 0x0F;
                    int remLen = readVariableLength(i);
                    byte[] data = new byte[remLen];
                    int readTotal = 0;
                    while (readTotal < remLen) {
                        int r = i.read(data, readTotal, remLen - readTotal);
                        if (r == -1) break;
                        readTotal += r;
                    }

                    if (type == 3) { // PUBLISH
                        if (remLen >= 2) {
                            int tLen = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
                            if (remLen >= 2 + tLen) {
                                String topic = new String(data, 2, tLen, StandardCharsets.UTF_8);
                                String payload = new String(data, 2 + tLen, remLen - (2 + tLen), StandardCharsets.UTF_8);
                                for (BiConsumer<String, String> listener : messageListeners) {
                                    try {
                                        listener.accept(topic, payload);
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                closeConnection();
            }
        });
    }

    private void startPingLoop() {
        executor.submit(() -> {
            while (running) {
                try {
                    Thread.sleep(25000);
                    if (connected && out != null) {
                        synchronized (this) {
                            if (out != null) {
                                out.write(new byte[]{(byte) 0xC0, 0x00}); // PINGREQ
                                out.flush();
                            }
                        }
                    }
                } catch (InterruptedException ignored) {
                    break;
                } catch (Throwable t) {
                    closeConnection();
                }
            }
        });
    }

    private synchronized void closeConnection() {
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (Throwable ignored) {}
        socket = null;
        out = null;
        in = null;
    }

    private static void writeVariableLength(ByteArrayOutputStream out, int length) {
        do {
            int digit = length % 128;
            length /= 128;
            if (length > 0) {
                digit |= 0x80;
            }
            out.write(digit);
        } while (length > 0);
    }

    private static int readVariableLength(InputStream in) throws IOException {
        int multiplier = 1;
        int value = 0;
        int digit;
        do {
            digit = in.read();
            if (digit == -1) throw new EOFException("MQTT stream closed prematurely");
            value += (digit & 127) * multiplier;
            multiplier *= 128;
        } while ((digit & 128) != 0);
        return value;
    }

    public boolean isConnected() {
        return connected;
    }
}
