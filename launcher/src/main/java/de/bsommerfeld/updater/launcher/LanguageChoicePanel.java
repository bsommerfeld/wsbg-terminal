package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * The language-choice view — the very first thing a fresh install shows, before
 * anything else has a language to be shown in. It carries no translated chrome
 * of its own: every option is labelled in ITS OWN language (native name, plus
 * the title and the OK button as that language would word them), so the screen
 * is readable no matter which language the user actually speaks. Title, hint
 * and button therefore re-render live as the selection moves.
 *
 * <p>German arrives preselected — the app's primary language — so confirming
 * the default is a single click.
 *
 * <p>Shares the morphing-window contract with {@link ModelChoicePanel}; see
 * {@link MorphView} for the flicker discipline this paint code obeys.
 */
final class LanguageChoicePanel extends JComponent implements MorphView {

    /**
     * One selectable language, fully pre-localized by the caller.
     *
     * @param code       ISO 639-1 code persisted as {@code user.language}
     * @param nativeName the language's name in itself ("Deutsch", "English")
     * @param title      the screen title as this language words it
     * @param hint       the footer hint as this language words it
     * @param okText     the confirm button as this language words it
     */
    record Row(String code, String nativeName, String title, String hint, String okText) {
    }

    private static final int PAD_X = 20;
    private static final int HEADER_H = 84;
    private static final int ROW_H = 46;
    private static final int ROW_GAP = 8;
    private static final int ROW_ARC = 12;
    private static final int OK_W = 76;
    private static final int OK_H = 32;

    // Logo end state: tucked small into the top-left corner (same as the
    // model choice, so the two screens read as one flow).
    private static final int LOGO_END_X = 18;
    private static final int LOGO_END_Y = 12;
    private static final int LOGO_END_W = 72;

    private static final Color ROW_BG = new Color(38, 38, 43);
    private static final Color ROW_BG_HOVER = new Color(48, 48, 54);
    private static final Color ROW_SELECTED_TINT = new Color(0xF9, 0xB6, 0x4F, 26);
    private static final Color TEXT_PRIMARY = new Color(222, 222, 226);
    private static final Color TEXT_DIM = new Color(145, 145, 152);
    private static final Color OK_TEXT = new Color(0x1A, 0x1A, 0x1A);

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font CODE_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font HINT_FONT = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font OK_FONT = new Font("SansSerif", Font.BOLD, 13);

    /** Per-row stagger of the grow-in, as a fraction of the whole morph. */
    private static final float STAGGER = 0.10f;

    private final List<Row> rows;
    private final Consumer<String> onOk;
    private final BufferedImage logo;
    private final int smallW;
    private final int smallH;
    private final int logoStartY;

    private int selected;
    private int hovered = -1;
    private boolean okHovered;
    private float morphT = 0f;
    private int dragX, dragY;

