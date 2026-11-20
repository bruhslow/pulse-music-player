package com.slowbag.pulse.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlbumArtManager {

    private static final AlbumArtManager INSTANCE = new AlbumArtManager();
    public static AlbumArtManager getInstance() {
        return INSTANCE;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MusicPlayer-AlbumArtThread");
        t.setDaemon(true);
        return t;
    });

    private final Identifier albumArtId = Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "current_album_art");
    private final Identifier circleAlbumArtId = Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "current_album_art_circle");

    private DynamicTexture currentSquareTexture = null;
    private DynamicTexture currentCircleTexture = null;
    private String currentUrl = null;
    private boolean isTextureReady = false;

    private AlbumArtManager() {}

    public synchronized void loadArtworkAsync(String artworkUrl) {
        if (artworkUrl == null || artworkUrl.isBlank()) {
            clearTexture();
            return;
        }

        if (artworkUrl.equals(currentUrl) && isTextureReady) {
            return;
        }

        this.currentUrl = artworkUrl;
        this.isTextureReady = false;

        executor.submit(() -> {
            try {
                byte[] imageBytes = fetchImageBytes(artworkUrl);
                if (imageBytes == null || imageBytes.length == 0) {
                    return;
                }

                // Process both Square (for MP UI) and Circular (for HUD) versions
                NativeImage nativeSquare = null;
                NativeImage nativeCircle = null;

                try {
                    BufferedImage raw = ImageIO.read(new ByteArrayInputStream(imageBytes));
                    if (raw != null) {
                        int w = raw.getWidth();
                        int h = raw.getHeight();
                        int sqSize = Math.min(w, h);
                        int cropX = (w - sqSize) / 2;
                        int cropY = (h - sqSize) / 2;
                        BufferedImage squareSrc = raw.getSubimage(cropX, cropY, sqSize, sqSize);

                        int targetSize = 128;

                        // 1. Square image with smooth rounded corners matching concept UI
                        BufferedImage squareImg = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D gSquare = squareImg.createGraphics();
                        gSquare.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        gSquare.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        gSquare.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        gSquare.setClip(new RoundRectangle2D.Float(0, 0, targetSize, targetSize, 18, 18));
                        gSquare.drawImage(squareSrc, 0, 0, targetSize, targetSize, null);
                        gSquare.dispose();

                        // 2. Circular masked image for outside HUD overlay
                        BufferedImage circleImg = new BufferedImage(targetSize, targetSize, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D gCircle = circleImg.createGraphics();
                        gCircle.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        gCircle.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        gCircle.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        gCircle.setClip(new Ellipse2D.Float(1, 1, targetSize - 2, targetSize - 2));
                        gCircle.drawImage(squareSrc, 0, 0, targetSize, targetSize, null);
                        gCircle.dispose();

                        ByteArrayOutputStream squareBaos = new ByteArrayOutputStream();
                        ImageIO.write(squareImg, "PNG", squareBaos);
                        nativeSquare = NativeImage.read(new ByteArrayInputStream(squareBaos.toByteArray()));

                        ByteArrayOutputStream circleBaos = new ByteArrayOutputStream();
                        ImageIO.write(circleImg, "PNG", circleBaos);
                        nativeCircle = NativeImage.read(new ByteArrayInputStream(circleBaos.toByteArray()));
                    }
                } catch (Throwable t) {
                    MusicPlayerMod.LOGGER.debug("[Music Player] ImageIO processing failed, using NativeImage raw fallback: " + t.getMessage());
                }

                // Fallback to raw NativeImage if ImageIO wasn't able to process it
                if (nativeSquare == null) {
                    nativeSquare = NativeImage.read(new ByteArrayInputStream(imageBytes));
                    nativeCircle = NativeImage.read(new ByteArrayInputStream(imageBytes));
                }

                final NativeImage finalSquare = nativeSquare;
                final NativeImage finalCircle = nativeCircle;

                Minecraft.getInstance().execute(() -> {
                    synchronized (AlbumArtManager.this) {
                        if (!artworkUrl.equals(currentUrl)) {
                            if (finalSquare != null) finalSquare.close();
                            if (finalCircle != null) finalCircle.close();
                            return;
                        }
                        if (currentSquareTexture != null) {
                            currentSquareTexture.close();
                        }
                        if (currentCircleTexture != null) {
                            currentCircleTexture.close();
                        }
                        currentSquareTexture = new DynamicTexture(() -> "album_art_square", finalSquare);
                        currentCircleTexture = new DynamicTexture(() -> "album_art_circle", finalCircle);

                        Minecraft.getInstance().getTextureManager().register(albumArtId, currentSquareTexture);
                        Minecraft.getInstance().getTextureManager().register(circleAlbumArtId, currentCircleTexture);
                        isTextureReady = true;
                    }
                });
            } catch (Exception e) {
                MusicPlayerMod.LOGGER.warn("[Music Player] Failed to load album artwork: " + artworkUrl + " - " + e.getMessage());
                synchronized (AlbumArtManager.this) {
                    isTextureReady = false;
                }
            }
        });
    }

    private static byte[] fetchImageBytes(String urlStr) throws Exception {
        if (urlStr.startsWith("file:/")) {
            try (InputStream in = new java.io.FileInputStream(new java.io.File(new URI(urlStr)))) {
                return in.readAllBytes();
            }
        }

        int redirects = 0;
        while (redirects < 5) {
            URL url = new URI(urlStr).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "image/webp,image/apng,image/png,image/jpeg,image/*,*/*;q=0.8");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(false);

            int status = conn.getResponseCode();
            if (status == HttpURLConnection.HTTP_MOVED_PERM || status == HttpURLConnection.HTTP_MOVED_TEMP || status == 307 || status == 308) {
                String newUrl = conn.getHeaderField("Location");
                if (newUrl != null && !newUrl.isBlank()) {
                    if (newUrl.startsWith("/")) {
                        URI base = new URI(urlStr);
                        newUrl = base.resolve(newUrl).toString();
                    }
                    urlStr = newUrl;
                    redirects++;
                    conn.disconnect();
                    continue;
                }
            }

            try (InputStream in = conn.getInputStream()) {
                return in.readAllBytes();
            }
        }
        return null;
    }

    public synchronized void clearTexture() {
        this.currentUrl = null;
        this.isTextureReady = false;
        if (currentSquareTexture != null || currentCircleTexture != null) {
            DynamicTexture sq = currentSquareTexture;
            DynamicTexture cir = currentCircleTexture;
            currentSquareTexture = null;
            currentCircleTexture = null;
            Minecraft.getInstance().execute(() -> {
                if (sq != null) sq.close();
                if (cir != null) cir.close();
            });
        }
    }

    public synchronized Identifier getAlbumArtId() {
        return isTextureReady ? albumArtId : null;
    }

    public synchronized Identifier getCircleAlbumArtId() {
        return isTextureReady ? circleAlbumArtId : null;
    }

    public synchronized String getCurrentUrl() {
        return currentUrl;
    }

    public synchronized boolean isTextureReady() {
        return isTextureReady;
    }
}
