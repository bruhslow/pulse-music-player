package com.slowbag.pulse.client.audio;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.concurrent.Executors;

/**
 * Embedded lightweight HTTP audio streaming server.
 * Allows host to stream local music files directly to room guests on the same LAN/VPN/direct connection.
 */
public class PulseLocalAudioServer {

    private static PulseLocalAudioServer INSTANCE;
    private HttpServer server;
    private int port = -1;
    private volatile File currentFile = null;

    public static synchronized PulseLocalAudioServer getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new PulseLocalAudioServer();
        }
        return INSTANCE;
    }

    private PulseLocalAudioServer() {
        startServer();
    }

    private void startServer() {
        try {
            int[] tryPorts = new int[]{28542, 28543, 28544, 28545, 0};
            for (int p : tryPorts) {
                try {
                    server = HttpServer.create(new InetSocketAddress(p), 0);
                    server.setExecutor(Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "PulseLocalAudioServer-Worker");
                        t.setDaemon(true);
                        return t;
                    }));
                    port = server.getAddress().getPort();
                    server.createContext("/pulse-audio", new AudioStreamHandler());
                    server.start();
                    System.out.println("[Pulse] Local audio streaming server online on port: " + port);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            System.err.println("[Pulse] Failed to initialize local audio server: " + t.getMessage());
        }
    }

    public void setCurrentFile(File file) {
        this.currentFile = file;
    }

    public File getCurrentFile() {
        return currentFile;
    }

    public int getPort() {
        return port;
    }

    public String getLocalStreamUrl() {
        if (port <= 0 || currentFile == null || !currentFile.exists()) return null;
        String hostIp = getLocalIpAddress();
        return "http://" + hostIp + ":" + port + "/pulse-audio";
    }

    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Throwable ignored) {
            return "127.0.0.1";
        }
    }

    private class AudioStreamHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            File file = currentFile;
            if (file == null || !file.exists() || !file.isFile()) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }

            String name = file.getName().toLowerCase();
            String contentType = "audio/mpeg";
            if (name.endsWith(".ogg")) contentType = "audio/ogg";
            else if (name.endsWith(".wav")) contentType = "audio/wav";
            else if (name.endsWith(".m4a") || name.endsWith(".mp4")) contentType = "audio/mp4";

            long fileLength = file.length();
            String rangeHeader = exchange.getRequestHeaders().getFirst("Range");

            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(fileLength));
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                try {
                    String[] parts = rangeHeader.substring(6).split("-");
                    long start = Long.parseLong(parts[0]);
                    long end = (parts.length > 1 && !parts[1].isBlank()) ? Long.parseLong(parts[1]) : fileLength - 1;
                    if (start >= fileLength) {
                        exchange.getResponseHeaders().set("Content-Range", "bytes */" + fileLength);
                        exchange.sendResponseHeaders(416, -1);
                        exchange.close();
                        return;
                    }
                    if (end >= fileLength) end = fileLength - 1;
                    long contentLength = end - start + 1;

                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                    exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
                    exchange.sendResponseHeaders(206, contentLength);

                    try (FileInputStream fis = new FileInputStream(file); OutputStream os = exchange.getResponseBody()) {
                        long skipped = fis.skip(start);
                        byte[] buf = new byte[64 * 1024];
                        long remaining = contentLength;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.length, remaining);
                            int read = fis.read(buf, 0, toRead);
                            if (read <= 0) break;
                            os.write(buf, 0, read);
                            remaining -= read;
                        }
                        os.flush();
                    }
                    exchange.close();
                    return;
                } catch (Throwable ignored) {}
            }

            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(200, fileLength);

            try (FileInputStream fis = new FileInputStream(file); OutputStream os = exchange.getResponseBody()) {
                byte[] buf = new byte[64 * 1024];
                int read;
                while ((read = fis.read(buf)) != -1) {
                    os.write(buf, 0, read);
                }
                os.flush();
            }
            exchange.close();
        }
    }
}
