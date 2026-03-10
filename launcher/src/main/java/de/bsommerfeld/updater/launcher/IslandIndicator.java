package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;

/**
 * Morphing indicator inspired by Apple's Dynamic Island.
 *
 * <p>Starts as a breathing dot, morphs into a progress bar on demand,
 * and collapses back to a dot when work is finished. Fill values are
 * gated behind full expansion to prevent stale progress from previous
 * phases flashing in the bar.
 */
final class IslandIndicator extends JComponent {

    private static final int DOT_DIAMETER = 6;
    private static final int BAR_WIDTH = 180;
    private static final int BAR_HEIGHT = 6;
    private static final int COMPONENT_HEIGHT = DOT_DIAMETER + 4;

    // Must match LauncherWindow.BG — painted as opaque background to
    // prevent Swing's dirty-region clear from exposing the transparent
    // window, which causes the desktop to bleed through for one frame.
    private static final Color BG_COLOR = new Color(0x1A, 0x1A, 0x1A);

    private static final Color TRACK_COLOR = new Color(45, 45, 50);
    private static final Color FILL_COLOR = new Color(90, 130, 255);
    private static final Color DOT_COLOR = new Color(90, 90, 90);

    private static final float DOT_MIN_ALPHA = 0.15f;
    private static final float DOT_MAX_ALPHA = 0.70f;

    // How long the dot breathes before accepting the first expand
    private static final long GRACE_PERIOD_MS = 400;
    // How long to wait after complete before collapsing
    private static final long COLLAPSE_DELAY_MS = 300;

    // Morph speeds per frame at ~60fps
    private static final float EXPAND_SPEED = 0.11f;
    private static final float COLLAPSE_SPEED = 0.08f;

    private enum State { IDLE, EXPANDED }

    private State state = State.IDLE;
    private float morphT = 0f;
    private double targetFill = 0;
    private double currentFill = 0;
    private float breathPhase = 0f;
    private final long createdAt;
    private long lastUpdateTime = 0;
    private boolean completeRequested = false;

    IslandIndicator() {
        setPreferredSize(new Dimension(BAR_WIDTH, COMPONENT_HEIGHT));
        // Opaque tells Swing we handle our own background — prevents
        // the RepaintManager from clearing the area with the
        // transparent window background (the flicker root cause).
        setOpaque(true);
        setBackground(BG_COLOR);
        createdAt = System.currentTimeMillis();
        Timer timer = new Timer(16, _ -> tick());
        timer.start();
    }

    void update(double progress) {
        lastUpdateTime = System.currentTimeMillis();

        if (progress >= 1.0) {
            // Only accept complete after fully expanded — ignore
            // during morph and grace to prevent stale fill
            if (state == State.EXPANDED && morphT >= 1.0f) {
                targetFill = 1.0;
                completeRequested = true;
            }
            return;
        }

        completeRequested = false;

        // Ignore during grace period — let the dot breathe
        if (System.currentTimeMillis() - createdAt < GRACE_PERIOD_MS) return;

        // Reset fill on first expansion so stale values from
        // previous phases don't flash the bar as full.
        if (state == State.IDLE) {
            targetFill = 0;
            currentFill = 0;
        }

        state = State.EXPANDED;

        // Fill values are only accepted once fully expanded — the bar
        // always expands empty, then the fill lerps up from 0. This
        // prevents stale download progress from flashing during
        // the expand animation.
        if (morphT < 1.0f) return;

        if (progress >= 0) {
            // A significant drop (>10%) signals a new phase
            if (progress < targetFill - 0.10) {
                currentFill = 0;
                targetFill = 0;
            }

            if (Math.abs(progress - targetFill) >= 0.02) {
                targetFill = progress;
            }
        }
    }

    private void tick() {
        breathPhase += 0.035f;
        if (breathPhase > 2 * Math.PI) breathPhase -= (float) (2 * Math.PI);

        if (state == State.EXPANDED) {
            morphT = Math.min(1f, morphT + EXPAND_SPEED);

            if (completeRequested) {
                long elapsed = System.currentTimeMillis() - lastUpdateTime;
                if (elapsed > COLLAPSE_DELAY_MS) {
                    state = State.IDLE;
                    completeRequested = false;
                }
            }
        } else {
            morphT = Math.max(0f, morphT - COLLAPSE_SPEED);
        }

        // Only interpolate fill when fully expanded — keep fill
        // frozen during expand/collapse morph to avoid visual noise.
        if (morphT >= 1.0f) {
            double diff = targetFill - currentFill;
            if (Math.abs(diff) > 0.001) {
                currentFill += diff * 0.12;
            } else {
                currentFill = targetFill;
            }
        }

        if (morphT <= 0f && state == State.IDLE) {
            currentFill = 0;
            targetFill = 0;
        }

        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();

        // Opaque fill — prevents transparent window background
        // from bleeding through between repaint cycles
        g2.setColor(BG_COLOR);
        g2.fillRect(0, 0, getWidth(), getHeight());

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int cx = getWidth() / 2;
        int cy = getHeight() / 2;

        if (morphT <= 0.01f) {
            paintDot(g2, cx, cy);
        } else {
            paintBar(g2, cx, cy);
        }

        g2.dispose();
    }

    private void paintDot(Graphics2D g2, int cx, int cy) {
        float t = (float) (Math.sin(breathPhase) * 0.5 + 0.5);
        float alpha = DOT_MIN_ALPHA + t * (DOT_MAX_ALPHA - DOT_MIN_ALPHA);

        int r = DOT_DIAMETER / 2;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        g2.setColor(DOT_COLOR);
        g2.fill(new Ellipse2D.Double(cx - r, cy - r, DOT_DIAMETER, DOT_DIAMETER));
        g2.setComposite(AlphaComposite.SrcOver);
    }

    private void paintBar(Graphics2D g2, int cx, int cy) {
        int currentWidth = (int) (DOT_DIAMETER + (BAR_WIDTH - DOT_DIAMETER) * morphT);
        int x = cx - currentWidth / 2;
        int y = cy - BAR_HEIGHT / 2;
        int arc = BAR_HEIGHT;

        g2.setColor(TRACK_COLOR);
        g2.fillRoundRect(x, y, currentWidth, BAR_HEIGHT, arc, arc);

        int fillWidth = (int) (currentWidth * currentFill);
        if (fillWidth > 0) {
            g2.setColor(FILL_COLOR);
            g2.fillRoundRect(x, y, Math.min(fillWidth, currentWidth), BAR_HEIGHT, arc, arc);
        }
    }
}
