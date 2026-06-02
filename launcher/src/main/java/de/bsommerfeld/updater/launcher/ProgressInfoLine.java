package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

/**
 * The single, subtle info line beneath the status label: remaining time (left)
 * and download speed (right), laid out side by side with a small gap and
 * centered as a unit.
 *
 * <p>Both are shown together whenever their value is known; each fades away on
 * its own once cleared (a phase transition clears both). Each reads
 * {@link #NEUTRAL neutral grey} normally and turns {@link #ORANGE orange} as a
 * gentle "this is taking a while" warning — the speed below
 * {@link #SLOW_SPEED_BYTES 1 MB/s}, the ETA above {@link #LONG_ETA_SEC 5 min}.
 *
 * <p>Glyphs (hourglass + download arrow) are vector-drawn so they render
 * identically on every platform with no font/emoji fallback. EDT-only: every
 * mutator is called from {@link LauncherWindow}'s flush on the Swing thread.
 */
final class ProgressInfoLine extends JComponent {

    private static final Color NEUTRAL = new Color(120, 120, 130);
    private static final Color ORANGE = new Color(0xCC, 0x88, 0x44);
    private static final Font INFO_FONT = new Font("SFMono-Regular", Font.PLAIN, 10);

    private static final float MAX_OPACITY = 0.55f;
    private static final long DISMISS_GRACE_MS = 500;

    // Warning thresholds.
    private static final long SLOW_SPEED_BYTES = 1_000_000; // < 1 MB/s → orange
    private static final long LONG_ETA_SEC = 5 * 60;        // > 5 min → orange

    // Gap between the ETA and speed groups.
    private static final int PART_GAP = 16;

    private long etaSeconds = -1; // -1 => not shown
    private long speedBytes = -1; // -1 => not shown

    private float opacity = 0f;
    private boolean active = false;
    private long dismissRequestTime = 0;

    ProgressInfoLine() {
        setPreferredSize(new Dimension(240, 14));
        setOpaque(false);
        Timer timer = new Timer(16, _ -> tick());
        timer.start();
    }

    /** Sets remaining seconds (negative hides the ETA group). */
    void setEta(long secs) {
        etaSeconds = secs < 0 ? -1 : secs;
        refreshActive();
    }

    /** Sets download speed in bytes/sec (negative hides the speed group). */
    void setSpeed(long bytesPerSec) {
        speedBytes = bytesPerSec < 0 ? -1 : bytesPerSec;
        refreshActive();
    }

    /** Hides the whole line (both groups). */
    void clear() {
        etaSeconds = -1;
        speedBytes = -1;
        requestDismiss();
    }

    private void refreshActive() {
        if (etaSeconds >= 0 || speedBytes >= 0) {
            active = true;
            dismissRequestTime = 0;
        } else {
            requestDismiss();
        }
    }

    private void requestDismiss() {
        if (active && dismissRequestTime == 0) {
            dismissRequestTime = System.currentTimeMillis();
        }
    }

    private void tick() {
        if (dismissRequestTime > 0
                && System.currentTimeMillis() - dismissRequestTime >= DISMISS_GRACE_MS) {
            active = false;
            dismissRequestTime = 0;
        }

        if (active && opacity < MAX_OPACITY) {
            opacity = Math.min(MAX_OPACITY, opacity + 0.07f);
        } else if (!active && opacity > 0f) {
            opacity = Math.max(0f, opacity - 0.06f);
            if (opacity < 0.02f) {
                opacity = 0f;
                etaSeconds = -1;
                speedBytes = -1;
                if (getParent() != null) {
                    getParent().repaint(getX(), getY(), getWidth(), getHeight());
                }
            }
        }

        if (opacity > 0f) {
            repaint();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (opacity <= 0f) return;

        long eta = etaSeconds;
        long speed = speedBytes;
        boolean hasEta = eta >= 0;
        boolean hasSpeed = speed >= 0;
        if (!hasEta && !hasSpeed) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));
        g2.setFont(INFO_FONT);
        FontMetrics fm = g2.getFontMetrics();

        double r = 4.0;
        int glyphW = (int) Math.ceil(r * 2) + 1;
        int innerGap = 5;

        String etaText = hasEta ? formatEta(eta) : "";
        String speedText = hasSpeed ? formatSpeed(speed) : "";
        int etaW = hasEta ? glyphW + innerGap + fm.stringWidth(etaText) : 0;
        int speedW = hasSpeed ? glyphW + innerGap + fm.stringWidth(speedText) : 0;
        int totalW = etaW + speedW + (hasEta && hasSpeed ? PART_GAP : 0);

        int x = (getWidth() - totalW) / 2;
        int baseY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
        double cy = getHeight() / 2.0 - 0.5;

        if (hasEta) {
            Color c = eta > LONG_ETA_SEC ? ORANGE : NEUTRAL;
            paintHourglass(g2, x + r, cy, r, c);
            g2.setColor(c);
            g2.drawString(etaText, x + glyphW + innerGap, baseY);
            x += etaW + PART_GAP;
        }
        if (hasSpeed) {
            Color c = speed < SLOW_SPEED_BYTES ? ORANGE : NEUTRAL;
            paintDownload(g2, x + r, cy, r, c);
            g2.setColor(c);
            g2.drawString(speedText, x + glyphW + innerGap, baseY);
        }

        g2.dispose();
    }

    /** Minimal hourglass outline centered at (cx, cy). */
    private static void paintHourglass(Graphics2D g2, double cx, double cy, double r, Color c) {
        double w = r * 0.62;
        Path2D p = new Path2D.Double();
        p.moveTo(cx - w, cy - r);
        p.lineTo(cx + w, cy - r);
        p.lineTo(cx - w, cy + r);
        p.lineTo(cx + w, cy + r);
        p.closePath();
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(p);
    }

    /** Minimal download glyph (arrow into a tray) centered at (cx, cy). */
    private static void paintDownload(Graphics2D g2, double cx, double cy, double r, Color c) {
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        double stemTop = cy - r;
        double stemBot = cy + r * 0.35;
        double head = r * 0.55;
        g2.draw(new Line2D.Double(cx, stemTop, cx, stemBot));            // stem
        g2.draw(new Line2D.Double(cx - head, cy - r * 0.2, cx, stemBot)); // arrow left
        g2.draw(new Line2D.Double(cx + head, cy - r * 0.2, cx, stemBot)); // arrow right
        g2.draw(new Line2D.Double(cx - r * 0.8, cy + r, cx + r * 0.8, cy + r)); // tray
    }

    /** Single coarse unit — h, m, OR s, never combined. */
    private static String formatEta(long s) {
        if (s >= 3600) return Math.round(s / 3600.0) + "h";
        if (s >= 60) return Math.round(s / 60.0) + "m";
        return Math.max(1, s) + "s";
    }

    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return (bytesPerSec / 1024) + " KB/s";
        return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }
}
