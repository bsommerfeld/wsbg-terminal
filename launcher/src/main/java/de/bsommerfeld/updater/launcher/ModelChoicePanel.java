package de.bsommerfeld.updater.launcher;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * The model-choice view: one row per gemma4 tier, each translating the
 * parameter count into the two numbers a non-technical user can actually
 * reason about — quality and speed, both 0–10 — plus the download size and a
 * plain-language fit verdict. No tier names, no RAM figures, no quantization
 * jargon.
 *
 * <p>This component IS the whole morphing window content: it paints the
 * rounded window body itself at an interpolated size (the frame is resized to
 * the large target ONCE, so the morph is pure repainting — no native resize
 * per frame, which is what makes it smooth), with the top edge anchored. The
 * logo glides from its splash position into the top-left corner while the
 * rows stagger in below, growing out of thin progress-bar-like tracks — the
 * visual continuation of the {@link IslandIndicator} bar they replace.
 *
 * <p>Selection is advisory-friendly: every row is selectable, including
 * "too large" ones (dimmed, never disabled — the recommendation informs, the
 * user decides). The recommended tier arrives preselected, so the default
 * action is a single click on OK.
 *
 * <h3>Flicker discipline</h3>
 * The morph performs ZERO native window mutations per frame — no setBounds,
 * no setShape (each native change applies on the compositor immediately while
 * the matching paint lands 1–2 display refreshes later; on a 120 Hz panel
 * every mismatch shows as flicker). Instead the frame is sized and shaped to
 * the final target ONCE, and each animation frame is painted synchronously
 * via {@code paintImmediately} — one complete blit per frame. While morphing
 * the panel is transparent outside the painted body; at rest it flips opaque
 * ({@link #setRested}) so ordinary hover repaints can never clear-bleed the
 * desktop through. EDT-only, like all launcher widgets.
 */
final class ModelChoicePanel extends JComponent {

    /** One selectable tier row, fully pre-localized by the caller. */
    record Row(String tag, int quality, int speed, String sizeText,
            ModelCatalog.Fit fit, boolean recommended, String verdictText) {
    }

    /** The localized strings the panel renders. */
    record Labels(String title, String qualityWord, String speedWord,
            String hint, String okText) {
    }

    private static final int PAD_X = 20;
    private static final int HEADER_H = 84;
    private static final int ROW_H = 42;
    private static final int ROW_GAP = 6;
    private static final int ROW_ARC = 12;
    private static final int OK_W = 76;
    private static final int OK_H = 32;

    // Logo end state: tucked small into the top-left corner.
    private static final int LOGO_END_X = 18;
    private static final int LOGO_END_Y = 12;
    private static final int LOGO_END_W = 72;

    private static final Color ROW_BG = new Color(38, 38, 43);
    private static final Color ROW_BG_HOVER = new Color(48, 48, 54);
    private static final Color ROW_SELECTED_TINT = new Color(0xF9, 0xB6, 0x4F, 26);
    private static final Color TEXT_PRIMARY = new Color(222, 222, 226);
    private static final Color TEXT_DIM = new Color(145, 145, 152);
    private static final Color VERDICT_TIGHT = new Color(0xCC, 0x88, 0x44);
    private static final Color VERDICT_TOO_LARGE = new Color(0xB0, 0x5A, 0x5A);
    private static final Color OK_TEXT = new Color(0x1A, 0x1A, 0x1A);

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final Font LEGEND_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font SCORE_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font SIZE_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font VERDICT_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font HINT_FONT = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font OK_FONT = new Font("SansSerif", Font.BOLD, 13);

    /** Per-row stagger of the grow-in, as a fraction of the whole morph. */
    private static final float STAGGER = 0.08f;

    private final List<Row> rows;
    private final Labels labels;
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
    ModelChoicePanel(List<Row> rows, String preselectTag, Labels labels,
            BufferedImage logo, int smallW, int smallH, int logoStartY,
            Consumer<String> onOk) {
        this.rows = rows;
        this.labels = labels;
        this.onOk = onOk;
        this.logo = logo;
        this.smallW = smallW;
        this.smallH = smallH;
        this.logoStartY = logoStartY;
        selected = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).tag().equals(preselectTag)) selected = i;
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
                Window w = SwingUtilities.getWindowAncestor(ModelChoicePanel.this);
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

    /**
     * Advances the morph WITHOUT scheduling a repaint: 0 = the normal window
     * body (only the logo painted, at its splash position), 1 = fully
     * unfolded choice list. The morph clock paints each frame synchronously
     * via {@code paintImmediately} right after this call.
     */
    void setMorphT(float t) {
        morphT = Math.max(0f, Math.min(1f, t));
    }

    /**
     * Flips between the morphing surface (transparent outside the body) and
     * the at-rest surface (fully opaque, so hover repaints can never bleed
     * the desktop through).
     */
    void setRested(boolean rested) {
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
            onOk.accept(rows.get(selected).tag());
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
            // At rest the body fills the frame (the window shape rounds the
            // corners) — paint every pixel, as opacity promises.
            g2.setColor(LauncherTheme.BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
        } else {
            // Morphing: only the body is painted, everything outside stays
            // transparent. Clip so no content can leak past the outline.
            g2.setColor(LauncherTheme.BG);
            g2.fill(bodyShape);
            g2.clip(bodyShape);
        }

        // Subtle 1px border along the current body outline (top anchored,
        // growing horizontally around the center and downward).
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

    /** Title + glyph legend beside the corner logo, fading in with the morph. */
    private void paintHeader(Graphics2D g2, float e) {
        float a = Math.max(0f, (e - 0.4f) / 0.6f);
        if (a <= 0f) return;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));

        int x = LOGO_END_X + LOGO_END_W + 18;
        g2.setFont(TITLE_FONT);
        g2.setColor(TEXT_PRIMARY);
        g2.drawString(labels.title(), x, 34);

        g2.setFont(LEGEND_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int ly = 56;
        double cy = ly - fm.getAscent() / 2.0 + 1;
        paintDiamond(g2, x + 5, cy, 5.5, LauncherTheme.ACCENT);
        g2.setColor(TEXT_DIM);
        g2.drawString(labels.qualityWord(), x + 14, ly);
        int qw = fm.stringWidth(labels.qualityWord());
        int sx = x + 14 + qw + 16;
        paintBolt(g2, sx + 4, cy, 6, TEXT_DIM);
        g2.drawString(labels.speedWord(), sx + 13, ly);
        g2.setComposite(AlphaComposite.SrcOver);
    }

    private void paintRow(Graphics2D g2, Row row, int index, int y) {
        // Staggered grow-in: each row fades in and unfolds from a thin,
        // bar-like track to full height, later rows slightly behind earlier
        // ones — the progress bar dissolving into the list.
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
            // Row content only near full height; "too large" rows stay
            // selectable but read visibly damped.
            float contentAlpha = ease * (row.fit() == ModelCatalog.Fit.TOO_LARGE ? 0.45f : 1f);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, contentAlpha));
            paintRowContent(g2, row, y);
        }
        g2.setComposite(old);
    }

    private void paintRowContent(Graphics2D g2, Row row, int y) {
        double cy = y + ROW_H / 2.0 - 0.5;
        g2.setFont(SCORE_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int baseY = y + (ROW_H + fm.getAscent() - fm.getDescent()) / 2;

        // Quality and speed as glyph+number pairs, column-aligned across rows.
        int qx = PAD_X + 24;
        paintDiamond(g2, qx, cy, 6.5, LauncherTheme.ACCENT);
        g2.setColor(TEXT_PRIMARY);
        g2.drawString(String.valueOf(row.quality()), qx + 14, baseY);

        int sx = PAD_X + 104;
        paintBolt(g2, sx, cy, 7, TEXT_PRIMARY);
        g2.setColor(TEXT_PRIMARY);
        g2.drawString(String.valueOf(row.speed()), sx + 14, baseY);

        g2.setFont(SIZE_FONT);
        FontMetrics sfm = g2.getFontMetrics();
        g2.setColor(TEXT_DIM);
        g2.drawString(row.sizeText(), PAD_X + 176,
                y + (ROW_H + sfm.getAscent() - sfm.getDescent()) / 2);

        g2.setFont(VERDICT_FONT);
        FontMetrics vfm = g2.getFontMetrics();
        Color verdictColor = switch (row.fit()) {
            case COMFORTABLE -> row.recommended() ? LauncherTheme.ACCENT : TEXT_DIM;
            case TIGHT -> VERDICT_TIGHT;
            case TOO_LARGE -> VERDICT_TOO_LARGE;
        };
        g2.setColor(verdictColor);
        g2.drawString(row.verdictText(),
                getWidth() - PAD_X - 14 - vfm.stringWidth(row.verdictText()),
                y + (ROW_H + vfm.getAscent() - vfm.getDescent()) / 2);
    }

    private void paintFooter(Graphics2D g2) {
        // Footer trails the rows: it only fades in at the very end of the morph.
        float a = Math.max(0f, (morphT - 0.85f) / 0.15f);
        if (a <= 0f) return;
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));

        Rectangle ok = okBounds();
        g2.setFont(HINT_FONT);
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(TEXT_DIM);
        g2.drawString(labels.hint(), PAD_X,
                ok.y + (ok.height + fm.getAscent() - fm.getDescent()) / 2);

        g2.setColor(okHovered ? LauncherTheme.ACCENT.brighter() : LauncherTheme.ACCENT);
        g2.fill(new RoundRectangle2D.Double(ok.x, ok.y, ok.width, ok.height, ROW_ARC, ROW_ARC));
        g2.setFont(OK_FONT);
        FontMetrics ofm = g2.getFontMetrics();
        g2.setColor(OK_TEXT);
        g2.drawString(labels.okText(),
                ok.x + (ok.width - ofm.stringWidth(labels.okText())) / 2,
                ok.y + (ok.height + ofm.getAscent() - ofm.getDescent()) / 2);
        g2.setComposite(AlphaComposite.SrcOver);
    }

    /** Four-point star/diamond — the quality glyph. */
    private static void paintDiamond(Graphics2D g2, double cx, double cy, double r, Color c) {
        double w = r * 0.45;
        Path2D p = new Path2D.Double();
        p.moveTo(cx, cy - r);
        p.quadTo(cx + w * 0.3, cy - w * 0.3, cx + r, cy);
        p.quadTo(cx + w * 0.3, cy + w * 0.3, cx, cy + r);
        p.quadTo(cx - w * 0.3, cy + w * 0.3, cx - r, cy);
        p.quadTo(cx - w * 0.3, cy - w * 0.3, cx, cy - r);
        p.closePath();
        g2.setColor(c);
        g2.fill(p);
    }

    /** Lightning bolt — the speed glyph. */
    private static void paintBolt(Graphics2D g2, double cx, double cy, double r, Color c) {
        Path2D p = new Path2D.Double();
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
