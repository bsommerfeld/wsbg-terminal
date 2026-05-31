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

    private final JLabel statusLabel;
    private final IslandIndicator islandIndicator;
    private final RollingCounterLabel rollingCounter;

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
        rollingCounter = new RollingCounterLabel();
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
        SwingUtilities.invokeLater(islandIndicator::reset);
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
        gbc.insets = new Insets(0, 0, 16, 0);
        root.add(islandIndicator, gbc);

        // Status label
        gbc.gridy = 3;
        gbc.insets = new Insets(0, 20, 2, 20);
        root.add(statusLabel, gbc);

        // Rolling counter below status
        gbc.gridy = 4;
        gbc.insets = new Insets(0, 20, 0, 20);
        root.add(rollingCounter, gbc);

        // Bottom spacer — absorbs remaining height so the stack stays top-weighted
        gbc.gridy = 5;
        gbc.weighty = 1.0;
        gbc.insets = new Insets(0, 0, 0, 0);
        root.add(Box.createGlue(), gbc);

        return root;
    }

    private JPanel createLogoPanel() {
        Image logoSource = loadLogoImage();
        if (logoSource == null) return new JPanel();

        // Pre-render the glyph at full opacity, scaled to fit the LOGO_W×LOGO_H
        // box while preserving its source aspect ratio.
        int sw = logoSource.getWidth(null);
        int sh = logoSource.getHeight(null);
        double scale = Math.min((double) LOGO_W / sw, (double) LOGO_H / sh);
        int w = Math.max(1, (int) Math.round(sw * scale));
        int h = Math.max(1, (int) Math.round(sh * scale));

        BufferedImage scaledLogo = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaledLogo.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(logoSource, 0, 0, w, h, null);
        g2.dispose();

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = (getWidth() - scaledLogo.getWidth()) / 2;
                int y = (getHeight() - scaledLogo.getHeight()) / 2;
                g2d.drawImage(scaledLogo, x, y, null);
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(LOGO_W, LOGO_H));
        return panel;
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
            pendingProgress = Double.NaN;
        }

        long speed = pendingSpeed;

        // Speed indicator — SPEED_UNCHANGED (-2) is filtered by setSpeed()
        if (speed != Long.MIN_VALUE) {
            rollingCounter.setSpeed(speed);
            pendingSpeed = Long.MIN_VALUE;
        } else {
            rollingCounter.dismiss();
        }
        lastFlushTime = System.currentTimeMillis();
        flushScheduled = false;
    }
}
