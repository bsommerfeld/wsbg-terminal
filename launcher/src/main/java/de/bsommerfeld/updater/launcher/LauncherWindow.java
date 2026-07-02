package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Minimalist splash window — matte dark background, stamped logo, and
 * a Dynamic Island-style morphing indicator that expands from a pulsing
 * dot into a progress bar and collapses back when complete.
 *
 * <h3>Layout (vertical, top-weighted)</h3>
 * <pre>
 *     ┌─────────────────┐
 *     │   (hands +      │  full opacity, fills the
 *     │    diamond)     │  upper area of the window
 *     │   ·────────·    │  dot ↔ progress bar morph
 *     │    status       │  only visible during work
 *     │                 │
 *     └─────────────────┘
 * </pre>
 *
 * <h3>Thread safety</h3>
 * Volatile fields with coalesced EDT flush, same as before.
 */
final class LauncherWindow extends JFrame {

    // Portrait rectangle — 1:1.2 ratio
    private static final int WIDTH = 320;
    private static final int HEIGHT = 330;
    private static final int CORNER_ARC = 20;

    private static final Color BG = new Color(0x1A, 0x1A, 0x1A);
    private static final Color STATUS_COLOR = new Color(100, 100, 100);

    // Freed hands+diamond glyph (no card background), rendered at full
    // opacity and sized to fill the upper area. The box preserves the
    // glyph's 781:670 source aspect ratio.
    private static final int LOGO_W = 210;
    private static final int LOGO_H = 180;

    private static final long UPDATE_INTERVAL_MS = 33;

    // Short warm-up before the ETA is trusted — just enough samples for a
    // stable velocity fit, not a "hide until late" gate. Whether a phase is
    // "taking long" is conveyed by the ETA turning orange, not by appearing.
    private static final long ETA_SHOW_AFTER_MS = 4_000;
    // Sliding window the velocity is regressed over. Wide enough to smooth the
    // 1%-granularity of ollama's model-pull output, short enough to track real
    // rate changes (a download throttling) within a few seconds.
    private static final long ETA_WINDOW_MS = 10_000;

    private final JLabel statusLabel;
    private final IslandIndicator islandIndicator;
    private final ModelPips modelPips;
    private final ProgressInfoLine infoLine;

    // Progress-ratio samples for the ETA velocity fit. EDT-only — touched solely
    // from flush()/resetProgress(), both of which run on the Swing thread.
    private final Deque<long[]> etaSamples = new ArrayDeque<>(); // [time, ratioBits]
    private long etaPhaseStart;
    private double etaLastRatio = -1;

    private volatile String pendingStatus;

    private volatile double pendingProgress = Double.NaN;
    private volatile long pendingSpeed = Long.MIN_VALUE;
    private volatile boolean flushScheduled;
    private volatile long lastFlushTime;

    private int dragX, dragY;

    LauncherWindow() {
        configureFrame();
        statusLabel = createStatusLabel();
        islandIndicator = new IslandIndicator();
        modelPips = new ModelPips();
        infoLine = new ProgressInfoLine();
        setContentPane(buildLayout());
        installDragSupport();
    }

    // =====================================================================
    // Public API (called from update thread)
    // =====================================================================

    /** Sets the primary status line (e.g. "Installing models..."). */
    void setStatus(String text) {
        pendingStatus = text;
        scheduleFlush();
    }



    /**
     * Sets progress: 0.0–1.0 for determinate, negative for indeterminate,
     * or 1.0 to signal completion (bar collapses back to dot).
     */
    void setProgress(double ratio) {
        pendingProgress = ratio;
        scheduleFlush();
    }

    /**
     * Snaps the indicator back to the idle dot immediately. Use on
     * phase transitions instead of {@link #setProgress} to avoid
     * the expand animation and stale fill from the previous phase.
     */
    void resetProgress() {
        SwingUtilities.invokeLater(() -> {
            islandIndicator.reset();
            // New phase — discard the old velocity fit so the next phase's ETA
            // starts fresh instead of inheriting the previous slope.
            etaSamples.clear();
            etaLastRatio = -1;
            infoLine.clear();
        });
    }

