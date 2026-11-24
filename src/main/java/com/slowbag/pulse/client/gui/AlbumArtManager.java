package com.slowbag.pulse.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.slowbag.pulse.MusicPlayerMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
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
import java.util.function.Supplier;

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
    private final Identifier defaultAlbumArtId = Identifier.fromNamespaceAndPath(MusicPlayerMod.MOD_ID, "default_album_art");

    private DynamicTexture currentSquareTexture = null;
    private DynamicTexture currentCircleTexture = null;
    private DynamicTexture defaultTexture = null;
    private String currentUrl = null;
    private boolean isTextureReady = false;

    public static DynamicTexture createDynamicTexture(String label, NativeImage image) {
        DynamicTexture tex = null;
        try {
            // 26.x and 1.21.4+ constructor: DynamicTexture(Supplier<String>, NativeImage)
            java.lang.reflect.Constructor<DynamicTexture> ctor26 = DynamicTexture.class.getConstructor(Supplier.class, NativeImage.class);
            tex = ctor26.newInstance((Supplier<String>) () -> label, image);
        } catch (Throwable t1) {
            try {
                // 1.21.0 - 1.21.3 constructor: DynamicTexture(NativeImage)
                java.lang.reflect.Constructor<DynamicTexture> ctor121 = DynamicTexture.class.getConstructor(NativeImage.class);
                tex = ctor121.newInstance(image);
            } catch (Throwable t2) {
                tex = new DynamicTexture(() -> label, image);
            }
        }

        if (tex != null) {
            try {
                // 26.x Blaze3D GpuSampler hardware linear filtering
                java.lang.reflect.Field samplerField = net.minecraft.client.renderer.texture.AbstractTexture.class.getDeclaredField("sampler");
                samplerField.setAccessible(true);
                samplerField.set(tex, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            } catch (Throwable ignored) {
                try {
                    // 1.21.x legacy setFilter(bilinear, mipmap)
                    java.lang.reflect.Method setFilterMethod = tex.getClass().getMethod("setFilter", boolean.class, boolean.class);
                    setFilterMethod.invoke(tex, true, false);
                } catch (Throwable ignored2) {}
            }
        }
        return tex;
    }

    private AlbumArtManager() {}

    public synchronized void loadArtworkAsync(String artworkUrl) {
        if (artworkUrl == null || artworkUrl.isBlank()) {
            clearTexture();
            return;
        }

        String cleanedUrl = artworkUrl.trim();
        if (cleanedUrl.startsWith("//")) {
            cleanedUrl = "https:" + cleanedUrl;
        }

        // If it's a YouTube WebP or variable thumbnail, map it to canonical reliable high-quality JPEG
        if (cleanedUrl.contains("ytimg.com") || cleanedUrl.contains("youtube.com")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:vi(?:_webp)?|embed)\\/([a-zA-Z0-9_-]{11})").matcher(cleanedUrl);
            if (m.find()) {
                cleanedUrl = "https://i.ytimg.com/vi/" + m.group(1) + "/hqdefault.jpg";
            }
        }

        if (cleanedUrl.equals(currentUrl) && isTextureReady) {
            return;
        }

        this.currentUrl = cleanedUrl;
        this.isTextureReady = false;

        final String finalUrl = cleanedUrl;
        executor.submit(() -> {
            try {
                byte[] imageBytes = fetchImageBytes(finalUrl);
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

                        // 1. Wide image (ratio ~1.44:1 matching 66x46 box) to fit the full YouTube thumbnail
                        int targetW = 256;
                        int targetH = 178;
                        double targetAspect = (double) targetW / targetH;
                        double imgAspect = (double) w / h;
                        int cropW, cropH;
                        if (imgAspect > targetAspect) {
                            cropH = h;
                            cropW = (int) (h * targetAspect);
                        } else {
                            cropW = w;
                            cropH = (int) (w / targetAspect);
                        }
                        int cropX = Math.max(0, (w - cropW) / 2);
                        int cropY = Math.max(0, (h - cropH) / 2);
                        BufferedImage rectSrc = raw.getSubimage(cropX, cropY, cropW, cropH);

                        BufferedImage squareImg = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D gSquare = squareImg.createGraphics();
                        gSquare.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        gSquare.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        gSquare.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        gSquare.setClip(new RoundRectangle2D.Float(0, 0, targetW, targetH, 20, 20));
                        gSquare.drawImage(rectSrc, 0, 0, targetW, targetH, null);
                        gSquare.dispose();

                        // 2. Circular masked image for outside HUD overlay with smooth edges
                        int sqSize = Math.min(w, h);
                        int circCropX = (w - sqSize) / 2;
                        int circCropY = (h - sqSize) / 2;
                        BufferedImage squareSrc = raw.getSubimage(circCropX, circCropY, sqSize, sqSize);

                        int circSize = 256;
                        BufferedImage circleImg = new BufferedImage(circSize, circSize, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D gCircle = circleImg.createGraphics();
                        gCircle.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        gCircle.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                        gCircle.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        gCircle.setClip(new Ellipse2D.Float(1, 1, circSize - 2, circSize - 2));
                        gCircle.drawImage(squareSrc, 0, 0, circSize, circSize, null);
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
                        // Cross-version smooth dynamic texture with hardware linear antialiasing
                        currentSquareTexture = createDynamicTexture("album_art_square", finalSquare);
                        currentCircleTexture = createDynamicTexture("album_art_circle", finalCircle);

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
        if (urlStr == null || urlStr.isBlank()) return null;
        if (urlStr.startsWith("//")) {
            urlStr = "https:" + urlStr;
        }
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
            conn.setRequestProperty("Accept", "image/jpeg,image/png,*/*;q=0.8");
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

    public synchronized Identifier getDefaultAlbumArtId() {
        if (defaultTexture == null) {
            initDefaultArtwork();
        }
        return defaultAlbumArtId;
    }

    private void initDefaultArtwork() {
        try {
            int targetW = 256;
            int targetH = 178;
            BufferedImage img = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 1. Sleek deep obsidian purple gradient card
            java.awt.GradientPaint bgGrad = new java.awt.GradientPaint(0, 0, new Color(0x11, 0x0E, 0x1A), targetW, targetH, new Color(0x22, 0x15, 0x33));
            g.setPaint(bgGrad);
            g.fillRoundRect(0, 0, targetW, targetH, 20, 20);

            // 2. Subtle ambient purple aura in center
            int cx = targetW / 2;
            int cy = targetH / 2 - 8;
            for (int r = 70; r >= 30; r -= 10) {
                g.setColor(new Color(0xA8, 0x55, 0xF7, 18));
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
            }

            // 3. Concentric soundwave / vinyl rings
            for (int r = 58; r >= 28; r -= 10) {
                g.setColor(new Color(0xC0, 0x84, 0xFC, 45));
                g.drawOval(cx - r, cy - r, r * 2, r * 2);
            }

            // 4. Center Glowing Disc
            int discR = 26;
            g.setColor(new Color(0xA8, 0x55, 0xF7, 220));
            g.fillOval(cx - discR, cy - discR, discR * 2, discR * 2);
            g.setColor(new Color(0xE9, 0xD5, 0xFF, 180));
            g.drawOval(cx - discR, cy - discR, discR * 2, discR * 2);

            // Center spindle dot
            g.setColor(new Color(0x10, 0x0E, 0x1A));
            g.fillOval(cx - 5, cy - 5, 10, 10);

            // White musical note inside disc
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            java.awt.FontMetrics fmNote = g.getFontMetrics();
            String note = "♪";
            g.drawString(note, cx - (fmNote.stringWidth(note) / 2), cy + 7);

            // 5. Modern "PULSE" brand label below disc
            g.setFont(new Font("SansSerif", Font.BOLD, 14));
            java.awt.FontMetrics fmLbl = g.getFontMetrics();
            String brand = "P U L S E";
            int bx = cx - (fmLbl.stringWidth(brand) / 2);
            int by = cy + discR + 26;
            g.setColor(new Color(0x00, 0x00, 0x00, 120));
            g.drawString(brand, bx + 1, by + 1);
            g.setColor(new Color(0xE9, 0xD5, 0xFF));
            g.drawString(brand, bx, by);

            // 6. Top glass gloss reflection sheen
            g.setColor(new Color(255, 255, 255, 28));
            g.fillRoundRect(4, 4, targetW - 8, targetH / 3, 14, 14);

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            NativeImage nativeDefault = NativeImage.read(new ByteArrayInputStream(baos.toByteArray()));

            Minecraft.getInstance().execute(() -> {
                defaultTexture = createDynamicTexture("default_album_art", nativeDefault);
                Minecraft.getInstance().getTextureManager().register(defaultAlbumArtId, defaultTexture);
            });
        } catch (Throwable t) {
            MusicPlayerMod.LOGGER.debug("[Music Player] Default artwork generation failed: " + t.getMessage());
        }
    }

    public synchronized String getCurrentUrl() {
        return currentUrl;
    }

    public synchronized boolean isTextureReady() {
        return isTextureReady;
    }
}
