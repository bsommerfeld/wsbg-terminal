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
 * <h3>Layout (vertical, centered)</h3>
 * <pre>
 *     ┌─────────────────┐
 *     │                 │
 *     │    (logo)       │  20% opacity, centered
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

    // Logo opacity — low enough to look "stamped into" the surface
    private static final float LOGO_OPACITY = 0.20f;
    private static final int LOGO_SIZE = 100;

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

        // Spacer — pushes logo toward vertical center
        gbc.gridy = 0;
        gbc.weighty = 1.0;
        root.add(Box.createGlue(), gbc);

        // Logo at low opacity
        gbc.gridy = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(0, 0, 24, 0);
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

        // Bottom spacer
        gbc.gridy = 5;
        gbc.weighty = 0.6;
        gbc.insets = new Insets(0, 0, 0, 0);
        root.add(Box.createGlue(), gbc);

        return root;
    }

    private JPanel createLogoPanel() {
        Image logoSource = loadLogoImage();
        if (logoSource == null) return new JPanel();

        // Pre-render stamped logo at low opacity
        BufferedImage scaledLogo = new BufferedImage(LOGO_SIZE, LOGO_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = scaledLogo.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, LOGO_OPACITY));
        g2.drawImage(logoSource, 0, 0, LOGO_SIZE, LOGO_SIZE, null);
        g2.dispose();

        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2d = (Graphics2D) g;
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int x = (getWidth() - LOGO_SIZE) / 2;
                int y = (getHeight() - LOGO_SIZE) / 2;
                g2d.drawImage(scaledLogo, x, y, null);
            }
        };
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(LOGO_SIZE, LOGO_SIZE));
        return panel;
    }

    private Image loadLogoImage() {
        URL url = getClass().getResource("/images/app-icon.png");
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
