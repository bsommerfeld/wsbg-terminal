package de.bsommerfeld.updater.launcher;

import javax.swing.SwingUtilities;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import javax.swing.JComponent;

/**
 * The shared chrome of every question the launcher asks: the logo tucked into
 * the top-left corner, the title beside it, and the footer with the hint and
 * the confirm button. Subclasses own only the body between the two -
 * {@link ListChoiceScreen} a list of options.
 *
 * <h3>One window, one size</h3>
 * A choice screen fills the launcher's normal window exactly. Nothing resizes,
 * nothing morphs: {@link LauncherWindow} cross-fades one screen into the next
 * inside a frame whose bounds never move (see {@link ScreenTransition}). Every
 * screen is therefore laid out against the plain component size, and painting
 * is a single opaque pass - no interpolated body, no transparency games.
 *
 * <p>Mouse handling lives here too, because a choice screen consumes the events
 * the frame's own drag support would otherwise see: the window stays draggable
 * anywhere the pointer is NOT on a control, and the hand cursor and the
 * drag-refusal are decided by the same {@link #hitsControl} answer, so what
 * looks clickable and what refuses to slide can never drift apart.
 *
 * <p>EDT-only, like all launcher widgets.
 */
abstract class ChoiceScreen extends JComponent {

    protected static final int PAD_X = 18;
    /** Where the body may start - directly under the logo/title header. */
    protected static final int HEADER_H = 70;
    protected static final int ROW_ARC = 12;

    private static final int LOGO_X = 16;
    private static final int LOGO_Y = 10;
    private static final int LOGO_W = 58;
    private static final int OK_W = 74;
    private static final int OK_H = 30;
    private static final int FOOTER_PAD = 14;

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final Font HINT_FONT = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font OK_FONT = new Font("SansSerif", Font.BOLD, 13);

    private final BufferedImage logo;
    private final Consumer<String> onOk;

    private boolean okHovered;
    private int dragX, dragY;
    /** Whether the press that started this gesture landed on empty surface. */
    private boolean dragging;

    /**
     * @param logo a snapshot of the splash logo, drawn small into the corner
     * @param onOk receives {@link #confirmedValue()} when the button is pressed
     */
    ChoiceScreen(BufferedImage logo, Consumer<String> onOk) {
        this.logo = logo;
        this.onOk = onOk;
        setOpaque(true);
        setBackground(LauncherTheme.BG);

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragX = e.getX();
                dragY = e.getY();
                dragging = !hitsControl(e.getX(), e.getY());
                if (okBounds().contains(e.getX(), e.getY())) {
                    onOk.accept(confirmedValue());
                    return;
                }
                pressBody(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // A control must not slide the window out from under the hand
                // that is aiming at it - everywhere else the surface drags.
                if (!dragging) return;
                Window w = SwingUtilities.getWindowAncestor(ChoiceScreen.this);
                if (w != null) w.setLocation(e.getXOnScreen() - dragX, e.getYOnScreen() - dragY);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                boolean ok = okBounds().contains(e.getX(), e.getY());
                boolean dirty = ok != okHovered;
                okHovered = ok;
                dirty |= hoverBody(e.getX(), e.getY());
                setCursor(Cursor.getPredefinedCursor(hitsControl(e.getX(), e.getY())
                        ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                if (dirty) repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                okHovered = false;
                hoverBody(-1, -1);
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                wheelBody(e.getWheelRotation());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    // =====================================================================
    // What a subclass fills in
    // =====================================================================

    /** The question, set beside the corner logo. */
    protected abstract String title();

    /** The quiet footer note left of the confirm button. */
    protected abstract String hint();

    /** The confirm button's label. */
    protected abstract String okText();

    /** The value the confirm button hands back. */
    protected abstract String confirmedValue();

    /** Paints everything between the header and the footer. */
    protected abstract void paintBody(Graphics2D g2);

    /** Handles a press in the body. */
    protected abstract void pressBody(int x, int y);

    /**
     * Updates the body's hover state for a pointer at {@code (x, y)}
     * ({@code -1, -1} when it left the screen).
     *
     * @return whether anything changed and the screen needs a repaint
     */
    protected abstract boolean hoverBody(int x, int y);

    /** Whether {@code (x, y)} lands on something the body offers. */
    protected abstract boolean bodyHitsControl(int x, int y);

    /** A wheel notch over the body; ignored unless a subclass uses it. */
    protected void wheelBody(int notches) {
    }

    // =====================================================================
    // Shared geometry + painting
    // =====================================================================

    /** The confirm button, bottom-right. */
    protected Rectangle okBounds() {
        return new Rectangle(getWidth() - PAD_X - OK_W, getHeight() - OK_H - FOOTER_PAD, OK_W, OK_H);
    }

    /** The vertical band a subclass may lay its body out in. */
    protected int bodyBottom() {
        return okBounds().y - 12;
    }

    /** Whether the pointer is on anything actionable - button or body. */
    protected boolean hitsControl(int x, int y) {
        return okBounds().contains(x, y) || bodyHitsControl(x, y);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        g2.setColor(LauncherTheme.BG);
        g2.fillRect(0, 0, getWidth(), getHeight());
        g2.setColor(LauncherTheme.HAIRLINE);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, 20, 20));

        paintHeader(g2);
        paintBody(g2);
        paintFooter(g2);
        g2.dispose();
    }

    private void paintHeader(Graphics2D g2) {
        int h = Math.round(LOGO_W * (float) logo.getHeight() / logo.getWidth());
        g2.drawImage(logo, LOGO_X, LOGO_Y, LOGO_W, h, null);

        g2.setFont(TITLE_FONT);
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(title(), LOGO_X + LOGO_W + 14, LOGO_Y + h / 2 + 5);
    }

    private void paintFooter(Graphics2D g2) {
        Rectangle ok = okBounds();

        // The hint takes whatever room the button leaves and gives up a point
        // of type rather than running under it - some languages are longer.
        String hint = hint();
        int room = ok.x - PAD_X - 12;
        Font hintFont = HINT_FONT;
        while (hintFont.getSize() > 8
                && getFontMetrics(hintFont).stringWidth(hint) > room) {
            hintFont = hintFont.deriveFont((float) hintFont.getSize() - 1);
        }
        g2.setFont(hintFont);
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(LauncherTheme.TEXT_DIM);
        g2.drawString(hint, PAD_X, ok.y + (ok.height + fm.getAscent() - fm.getDescent()) / 2);

        g2.setColor(okHovered ? LauncherTheme.ACCENT.brighter() : LauncherTheme.ACCENT);
        g2.fill(new RoundRectangle2D.Double(ok.x, ok.y, ok.width, ok.height, ROW_ARC, ROW_ARC));
        g2.setFont(OK_FONT);
        FontMetrics ofm = g2.getFontMetrics();
        g2.setColor(LauncherTheme.ON_ACCENT);
        g2.drawString(okText(),
                ok.x + (ok.width - ofm.stringWidth(okText())) / 2,
                ok.y + (ok.height + ofm.getAscent() - ofm.getDescent()) / 2);
    }

    /** Paints {@code text} at {@code alpha}, restoring the composite after. */
    protected static void withAlpha(Graphics2D g2, float alpha, Runnable paint) {
        java.awt.Composite old = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                Math.max(0f, Math.min(1f, alpha))));
        paint.run();
        g2.setComposite(old);
    }

