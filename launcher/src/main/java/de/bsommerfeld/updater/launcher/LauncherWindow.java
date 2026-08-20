package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
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
 * <h3>One window, several screens</h3>
 * The questions the launcher asks (language, update channel, AI model) are
 * screens inside this same window, not windows of their own: the frame keeps
 * its size and shape from start to finish, and every screen change is a
 * dissolve between two snapshots ({@link ScreenTransition}). Choice screens
 * share their chrome via {@link ChoiceScreen}.
 *
 * <p>Two further concerns are delegated: {@link LogoRenderer} builds the
 * pre-scaled logo panel, and {@link EtaEstimator} owns the remaining-time
 * regression + smoothing math. This class is the view + EDT-coalescing facade.
 *
 * <h3>Thread safety</h3>
 * Volatile fields with coalesced EDT flush, same as before.
 */
final class LauncherWindow extends JFrame {

    // Portrait rectangle — 1:1.2 ratio. Every screen the launcher shows lives
    // inside exactly this rectangle; the frame is never resized after it is
    // built (see ScreenTransition for why the morph that used to do so is gone).
    private static final int WIDTH = 320;
    private static final int HEIGHT = 330;
    private static final int CORNER_ARC = 20;

    /**
     * How long the finished choice screen stays up before the window dissolves
     * back to the progress screen. Long enough for the confirm to register as
     * an answer, short enough that a following question dissolves straight out
     * of the previous one — a pipeline that asks its next question inside this
     * beat cancels the return entirely and goes screen → screen.
     */
    private static final int RETURN_DELAY_MS = 220;

    /** The logo's top inset in the normal layout. */
    private static final int LOGO_TOP_NORMAL = 28;

    private static final Color BG = LauncherTheme.BG;
    private static final Color STATUS_COLOR = new Color(100, 100, 100);

    private static final long UPDATE_INTERVAL_MS = 33;

    private final JLabel statusLabel;
    private final IslandIndicator islandIndicator;
    private final ModelPips modelPips;
    private final ProgressInfoLine infoLine;
    private final JLabel bytesLabel;

    // Screen state. A choice screen replaces the content pane wholesale and
    // fills the same rectangle the progress screen does; the normal root
    // returns once the question is answered.
    private final JComponent logoPanel = LogoRenderer.createPanel();
    private final JPanel normalRoot;
    private Timer transitionTimer;
    private Timer returnTimer;
    private CompletableFuture<String> choiceFuture;

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
     * Dissolves the window into its language choice; the returned future
     * completes with the chosen ISO language code. Safe to call from any
     * thread; the caller blocks on the future.
     */
    CompletableFuture<String> showLanguageChoice(List<LanguageChoicePanel.Row> rows,
            String preselectCode) {
        return showChoice(logo ->
                new LanguageChoicePanel(rows, preselectCode, logo, this::onChoiceConfirmed));
    }

    /**
     * Dissolves the window into the update-channel question; the returned
     * future completes with the chosen answer ({@code "yes"}/{@code "no"}).
     * Safe to call from any thread; the caller blocks on the future.
     */
    CompletableFuture<String> showChannelChoice(List<ChannelChoicePanel.Row> rows,
            String preselectAnswer, ChannelChoicePanel.Labels labels) {
        return showChoice(logo ->
                new ChannelChoicePanel(rows, preselectAnswer, labels, logo,
                        this::onChoiceConfirmed));
    }

    /**
     * Dissolves the window into the model choice - a stack of tier cards the
     * user flips through; the returned future completes with the chosen tag.
     * Safe to call from any thread; the caller blocks on the future.
     */
    /**
     * The model choice, held on to after it closes: the screen answers with a
     * model TAG, but its advanced sheet may instead answer with an external
     * endpoint - two different things that one {@code CompletableFuture<String>}
     * cannot carry. The caller reads {@link #chosenEndpoint()} once the future
     * completes.
     */
    private ModelChoicePanel modelChoicePanel;

    CompletableFuture<String> showModelChoice(List<ModelChoicePanel.Row> rows,
            String preselectTag, ModelChoicePanel.Labels labels,
            AdvancedEndpointSheet.Labels advancedLabels) {
        return showChoice(logo -> {
            modelChoicePanel = new ModelChoicePanel(rows, preselectTag, labels,
                    advancedLabels, logo, this::onChoiceConfirmed);
            return modelChoicePanel;
        });
    }

    /** The external endpoint the model screen's advanced sheet held, or null. */
    AdvancedEndpointSheet.Endpoint chosenEndpoint() {
        return modelChoicePanel == null ? null : modelChoicePanel.chosenEndpoint();
    }

