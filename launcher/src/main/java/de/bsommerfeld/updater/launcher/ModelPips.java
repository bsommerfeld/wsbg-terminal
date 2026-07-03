package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;

/**
 * A tiny row of page-style dots beneath the {@link IslandIndicator} — one pip
 * per AI model being installed. A model's pip fills amber the moment its
 * install begins; only the not-yet-started ones stay a dim track colour. It
 * exists so the user can see
 * <em>how many</em> models install (two, currently), which the single download
 * bar cannot convey on its own — the bar collapses and re-expands between
 * models, reading as one repeated install rather than a set.
 *
 * <p>Shown only while models are pulling: {@link #set} gives it a count,
 * {@link #clear} hides it again on the next phase. It reserves a fixed slice of
 * height at all times so toggling it never shifts the layout. EDT-only, like
 * the rest of {@link LauncherWindow}'s widgets.
 */
final class ModelPips extends JComponent {

    private static final int DOT = 5;
    private static final int GAP = 8;
    private static final int HEIGHT = DOT + 6;

    // Matches IslandIndicator's fill/track so the pips read as the same family.
    private static final Color LIT = new Color(0xF9, 0xB6, 0x4F);
    private static final Color PENDING = new Color(60, 60, 66);

    private int total = 0;
    private int lit = 0;

    ModelPips() {
        setOpaque(false);
        setPreferredSize(new Dimension(120, HEIGHT));
    }

    /** Sets the pip count and how many are lit, clamping to a sane range. */
    void set(int total, int lit) {
        this.total = Math.max(0, total);
        this.lit = Math.max(0, Math.min(lit, this.total));
        repaint();
    }

    /** Hides the row (no dots painted) until the next {@link #set}. */
    void clear() {
        total = 0;
        lit = 0;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        if (total <= 0) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int rowWidth = total * DOT + (total - 1) * GAP;
        double x = (getWidth() - rowWidth) / 2.0;
        double cy = getHeight() / 2.0;

        for (int i = 0; i < total; i++) {
            g2.setColor(i < lit ? LIT : PENDING);
            g2.fill(new Ellipse2D.Double(x, cy - DOT / 2.0, DOT, DOT));
            x += DOT + GAP;
        }

        g2.dispose();
    }
}
