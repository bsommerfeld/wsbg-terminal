package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.net.URL;
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
 * <p>Two concerns are delegated: {@link LogoRenderer} builds the pre-scaled
 * logo panel, and {@link EtaEstimator} owns the remaining-time regression +
 * smoothing math. This class is the view + EDT-coalescing facade.
 *
 * <h3>Thread safety</h3>
 * Volatile fields with coalesced EDT flush, same as before.
 */
final class LauncherWindow extends JFrame {

    // Portrait rectangle — 1:1.2 ratio
    private static final int WIDTH = 320;
    private static final int HEIGHT = 330;
    private static final int CORNER_ARC = 20;

    private static final Color BG = LauncherTheme.BG;
    private static final Color STATUS_COLOR = new Color(100, 100, 100);

    private static final long UPDATE_INTERVAL_MS = 33;

    private final JLabel statusLabel;
    private final IslandIndicator islandIndicator;
    private final ModelPips modelPips;
    private final ProgressInfoLine infoLine;

    // Remaining-time estimator. EDT-only — touched solely from
    // flush()/resetProgress(), both of which run on the Swing thread.
    private final EtaEstimator etaEstimator = new EtaEstimator();

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
            etaEstimator.reset();
            infoLine.clear();
        });
    }

    /**
     * Shows one pip per installing AI model, with {@code started} of them
     * filled (a pip lights the moment its model's pull begins). Conveys how
     * many models install (the download bar alone can't).
     */
    void setModelPips(int total, int started) {
        SwingUtilities.invokeLater(() -> modelPips.set(total, started));
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
        root.add(LogoRenderer.createPanel(), gbc);

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
            long etaSecs = etaEstimator.sample(progress);
            if (etaSecs != EtaEstimator.NO_CHANGE) {
                infoLine.setEta(etaSecs);
            }
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
}
