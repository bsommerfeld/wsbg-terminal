package de.bsommerfeld.tinyupdate.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Undecorated, dark-themed Swing window showing a sleek progress bar.
 * Designed to display update progress and environment setup status.
 *
 * <p>Uses a borderless layout with rounded corners and a custom-painted progress bar
 * to provide a native-feeling experience without looking like a generic Java app.
 */
final class LauncherWindow extends JFrame {

    private static final int WIDTH = 420;
    private static final int HEIGHT = 140;

    private static final Color BG = new Color(22, 22, 26);
    private static final Color FG = Color.WHITE;
    private static final Color ACCENT = new Color(90, 130, 255);
    private static final Color TRACK = new Color(45, 45, 50);
    private static final Color SUBTLE = new Color(140, 140, 150);

    private final JLabel titleLabel;
    private final JLabel statusLabel;
    private final ProgressBar progressBar;

    LauncherWindow() {
        setUndecorated(true);
        setSize(WIDTH, HEIGHT);
        setLocationRelativeTo(null);
        setBackground(new Color(0, 0, 0, 0));
        setAlwaysOnTop(true);
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);

        // Rounded window shape
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

        titleLabel = new JLabel("WSBG Terminal");
        titleLabel.setForeground(FG);
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));

        statusLabel = new JLabel("Initializing...");
        statusLabel.setForeground(SUBTLE);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));

        progressBar = new ProgressBar();

        JPanel textPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        textPanel.setOpaque(false);
        textPanel.add(titleLabel);
        textPanel.add(statusLabel);

        root.add(textPanel, BorderLayout.NORTH);
        root.add(Box.createVerticalStrut(12), BorderLayout.CENTER);
        root.add(progressBar, BorderLayout.SOUTH);

        setContentPane(root);
    }

    void setStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    /**
     * Sets the progress bar value.
     *
     * @param ratio 0.0 to 1.0 for determinate, or negative for indeterminate pulse
     */
    void setProgress(double ratio) {
        SwingUtilities.invokeLater(() -> {
            if (ratio < 0) {
                progressBar.setIndeterminate(true);
            } else {
                progressBar.setIndeterminate(false);
                progressBar.setValue(ratio);
            }
        });
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

            // Track
            g2.setColor(TRACK);
            g2.fillRoundRect(0, 0, w, h, ARC, ARC);

            if (indeterminate) {
                // Sliding pulse effect
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
