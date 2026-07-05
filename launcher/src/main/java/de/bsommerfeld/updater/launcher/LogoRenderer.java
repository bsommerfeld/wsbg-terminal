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
 */
final class LogoRenderer {

    // Freed hands+diamond glyph (no card background), sized to fill the upper
    // area. The box preserves the glyph's 781:670 source aspect ratio.
    private static final int LOGO_W = 210;
    private static final int LOGO_H = 180;

    private LogoRenderer() {
    }

    /**
     * Creates the logo panel, or a blank panel if the asset is missing.
     */
    static JPanel createPanel() {
        Image logoSource = loadLogoImage();
        if (logoSource == null) return new JPanel();

        // Pre-render the glyph at full opacity, scaled to fit the LOGO_W×LOGO_H
        // box while preserving its source aspect ratio. The glyph asset is
        // shipped at 2x-retina resolution, so a single bilinear pass would
        // undersample (2x2 taps across a ~7x reduction) and shred the thin
        // diamond strokes — halve progressively until within 2x of the target,
        // then do the final fractional step.
        int sw = logoSource.getWidth(null);
        int sh = logoSource.getHeight(null);
        double scale = Math.min((double) LOGO_W / sw, (double) LOGO_H / sh);
        int w = Math.max(1, (int) Math.round(sw * scale));
        int h = Math.max(1, (int) Math.round(sh * scale));

        // Pre-render at 2x the logical size and draw scaled down in paint:
        // on a HiDPI (Retina) display the device transform then maps the
        // bitmap ~1:1 instead of upscaling a tiny pre-scaled image.
        BufferedImage stage = toArgb(logoSource, sw, sh);
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
        panel.setPreferredSize(new Dimension(LOGO_W, LOGO_H));
        return panel;
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