    /**
     * The shared entry for every choice screen: snapshots the splash logo for
     * the screen's corner, builds the view and dissolves the window into it.
     * A pending return to the progress screen (from the previous question) is
     * cancelled, so two questions in a row read as one continuous flow.
     */
    private CompletableFuture<String> showChoice(
            java.util.function.Function<BufferedImage, ChoiceScreen> viewFactory) {
        CompletableFuture<String> future = new CompletableFuture<>();
        choiceFuture = future;
        SwingUtilities.invokeLater(() -> {
            if (!isVisible()) setVisible(true);
            if (returnTimer != null) {
                returnTimer.stop();
                returnTimer = null;
            }

            // Snapshot the splash logo so the choice screen can put it in its
            // corner as a plain image — no component re-parenting.
            BufferedImage logo = new BufferedImage(
                    Math.max(1, logoPanel.getWidth()), Math.max(1, logoPanel.getHeight()),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D lg = logo.createGraphics();
            logoPanel.paint(lg);
            lg.dispose();

            transitionTo(viewFactory.apply(logo));
        });
        return future;
    }

    // =====================================================================
    // Frame Setup
    // =====================================================================

    private void configureFrame() {
        setUndecorated(true);

        // macOS: the launcher is an accessory app without a dock tile (see
        // LauncherMain) — so there is nothing to click when the splash ends up
        // behind another window. It floats instead. Everywhere else the window
        // has a taskbar button and keeps the normal stacking order.
        if (System.getProperty("os.name", "").toLowerCase().contains("mac"))
            setAlwaysOnTop(true);

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

        // The row directly under the bar, spanning exactly the bar's width (180
        // wide in a 240 row → 30 in on each side): model pips flush left (one
        // dot per installing AI model, hidden otherwise), the downloaded/total
        // figures flush right. The two split the row instead of sharing it -
        // centered pips grew into the figures as soon as those got long. Fixed
        // height so appearing/disappearing never shifts the layout.
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 0, 10, 0);
        JPanel underBar = new JPanel(null);
        underBar.setOpaque(false);
        underBar.setPreferredSize(new Dimension(240, 14));
        modelPips.setBounds(30, 0, 60, 14);
        underBar.add(modelPips);
        bytesLabel.setBounds(96, 1, 114, 13);
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
    // Screen changes
    // =====================================================================

    /**
     * A question was answered: hand the answer to the waiting pipeline at once
     * and schedule the dissolve back to the progress screen. The delay is what
     * lets a pipeline with another question ready go straight from one screen
     * into the next ({@link #showChoice} cancels this timer) instead of
     * blinking through the progress screen in between.
     */
    private void onChoiceConfirmed(String value) {
        CompletableFuture<String> future = choiceFuture;
        choiceFuture = null;

        if (returnTimer != null) returnTimer.stop();
        returnTimer = new Timer(RETURN_DELAY_MS, _ -> {
            returnTimer = null;
            transitionTo(normalRoot);
        });
        returnTimer.setRepeats(false);
        returnTimer.start();

        if (future != null) future.complete(value);
    }

    /**
     * Dissolves the currently shown screen into {@code next}. Both screens are
     * snapshotted first — the outgoing one as it looks right now (a transition
     * already running is simply caught mid-frame), the incoming one after it
     * has been installed and laid out — so the animation itself touches no
     * live component and mutates nothing native: same frame, same bounds, same
     * shape, one blit per frame via {@code paintImmediately}.
     */
    private void transitionTo(JComponent next) {
        if (transitionTimer != null) transitionTimer.stop();

        BufferedImage from = snapshot(getContentPane());
        setContentPane(next);
        validate();
        BufferedImage to = snapshot(next);

        ScreenTransition transition = new ScreenTransition(from, to);
        setContentPane(transition);
        validate();
        repaint();

        long start = System.currentTimeMillis();
        transitionTimer = new Timer(16, _ -> {
            float raw = Math.min(1f,
                    (System.currentTimeMillis() - start) / (float) ScreenTransition.DURATION_MS);
            // Cubic ease-in-out — soft start, soft landing, no snap.
            float e = raw < 0.5f ? 4 * raw * raw * raw
                    : 1 - (float) Math.pow(-2 * raw + 2, 3) / 2;
            transition.setProgress(e);
            transition.paintImmediately(0, 0, transition.getWidth(), transition.getHeight());
            if (raw >= 1f) {
                transitionTimer.stop();
                transitionTimer = null;
                setContentPane(next);
                validate();
                repaint();
            }
        });
        transitionTimer.start();
    }

    /** Paints {@code c} into an image at the window's fixed size. */
    private BufferedImage snapshot(Container c) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        c.paint(g);
        g.dispose();
        return image;
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
