package de.bsommerfeld.updater.launcher;

import javax.swing.JComponent;
import java.awt.AlphaComposite;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The dissolve between two launcher screens: the outgoing one fades out while
 * swelling a hair, the incoming one fades in from a hair too small. Both are
 * plain snapshots taken before the transition starts, so the whole animation is
 * two image blits per frame - nothing lays out, nothing resizes, and no live
 * component can repaint itself into the middle of the fade.
 *
 * <h3>Why snapshots, and why no window mutation</h3>
 * The launcher window used to MORPH between its states, resizing the frame and
 * painting an interpolated body inside it. It looked deliberate in isolation
 * and jerky in practice: a native window resize lands on the compositor a frame
 * or two apart from the paint that matches it, and the window ended up sitting
 * at whichever size the last screen happened to need. The window now never
 * changes size at all - every screen is the same rectangle, and this dissolve
 * is the only thing that moves between them.
 *
 * <p>EDT-only. {@link LauncherWindow} drives the clock and paints each frame
 * synchronously via {@code paintImmediately}.
 */
final class ScreenTransition extends JComponent {

    /** How long a screen change takes, end to end. */
    static final int DURATION_MS = 300;

    /** How far the two screens scale apart around the centre. */
    private static final float SCALE_SPREAD = 0.035f;

    private final BufferedImage from;
    private final BufferedImage to;

    private float t;

    ScreenTransition(BufferedImage from, BufferedImage to) {
        this.from = from;
        this.to = to;
        setOpaque(true);
        setBackground(LauncherTheme.BG);
    }

    /** Advances the dissolve WITHOUT scheduling a repaint: 0 = from, 1 = to. */
    void setProgress(float t) {
        this.t = Math.max(0f, Math.min(1f, t));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Both screens are scaled around the centre, so the window body's own
        // background has to be laid down first - otherwise the few pixels the
        // shrunken incoming screen does not cover show the desktop through.
        g2.setColor(LauncherTheme.BG);
        g2.fillRect(0, 0, getWidth(), getHeight());

        draw(g2, from, 1f + SCALE_SPREAD * t, 1f - t);
        draw(g2, to, 1f - SCALE_SPREAD * (1f - t), t);
        g2.dispose();
    }

    private void draw(Graphics2D g2, BufferedImage image, float scale, float alpha) {
        if (alpha <= 0.001f) return;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                Math.min(1f, alpha)));
        int w = Math.round(getWidth() * scale);
        int h = Math.round(getHeight() * scale);
        g2.drawImage(image, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
    }
}
