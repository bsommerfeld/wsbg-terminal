package de.bsommerfeld.updater.launcher;

import javax.swing.Timer;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The model choice: one card per model tier, held as a STACK rather than a
 * list. Four tiers never fit the launcher window as four full rows - and they
 * should not have to. The card in front is the choice; the ones behind it peek
 * out below it as a visible stack of cards, and the user flips through them
 * (wheel, chevrons, a tap on the stack, or a page dot) until the card they want
 * is in front. The stack is the selection: what is on top is what OK confirms.
 *
 * <p>Each card names the model on its own line and translates the parameter
 * count underneath into the two numbers a non-technical user can actually
 * reason about - quality and speed, both 0-10 - plus the download size and a
 * plain-language fit verdict. The name is the only technical thing on the card:
 * no RAM figures, no parameter counts, no quantization jargon.
 *
 * <p>Browsing is advisory-friendly: every tier can be brought to the front,
 * including "too large" ones (they read visibly damped, but are never blocked -
 * the recommendation informs, the user decides). The recommended tier starts on
 * top, so the default action is a single click on OK.
 *
 * <p>The flip animates continuously: {@link #position} is a floating card index
 * that eases toward {@link #target}, and every card's place in the stack is a
 * pure function of its distance from it. Nothing snaps.
 */
final class ModelChoicePanel extends ChoiceScreen {

    /** One selectable tier card, fully pre-localized by the caller. */
    record Row(String tag, String name, int quality, int speed, String sizeText,
            ModelCatalog.Fit fit, boolean recommended, String verdictText) {
    }

    /** The localized strings the panel renders. */
    record Labels(String title, String qualityWord, String speedWord,
            String hint, String okText) {
    }

    private static final int STACK_TOP = 72;
    private static final int CARD_TOP = 108;
    private static final int CARD_H = 96;
    private static final int CARD_ARC = 16;

    /**
     * How far each card away from the front peeks out past it - upward for the
     * tiers above the current one, downward for the ones below. The stack is
     * deliberately symmetric: whichever card is in front, the ones on both
     * sides of it stay visible as edges, so the screen reads as a stack even
     * when the recommendation puts the topmost or bottommost tier in front.
     */
    private static final int PEEK_Y = 14;
    /** How much narrower each card away from the front is drawn. */
    private static final float PEEK_SCALE = 0.05f;
    /** Cards further from the front than this are not drawn at all. */
    private static final float STACK_DEPTH = 3.4f;

    // The navigation strip under the stack: page dots left of centre, the two
    // chevrons right - the stack is flippable in three ways, and all three
    // have to be visible for anyone to try the first one.
    private static final int NAV_H = 26;
    private static final int DOT_R = 3;
    private static final int DOT_GAP = 14;
    private static final int CHEVRON_R = 11;

    /** A page dot whose card is not in front. */
    private static final Color DOT_IDLE = new Color(88, 88, 96);

    private static final Color VERDICT_TIGHT = new Color(0xCC, 0x88, 0x44);
    private static final Color VERDICT_TOO_LARGE = new Color(0xB0, 0x5A, 0x5A);

    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font SCORE_FONT = new Font("SansSerif", Font.BOLD, 16);
    private static final Font LEGEND_FONT = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font SIZE_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font VERDICT_FONT = new Font("SansSerif", Font.PLAIN, 11);

    private final List<Row> rows;
    private final Labels labels;

    /** The card the stack is settling on - the one OK confirms. */
    private int target;
    /** The animated card index; {@code target} once the stack has settled. */
    private float position;
    private final Timer flipTimer;

    private int hoveredDot = -1;
    private boolean upHovered;
    private boolean downHovered;
    private boolean stackHovered;

    ModelChoicePanel(List<Row> rows, String preselectTag, Labels labels,
            BufferedImage logo, Consumer<String> onOk) {
        super(logo, onOk);
        this.rows = rows;
        this.labels = labels;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).tag().equals(preselectTag)) target = i;
        }
        position = target;
        flipTimer = new Timer(16, _ -> tick());
    }

    @Override
    protected String title() {
        return labels.title();
    }

    @Override
    protected String hint() {
        return labels.hint();
    }

    @Override
    protected String okText() {
        return labels.okText();
    }

    @Override
    protected String confirmedValue() {
        return rows.get(target).tag();
    }

    // =====================================================================
    // Flipping through the stack
    // =====================================================================

    /** Brings card {@code index} to the front, easing the whole stack along. */
    private void flipTo(int index) {
        int clamped = Math.max(0, Math.min(rows.size() - 1, index));
        if (clamped == target) return;
        target = clamped;
        if (!flipTimer.isRunning()) flipTimer.start();
    }

    /**
     * One animation step: an exponential ease toward the target card. Framerate
     * -independent enough at a fixed 16 ms tick, and it never overshoots - a
     * stack of cards that bounces reads as a toy, not as a control.
     */
    private void tick() {
        float delta = target - position;
        if (Math.abs(delta) < 0.002f) {
            position = target;
            flipTimer.stop();
        } else {
            position += delta * 0.22f;
        }
        repaint();
    }

    @Override
    protected void wheelBody(int notches) {
        if (notches != 0) flipTo(target + Integer.signum(notches));
    }

    @Override
    protected void pressBody(int x, int y) {
        if (upBounds().contains(x, y)) {
            flipTo(target - 1);
            return;
        }
        if (downBounds().contains(x, y)) {
            flipTo(target + 1);
            return;
        }
        int dot = dotAt(x, y);
        if (dot >= 0) {
            flipTo(dot);
            return;
        }
        // A tap on the peeking edges deals that side's next card to the top -
        // the gesture the stack's own shape suggests.
        if (topPeekBounds().contains(x, y)) flipTo(target - 1);
        else if (bottomPeekBounds().contains(x, y)) flipTo(target + 1);
    }

    @Override
    protected boolean hoverBody(int x, int y) {
        boolean up = upBounds().contains(x, y);
        boolean down = downBounds().contains(x, y);
        int dot = dotAt(x, y);
        boolean stack = topPeekBounds().contains(x, y) || bottomPeekBounds().contains(x, y);
        boolean dirty = up != upHovered || down != downHovered
                || dot != hoveredDot || stack != stackHovered;
        upHovered = up;
        downHovered = down;
        hoveredDot = dot;
        stackHovered = stack;
        return dirty;
    }

    @Override
    protected boolean bodyHitsControl(int x, int y) {
        return upBounds().contains(x, y) || downBounds().contains(x, y)
                || dotAt(x, y) >= 0
                || topPeekBounds().contains(x, y) || bottomPeekBounds().contains(x, y);
    }

    // =====================================================================
    // Geometry
    // =====================================================================

    private int navCenterY() {
        return bodyBottom() - NAV_H / 2;
    }

    private Rectangle upBounds() {
        int cy = navCenterY();
        return new Rectangle(getWidth() - PAD_X - 2 * (2 * CHEVRON_R) - 6,
                cy - CHEVRON_R, 2 * CHEVRON_R, 2 * CHEVRON_R);
    }

    private Rectangle downBounds() {
        int cy = navCenterY();
        return new Rectangle(getWidth() - PAD_X - 2 * CHEVRON_R,
                cy - CHEVRON_R, 2 * CHEVRON_R, 2 * CHEVRON_R);
    }

    /** The strip of peeking card edges above the front card. */
    private Rectangle topPeekBounds() {
        int depth = Math.min(target, (int) STACK_DEPTH);
        if (depth == 0) return new Rectangle();
        return new Rectangle(PAD_X, CARD_TOP - depth * PEEK_Y - 4,
                getWidth() - 2 * PAD_X, depth * PEEK_Y + 4);
    }

    /** The strip of peeking card edges below the front card. */
    private Rectangle bottomPeekBounds() {
        int depth = Math.min(rows.size() - 1 - target, (int) STACK_DEPTH);
        if (depth == 0) return new Rectangle();
        return new Rectangle(PAD_X, CARD_TOP + CARD_H, getWidth() - 2 * PAD_X,
                depth * PEEK_Y + 4);
    }

    private int dotsLeft() {
        return PAD_X + 4;
    }

    private int dotAt(int x, int y) {
        int cy = navCenterY();
        if (Math.abs(y - cy) > 10) return -1;
        for (int i = 0; i < rows.size(); i++) {
            int cx = dotsLeft() + i * DOT_GAP;
            if (Math.abs(x - cx) <= 7) return i;
        }
        return -1;
    }

    // =====================================================================
    // Painting
    // =====================================================================

    @Override
    protected void paintBody(Graphics2D g2) {
        Shape oldClip = g2.getClip();
        // The deepest cards of the stack fade out as they go; the clip is what
        // keeps their last pixels off the title and the nav strip.
        g2.clipRect(0, STACK_TOP, getWidth(), bodyBottom() - NAV_H - STACK_TOP);
        // Back to front, so the card in front lands on top of the pile - and
        // "back" means distance from the front in EITHER direction.
        List<Integer> order = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) order.add(i);
        order.sort((a, b) -> Float.compare(Math.abs(b - position), Math.abs(a - position)));
        for (int i : order) {
            float rel = i - position;
            if (Math.abs(rel) > STACK_DEPTH) continue;
            paintCard(g2, rows.get(i), rel);
        }
        g2.setClip(oldClip);

        paintNav(g2);
    }

    /**
     * One card at its stack distance: the further from the front, the further
     * out (up or down), the narrower and the dimmer - so a flip is the whole
     * pile sliding one notch, not a card teleporting.
     */
    private void paintCard(Graphics2D g2, Row row, float rel) {
        float d = Math.abs(rel);
        float scale = 1f - PEEK_SCALE * d;
        float alpha = Math.max(0f, 1f - 0.20f * d);
        if (alpha <= 0.01f) return;

        int fullW = getWidth() - 2 * PAD_X;
        int w = Math.round(fullW * scale);
        int h = Math.round(CARD_H * scale);
        int x = (getWidth() - w) / 2;
        int y = Math.round(CARD_TOP + rel * PEEK_Y + (CARD_H - h) / 2f);

        boolean front = d < 0.5f;
        RoundRectangle2D shape = new RoundRectangle2D.Double(x, y, w, h, CARD_ARC, CARD_ARC);

        // Content fades with the card's distance from the front, so a flip
        // cross-fades the outgoing text into the incoming one instead of
        // switching it; the peeking edges further back carry no text at all.
        float contentFade = Math.max(0f, 1f - d / 0.6f);

        withAlpha(g2, alpha, () -> {
            if (front) {
                paintSurface(g2, shape, true, false, CARD_ARC);
            } else {
                // Cards deeper in the pile sit further back in the light too,
                // and each keeps a hairline edge - a stack has to be countable
                // where the cards overlap.
                g2.setColor(blend(LauncherTheme.SURFACE,
                        stackHovered ? LauncherTheme.SURFACE_HOVER : LauncherTheme.BG,
                        Math.min(0.55f, d * 0.22f)));
                g2.fill(shape);
                g2.setColor(LauncherTheme.HAIRLINE);
                g2.setStroke(new java.awt.BasicStroke(1f));
                g2.draw(shape);
            }
            if (contentFade <= 0.01f) return;
            float damped = row.fit() == ModelCatalog.Fit.TOO_LARGE ? 0.5f : 1f;
            withAlpha(g2, alpha * contentFade * damped, () -> paintCardContent(g2, row, x, y, w));
        });
    }

    private void paintCardContent(Graphics2D g2, Row row, int x, int y, int w) {
        int nameY = y + 32;
        int metricsY = y + 68;

        // The verdict takes its room first - it is the short, fixed half of
        // the line - and the name gives up type size and finally its tail to
        // fit beside it. Model names are product names and get long.
        g2.setFont(VERDICT_FONT);
        FontMetrics vfm = g2.getFontMetrics();
        int pillW = vfm.stringWidth(row.verdictText()) + 18;
        int pillH = 18;
        Color verdictColor = switch (row.fit()) {
            case COMFORTABLE -> row.recommended() ? LauncherTheme.ACCENT : LauncherTheme.TEXT_DIM;
            case TIGHT -> VERDICT_TIGHT;
            case TOO_LARGE -> VERDICT_TOO_LARGE;
        };
        int pillX = x + w - 18 - pillW;
        g2.setColor(new Color(verdictColor.getRed(), verdictColor.getGreen(),
                verdictColor.getBlue(), 30));
        g2.fill(new RoundRectangle2D.Double(pillX, nameY - pillH + 5, pillW, pillH, pillH, pillH));
        g2.setColor(verdictColor);
        g2.drawString(row.verdictText(), pillX + 9, nameY);

        g2.setFont(NAME_FONT);
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(fitted(g2, row.name(), pillX - (x + 20) - 10), x + 20, nameY);

        // Quality and speed as glyph + number + word, so the two scales explain
        // themselves on the card instead of needing a legend in the header.
        g2.setFont(SCORE_FONT);
        FontMetrics fm = g2.getFontMetrics();
        double cy = metricsY - fm.getAscent() / 2.0 + 1;

        int qx = x + 26;
        paintDiamond(g2, qx, cy, 6.5, LauncherTheme.ACCENT);
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(String.valueOf(row.quality()), qx + 13, metricsY);
        int qNumW = fm.stringWidth(String.valueOf(row.quality()));

        g2.setFont(LEGEND_FONT);
        FontMetrics lfm = g2.getFontMetrics();
        g2.setColor(LauncherTheme.TEXT_DIM);
        g2.drawString(labels.qualityWord(), qx + 17 + qNumW, metricsY);
        int qWordW = lfm.stringWidth(labels.qualityWord());

        int sx = qx + 17 + qNumW + qWordW + 20;
        g2.setFont(SCORE_FONT);
        paintBolt(g2, sx, cy, 7, LauncherTheme.TEXT_PRIMARY);
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(String.valueOf(row.speed()), sx + 13, metricsY);
        int sNumW = fm.stringWidth(String.valueOf(row.speed()));
        g2.setFont(LEGEND_FONT);
        g2.setColor(LauncherTheme.TEXT_DIM);
        g2.drawString(labels.speedWord(), sx + 17 + sNumW, metricsY);

        g2.setFont(SIZE_FONT);
        FontMetrics zfm = g2.getFontMetrics();
        g2.setColor(LauncherTheme.TEXT_DIM);
        g2.drawString(row.sizeText(), x + w - 20 - zfm.stringWidth(row.sizeText()), metricsY);
    }

    /** Page dots and the two chevrons - the stack's visible controls. */
    private void paintNav(Graphics2D g2) {
        int cy = navCenterY();
        for (int i = 0; i < rows.size(); i++) {
            int cx = dotsLeft() + i * DOT_GAP;
            // The active dot follows the animation, not the target: it grows
            // and lights as its card actually arrives in front.
            float nearness = Math.max(0f, 1f - Math.abs(i - position));
            double r = DOT_R + 1.4 * nearness;
            g2.setColor(blend(i == hoveredDot ? LauncherTheme.TEXT_PRIMARY : DOT_IDLE,
                    LauncherTheme.ACCENT, nearness));
            g2.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        }

        paintChevron(g2, upBounds(), true, upHovered, target > 0);
        paintChevron(g2, downBounds(), false, downHovered, target < rows.size() - 1);
    }

    private void paintChevron(Graphics2D g2, Rectangle b, boolean up, boolean hovered,
            boolean enabled) {
        double cx = b.getCenterX();
        double cy = b.getCenterY();
        g2.setColor(hovered && enabled ? LauncherTheme.SURFACE_HOVER : LauncherTheme.SURFACE);
        g2.fill(new Ellipse2D.Double(b.x, b.y, b.width, b.height));

        double r = 3.6;
        double dir = up ? -1 : 1;
        Path2D p = new Path2D.Double();
        p.moveTo(cx - r, cy - dir * r * 0.5);
        p.lineTo(cx, cy + dir * r * 0.55);
        p.lineTo(cx + r, cy - dir * r * 0.5);
        g2.setColor(enabled ? LauncherTheme.TEXT_PRIMARY : LauncherTheme.SURFACE_HOVER);
        g2.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g2.draw(p);
    }

    /**
     * Fits {@code text} into {@code room}: first by giving up type size down to
     * a floor, then by cutting the tail. Applies the chosen font to {@code g2}.
     */
    private static String fitted(Graphics2D g2, String text, int room) {
        Font font = g2.getFont();
        while (font.getSize() > 12 && g2.getFontMetrics(font).stringWidth(text) > room) {
            font = font.deriveFont((float) font.getSize() - 1);
        }
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(text) <= room) return text;
        String cut = text;
        while (cut.length() > 1 && fm.stringWidth(cut + "…") > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }

    private static Color blend(Color from, Color to, float t) {
        float c = Math.max(0f, Math.min(1f, t));
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * c),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * c),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * c));
    }
}
