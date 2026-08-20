package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

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

    /**
     * One selectable tier card, fully pre-localized by the caller.
     *
     * <p>{@code mlx} marks a tier whose EFFECTIVE tag is the Apple-Silicon MLX
     * build — derived by the caller from what {@link ModelCatalog#tagFor}
     * actually returned, never from a second list. Since the catalog grew
     * tiers without an MLX twin (Granite), this is real information on an
     * Apple-Silicon machine: some cards run as MLX builds and some do not.
     * On Windows/Linux no tag ever ends in {@code -mlx}, so the chip never
     * appears there by construction.
     */
    record Row(String tag, String name, int quality, int speed, String sizeText,
            ModelCatalog.Fit fit, boolean recommended, String verdictText, boolean mlx) {
    }

    /**
     * The localized strings the panel renders. {@code qualityWord} /
     * {@code speedWord} no longer appear on the cards — they caption the two
     * glyphs ONCE, in the footer legend where the recommendation note used to
     * sit. {@code baseToggleWord} labels the "install without MLX" toggle in
     * the nav strip, which only ever surfaces on machines where an MLX choice
     * exists at all.
     */
    record Labels(String title, String qualityWord, String speedWord,
            String okText, String baseToggleWord, String advancedWord) {
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
    // ONE size for the whole metrics line (user decision 2026-08-14): with the
    // "Qualität"/"Tempo" words moved off the card into the footer legend,
    // nothing on the line justifies a second type size anymore. Weight and
    // colour still separate the scores (bold, primary) from the quiet
    // companions MLX + size (plain, dim).
    private static final Font METRIC_FONT = new Font("SansSerif", Font.BOLD, 14);
    private static final Font METRIC_QUIET_FONT = new Font("SansSerif", Font.PLAIN, 14);
    private static final Font LEGEND_FONT = new Font("SansSerif", Font.PLAIN, 10);
    private static final Font VERDICT_FONT = new Font("SansSerif", Font.PLAIN, 11);
    /** The toggle word at control size - same visual weight as the chevrons. */
    private static final Font TOGGLE_FONT = new Font("SansSerif", Font.PLAIN, 13);

    private final List<Row> rows;
    private final Labels labels;
    /**
     * The "Erweitert" sheet. Always present, always closed to begin with: the
     * screen's question is the stack, and this is the exception under it.
     */
    private final AdvancedEndpointSheet sheet;
    private boolean advancedHovered;

    /** The card the stack is settling on - the one OK confirms. */
    private int target;
    /** The animated card index; {@code target} once the stack has settled. */
    private float position;
    private final Timer flipTimer;

    /**
     * The exception lever: install the BASE (GGUF) build instead of the MLX
     * twin on Apple Silicon. Default off — MLX stays the standard there; this
     * only widens {@link #confirmedValue()} to the un-suffixed tag. The
     * toggle lives in the nav strip's free middle (between the page dots and
     * the chevrons — deliberately none of the existing tap zones) and shows
     * ONLY while the front card actually has an MLX twin: a control that
     * stands around inert on a Granite card would be the worst variant, and
     * on Windows/Linux no row is ever MLX, so it never appears at all.
     */
    private boolean baseVariant;

    private int hoveredDot = -1;
    private boolean upHovered;
    private boolean downHovered;
    private boolean stackHovered;
    private boolean toggleHovered;

    ModelChoicePanel(List<Row> rows, String preselectTag, Labels labels,
            AdvancedEndpointSheet.Labels advancedLabels,
            BufferedImage logo, Consumer<String> onOk) {
        super(logo, onOk);
        this.rows = rows;
        this.labels = labels;
        this.sheet = new AdvancedEndpointSheet(this, advancedLabels);
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
        // The footer's text slot is empty on purpose: paintLegend() draws the
        // glyph legend (diamond = quality, bolt = speed) into that exact spot
        // — glyphs cannot travel through the plain hint string. The old
        // recommendation note fell away with it (user decision 2026-08-14),
        // as did the MLX explainer sentence ("irrelevant", same decision).
        return "";
    }

    @Override
    protected String okText() {
        return labels.okText();
    }

    @Override
    protected String confirmedValue() {
        // The lever widens the choice to the base build: the confirmed tag
        // simply loses its -mlx suffix. Everything downstream
        // (ModelConfigWriter → config.toml → WSBG_REASONING_MODEL) carries
        // the tag verbatim, so this is the ONLY place the choice is made.
        String tag = rows.get(target).tag();
        return baseVariant && tag.endsWith("-mlx")
                ? tag.substring(0, tag.length() - "-mlx".length())
                : tag;
    }

    /**
     * The external endpoint the user filled in, or {@code null} when they did
     * not - then the confirmed stack tag applies as before. Read by the
     * launcher AFTER OK; the two are mutually exclusive by construction,
     * because an endpoint means no model of ours is installed at all.
     */
    AdvancedEndpointSheet.Endpoint chosenEndpoint() {
        return sheet.endpoint();
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
        // A wheel notch while the sheet is up would flip cards the user cannot
        // even see.
        if (sheet.isOpen()) return;
        if (notches != 0) flipTo(target + Integer.signum(notches));
    }

    @Override
    protected void pressBody(int x, int y) {
        // The sheet is modal over the body: it consumes what it covers, so a
        // click meant for a field can never reach a card underneath it.
        if (sheet.press(x, y)) return;
        if (advancedBounds().contains(x, y)) {
            sheet.toggle();
            repaint();
            return;
        }
        if (toggleVisible() && toggleBounds().contains(x, y)) {
            baseVariant = !baseVariant;
            repaint();
            return;
        }
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
        boolean advanced = advancedBounds().contains(x, y);
        boolean dirtySheet = sheet.hover(x, y) || advanced != advancedHovered;
        advancedHovered = advanced;
        if (sheet.isOpen()) {
            // Everything under the sheet is out of reach; leaving the stack's
            // own hover states set would light up controls nobody can press.
            boolean hadStackHover = upHovered || downHovered || stackHovered
                    || toggleHovered || hoveredDot >= 0;
            upHovered = downHovered = stackHovered = toggleHovered = false;
            hoveredDot = -1;
            return dirtySheet || hadStackHover;
        }
        boolean up = upBounds().contains(x, y);
        boolean down = downBounds().contains(x, y);
        int dot = dotAt(x, y);
        boolean stack = topPeekBounds().contains(x, y) || bottomPeekBounds().contains(x, y);
        boolean toggle = toggleVisible() && toggleBounds().contains(x, y);
        boolean dirty = up != upHovered || down != downHovered
                || dot != hoveredDot || stack != stackHovered || toggle != toggleHovered
                || dirtySheet;
        upHovered = up;
        downHovered = down;
        hoveredDot = dot;
        stackHovered = stack;
        toggleHovered = toggle;
        return dirty;
    }

    @Override
    protected boolean bodyHitsControl(int x, int y) {
        if (advancedBounds().contains(x, y) || sheet.hitsControl(x, y)) return true;
        // While the sheet is up nothing behind it is a control - including for
        // the window drag, which must not slide the launcher out from under a
        // field the user is typing in.
        if (sheet.isOpen()) return true;
        return upBounds().contains(x, y) || downBounds().contains(x, y)
                || dotAt(x, y) >= 0
                || (toggleVisible() && toggleBounds().contains(x, y))
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

    /** Whether the base-build lever applies to the FRONT card at all. */
    private boolean toggleVisible() {
        return rows.get(target).mlx();
    }

    /**
     * The "without MLX" toggle: checkbox + word in the nav strip's free
     * middle. Bounded left by the page dots and right by the chevrons, so it
     * can never overlay or blur any of the existing tap zones (peek edges,
     * dots, chevrons). Package-visible for the toggle test.
     */
    Rectangle toggleBounds() {
        int left = dotsLeft() + rows.size() * DOT_GAP + 8;
        int right = getWidth() - PAD_X - 2 * (2 * CHEVRON_R) - 6 - 10;
        int cy = navCenterY();
        // Height matches the chevron circles (2 × CHEVRON_R) - the toggle is
        // a control-sized control now, and its hit zone grew with it while
        // the left/right bounds still fence it off dots and chevrons.
        return new Rectangle(left, cy - CHEVRON_R, Math.max(0, right - left), 2 * CHEVRON_R);
    }

    /**
     * The "Erweitert" entry: a quiet word with a chevron in the footer, right
     * -aligned against the OK button.
     *
     * <p>The footer, not the nav strip, because the strip is already full (page
     * dots, the MLX lever, two chevrons) and this screen lives in a 320x330
     * window - there is no free row to add. Right-aligned rather than beside
     * the legend so it cannot collide with a longer translation of it.
     */
    Rectangle advancedBounds() {
        Rectangle ok = okBounds();
        int width = getFontMetrics(LEGEND_FONT).stringWidth(labels.advancedWord()) + 16;
        return new Rectangle(ok.x - 12 - width, ok.y, width, ok.height);
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
        paintLegend(g2);
        paintAdvancedEntry(g2);
        // Last, so it lands over the stack and the nav strip - the footer sits
        // below the body band and stays reachable, which is what OK needs.
        sheet.paint(g2, STACK_TOP, bodyBottom());
    }

    /**
     * The footer's "Erweitert" entry. Deliberately understated - dim text, a
     * small chevron, no surface: almost nobody runs their own model server, and
     * a button competing with OK would be a button most people have to decide
     * about for nothing.
     */
    private void paintAdvancedEntry(Graphics2D g2) {
        Rectangle b = advancedBounds();
        g2.setFont(LEGEND_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int textY = b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2;
        g2.setColor(advancedHovered || sheet.isOpen()
                ? LauncherTheme.TEXT_PRIMARY : LauncherTheme.TEXT_DIM);
        g2.drawString(labels.advancedWord(), b.x, textY);

        // The chevron points the way the sheet will move.
        double cx = b.x + b.width - 7;
        double cy = b.getCenterY();
        double dir = sheet.isOpen() ? -1 : 1;
        Path2D chevron = new Path2D.Double();
        chevron.moveTo(cx - 3.5, cy + 1.5 * dir);
        chevron.lineTo(cx, cy - 2 * dir);
        chevron.lineTo(cx + 3.5, cy + 1.5 * dir);
        g2.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND));
        g2.draw(chevron);
    }

    /**
     * The glyph legend — diamond = quality, bolt = speed — painted once for
     * all cards, in the footer slot the recommendation note used to hold
     * (hint() returns "" so the slot is genuinely ours). This is what allowed
     * the words to leave the cards: the meaning is stated here instead of
     * repeated seven times.
     */
    private void paintLegend(Graphics2D g2) {
        Rectangle ok = okBounds();
        double cy = ok.getCenterY();
        g2.setFont(LEGEND_FONT);
        FontMetrics fm = g2.getFontMetrics();
        int textY = (int) Math.round(cy + (fm.getAscent() - fm.getDescent()) / 2.0);

        int x = PAD_X;
        paintDiamond(g2, x + 5, cy, 5, LauncherTheme.ACCENT);
        g2.setColor(LauncherTheme.TEXT_DIM);
        g2.drawString(labels.qualityWord(), x + 13, textY);
        x += 13 + fm.stringWidth(labels.qualityWord()) + 16;

        paintBolt(g2, x + 5, cy, 5.5, LauncherTheme.TEXT_PRIMARY);
        g2.setColor(LauncherTheme.TEXT_DIM);
        g2.drawString(labels.speedWord(), x + 13, textY);
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

        // The name gets the full run up to the verdict pill back — the MLX
        // marker moved down into the metrics line after it cut long names
        // short here (seen live 2026-08-14 on "Nemotron 3.5 Lightning").
        g2.setFont(NAME_FONT);
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(fitted(g2, row.name(), pillX - (x + 20) - 10), x + 20, nameY);

        // The metrics line: glyph + number for both scales, then the quiet
        // MLX marker, size right-aligned — everything at ONE type size. The
        // words moved into the footer legend (paintLegend), stated once for
        // all cards instead of repeated on each.
        g2.setFont(METRIC_FONT);
        FontMetrics fm = g2.getFontMetrics();
        double cy = metricsY - fm.getAscent() / 2.0 + 1;

        int qx = x + 26;
        paintDiamond(g2, qx, cy, 6.5, LauncherTheme.ACCENT);
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(String.valueOf(row.quality()), qx + 13, metricsY);
        int qNumW = fm.stringWidth(String.valueOf(row.quality()));

        int sx = qx + 13 + qNumW + 22;
        paintBolt(g2, sx, cy, 7, LauncherTheme.TEXT_PRIMARY);
        g2.drawString(String.valueOf(row.speed()), sx + 13, metricsY);
        int sNumW = fm.stringWidth(String.valueOf(row.speed()));

        // The MLX marker, in line with the scores (Apple-Silicon MLX builds
        // only, see Row.mlx). "MLX" is a product name and never translated.
        // It shows the EFFECTIVE build: with the base-variant lever active the
        // install is not MLX, so the marker disappears rather than lie. Plain
        // and dim like the size text — it joins the line, it does not lead it.
        if (row.mlx() && !baseVariant) {
            g2.setFont(METRIC_QUIET_FONT);
            g2.setColor(LauncherTheme.TEXT_DIM);
            g2.drawString("MLX", sx + 13 + sNumW + 22, metricsY);
        }

        g2.setFont(METRIC_QUIET_FONT);
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

        if (toggleVisible()) paintBaseToggle(g2);
    }

    /**
     * The base-build lever: a small checkbox and its word, quiet like the
     * dots beside it. "MLX" inside the label is a product name and arrives
     * untranslated; everything around it came through {@code LauncherI18n}.
     */
    private void paintBaseToggle(Graphics2D g2) {
        Rectangle b = toggleBounds();
        // Control-sized (user decision 2026-08-14): the box matches the
        // chevrons' visual weight instead of hiding at page-dot scale.
        int box = 16;
        int by = b.y + (b.height - box) / 2;

        g2.setStroke(new java.awt.BasicStroke(1.2f));
        if (baseVariant) {
            g2.setColor(LauncherTheme.ACCENT);
            g2.fill(new RoundRectangle2D.Double(b.x, by, box, box, 5, 5));
            g2.setColor(LauncherTheme.ON_ACCENT);
            Path2D check = new Path2D.Double();
            check.moveTo(b.x + 4, by + 8);
            check.lineTo(b.x + 7, by + 11.2);
            check.lineTo(b.x + 12, by + 4.8);
            g2.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g2.draw(check);
        } else {
            g2.setColor(toggleHovered ? LauncherTheme.TEXT_DIM : DOT_IDLE);
            g2.draw(new RoundRectangle2D.Double(b.x + 0.5, by + 0.5, box - 1, box - 1, 5, 5));
        }

        // The word gives up type size rather than running into the chevrons -
        // some languages are longer than the German "Ohne MLX".
        Font word = TOGGLE_FONT;
        int room = b.width - box - 7;
        while (word.getSize() > 9
                && g2.getFontMetrics(word).stringWidth(labels.baseToggleWord()) > room) {
            word = word.deriveFont((float) word.getSize() - 1);
        }
        g2.setFont(word);
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(baseVariant || toggleHovered
                ? LauncherTheme.TEXT_PRIMARY : LauncherTheme.TEXT_DIM);
        g2.drawString(labels.baseToggleWord(), b.x + box + 7,
                b.y + (b.height + fm.getAscent() - fm.getDescent()) / 2);
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
