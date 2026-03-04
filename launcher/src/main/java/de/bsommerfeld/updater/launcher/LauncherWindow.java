package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Undecorated, dark-themed Swing window that visualizes update/setup progress.
 *
 * <h3>Layout</h3>
 * Three text lines (title → status → detail) above a custom-painted progress
 * bar. The window has rounded corners and supports drag-to-move since there
 * is no title bar.
 *
 * <h3>Thread safety</h3>
 * {@link #setStatus}, {@link #setDetail}, and {@link #setProgress} are called
 * from the virtual update thread. They store values in {@code volatile} fields
 * and schedule a single EDT flush. Multiple rapid calls coalesce into one
 * repaint, capped at ~30 fps. This prevents Swing from drowning in repaint
 * events during fast Ollama/download output.
 *
 * <p>
 * Volatile fields instead of locks because overwrites are harmless — the
 * latest value always wins, and the window only needs to show the most
 * recent state.
 */
final class LauncherWindow extends JFrame {

    private static final int WIDTH = 420;
    private static final int HEIGHT = 180;
    private static final int CORNER_ARC = 16;
    private static final int INFO_ARC = 10;
    private static final long UPDATE_INTERVAL_MS = 33;

    private static final Color BG = new Color(22, 22, 26);
    private static final Color INFO_BG = new Color(14, 14, 18);
    private static final Color FG = Color.WHITE;
    private static final Color ACCENT = new Color(90, 130, 255);
    private static final Color TRACK = new Color(45, 45, 50);
    private static final Color SUBTLE = new Color(140, 140, 150);
    private static final Color DETAIL_COLOR = new Color(110, 110, 120);
    private static final Color CLOSE_HOVER = new Color(200, 60, 60);

    private final JLabel statusLabel;
    private final JLabel detailLabel;
    private final ProgressBar progressBar;

    private volatile String pendingStatus;
    private volatile String pendingDetail;
    private volatile double pendingProgress = Double.NaN;
    private volatile boolean flushScheduled;
    private volatile long lastFlushTime;

    private int dragX, dragY;

    LauncherWindow() {
        configureFrame();
        statusLabel = createLabel("Initializing...", SUBTLE, Font.PLAIN, 12);
        detailLabel = createLabel(" ", DETAIL_COLOR, Font.PLAIN, 11);
        progressBar = new ProgressBar();
        setContentPane(buildLayout());
        installDragSupport();
    }

    // =====================================================================
    // Public API (called from update thread)
    // =====================================================================

    /** Sets the primary status line (e.g. "Downloading update..."). */
    void setStatus(String text) {
        pendingStatus = text;
        scheduleFlush();
    }

    /** Sets the secondary detail line (e.g. "42% — 1.2 GB"). */
    void setDetail(String text) {
        pendingDetail = text;
        scheduleFlush();
    }

    /**
     * Sets the progress bar position.
     *
     * @param ratio 0.0–1.0 for determinate progress, or negative for
     *              indeterminate pulse animation
     */
    void setProgress(double ratio) {
        pendingProgress = ratio;
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

        // Windows picks the closest size from iconImages for taskbar/alt-tab.
        // A single 512px source gets downscaled poorly by the Windows WM —
        // providing pre-scaled sizes avoids that blur.
        java.net.URL iconUrl = getClass().getResource("/images/app-icon.png");
        if (iconUrl != null) {
            Image source = new ImageIcon(iconUrl).getImage();
            setIconImages(java.util.List.of(
                    source.getScaledInstance(16, 16, Image.SCALE_SMOOTH),
                    source.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
                    source.getScaledInstance(48, 48, Image.SCALE_SMOOTH),
                    source));
        }
    }

    private JPanel buildLayout() {
        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG);
                g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), CORNER_ARC, CORNER_ARC));
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(18, 24, 14, 24));

        // Darker inset container for status + detail labels
        JPanel infoPanel = new JPanel(new GridLayout(2, 1, 0, 4)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(INFO_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), INFO_ARC, INFO_ARC);
                g2.dispose();
            }
        };
        infoPanel.setOpaque(false);
        infoPanel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        infoPanel.add(statusLabel);
        infoPanel.add(detailLabel);

        // Title + info container stacked vertically
        JPanel topSection = new JPanel(new BorderLayout(0, 12));
        topSection.setOpaque(false);
        topSection.add(createLabel("WSBG Terminal", FG, Font.BOLD, 15), BorderLayout.NORTH);
        topSection.add(infoPanel, BorderLayout.CENTER);

        // Bottom row: progress bar + close button (right-aligned, non-standard)
        JPanel bottomRow = new JPanel(new BorderLayout(8, 0));
        bottomRow.setOpaque(false);
        bottomRow.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        bottomRow.add(progressBar, BorderLayout.CENTER);
        bottomRow.add(createCloseButton(), BorderLayout.EAST);

        root.add(topSection, BorderLayout.CENTER);
        root.add(bottomRow, BorderLayout.SOUTH);

        return root;
    }

    private JButton createCloseButton() {
        JButton btn = new JButton("✕");
        btn.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btn.setForeground(SUBTLE);
        btn.setBackground(BG);
        btn.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
        btn.setFocusPainted(false);
        btn.setContentAreaFilled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btn.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                btn.setForeground(FG);
                btn.setContentAreaFilled(true);
                btn.setBackground(CLOSE_HOVER);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                btn.setForeground(SUBTLE);
                btn.setContentAreaFilled(false);
            }
        });

        btn.addActionListener(e -> System.exit(0));
        return btn;
    }

    private JLabel createLabel(String text, Color color, int style, int size) {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(new Font("SansSerif", style, size));
        return label;
    }

    /**
     * Undecorated windows have no title bar, so drag-to-move must be
     * implemented manually via mouse press/drag listeners.
     */
    private void installDragSupport() {
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                dragX = e.getX();
                dragY = e.getY();
            }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                setLocation(e.getXOnScreen() - dragX, e.getYOnScreen() - dragY);
            }
        });
    }

    // =====================================================================
    // EDT Coalescing
    // =====================================================================

    /**
     * Schedules a single EDT flush. If a flush is already pending, this is a
     * no-op — the pending flush will pick up the latest volatile values.
     * The flush is delayed by the remaining time in the current frame interval
     * to cap repaints at ~30 fps.
     */
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

    /**
     * Applies all pending state to the Swing components in one atomic EDT pass.
     * Each field is only consumed if a new value was set since the last flush.
     */
    private void flush() {
        String status = pendingStatus;
        String detail = pendingDetail;
        double progress = pendingProgress;

        if (status != null) {
            statusLabel.setText(status);
            pendingStatus = null;
        }

        // Detail uses a space placeholder when null to maintain consistent line height
        detailLabel.setText(detail != null ? detail : " ");
        pendingDetail = null;

        if (!Double.isNaN(progress)) {
            progressBar.setIndeterminate(progress < 0);
            if (progress >= 0)
                progressBar.setValue(progress);
            pendingProgress = Double.NaN;
        }

        lastFlushTime = System.currentTimeMillis();
        flushScheduled = false;
    }

    // =====================================================================
    // Progress Bar
    // =====================================================================

    /**
     * Custom-painted progress bar with rounded caps and an indeterminate pulse
     * animation. Avoids the default Swing LAF which looks different on every
     * platform — this provides a consistent dark-theme appearance everywhere.
     */
    private static final class ProgressBar extends JComponent {

        private static final int BAR_HEIGHT = 6;
        private static final int ARC = 6;

        private double value = 0;
        private boolean indeterminate = false;
        private float pulseOffset = 0f;
        private Timer pulseTimer;

        ProgressBar() {
            setPreferredSize(new Dimension(0, BAR_HEIGHT));
        }

        void setValue(double v) {
            this.value = Math.clamp(v, 0.0, 1.0);
            repaint();
        }

        void setIndeterminate(boolean b) {
            if (this.indeterminate == b)
                return;
            this.indeterminate = b;

            if (b) {
                pulseTimer = new Timer(30, _ -> {
                    pulseOffset += 0.015f;
                    if (pulseOffset > 1f)
                        pulseOffset = 0f;
                    repaint();
                });
                pulseTimer.start();
            } else if (pulseTimer != null) {
                pulseTimer.stop();
                pulseTimer = null;
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            g2.setColor(TRACK);
            g2.fillRoundRect(0, 0, w, h, ARC, ARC);

            if (indeterminate) {
                int pulseWidth = w / 3;
                int x = (int) ((w + pulseWidth) * pulseOffset) - pulseWidth;
                g2.setColor(ACCENT);
                g2.setClip(new RoundRectangle2D.Double(0, 0, w, h, ARC, ARC));
                g2.fillRoundRect(x, 0, pulseWidth, h, ARC, ARC);
            } else {
                int fillWidth = (int) (w * value);
                if (fillWidth > 0) {
                    g2.setColor(ACCENT);
                    g2.fillRoundRect(0, 0, fillWidth, h, ARC, ARC);
                }
            }

            g2.dispose();
        }
    }
}
