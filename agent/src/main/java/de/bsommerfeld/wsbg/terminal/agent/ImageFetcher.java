package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Image IO for the mechanical image read (extracted from {@link AgentBrain}):
 * fetches an image URL at full resolution for the OCR engine. Has no Ollama
 * coupling — pure network work, independently testable.
 */
final class ImageFetcher {

    /**
     * A random, realistic browser User-Agent chosen once per process for image
     * fetches — a browser-shaped agent is what an image CDN expects and is the
     * most reliably accepted, while keeping installs off one shared fingerprint.
     */
    private final String userAgent = BrowserUserAgent.random();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Bound the connect phase: image fetches run on the single prefetch
            // worker, so a hung CDN connection would otherwise stall ALL cache warming.
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    /**
     * Fetches the image at FULL resolution for the mechanical OCR read — no
     * downscale, no re-encode. Small UI glyphs (WKNs, percentages) don't survive
     * resizing; OCR needs every pixel the source has.
     */
    BufferedImage fetchFullResolution(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("User-Agent", userAgent).GET().build();

        byte[] bytes = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
        if (isTextResponse(bytes)) {
            throw new RuntimeException("Fetched content is not an image (HTML/JSON/Text detected).");
        }
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) {
            // ImageIO has no stock WebP reader — a webp CDN variant lands here.
            throw new RuntimeException("Undecodable image format (" + detectMimeType(bytes) + ").");
        }
        return image;
    }

    boolean isTextResponse(byte[] data) {
        if (data == null || data.length < 5)
            return false;
        String start = new String(data, 0, Math.min(data.length, 20), StandardCharsets.US_ASCII).trim().toLowerCase();
        return start.startsWith("<html") || start.startsWith("<!doc") || start.startsWith("{")
                || start.startsWith("<xml") || start.startsWith("<?xml") || start.contains("access denied");
    }

    String detectMimeType(byte[] data) {
        if (data == null || data.length < 12)
            return null;
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8)
            return "image/jpeg";
        if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47)
            return "image/png";
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P')
            return "image/webp";
        return null;
    }
}