    /**
     * @param logo       a snapshot of the splash logo, glided into the corner
     * @param smallW     width of the normal (collapsed) window body
     * @param smallH     height of the normal window body
     * @param logoStartY the logo's top inset in the normal layout
     */
    LanguageChoicePanel(List<Row> rows, String preselectCode, BufferedImage logo,
            int smallW, int smallH, int logoStartY, Consumer<String> onOk) {
        this.rows = rows;
        this.onOk = onOk;
        this.logo = logo;
        this.smallW = smallW;
        this.smallH = smallH;
        this.logoStartY = logoStartY;
        selected = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).code().equals(preselectCode)) selected = i;
        }
        setOpaque(false);
        setBackground(LauncherTheme.BG);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragX = e.getX();
                dragY = e.getY();
                handlePress(e.getX(), e.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                // This view consumes mouse events, so the frame's own drag
                // support never sees them — the window must stay draggable
                // in the choice state too.
                Window w = SwingUtilities.getWindowAncestor(LanguageChoicePanel.this);
                if (w != null) w.setLocation(e.getXOnScreen() - dragX, e.getYOnScreen() - dragY);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                handleMove(e.getX(), e.getY());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovered = -1;
                okHovered = false;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    @Override
    public void setMorphT(float t) {
        morphT = Math.max(0f, Math.min(1f, t));
    }

    @Override
    public void setRested(boolean rested) {
        setOpaque(rested);
        repaint();
    }

    /** The morphing window body's geometry at progress {@code e}, in component coords. */
    private Rectangle bodyAt(float e) {
        int bodyW = Math.round(smallW + (getWidth() - smallW) * e);
        int bodyH = Math.round(smallH + (getHeight() - smallH) * e);
        return new Rectangle((getWidth() - bodyW) / 2, 0, bodyW, bodyH);
    }

    private boolean interactive() {
        return morphT >= 1f;
    }

    private void handlePress(int x, int y) {
        if (!interactive()) return;
        int row = rowAt(y);
        if (row >= 0 && x >= PAD_X && x <= getWidth() - PAD_X) {
            selected = row;
            repaint();
            return;
        }
        if (okBounds().contains(x, y)) {
            onOk.accept(rows.get(selected).code());
        }
    }

    private void handleMove(int x, int y) {
        if (!interactive()) return;
        int row = (x >= PAD_X && x <= getWidth() - PAD_X) ? rowAt(y) : -1;
        boolean ok = okBounds().contains(x, y);
        if (row != hovered || ok != okHovered) {
            hovered = row;
            okHovered = ok;
            setCursor(Cursor.getPredefinedCursor(
                    row >= 0 || ok ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }
    }

    private int rowAt(int y) {
        for (int i = 0; i < rows.size(); i++) {
            int rowY = HEADER_H + i * (ROW_H + ROW_GAP);
            if (y >= rowY && y < rowY + ROW_H) return i;
        }
        return -1;
    }

    private Rectangle okBounds() {
        return new Rectangle(getWidth() - PAD_X - OK_W, getHeight() - OK_H - 16, OK_W, OK_H);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        float e = morphT;
        Rectangle body = bodyAt(e);
        RoundRectangle2D bodyShape = new RoundRectangle2D.Float(
                body.x, body.y, body.width, body.height, 20, 20);

        if (isOpaque()) {
            g2.setColor(LauncherTheme.BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
        } else {
            g2.setColor(LauncherTheme.BG);
            g2.fill(bodyShape);
            g2.clip(bodyShape);
        }

        g2.setColor(new Color(255, 255, 255, 10));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new RoundRectangle2D.Float(body.x + 0.5f, 0.5f,
                body.width - 1, body.height - 1, 20, 20));

        paintLogo(g2, body.x, e);
        paintHeader(g2, e);
        for (int i = 0; i < rows.size(); i++) {
            paintRow(g2, rows.get(i), i, HEADER_H + i * (ROW_H + ROW_GAP));
        }
        paintFooter(g2);
        g2.dispose();
    }

    /** The logo gliding from its centered splash position into the corner. */
    private void paintLogo(Graphics2D g2, float bodyX, float e) {
        float startX = bodyX + (smallW - logo.getWidth()) / 2f;
        float scale = 1f + ((float) LOGO_END_W / logo.getWidth() - 1f) * e;
        float w = logo.getWidth() * scale;
        float h = logo.getHeight() * scale;
        float x = startX + (LOGO_END_X - startX) * e;
        float y = logoStartY + (LOGO_END_Y - logoStartY) * e;
        g2.drawImage(logo, Math.round(x), Math.round(y), Math.round(w), Math.round(h), null);
    }

    /** Title beside the corner logo — worded in the currently selected language. */
    private void paintHeader(Graphics2D g2, float e) {
        float a = Math.max(0f, (e - 0.4f) / 0.6f);
        if (a <= 0f) return;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));

        int x = LOGO_END_X + LOGO_END_W + 18;
        g2.setFont(TITLE_FONT);
        g2.setColor(TEXT_PRIMARY);
        g2.drawString(rows.get(selected).title(), x, 44);
        g2.setComposite(AlphaComposite.SrcOver);
    }

    private void paintRow(Graphics2D g2, Row row, int index, int y) {
        // Staggered grow-in: each row unfolds from a thin, bar-like track to
        // full height — the progress bar dissolving into the list.
        float local = Math.min(1f,
                Math.max(0f, (morphT - index * STAGGER) / (1f - rows.size() * STAGGER)));
        if (local <= 0f) return;
        float ease = local * local * (3 - 2 * local);

        int fullW = getWidth() - 2 * PAD_X;
        int h = Math.max(6, Math.round(ROW_H * ease));
        int rowY = y + (ROW_H - h) / 2;

        Composite old = g2.getComposite();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ease));

        boolean sel = index == selected;
        g2.setColor(index == hovered && !sel ? ROW_BG_HOVER : ROW_BG);
        RoundRectangle2D shape = new RoundRectangle2D.Double(PAD_X, rowY, fullW, h, ROW_ARC, ROW_ARC);
        g2.fill(shape);
        if (sel) {
            g2.setColor(ROW_SELECTED_TINT);
            g2.fill(shape);
            g2.setColor(LauncherTheme.ACCENT);
            g2.setStroke(new BasicStroke(1.6f));
            g2.draw(new RoundRectangle2D.Double(PAD_X + 0.8, rowY + 0.8,
                    fullW - 1.6, h - 1.6, ROW_ARC, ROW_ARC));
        }

        if (ease > 0.6f) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, ease));
            paintRowContent(g2, row, y);
        }
        g2.setComposite(old);
    }

    private void paintRowContent(Graphics2D g2, Row row, int y) {
        g2.setFont(NAME_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int baseY = y + (ROW_H + fm.getAscent() - fm.getDescent()) / 2;
        g2.setColor(TEXT_PRIMARY);
        g2.drawString(row.nativeName(), PAD_X + 24, baseY);

        // The ISO code as a quiet anchor on the right — recognisable even when
        // the native name is not.
        g2.setFont(CODE_FONT);
        FontMetrics cfm = g2.getFontMetrics();
        String code = row.code().toUpperCase(java.util.Locale.ROOT);
        g2.setColor(TEXT_DIM);
        g2.drawString(code, getWidth() - PAD_X - 24 - cfm.stringWidth(code),
                y + (ROW_H + cfm.getAscent() - cfm.getDescent()) / 2);
    }

    private void paintFooter(Graphics2D g2) {
        // Footer trails the rows: it only fades in at the very end of the morph.
        float a = Math.max(0f, (morphT - 0.85f) / 0.15f);
        if (a <= 0f) return;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));

        Row row = rows.get(selected);
        Rectangle ok = okBounds();
        g2.setFont(HINT_FONT);
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(TEXT_DIM);
        g2.drawString(row.hint(), PAD_X,
                ok.y + (ok.height + fm.getAscent() - fm.getDescent()) / 2);

        g2.setColor(okHovered ? LauncherTheme.ACCENT.brighter() : LauncherTheme.ACCENT);
        g2.fill(new RoundRectangle2D.Double(ok.x, ok.y, ok.width, ok.height, ROW_ARC, ROW_ARC));
        g2.setFont(OK_FONT);
        FontMetrics ofm = g2.getFontMetrics();
        g2.setColor(OK_TEXT);
        g2.drawString(row.okText(),
                ok.x + (ok.width - ofm.stringWidth(row.okText())) / 2,
                ok.y + (ok.height + ofm.getAscent() - ofm.getDescent()) / 2);
        g2.setComposite(AlphaComposite.SrcOver);
    }
}