    /**
     * Shows one pip per installing AI model, with {@code completed} of them
     * filled. Conveys how many models install (the download bar alone can't).
     */
    void setModelPips(int total, int completed) {
        SwingUtilities.invokeLater(() -> modelPips.set(total, completed));
    }

    /** Hides the model pips (called when the model-install phase ends). */
    void clearModelPips() {
        SwingUtilities.invokeLater(modelPips::clear);
    }

    /**
     * Sets download speed in bytes/sec. Ignores SPEED_UNCHANGED (-2)
     * to prevent it from overwriting valid pending values before the
     * EDT flush can read them.
     */
    void setSpeed(long bytesPerSec) {
        if (bytesPerSec == -2) return;
        pendingSpeed = bytesPerSec;
        scheduleFlush();
    }

    // =====================================================================
    // Frame Setup
    // =====================================================================

    private void configureFrame() {
        setUndecorated(true);
        setSize(WIDTH, HEIGHT);
        setLocationRelativeTo(null);
        setBackground(new Color(0, 0, 0, 0));
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setShape(new RoundRectangle2D.Double(0, 0, WIDTH, HEIGHT, CORNER_ARC, CORNER_ARC));

        URL iconUrl = getClass().getResource("/images/app-icon.png");
        if (iconUrl != null) {
            Image source = new ImageIcon(iconUrl).getImage();
            setIconImages(List.of(
                    source.getScaledInstance(16, 16, Image.SCALE_SMOOTH),
                    source.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
                    source.getScaledInstance(48, 48, Image.SCALE_SMOOTH),
                    source));
        }
    }

