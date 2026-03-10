package de.bsommerfeld.updater.launcher;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

/**
 * Dual-mode status label below the main status line.
 *
 * <p><b>Count mode:</b> Displays "{current}/{total}" during file extraction.
 * <p><b>Speed mode:</b> Displays a WiFi alert icon + download speed,
 * only when speed drops below 1 MB/s. Speed value rolls with
 * ease-in-out interpolation.
 *
 * <p>Modes are mutually exclusive with guards to prevent overlap.
 * A 500ms grace period prevents fading during brief phase
 * transitions (e.g., between consecutive downloads).
 */
final class RollingCounterLabel extends JComponent {

    private static final Color BG_COLOR = new Color(0x1A, 0x1A, 0x1A);
    private static final Color TEXT_COLOR = new Color(110, 110, 120);
    private static final Color WARN_COLOR = new Color(0xCC, 0x88, 0x44);
    private static final Font COUNTER_FONT = new Font("SFMono-Regular", Font.PLAIN, 10);

    private static final long SLOW_THRESHOLD_BYTES = 1_200_000;

    // Grace period before fading out — bridges brief gaps
    // between consecutive downloads (extract step, diff check).
    private static final long DISMISS_GRACE_MS = 500;

    // Duration of the ease-in-out speed roll animation
    private static final long ROLL_DURATION_MS = 400;

    private enum Mode { NONE, COUNT, SPEED }

    private Mode activeMode = Mode.NONE;

    // Count state
    private int currentCount = 0;
    private int totalCount = 0;

    // Speed state — ease-in-out interpolation from startSpeed to targetSpeed
    private long targetSpeed = -1;
    private float startSpeed = 0;
    private float displayedSpeed = 0;
    private long rollStartTime = 0;

    private float opacity = 0f;
    private boolean active = false;
    private long dismissRequestTime = 0;

    private final BufferedImage wifiIcon;

    RollingCounterLabel() {
        setPreferredSize(new Dimension(180, 14));
        setOpaque(false);
        wifiIcon = loadWifiIcon();
        Timer timer = new Timer(16, _ -> tick());
        timer.start();
    }

    /** Sets file extraction count — switches to count mode. */
    void setCount(int current, int total) {
        currentCount = current;
        totalCount = total;
        activeMode = Mode.COUNT;
        active = true;
        dismissRequestTime = 0;
    }

    /**
     * Sets download speed — switches to speed mode only if speed
     * is below the slow threshold. Fast downloads dismiss the indicator.
     */
    void setSpeed(long bytesPerSec) {
        if (bytesPerSec >= 0 && bytesPerSec < SLOW_THRESHOLD_BYTES) {
            if (targetSpeed != bytesPerSec) {
                startSpeed = displayedSpeed;
                rollStartTime = System.currentTimeMillis();
                targetSpeed = bytesPerSec;
            }
            activeMode = Mode.SPEED;
            active = true;
            dismissRequestTime = 0;
        } else if (activeMode == Mode.SPEED) {
            requestDismiss();
        }
    }

    /** Triggers fade-out with grace period. */
    void dismiss() {
        requestDismiss();
    }

    private void requestDismiss() {
        if (dismissRequestTime == 0) {
            dismissRequestTime = System.currentTimeMillis();
        }
    }

    private void tick() {
        // Apply grace period before actually deactivating
        if (dismissRequestTime > 0) {
            if (System.currentTimeMillis() - dismissRequestTime >= DISMISS_GRACE_MS) {
                active = false;
                dismissRequestTime = 0;
            }
        }

        if (active && opacity < 0.55f) {
            opacity = Math.min(0.55f, opacity + 0.08f);
        } else if (!active && opacity > 0f) {
            opacity = Math.max(0f, opacity - 0.06f);
            if (opacity < 0.02f) {
                opacity = 0f;
                activeMode = Mode.NONE;
                currentCount = 0;
                totalCount = 0;
                targetSpeed = -1;
                displayedSpeed = 0;
                // Force parent to repaint our area — non-opaque components
                // leave stale pixels unless the parent clears them.
                if (getParent() != null) {
                    getParent().repaint(getX(), getY(), getWidth(), getHeight());
                }
            }
        }

        // Ease-in-out interpolation: slow→fast→slow
        if (activeMode == Mode.SPEED && targetSpeed >= 0) {
            long elapsed = System.currentTimeMillis() - rollStartTime;
            float t = Math.min(1f, (float) elapsed / ROLL_DURATION_MS);

            // Smoothstep: 3t² - 2t³ (slow start, fast middle, slow end)
            float eased = t * t * (3f - 2f * t);

            displayedSpeed = startSpeed + (targetSpeed - startSpeed) * eased;
        }

        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (opacity <= 0f || activeMode == Mode.NONE) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setFont(COUNTER_FONT);
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, opacity));

        if (activeMode == Mode.COUNT) {
            paintCount(g2);
        } else if (activeMode == Mode.SPEED) {
            paintSpeed(g2);
        }

        g2.dispose();
    }

    private void paintCount(Graphics2D g2) {
        g2.setColor(TEXT_COLOR);
        FontMetrics fm = g2.getFontMetrics();
        String text = currentCount + "/" + totalCount;
        int x = (getWidth() - fm.stringWidth(text)) / 2;
        int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
        g2.drawString(text, x, y);
    }

    private void paintSpeed(Graphics2D g2) {
        FontMetrics fm = g2.getFontMetrics();
        String text = formatSpeed((long) displayedSpeed);
        int textWidth = fm.stringWidth(text);

        // Icon is pre-scaled to 14×14 at load time — no runtime scaling
        int iconW = wifiIcon != null ? wifiIcon.getWidth() : 0;
        int iconH = wifiIcon != null ? wifiIcon.getHeight() : 0;

        int gap = 4;
        int totalWidth = iconW + gap + textWidth;
        int startX = (getWidth() - totalWidth) / 2;
        int textY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;

        // WiFi alert icon — vertically centered with text
        if (wifiIcon != null) {
            int iconY = textY - fm.getAscent() + (fm.getAscent() - iconH) / 2;
            g2.drawImage(wifiIcon, startX, iconY, null);
        }

        g2.setColor(WARN_COLOR);
        g2.drawString(text, startX + iconW + gap, textY);
    }

    private static final int ICON_SIZE = 12;

    private static BufferedImage loadWifiIcon() {
        try (InputStream is = RollingCounterLabel.class.getResourceAsStream("/images/wifi-alert.png")) {
            if (is == null) return null;
            BufferedImage raw = ImageIO.read(is);

            // Pre-scale to target size with high-quality interpolation
            BufferedImage scaled = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(raw, 0, 0, ICON_SIZE, ICON_SIZE, null);
            g.dispose();
            return scaled;
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String formatSpeed(long bytesPerSec) {
        if (bytesPerSec < 1024) return bytesPerSec + " B/s";
        if (bytesPerSec < 1024 * 1024) return (bytesPerSec / 1024) + " KB/s";
        return String.format("%.1f MB/s", bytesPerSec / (1024.0 * 1024.0));
    }
}
