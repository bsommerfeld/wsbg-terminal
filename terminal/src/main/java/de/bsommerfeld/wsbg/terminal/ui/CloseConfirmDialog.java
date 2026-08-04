package de.bsommerfeld.wsbg.terminal.ui;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.font.TextAttribute;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The terminal's own confirmation sheet — a small, flat, dark panel that reads
 * like the window it interrupts, instead of the system {@code JOptionPane} with
 * its platform icons and chrome.
 *
 * <p>Deliberately hand-painted: the page's design tokens (bg/line/txt/mute/amber)
 * are Swing-side constants here, because the live theme lives in the page's
 * localStorage and cannot be read from a synchronous close veto. Dark is the
 * terminal's identity — the native frame is themed dark unconditionally too.
 *
 * <p>The layout follows the page's own overlay language ({@code css/overlay.css}
 * + {@code css/settings.css}) one to one: a raised {@code --bg-1} panel over a
 * {@code --bg} title strip, a 12px radius, the overlay's soft drop shadow, a
 * title line carrying the amber "still running" dot, the explanation in
 * {@code --txt-2}, and the two actions as the page's {@code .plain-btn} /
 * {@code .danger-btn} pair — neutral outline for staying, red for the
 * destructive answer.
 */
final class CloseConfirmDialog {

    // Design tokens, mirrored from web/css/tokens.css (dark theme), converted
    // from their oklch() definitions to sRGB.
    private static final Color BG = new Color(0x0E, 0x0C, 0x0A);        // --bg
    private static final Color BG_1 = new Color(0x15, 0x12, 0x10);      // --bg-1
    private static final Color BG_HOVER = new Color(0x1B, 0x17, 0x15);  // --bg-hover
    private static final Color LINE = new Color(0x29, 0x26, 0x24);      // --line
    private static final Color LINE_SOFT = new Color(0x1E, 0x1B, 0x19); // --line-soft
    private static final Color TXT = new Color(0xEA, 0xE7, 0xE4);       // --txt
    private static final Color TXT_2 = new Color(0xB9, 0xB7, 0xB4);     // --txt-2
    private static final Color MUTE = new Color(0x74, 0x71, 0x6E);      // --mute
    private static final Color MUTE_2 = new Color(0x4F, 0x4D, 0x4A);    // --mute-2
    private static final Color AMBER = new Color(0xF9, 0xB6, 0x4F);     // --amber
    private static final Color RED = new Color(0xFF, 0x6C, 0x5D);       // --red

    /** The window's own titlebar height (--titlebar-h). */
    private static final int TITLEBAR_H = 38;
    /** The overlay panel's radius. */
    private static final int RADIUS = 12;
    /** Transparent margin the drop shadow is painted into. */
    private static final int SHADOW = 22;
    /** The sheet's fixed width — a confirm sheet is a sentence wide, not a window. */
    private static final int SHEET_W = 460;
    private static final int PAD_X = 28;

    /**
     * Per-pixel window translucency: without it the shadow margin would paint as
     * a black box around the sheet, so both the shadow and its inset are dropped
     * and the square opaque panel stands on its own.
     */
    private static final boolean TRANSLUCENT = translucencySupported();

    private CloseConfirmDialog() {
    }