    private JPanel buildLayout() {
        JPanel root = new JPanel(new GridBagLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG);
                g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), CORNER_ARC, CORNER_ARC));

                // Subtle 1px border
                g2.setColor(new Color(255, 255, 255, 10));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Double(0.5, 0.5, getWidth() - 1, getHeight() - 1,
                        CORNER_ARC, CORNER_ARC));
                g2.dispose();
            }
        };
        root.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;

        // Hands + diamond glyph — full opacity, anchored near the top so it
        // fills the upper area of the window.
        gbc.gridy = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(28, 0, 22, 0);
        root.add(createLogoPanel(), gbc);

        // Dynamic Island indicator
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 8, 0);
        root.add(islandIndicator, gbc);

        // Model pips — one dot per installing AI model (hidden otherwise). Sits
        // directly under the bar, reserving fixed height so it never shifts the
        // layout when it appears/disappears.
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 0, 10, 0);
        root.add(modelPips, gbc);

        // Status label
        gbc.gridy = 4;
        gbc.insets = new Insets(0, 20, 2, 20);
        root.add(statusLabel, gbc);

        // Remaining-time + speed, side by side, centered directly under status.
        gbc.gridy = 5;
        gbc.insets = new Insets(0, 20, 0, 20);
        root.add(infoLine, gbc);

        // Bottom spacer — absorbs remaining height so the stack stays top-weighted
        gbc.gridy = 6;
        gbc.weighty = 1.0;
        gbc.insets = new Insets(0, 0, 0, 0);
        root.add(Box.createGlue(), gbc);

        return root;
    }

    private JPanel createLogoPanel() {
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

    private Image loadLogoImage() {
        // Freed hands+diamond glyph (transparent background). Falls back to
        // the full app icon if the glyph asset is missing.
        URL url = getClass().getResource("/images/logo-glyph.png");
        if (url == null) url = getClass().getResource("/images/app-icon.png");
        if (url == null) return null;
        return new ImageIcon(url).getImage();
    }

    private JLabel createStatusLabel() {
        JLabel label = new JLabel(" ");
        label.setForeground(STATUS_COLOR);
        label.setFont(new Font("SansSerif", Font.PLAIN, 11));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVisible(false);
        return label;
    }

    private void installDragSupport() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragX = e.getX();
                dragY = e.getY();
            }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                setLocation(e.getXOnScreen() - dragX, e.getYOnScreen() - dragY);
            }
        });
    }

    // =====================================================================
    // EDT Coalescing
    // =====================================================================

    private void scheduleFlush() {
        if (flushScheduled)
            return;
        flushScheduled = true;

        long elapsed = System.currentTimeMillis() - lastFlushTime;
        long delay = Math.max(0, UPDATE_INTERVAL_MS - elapsed);

        SwingUtilities.invokeLater(() -> {
            if (delay > 0) {
                Timer timer = new Timer((int) delay, _ -> flush());
                timer.setRepeats(false);
                timer.start();
            } else {
                flush();
            }
        });
    }

    private void flush() {
        String status = pendingStatus;
        double progress = pendingProgress;

        if (status != null && !status.isBlank()) {
            statusLabel.setText(status);
            statusLabel.setVisible(true);
            pendingStatus = null;
        } else if (status != null) {
            statusLabel.setVisible(false);
            pendingStatus = null;
        }

        if (!Double.isNaN(progress)) {
            islandIndicator.update(progress);
            updateEta(progress);
            pendingProgress = Double.NaN;
        }

        long speed = pendingSpeed;

        // Speed group — SPEED_UNCHANGED (-2) is filtered by setSpeed(). The
        // value persists between updates (no dismiss on a speed-less flush);
        // it's only hidden when an explicit -1 arrives at a phase boundary.
        if (speed != Long.MIN_VALUE) {
            infoLine.setSpeed(speed);
            pendingSpeed = Long.MIN_VALUE;
        }
        lastFlushTime = System.currentTimeMillis();
        flushScheduled = false;
    }

    // =====================================================================
    // ETA estimation (EDT-only)
    // =====================================================================

    /**
     * Feeds a progress ratio into the remaining-time estimator and updates the
     * {@link ProgressInfoLine}'s ETA group. The estimate is the linear-regression
     * slope of the most recent {@link #ETA_WINDOW_MS} of (time, ratio) samples —
     * i.e. the observed velocity — extrapolated to ratio 1.0. It is surfaced once
     * the phase has past the brief {@link #ETA_SHOW_AFTER_MS} warm-up and the
     * velocity is positive, so a stall or a not-yet-moving phase shows no guess.
     */
    private void updateEta(double ratio) {
        long now = System.currentTimeMillis();

        // Indeterminate or finished — nothing meaningful to extrapolate. Hide
        // only the ETA group; the speed group lives on its own.
        if (ratio < 0 || ratio >= 1.0) {
            etaSamples.clear();
            etaLastRatio = -1;
            infoLine.setEta(-1);
            return;
        }

        // Start of a phase, or a backwards jump (a new sub-step reusing the bar)
        // restarts the fit and the warm-up clock.
        if (etaSamples.isEmpty() || ratio + 0.0005 < etaLastRatio) {
            etaSamples.clear();
            etaPhaseStart = now;
        }
        etaLastRatio = ratio;
        etaSamples.addLast(new long[]{now, Double.doubleToRawLongBits(ratio)});

        // Drop samples outside the sliding window, always keeping a minimum
        // spread to regress over.
        while (etaSamples.size() > 2 && now - etaSamples.peekFirst()[0] > ETA_WINDOW_MS) {
            etaSamples.removeFirst();
        }

        if (now - etaPhaseStart < ETA_SHOW_AFTER_MS) {
            infoLine.setEta(-1);
            return;
        }

        double slope = ratioSlopePerMs(); // ratio units per millisecond
        if (slope <= 1e-9) {              // stalled or not yet moving
            infoLine.setEta(-1);
            return;
        }

        double remainingMs = (1.0 - ratio) / slope;
        infoLine.setEta(Math.round(remainingMs / 1000.0));
    }

    /**
     * Least-squares slope of ratio over time across the current sample window.
     * Time is taken relative to the first sample to keep the sums well-scaled.
     *
     * @return ratio increase per millisecond, or 0 if it can't be determined
     */
    private double ratioSlopePerMs() {
        int n = etaSamples.size();
        if (n < 2) return 0;

        long t0 = etaSamples.peekFirst()[0];
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (long[] s : etaSamples) {
            double x = s[0] - t0;
            double y = Double.longBitsToDouble(s[1]);
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double denom = n * sxx - sx * sx;
        if (denom == 0) return 0;
        return (n * sxy - sx * sy) / denom;
    }
}
