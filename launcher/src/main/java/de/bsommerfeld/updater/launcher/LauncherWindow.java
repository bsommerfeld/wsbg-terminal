package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    // Model-choice morph target: +15% in both directions. The frame is
    // resized to this ONCE at morph start (top edge anchored, horizontally
    // centered) — the visible morph is pure repainting inside the already
    // transparent frame, which is what keeps it smooth: a native window
    // resize per frame stutters on macOS.
    private static final int CHOICE_WIDTH = 368;
    private static final int CHOICE_HEIGHT = 380;
    private static final int MORPH_MS = 450;

    /** The logo's top inset in the normal layout (the morph's start point). */
    private static final int LOGO_TOP_NORMAL = 28;

    private static final Color BG = LauncherTheme.BG;
    private static final Color STATUS_COLOR = new Color(100, 100, 100);

    private static final long UPDATE_INTERVAL_MS = 33;

    private final JLabel statusLabel;
    private final IslandIndicator islandIndicator;
    private final ModelPips modelPips;
    private final ProgressInfoLine infoLine;
    private final JLabel bytesLabel;

    // Model-choice morph state. The choice view replaces the content pane
    // wholesale and paints the (smaller) window body itself while morphing;
    // the normal root returns after the collapse.
    private final JComponent logoPanel = LogoRenderer.createPanel();
    private final JPanel normalRoot;
    private ModelChoicePanel choicePanel;
    private Timer morphTimer;
    private long morphStart;
    private boolean morphExpanding;
    private CompletableFuture<String> choiceFuture;
    private String chosenTag;

    // Remaining-time estimator. EDT-only — touched solely from
    // flush()/resetProgress(), both of which run on the Swing thread.
    private final EtaEstimator etaEstimator = new EtaEstimator();

    private volatile String pendingStatus;

    private volatile double pendingProgress = Double.NaN;
    private volatile long pendingSpeed = Long.MIN_VALUE;
    private volatile String pendingBytes = "";
    private volatile boolean bytesDirty;
    private volatile boolean flushScheduled;
    private volatile long lastFlushTime;

    private int dragX, dragY;

    LauncherWindow() {
        configureFrame();
        statusLabel = createStatusLabel();
        islandIndicator = new IslandIndicator();
        modelPips = new ModelPips();
        infoLine = new ProgressInfoLine();
        bytesLabel = createBytesLabel();
        normalRoot = buildLayout();
        setContentPane(normalRoot);
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
            bytesLabel.setText(" ");
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

    /**
     * Shows the verbatim "downloaded / total" figures of the running download
     * top-right above the progress bar (e.g. "739 MB / 3.3 GB"), so the user
     * sees where in the whole download they are — the bar alone only shows a
     * ratio. {@code null} hides the readout (no figures known).
     */
    void setByteFigures(String figures) {
        pendingBytes = figures == null ? "" : figures;
        bytesDirty = true;
        scheduleFlush();
    }

    /**
     * Morphs the window into its large model-choice state and shows the tier
     * list; the returned future completes with the chosen tag after the user
     * confirmed and the window has morphed back to its normal state. Safe to
     * call from any thread; the caller blocks on the future.
     */
    CompletableFuture<String> showModelChoice(List<ModelChoicePanel.Row> rows,
            String preselectTag, ModelChoicePanel.Labels labels) {
        CompletableFuture<String> future = new CompletableFuture<>();
        choiceFuture = future;
        SwingUtilities.invokeLater(() -> {
            if (!isVisible()) setVisible(true);

            // Snapshot the splash logo so the choice view can glide it into
            // the corner as a plain image — no component re-parenting.
            java.awt.image.BufferedImage logo = new java.awt.image.BufferedImage(
                    Math.max(1, logoPanel.getWidth()), Math.max(1, logoPanel.getHeight()),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D lg = logo.createGraphics();
            logoPanel.paint(lg);
            lg.dispose();

            choicePanel = new ModelChoicePanel(rows, preselectTag, labels,
                    logo, WIDTH, HEIGHT, LOGO_TOP_NORMAL, this::onModelChosen);

            // Size and shape the frame to the target ONCE — top edge
            // anchored, horizontally centered. The newly claimed area is
            // still transparent at this instant, so nothing visibly jumps;
            // from here on the morph is pure synchronous painting (see the
            // flicker discipline on ModelChoicePanel).
            setBounds(getX() - (CHOICE_WIDTH - WIDTH) / 2, getY(),
                    CHOICE_WIDTH, CHOICE_HEIGHT);
            setShape(new RoundRectangle2D.Double(0, 0, CHOICE_WIDTH, CHOICE_HEIGHT,
                    CORNER_ARC, CORNER_ARC));
            setContentPane(choicePanel);
            validate();
            repaint();
            startMorph(true);
        });
        return future;
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
        JPanel root = createRoundedRoot();

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;

        // Hands + diamond glyph — full opacity, anchored near the top so it
        // fills the upper area of the window.
        gbc.gridy = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(LOGO_TOP_NORMAL, 0, 22, 0);
        root.add(logoPanel, gbc);

        // Dynamic Island indicator
        gbc.gridy = 2;
        gbc.insets = new Insets(0, 0, 8, 0);
        root.add(islandIndicator, gbc);

        // The row directly under the bar: model pips centered (one dot per
        // installing AI model, hidden otherwise) with the downloaded/total
        // figures bottom-right, right-aligned to the bar's right edge. Fixed
        // height so appearing/disappearing never shifts the layout.
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 0, 10, 0);
        JPanel underBar = new JPanel(null);
        underBar.setOpaque(false);
        underBar.setPreferredSize(new Dimension(240, 14));
        modelPips.setBounds(0, 0, 240, 14);
        underBar.add(modelPips);
        // The bar is 180 wide in a 240 row → its right edge sits 30 in.
        bytesLabel.setBounds(90, 1, 120, 13);
        underBar.add(bytesLabel);
        root.add(underBar, gbc);

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

    /** The rounded matte-dark window body of the normal layout. */
    private JPanel createRoundedRoot() {
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
        return root;
    }

    // =====================================================================
    // Model-choice morph
    // =====================================================================

    private void onModelChosen(String tag) {
        chosenTag = tag;
        // Back to the morphing surface: transparent outside the shrinking body.
        choicePanel.setRested(false);
        startMorph(false);
    }

    /**
     * Drives the morph clock. During the animation the frame is never
     * touched natively (no bounds, no shape — each such change applies on
     * the compositor ahead of the matching paint and reads as flicker);
     * every tick paints the eased (cubic in-out) state synchronously in one
     * blit. Collapsing shrinks the frame back around its CURRENT position
     * (the window may have been dragged), restores the normal root and
     * completes the choice future.
     */
    private void startMorph(boolean expanding) {
        if (morphTimer != null) morphTimer.stop();
        morphExpanding = expanding;
        morphStart = System.currentTimeMillis();
        morphTimer = new Timer(16, _ -> morphTick());
        morphTimer.start();
    }

    private void morphTick() {
        float raw = Math.min(1f, (System.currentTimeMillis() - morphStart) / (float) MORPH_MS);
        float t = morphExpanding ? raw : 1f - raw;
        // Cubic ease-in-out — soft start, soft landing, no snap.
        float e = t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;

        choicePanel.setMorphT(e);
        choicePanel.paintImmediately(0, 0, choicePanel.getWidth(), choicePanel.getHeight());

        if (raw >= 1f) {
            morphTimer.stop();
            if (morphExpanding) {
                // At rest the body fills the frame — flip opaque so ordinary
                // hover repaints can never clear-bleed the desktop through.
                choicePanel.setRested(true);
            } else {
                setBounds(getX() + (CHOICE_WIDTH - WIDTH) / 2, getY(), WIDTH, HEIGHT);
                setShape(new RoundRectangle2D.Double(0, 0, WIDTH, HEIGHT,
                        CORNER_ARC, CORNER_ARC));
                setContentPane(normalRoot);
                validate();
                repaint();
                CompletableFuture<String> future = choiceFuture;
                choiceFuture = null;
                if (future != null) future.complete(chosenTag);
            }
        }
    }

    private JLabel createStatusLabel() {
        JLabel label = new JLabel(" ");
        label.setForeground(STATUS_COLOR);
        label.setFont(new Font("SansSerif", Font.PLAIN, 11));
        label.setHorizontalAlignment(SwingConstants.CENTER);
        label.setVisible(false);
        return label;
    }

    private JLabel createBytesLabel() {
        JLabel label = new JLabel(" ");
        label.setForeground(new Color(110, 110, 118));
        label.setFont(new Font("SFMono-Regular", Font.PLAIN, 10));
        label.setHorizontalAlignment(SwingConstants.RIGHT);
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

        if (bytesDirty) {
            String figures = pendingBytes;
            bytesLabel.setText(figures.isEmpty() ? " " : figures);
            bytesDirty = false;
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