    /**
     * Shows the sheet modally and returns the answer.
     *
     * @return {@code true} when the user picked the confirming (destructive) action
     */
    static boolean confirm(Window parent, String title, String message,
                           String confirmLabel, String cancelLabel) {
        JDialog dialog = new JDialog(parent, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setUndecorated(true);
        dialog.setResizable(false);

        final boolean[] confirmed = {false};
        Sheet sheet = sheet(dialog, title, message, confirmLabel, cancelLabel,
                () -> confirmed[0] = true);

        dialog.setContentPane(sheet);
        // Translucency is optional on X11/older toolkits; a square opaque sheet
        // is the graceful fallback, never a crash on the close path.
        if (TRANSLUCENT) {
            try {
                dialog.setBackground(new Color(0, 0, 0, 0));
            } catch (Throwable ignored) {
            }
        }
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
        dialog.setVisible(true);
        return confirmed[0];
    }

    /** Dev-only: the exact sheet, unattached, for the preview dump to paint. */
    static JComponent previewSheet(String title, String message,
                                   String confirmLabel, String cancelLabel) {
        return sheet(null, title, message, confirmLabel, cancelLabel, () -> { });
    }

    /**
     * Builds the sheet. Split out so the dev-only preview dump can paint the
     * exact same panel without opening a modal window; {@code dialog} is the
     * window it drags and closes, and {@code onConfirm} records the destructive
     * answer before the close.
     */
    private static Sheet sheet(JDialog dialog, String title, String message,
                               String confirmLabel, String cancelLabel, Runnable onConfirm) {
        Sheet sheet = new Sheet();

        // The app draws its own titlebar (HTML on macOS/Windows), so a native
        // dialog caption would be foreign chrome. The sheet carries our strip
        // instead — same height, same typography, same flag-G, and it drags the
        // dialog.
        TitleStrip strip = new TitleStrip();
        if (dialog != null) strip.dragged(dialog);
        sheet.add(strip, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.PAGE_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(24, PAD_X, 20, PAD_X));
        sheet.add(body, BorderLayout.CENTER);

        int textWidth = SHEET_W - 2 * PAD_X;
        body.add(column(new TitleLine(title, textWidth)));
        body.add(Box.createRigidArea(new Dimension(0, 12)));

        body.add(column(new Paragraph(message, textWidth)));
        body.add(Box.createRigidArea(new Dimension(0, 26)));

        FlatButton cancel = new FlatButton(cancelLabel, false);
        cancel.addActionListener(e -> close(dialog));
        FlatButton confirm = new FlatButton(confirmLabel, true);
        confirm.addActionListener(e -> {
            onConfirm.run();
            close(dialog);
        });

        JPanel actions = new JPanel();
        actions.setOpaque(false);
        actions.setLayout(new BoxLayout(actions, BoxLayout.LINE_AXIS));
        actions.add(Box.createHorizontalGlue());
        // The safe answer sits rightmost — it is the default, and the outer
        // corner is where the eye and the pointer come to rest.
        actions.add(confirm);
        actions.add(Box.createRigidArea(new Dimension(10, 0)));
        actions.add(cancel);
        body.add(column(actions));

        // Esc is the safe answer; Enter takes the focused (safe) default.
        sheet.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke("ESCAPE"), "wsbg-cancel");
        sheet.getActionMap().put("wsbg-cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                close(dialog);
            }
        });

        if (dialog != null) {
            dialog.getRootPane().setDefaultButton(cancel);
            // The safe answer owns the focus, so Enter and Space never destroy
            // a running report by reflex.
            javax.swing.SwingUtilities.invokeLater(cancel::requestFocusInWindow);
        }
        return sheet;
    }

    private static void close(JDialog dialog) {
        if (dialog != null) dialog.dispose();
    }

    /**
     * Pins a row into the body's column: left-aligned (BoxLayout centres by
     * default) and capped at its own height (a row would otherwise stretch to
     * its maximum and the copy would drift apart).
     */
    private static JComponent column(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    /**
     * The explanation: word-wrapped by hand at the text column. A JLabel would
     * need HTML for that, and its HTML view sizes itself to the text rather than
     * to the column it was styled for — the sentence then ran past the panel
     * edge. Hand-wrapping also buys the page's own line-height (1.45), which the
     * label's single-line metrics cannot give.
     */
    private static final class Paragraph extends JPanel {

        private static final float LINE_HEIGHT = 1.45f;

        private final String text;
        private final int width;
        private final Font font = uiFont(Font.PLAIN, 13.5f);

        Paragraph(String text, int width) {
            this.text = text;
            this.width = width;
            setOpaque(false);
            List<String> lines = wrap(getFontMetrics(font));
            int line = Math.round(getFontMetrics(font).getHeight() * LINE_HEIGHT);
            setPreferredSize(new Dimension(width, lines.size() * line));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = painter(g);
            g2.setFont(font);
            g2.setColor(TXT_2);
            FontMetrics fm = g2.getFontMetrics();
            int line = Math.round(fm.getHeight() * LINE_HEIGHT);
            int y = (line + fm.getAscent() - fm.getDescent()) / 2;
            for (String s : wrap(fm)) {
                g2.drawString(s, 0, y);
                y += line;
            }
            g2.dispose();
        }

        private List<String> wrap(FontMetrics fm) {
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && fm.stringWidth(candidate) > width) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            if (!line.isEmpty()) lines.add(line.toString());
            return lines;
        }
    }

    /**
     * The title line: the amber "still running" dot (the page's own busy signal)
     * and the headline, in the overlay title's weight and tracking.
     */
    private static final class TitleLine extends JPanel {

        private final String title;

        TitleLine(String title, int width) {
            this.title = title;
            setOpaque(false);
            setPreferredSize(new Dimension(width, 22));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = painter(g);
            int cy = getHeight() / 2;

            // The dot with its soft halo — the same idea as the live tag's glow.
            g2.setColor(new Color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), 0x33));
            g2.fillOval(0, cy - 7, 14, 14);
            g2.setColor(AMBER);
            g2.fillOval(3, cy - 4, 8, 8);

            Map<TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(TextAttribute.TRACKING, 0.015f);  // .overlay-title letter-spacing
            g2.setFont(uiFont(Font.BOLD, 15f).deriveFont(attrs));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(TXT);
            g2.drawString(title, 24, cy + (fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    /**
     * The sheet's titlebar — the Swing twin of {@code .titlebar} / {@code .tb-title}:
     * the page background, a hairline underneath, and the centred "WSBG · TERMINAL"
     * in letterspaced uppercase with the G in the German flag's three stripes.
     * No window controls: the two buttons below ARE the answer to this window.
     */
    private static final class TitleStrip extends JPanel {

        private static final String HEAD = "WSB";
        private static final String FLAG = "G";
        private static final String SEP = " · ";
        private static final String TAIL = "TERMINAL";

        TitleStrip() {
            setOpaque(false);
            setPreferredSize(new Dimension(0, TITLEBAR_H));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = painter(g);

            // Only the TOP corners are round: the strip clips a taller rounded
            // rect, so its lower edge stays square against the body.
            g2.setClip(0, 0, getWidth(), getHeight());
            g2.setColor(BG);
            g2.fillRoundRect(0, 0, getWidth(), getHeight() + RADIUS, RADIUS, RADIUS);
            g2.setColor(LINE_SOFT);
            g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);

            g2.setFont(titleFont());
            FontMetrics fm = g2.getFontMetrics();
            int total = fm.stringWidth(HEAD) + fm.stringWidth(FLAG) + fm.stringWidth(SEP)
                    + fm.stringWidth(TAIL);
            int x = Math.max(12, (getWidth() - total) / 2);
            int baseline = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;

            g2.setColor(TXT_2);
            g2.drawString(HEAD, x, baseline);
            x += fm.stringWidth(HEAD);

            // The flag-G: black/red/gold across the glyph's own height, exactly
            // like the CSS gradient the HTML titlebar clips into the text.
            int top = baseline - fm.getAscent();
            int bottom = baseline + fm.getDescent();
            g2.setPaint(new LinearGradientPaint(
                    new Point2D.Float(0, top),
                    new Point2D.Float(0, bottom),
                    new float[] {0f, 0.33f, 0.3301f, 0.66f, 0.6601f, 1f},
                    new Color[] {new Color(0x52, 0x52, 0x52), new Color(0x52, 0x52, 0x52),
                            new Color(0xEE, 0x0A, 0x00), new Color(0xEE, 0x0A, 0x00),
                            new Color(0xFF, 0xD0, 0x00), new Color(0xFF, 0xD0, 0x00)}));
            g2.drawString(FLAG, x, baseline);
            x += fm.stringWidth(FLAG);

            g2.setColor(MUTE_2);
            g2.drawString(SEP, x, baseline);
            x += fm.stringWidth(SEP);
            g2.setColor(TXT_2);
            g2.drawString(TAIL, x, baseline);
            g2.dispose();
        }

        /** Makes the strip drag the (undecorated) dialog, like the app's own bar. */
        void dragged(Window window) {
            final Point[] grab = {null};
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    grab[0] = e.getPoint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    grab[0] = null;
                }
            });
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (grab[0] == null) return;
                    Point p = e.getLocationOnScreen();
                    window.setLocation(p.x - grab[0].x, p.y - grab[0].y);
                }
            });
        }

        private static Font titleFont() {
            Map<TextAttribute, Object> attrs = new HashMap<>();
            attrs.put(TextAttribute.TRACKING, 0.22f);  // .tb-title letter-spacing
            return uiFont(Font.BOLD, 11.5f).deriveFont(attrs);
        }
    }

    /**
     * The panel itself: the raised surface, a hairline border, the 12px radius
     * and the overlay's drop shadow — painted into the transparent margin the
     * layout border reserves, so no child ever sits in it.
     */
    private static final class Sheet extends JPanel {

        private final int inset = TRANSLUCENT ? SHADOW : 0;

        Sheet() {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(inset, inset, inset, inset));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(SHEET_W + 2 * inset, super.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = painter(g);

            // Clear first: the window is per-pixel translucent, so whatever the
            // previous frame left in the shadow margin has to go.
            if (inset > 0) {
                g2.setComposite(AlphaComposite.Clear);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setComposite(AlphaComposite.SrcOver);
                paintShadow(g2);
            }

            int w = getWidth() - 2 * inset;
            int h = getHeight() - 2 * inset;
            g2.setColor(BG_1);
            g2.fillRoundRect(inset, inset, w, h, RADIUS, RADIUS);
            g2.setColor(LINE);
            g2.drawRoundRect(inset, inset, w - 1, h - 1, RADIUS, RADIUS);
            g2.dispose();
            super.paintComponent(g);
        }

        /**
         * The overlay's {@code box-shadow: 0 24px 80px oklch(0 0 0 / .5)},
         * approximated by stacked rounded rects: no blur filter, no image buffer,
         * and it costs nothing on a sheet this size.
         */
        private void paintShadow(Graphics2D g2) {
            int w = getWidth() - 2 * inset;
            int h = getHeight() - 2 * inset;
            for (int i = SHADOW; i > 0; i--) {
                float t = 1f - (float) i / SHADOW;
                g2.setColor(new Color(0f, 0f, 0f, 0.028f + 0.050f * t * t));
                g2.fillRoundRect(inset - i, inset - i + 6, w + 2 * i, h + 2 * i,
                        RADIUS + i, RADIUS + i);
            }
        }
    }

    /**
     * A flat text button — the Swing twin of the page's {@code .plain-btn} (stay)
     * and {@code .danger-btn} (the destructive answer): hairline box, 8px radius,
     * hover fill, and a focus ring so the keyboard's answer is visible without
     * masquerading as hover.
     */
    private static final class FlatButton extends JButton {

        private final boolean danger;

        FlatButton(String label, boolean danger) {
            super(label);
            this.danger = danger;
            setFont(uiFont(Font.PLAIN, 13.5f));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setForeground(danger ? RED : TXT);
        }

        @Override
        public Dimension getPreferredSize() {
            // One height and a floor width for both answers — the pair reads as a
            // pair, not as two differently sized boxes.
            return new Dimension(Math.max(super.getPreferredSize().width, 124), 38);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = painter(g);
            boolean hot = getModel().isRollover() || getModel().isPressed();

            if (hot) {
                g2.setColor(danger ? alpha(RED, 0x24) : BG_HOVER);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
            }
            g2.setColor(danger ? RED : (hot ? MUTE : LINE));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
            if (isFocusOwner()) {
                g2.setColor(alpha(danger ? RED : MUTE, 0x55));
                g2.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 6, 6);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Antialiased text/shape context — every painter starts here. */
    private static Graphics2D painter(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
                RenderingHints.VALUE_STROKE_PURE);
        return g2;
    }

    /**
     * The UI font: the page's families first (installed system-wide on some
     * machines), then the platform's own UI stack. The bundled webfonts are
     * woff2 and unreadable for AWT, so this is a best-effort match, never a load.
     */
    private static Font uiFont(int style, float size) {
        for (String family : new String[] {"Inter", "SF Pro Text", "Helvetica Neue", "Segoe UI"}) {
            if (INSTALLED.contains(family)) return new Font(family, style, Math.round(size)).deriveFont(size);
        }
        return new Font(Font.SANS_SERIF, style, Math.round(size)).deriveFont(size);
    }

    private static final Set<String> INSTALLED = installedFamilies();

    private static Set<String> installedFamilies() {
        Set<String> families = new HashSet<>();
        try {
            Collections.addAll(families,
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        } catch (Throwable ignored) {
            // Headless or a broken font config: the sans-serif fallback stands.
        }
        return families;
    }

    /** Headless or an old X11 toolkit: no per-pixel alpha, so no shadow margin. */
    private static boolean translucencySupported() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
