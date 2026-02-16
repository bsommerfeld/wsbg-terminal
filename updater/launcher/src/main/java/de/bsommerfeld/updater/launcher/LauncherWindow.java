package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Undecorated, dark-themed Swing window showing update progress.
 *
 * <p>Three-line layout: title, status (phase), detail (file name/bytes).
 * Custom-painted progress bar avoids platform-inconsistent Swing LAF.
 * Updates are coalesced at ~30fps to prevent UI flickering from rapid progress events.
 */
final class LauncherWindow extends JFrame {

    private static final int WIDTH = 420;
    private static final int HEIGHT = 160;
    private static final long UPDATE_INTERVAL_MS = 33; // ~30fps

    private static final Color BG = new Color(22, 22, 26);
    private static final Color FG = Color.WHITE;
    private static final Color ACCENT = new Color(90, 130, 255);
    private static final Color TRACK = new Color(45, 45, 50);
    private static final Color SUBTLE = new Color(140, 140, 150);
    private static final Color DETAIL_COLOR = new Color(110, 110, 120);

    private final JLabel statusLabel;
    private final JLabel detailLabel;
    private final ProgressBar progressBar;

    // Coalescing state — written from any thread, flushed on EDT at capped rate.
    // Volatile fields instead of locks because overwrites are harmless (latest wins).
    private volatile String pendingStatus;
    private volatile String pendingDetail;
    private volatile double pendingProgress = Double.NaN;
    private volatile boolean flushScheduled;
    private volatile long lastFlushTime;

    LauncherWindow() {
        setUndecorated(true);
        setSize(WIDTH, HEIGHT);
        setLocationRelativeTo(null);
        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        setShape(new RoundRectangle2D.Double(0, 0, WIDTH, HEIGHT, 16, 16));

        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(BG);
                g2.fill(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 16, 16));
                g2.dispose();
            }
        };
        root.setOpaque(false);
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        JLabel titleLabel = new JLabel("WSBG Terminal");
        titleLabel.setForeground(FG);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));

        statusLabel = new JLabel("Initializing...");
        statusLabel.setForeground(SUBTLE);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));

        detailLabel = new JLabel(" ");
        detailLabel.setForeground(DETAIL_COLOR);
        detailLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));

        progressBar = new ProgressBar();

        JPanel textPanel = new JPanel(new GridLayout(3, 1, 0, 1));
        textPanel.setOpaque(false);
        textPanel.add(titleLabel);
        textPanel.add(statusLabel);
        textPanel.add(detailLabel);

        root.add(textPanel, BorderLayout.NORTH);
        root.add(Box.createVerticalStrut(10), BorderLayout.CENTER);
        root.add(progressBar, BorderLayout.SOUTH);

        setContentPane(root);
    }

    void setStatus(String text) {
        pendingStatus = text;
        scheduleFlush();
    }

    void setDetail(String text) {
        pendingDetail = text;
        scheduleFlush();
    }

    /**
     * @param ratio 0.0 to 1.0 for determinate, or negative for indeterminate pulse
     */
    void setProgress(double ratio) {
        pendingProgress = ratio;
        scheduleFlush();
    }

    /**
     * Schedules a single EDT flush. Multiple rapid calls coalesce into one
     * repaint, capped at ~30fps. This prevents Swing from drowning in
     * repaint events during fast Ollama/download output.
     */
    private void scheduleFlush() {
        if (flushScheduled) return;
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
        String detail = pendingDetail;
        double progress = pendingProgress;

        if (status != null) {
            statusLabel.setText(status);
            pendingStatus = null;
        }
        detailLabel.setText(detail != null ? detail : " ");
        pendingDetail = null;

        if (!Double.isNaN(progress)) {
            if (progress < 0) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setValue(progress);
            }
            pendingProgress = Double.NaN;
        }

        lastFlushTime = System.currentTimeMillis();
        flushScheduled = false;
    }

    /**
     * Custom painted progress bar — avoids the default Swing LAF that looks platform-inconsistent.
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
            if (this.indeterminate == b) return;
            this.indeterminate = b;

            if (b) {
                pulseTimer = new Timer(30, _ -> {
                    pulseOffset += 0.015f;
                    if (pulseOffset > 1f) pulseOffset = 0f;
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
