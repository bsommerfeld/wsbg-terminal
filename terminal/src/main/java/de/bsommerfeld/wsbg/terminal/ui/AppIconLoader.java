package de.bsommerfeld.wsbg.terminal.ui;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * Pure image-processing for the taskbar/window icon set — load the bundled art,
 * crop its transparent border, and produce a multi-resolution set. Extracted
 * from {@link BrowserWindow}: no window coupling beyond the resource path.
 */
final class AppIconLoader {

    private AppIconLoader() {}

    /** Loads and content-trims the bundled app icon; empty if the resource is missing/unreadable. */
    static Optional<Image> load() {
        try (InputStream in = AppIconLoader.class.getResourceAsStream("/images/app-icon.png")) {
            if (in == null) return Optional.empty();
            BufferedImage raw = ImageIO.read(in);
            return Optional.ofNullable(raw == null ? null : trimToContent(raw));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Crops the source icon's transparent border and re-centres it on a square
     * canvas with a small (~6%) margin. The shared art carries ~20% macOS-style
     * padding, which renders the Windows/Linux taskbar icon visibly smaller than
     * its neighbours (those fill the square). Trimming makes our icon fill its
     * slot the same way. macOS is unaffected — its dock icon comes from the
     * {@code -Xdock:icon} flag, not this {@code setIconImages} call.
     */
    private static BufferedImage trimToContent(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) > 8) { // alpha above a tiny threshold
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX) return img; // fully transparent — nothing to trim

        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        BufferedImage content = img.getSubimage(minX, minY, cw, ch);

        int side = (int) (Math.max(cw, ch) / 0.88); // ~6% margin per side
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(content, (side - cw) / 2, (side - ch) / 2, null);
        g.dispose();
        return square;
    }

    /**
     * Builds a multi-resolution icon set from the 1024px source. Handing AWT a
     * single oversized image makes Windows pick a poor downscale for the small
     * taskbar/title-bar slots; supplying explicit small sizes lets it choose a
     * crisp match per slot. The full-size source stays in the list for the
     * large Alt+Tab / window-switcher rendering.
     */
    static List<Image> scaledSet(Image source) {
        return List.of(
                source.getScaledInstance(16, 16, Image.SCALE_SMOOTH),
                source.getScaledInstance(24, 24, Image.SCALE_SMOOTH),
                source.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
                source.getScaledInstance(48, 48, Image.SCALE_SMOOTH),
                source.getScaledInstance(64, 64, Image.SCALE_SMOOTH),
                source.getScaledInstance(128, 128, Image.SCALE_SMOOTH),
                source.getScaledInstance(256, 256, Image.SCALE_SMOOTH),
                source);
    }
}
