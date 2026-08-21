package de.bsommerfeld.updater.launcher;

import javax.swing.ImageIcon;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.net.URL;

/**
 * Builds the splash logo panel: loads the hands+diamond glyph and pre-renders
 * it with progressive-bilinear HiDPI scaling so the thin diamond strokes
 * survive the reduction from the 2x-retina source. Extracted from
 * {@link LauncherWindow} as a self-contained rendering concern.
 *
 * <p>The panel keeps its size no matter what the glyph export looks like:
 * everything below is measured from the glyph's INK (its non-transparent
 * bounding box), never from the image's own edges. Fitting the image made the
 * displayed size a property of the exporter's padding - re-exported trimmed,
 * the logo silently grew by ~40 % (twice: once in July, again with the
 * Liquid-Glass icon). Ink-fitting cannot drift that way.
 */
final class LogoRenderer {

    /** The box the splash layout reserves, and what the choice screens snapshot. */
    private static final int PANEL_W = 210;
    private static final int PANEL_H = 180;

    /** How large the glyph's ink is drawn; its aspect ratio is preserved. */
    private static final int INK_W = 120;
    private static final int INK_H = 106;

    /** Alpha below this is the export's feathered edge, not ink. */
    private static final int INK_ALPHA = 8;

    private LogoRenderer() {
    }

    /**
     * Creates the logo panel, or a blank panel if the asset is missing.
     */
    static JPanel createPanel() {
        Image logoSource = loadLogoImage();
        if (logoSource == null) return new JPanel();

        // Scale the ink to fit the INK_W×INK_H box while preserving its aspect
        // ratio. The glyph asset is shipped at 2x-retina resolution, so a
        // single bilinear pass would undersample (2x2 taps across a ~7x
        // reduction) and shred the thin diamond strokes — halve progressively
        // until within 2x of the target, then do the final fractional step.
        BufferedImage ink = trimToInk(logoSource);
        if (ink == null) return new JPanel();

        int sw = ink.getWidth();
        int sh = ink.getHeight();
        double scale = Math.min((double) INK_W / sw, (double) INK_H / sh);
        int w = Math.max(1, (int) Math.round(sw * scale));
        int h = Math.max(1, (int) Math.round(sh * scale));

        // Pre-render at 2x the logical size and draw scaled down in paint:
        // on a HiDPI (Retina) display the device transform then maps the
        // bitmap ~1:1 instead of upscaling a tiny pre-scaled image.
        BufferedImage stage = ink;
        while (stage.getWidth() / 2 >= w * 2 && stage.getHeight() / 2 >= h * 2) {
            stage = resizeBilinear(stage, stage.getWidth() / 2, stage.getHeight() / 2);
        }
        BufferedImage scaledLogo = resizeBilinear(stage, w * 2, h * 2);

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

                int x = (getWidth() - w) / 2;
                int y = (getHeight() - h) / 2;
                g2d.drawImage(scaledLogo, x, y, w, h, null);
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(PANEL_W, PANEL_H));
        return panel;
    }

    /**
     * The glyph cropped to its non-transparent bounding box — the drop shadow
     * counts as ink, it is part of the artwork. Null if the image carries no
     * pixel above {@link #INK_ALPHA} at all.
     */
    private static BufferedImage trimToInk(Image source) {
        int sw = source.getWidth(null);
        int sh = source.getHeight(null);
        if (sw <= 0 || sh <= 0) return null;

        BufferedImage img = toArgb(source, sw, sh);
        int minX = sw, minY = sh, maxX = -1, maxY = -1;
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                if ((img.getRGB(x, y) >>> 24) <= INK_ALPHA) continue;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX) return null;
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static BufferedImage toArgb(Image src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(src, 0, 0, null);
        g2.dispose();
        return out;
    }

    private static BufferedImage resizeBilinear(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(src, 0, 0, w, h, null);
        g2.dispose();
        return out;
    }

    private static Image loadLogoImage() {
        // Freed hands+diamond glyph (transparent background). Falls back to
        // the full app icon if the glyph asset is missing.
        URL url = LogoRenderer.class.getResource("/images/logo-glyph.png");
        if (url == null) url = LogoRenderer.class.getResource("/images/app-icon.png");
        if (url == null) return null;
        return new ImageIcon(url).getImage();
    }
}
