package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Image IO for the vision pipeline (extracted from {@link AgentBrain}): fetches an
 * image URL, standardises it (resize + align + JPEG re-encode) and returns a
 * base64 {@link ImagePayload} ready to hand to the multimodal model. Has no Ollama
 * coupling — pure network + pixel work, independently testable.
 */
final class ImageFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(ImageFetcher.class);

    /**
     * A random, realistic browser User-Agent chosen once per process for image
     * fetches — a browser-shaped agent is what an image CDN expects and is the
     * most reliably accepted, while keeping installs off one shared fingerprint.
     */
    private final String userAgent = BrowserUserAgent.random();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Bound the connect phase: image fetches run on the single vision-prefetch
            // worker, so a hung CDN connection would otherwise stall ALL vision warming.
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    /** A fetched + standardised image ready for the model. */
    record ImagePayload(String base64, String mimeType) {
    }

    ImagePayload fetchAndOptimize(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                // Hard read deadline so a slow/stalled image host can't pin the
                // single vision-prefetch worker indefinitely.
                .timeout(java.time.Duration.ofSeconds(20))
                .header("User-Agent", userAgent).GET().build();

        byte[] bytes = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
        boolean converted = false;

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(bytes));
            if (original != null) {
                int[] dims = constrainAndAlign(original.getWidth(), original.getHeight(), 1024, 32);
                BufferedImage resized = new BufferedImage(dims[0], dims[1], BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, dims[0], dims[1]);
                g.drawImage(original, 0, 0, dims[0], dims[1], null);
                g.dispose();

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, "jpg", baos);
                bytes = baos.toByteArray();
                converted = true;
            }
        } catch (Exception e) {
            LOG.warn("Image standardization failed, using original bytes: {}", e.getMessage());
        }

        String mimeType;
        if (converted) {
            mimeType = "image/jpeg";
        } else {
            if (isTextResponse(bytes)) {
                throw new RuntimeException("Fetched content is not an image (HTML/JSON/Text detected).");
            }
            mimeType = detectMimeType(bytes);
            if (mimeType == null) {
                throw new RuntimeException("Unrecognized image format (no valid JPEG/PNG/WebP header).");
            }
        }

        return new ImagePayload(Base64.getEncoder().encodeToString(bytes), mimeType);
    }

    /** Constrains dimensions to maxDim and aligns to alignment boundary. */
    int[] constrainAndAlign(int w, int h, int maxDim, int alignment) {
        if (w > maxDim || h > maxDim) {
            double ratio = (double) w / h;
            if (w > h) {
                w = maxDim;
                h = (int) (maxDim / ratio);
            } else {
                h = maxDim;
                w = (int) (maxDim * ratio);
            }
        }
        w = Math.max((w / alignment) * alignment, alignment);
        h = Math.max((h / alignment) * alignment, alignment);
        return new int[] { w, h };
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