    /** A filled rounded surface with the selected/hovered treatment applied. */
    protected static void paintSurface(Graphics2D g2, RoundRectangle2D shape,
            boolean selected, boolean hovered, int arc) {
        g2.setColor(hovered && !selected ? LauncherTheme.SURFACE_HOVER : LauncherTheme.SURFACE);
        g2.fill(shape);
        if (!selected) return;
        g2.setColor(LauncherTheme.SELECTED_TINT);
        g2.fill(shape);
        g2.setColor(LauncherTheme.ACCENT);
        g2.setStroke(new BasicStroke(1.6f));
        g2.draw(new RoundRectangle2D.Double(shape.getX() + 0.8, shape.getY() + 0.8,
                shape.getWidth() - 1.6, shape.getHeight() - 1.6, arc, arc));
    }

    /** Four-point star/diamond - the quality glyph. */
    protected static void paintDiamond(Graphics2D g2, double cx, double cy, double r, Color c) {
        double w = r * 0.45;
        java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
        p.moveTo(cx, cy - r);
        p.quadTo(cx + w * 0.3, cy - w * 0.3, cx + r, cy);
        p.quadTo(cx + w * 0.3, cy + w * 0.3, cx, cy + r);
        p.quadTo(cx - w * 0.3, cy + w * 0.3, cx - r, cy);
        p.quadTo(cx - w * 0.3, cy - w * 0.3, cx, cy - r);
        p.closePath();
        g2.setColor(c);
        g2.fill(p);
    }

    /** Lightning bolt - the speed glyph. */
    protected static void paintBolt(Graphics2D g2, double cx, double cy, double r, Color c) {
        java.awt.geom.Path2D p = new java.awt.geom.Path2D.Double();
        p.moveTo(cx + r * 0.45, cy - r);
        p.lineTo(cx - r * 0.55, cy + r * 0.25);
        p.lineTo(cx - r * 0.05, cy + r * 0.25);
        p.lineTo(cx - r * 0.45, cy + r);
        p.lineTo(cx + r * 0.55, cy - r * 0.25);
        p.lineTo(cx + r * 0.05, cy - r * 0.25);
        p.closePath();
        g2.setColor(c);
        g2.fill(p);
    }
}
