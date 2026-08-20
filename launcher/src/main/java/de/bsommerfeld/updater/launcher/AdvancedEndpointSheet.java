package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.endpoint.EndpointProbe;

import javax.swing.JComponent;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The "Erweitert" sheet on the model-choice screen: the three fields that point
 * the terminal at an AI server the user already runs, slid up over the card
 * stack like a phone's action sheet.
 *
 * <h3>Why it is here and not only in the app's settings</h3>
 * Because of what the installer does five seconds later. Without this, someone
 * with their own machine still downloads the multi-GB managed runtime first,
 * and only afterwards - in the running app - can say they never wanted it. The
 * question has to be askable before the download, which means here.
 *
 * <h3>Why it hides instead of being a screen</h3>
 * It is the exception. Almost nobody has their own model server, and a full
 * screen asking about one would be a screen almost everybody dismisses. So the
 * stack stays the question, and this is a quiet line under it that opens when
 * asked.
 *
 * <h3>The one thing that is NOT hand-painted</h3>
 * The panel, the wells, the buttons and the animation are ours, drawn like the
 * rest of the launcher. The three input FIELDS are real (chrome-less) Swing
 * text components, deliberately: {@link ChoiceScreen} handles mouse events
 * only, so a painted field would have no caret, no selection, no clipboard -
 * and an access key is pasted, never typed. They are stripped of their border
 * and background and positioned inside our wells, so they read as part of the
 * drawing while behaving like real fields.
 */
final class AdvancedEndpointSheet {

    /** The strings this sheet renders - localized by the caller. */
    record Labels(String title, String url, String model, String key,
            String test, String testing, String ok, String fail, String hint,
            String warning) {
    }

    /**
     * What OK confirms when the sheet holds a usable endpoint.
     *
     * @param api which protocol the address speaks - {@code ollama} or
     *            {@code openai}. Detected, never asked: people know their
     *            server's address, not which chat API it serves.
     */
    record Endpoint(String url, String model, String auth, String api) {
    }

    private static final int PAD = 14;
    private static final int ROW_H = 26;
    private static final int ROW_GAP = 8;
    private static final int LABEL_W = 74;
    private static final int ARC = 14;
    private static final int WELL_ARC = 7;
    private static final int TEST_W = 82;

    /** Total sheet height - three rows, a title line and the test row. */
    static final int HEIGHT = 10 + 18 + 8 + 3 * ROW_H + 2 * ROW_GAP + 10 + ROW_H + PAD;

    private static final Font TITLE_FONT = new Font("SansSerif", Font.BOLD, 12);
    private static final Font LABEL_FONT = new Font("SansSerif", Font.PLAIN, 11);
    private static final Font FIELD_FONT = new Font("SansSerif", Font.PLAIN, 12);
    private static final Font RESULT_FONT = new Font("SansSerif", Font.PLAIN, 10);

    private static final Color SHEET_BG = new Color(30, 30, 34);
    private static final Color WELL_BG = new Color(22, 22, 25);
    private static final Color OK_GREEN = new Color(0x6F, 0xC3, 0x76);
    private static final Color FAIL_RED = new Color(0xD1, 0x6B, 0x6B);

    private final JComponent host;
    private final Labels labels;

    // Package-visible rather than private: the tests drive them directly. The
    // alternative was a fill() method that production code never calls, which
    // is the same exposure with an extra name on it.
    final JTextField urlField = field(new JTextField());
    final JTextField modelField = field(new JTextField());
    final JPasswordField keyField = (JPasswordField) field(new JPasswordField());

    /** 0 = closed, 1 = fully up. Eased, never snapped - see {@link #tick}. */
    private float offset;
    private float target;
    private final Timer slideTimer;

    private boolean testing;
    /**
     * The protocol the last successful probe found. Ollama until proven
     * otherwise - it is the one the pipeline loses nothing on, so it is the
     * safe end of an unanswered question.
     */
    private EndpointProbe.Api detectedApi = EndpointProbe.Api.OLLAMA;
    /** The address the last probe ran against, so focus-out does not re-run it. */
    private String probedUrl = "";
    private String result = "";
    private Color resultColor = LauncherTheme.TEXT_DIM;

    private boolean testHovered;
    private boolean closeHovered;
    private boolean warnHovered;
    /**
     * Whether the warning callout is up. Behind a glyph rather than printed on
     * the sheet: it concerns whoever points this at a paid provider, and at
     * 320x330 a standing sentence would cost a row every other user pays for.
     */
    private boolean warnOpen;

    AdvancedEndpointSheet(JComponent host, Labels labels) {
        this.host = host;
        this.labels = labels;
        this.slideTimer = new Timer(16, _ -> tick());
        for (JComponent c : new JComponent[] {urlField, modelField, keyField}) {
            c.setVisible(false);
            host.add(c);
        }
        // Detect on leaving the address field, not only on the button: someone
        // who fills in three fields and presses OK never pressed Testen, and
        // guessing their protocol wrong would hand them an install that fails
        // at the first headline instead of at the first click.
        urlField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                String typed = urlField.getText().strip();
                if (!typed.isEmpty() && !typed.equals(probedUrl)) runTest();
            }
        });
    }

    /** A text component stripped down to its caret - the wells are painted. */
    private static JTextField field(JTextField f) {
        f.setBorder(null);
        f.setOpaque(false);
        f.setBackground(new Color(0, 0, 0, 0));
        f.setForeground(LauncherTheme.TEXT_PRIMARY);
        f.setCaretColor(LauncherTheme.ACCENT);
        f.setSelectionColor(LauncherTheme.SELECTED_TINT);
        f.setSelectedTextColor(LauncherTheme.TEXT_PRIMARY);
        f.setFont(FIELD_FONT);
        return f;
    }

    // =====================================================================
    // State
    // =====================================================================

    boolean isOpen() {
        return target > 0.5f;
    }

    /** Visible at all - open, or still sliding either way. */
    private boolean isShowing() {
        return offset > 0.001f;
    }

    void toggle() {
        target = isOpen() ? 0f : 1f;
        if (!slideTimer.isRunning()) slideTimer.start();
    }

    /**
     * Opens without the slide - the animation runs on a Swing timer that does
     * not tick in a headless test, and the geometry under test is the settled
     * one anyway.
     */
    void openNow() {
        target = 1f;
        offset = 1f;
        layoutFields();
    }

    void close() {
        if (target == 0f) return;
        target = 0f;
        if (!slideTimer.isRunning()) slideTimer.start();
    }

    /**
     * The endpoint the sheet holds, or {@code null} when it does not hold one.
     *
     * <p>Address AND model are required - exactly the rule the app applies when
     * it resolves the config, so a half-filled sheet cannot produce an install
     * that silently runs as managed anyway while the user believes otherwise.
     */
    Endpoint endpoint() {
        String url = urlField.getText().strip();
        String model = modelField.getText().strip();
        if (url.isEmpty() || model.isEmpty()) return null;
        return new Endpoint(EndpointProbe.normalizeUrl(url), model,
                new String(keyField.getPassword()).strip(),
                detectedApi.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * One animation step - the same exponential ease the card stack flips
     * with, so the two motions on this screen feel like one hand.
     */
    private void tick() {
        float delta = target - offset;
        if (Math.abs(delta) < 0.004f) {
            offset = target;
            slideTimer.stop();
        } else {
            offset += delta * 0.22f;
        }
        layoutFields();
        host.repaint();
    }

    // =====================================================================
    // Geometry
    // =====================================================================

    /** Where the sheet's top edge currently sits; below the body when closed. */
    private int top(int bodyBottom) {
        return bodyBottom - Math.round(HEIGHT * offset);
    }

    private Rectangle rowBounds(int bodyBottom, int index) {
        int y = top(bodyBottom) + 10 + 18 + 8 + index * (ROW_H + ROW_GAP);
        return new Rectangle(PAD_LEFT() + LABEL_W, y,
                host.getWidth() - 2 * PAD_LEFT() - LABEL_W, ROW_H);
    }

    private static int PAD_LEFT() {
        return ChoiceScreen.PAD_X + PAD;
    }

    private Rectangle testBounds(int bodyBottom) {
        int y = top(bodyBottom) + 10 + 18 + 8 + 3 * (ROW_H + ROW_GAP) + 10;
        return new Rectangle(PAD_LEFT(), y, TEST_W, ROW_H);
    }

    /** The warning glyph, immediately right of the title. */
    private Rectangle warnBounds(int bodyBottom) {
        return new Rectangle(PAD_LEFT() + titleWidth() + 8, top(bodyBottom) + 6, 20, 20);
    }

    private int titleWidth() {
        return host.getFontMetrics(TITLE_FONT).stringWidth(labels.title());
    }

    /** The warning glyph's box at the sheet's current position - for the test. */
    Rectangle warnBoundsForTest() {
        return warnBounds(bodyBottom());
    }

    /** The close chevron, top-right of the sheet. */
    private Rectangle closeBounds(int bodyBottom) {
        return new Rectangle(host.getWidth() - PAD_LEFT() - 20, top(bodyBottom) + 6, 24, 22);
    }

    /**
     * Keeps the real text fields sitting inside the painted wells while the
     * sheet moves, and takes them off screen entirely when it is closed - a
     * stray caret floating over the card stack would be the one thing that
     * gives the illusion away.
     */
    void layoutFields() {
        // The callout is PAINTED; the fields are real components and Swing draws
        // those after everything painted, so they punched straight through it
        // (seen in the render: the address on top of the warning, both
        // unreadable). While the callout is up the fields step aside.
        if (!isShowing() || warnOpen) {
            for (JComponent c : new JComponent[] {urlField, modelField, keyField}) {
                c.setVisible(false);
            }
            return;
        }
        int bodyBottom = bodyBottom();
        JTextField[] fields = {urlField, modelField, keyField};
        for (int i = 0; i < fields.length; i++) {
            Rectangle well = rowBounds(bodyBottom, i);
            fields[i].setVisible(true);
            fields[i].setBounds(well.x + 9, well.y + 4, well.width - 18, well.height - 8);
        }
    }

    /** The host's body band - mirrors {@link ChoiceScreen#bodyBottom()}. */
    private int bodyBottom() {
        return host.getHeight() - 30 - 14 - 12;
    }

    // =====================================================================
    // Input
    // =====================================================================

    /** @return whether the press was consumed by the sheet */
    boolean press(int x, int y) {
        if (!isShowing()) return false;
        int bodyBottom = bodyBottom();
        if (warnOpen) {
            // Anywhere closes it - a callout is dismissed, not managed.
            warnOpen = false;
            layoutFields();
            host.repaint();
            return true;
        }
        if (warnBounds(bodyBottom).contains(x, y)) {
            warnOpen = true;
            layoutFields();
            host.repaint();
            return true;
        }
        if (closeBounds(bodyBottom).contains(x, y)) {
            close();
            return true;
        }
        if (testBounds(bodyBottom).contains(x, y)) {
            runTest();
            return true;
        }
        // Anything above the sheet is the dimmed stack: a tap there closes,
        // the way a sheet anywhere else does.
        if (y < top(bodyBottom)) {
            close();
            return true;
        }
        // Inside the sheet but on no control: swallow it, so a stray click does
        // not flip the cards hidden behind it.
        return y >= top(bodyBottom);
    }

    /** @return whether anything changed and the host needs a repaint */
    boolean hover(int x, int y) {
        if (!isShowing()) {
            boolean dirty = testHovered || closeHovered;
            testHovered = false;
            closeHovered = false;
            return dirty;
        }
        int bodyBottom = bodyBottom();
        boolean test = testBounds(bodyBottom).contains(x, y) && !testing;
        boolean close = closeBounds(bodyBottom).contains(x, y);
        boolean warn = warnBounds(bodyBottom).contains(x, y);
        boolean dirty = test != testHovered || close != closeHovered || warn != warnHovered;
        testHovered = test;
        closeHovered = close;
        warnHovered = warn;
        return dirty;
    }

    boolean hitsControl(int x, int y) {
        if (!isShowing()) return false;
        int bodyBottom = bodyBottom();
        return testBounds(bodyBottom).contains(x, y) || closeBounds(bodyBottom).contains(x, y)
                || warnBounds(bodyBottom).contains(x, y);
    }

    /**
     * Asks the address whether it answers. Off the EDT: an unreachable host
     * costs the full connect timeout, and freezing the launcher for it would
     * look exactly like the failure the button exists to find.
     */
    private void runTest() {
        if (testing) return;
        testing = true;
        result = labels.testing();
        resultColor = LauncherTheme.TEXT_DIM;
        host.repaint();

        String url = urlField.getText();
        String auth = new String(keyField.getPassword());
        probedUrl = url.strip();
        Thread.ofVirtual().name("launcher-endpoint-probe").start(() -> {
            EndpointProbe.Result probe = EndpointProbe.probe(url, "Authorization", auth);
            javax.swing.SwingUtilities.invokeLater(() -> {
                testing = false;
                if (probe.ok()) {
                    detectedApi = probe.api();
                    // The protocol is named in the verdict rather than offered
                    // as a choice: it is information ("I found an Ollama"), and
                    // making it a control would ask a question nobody can
                    // answer better than the probe just did.
                    result = labels.ok() + " - " + probe.api().name().toLowerCase(
                            java.util.Locale.ROOT) + " (" + probe.models().size() + ")";
                    resultColor = OK_GREEN;
                    // The single best thing to do with a successful probe: fill
                    // the model field, so nobody has to type a tag correctly
                    // from memory. Only when it is still empty - never over
                    // something the user chose.
                    if (modelField.getText().isBlank() && !probe.models().isEmpty()) {
                        modelField.setText(probe.models().get(0));
                    }
                } else {
                    // The server's / network's own words, verbatim.
                    result = labels.fail() + ": " + probe.reason();
                    resultColor = FAIL_RED;
                }
                host.repaint();
            });
        });
    }

    // =====================================================================
    // Painting
    // =====================================================================

    /**
     * Paints the dimmed stack and the sheet over it. Called last by the host so
     * it lands on top of everything the screen already drew.
     */
    void paint(Graphics2D g2, int bodyTop, int bodyBottom) {
        if (!isShowing()) return;

        // The stack behind is dimmed proportionally to the slide, so the sheet
        // arrives with the room already darkening rather than after it.
        g2.setColor(new Color(0, 0, 0, Math.round(150 * offset)));
        g2.fillRect(0, bodyTop, host.getWidth(), bodyBottom - bodyTop);

        // Clipped to the body band, so the sheet slides UP OUT OF the bottom
        // edge instead of sweeping across the footer on its way - the legend
        // and the OK button belong to the screen, not to the sheet.
        java.awt.Shape oldClip = g2.getClip();
        g2.clipRect(0, bodyTop, host.getWidth(), bodyBottom - bodyTop);

        int top = top(bodyBottom);
        int left = ChoiceScreen.PAD_X;
        int width = host.getWidth() - 2 * left;
        // Drawn taller than it shows so the bottom corners stay off screen -
        // a sheet is anchored to the edge it came from, not floating above it.
        RoundRectangle2D panel = new RoundRectangle2D.Double(
                left, top, width, HEIGHT + ARC, ARC, ARC);
        g2.setColor(SHEET_BG);
        g2.fill(panel);
        g2.setColor(LauncherTheme.HAIRLINE);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(panel);

        // No grab handle: at this width it lands on the same line as the title
        // and reads as debris beside it (seen in the rendered screen). The
        // chevron on the right already says the sheet can go back down.
        g2.setFont(TITLE_FONT);
        FontMetrics tfm = g2.getFontMetrics();
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(labels.title(), PAD_LEFT(), top + 14 + tfm.getAscent() / 2);
        paintWarnGlyph(g2, warnBounds(bodyBottom));
        paintCloseChevron(g2, closeBounds(bodyBottom));

        String[] rowLabels = {labels.url(), labels.model(), labels.key()};
        for (int i = 0; i < rowLabels.length; i++) {
            Rectangle well = rowBounds(bodyBottom, i);
            g2.setFont(LABEL_FONT);
            FontMetrics lfm = g2.getFontMetrics();
            g2.setColor(LauncherTheme.TEXT_DIM);
            g2.drawString(rowLabels[i], PAD_LEFT(),
                    well.y + (well.height + lfm.getAscent() - lfm.getDescent()) / 2);

            g2.setColor(WELL_BG);
            g2.fill(new RoundRectangle2D.Double(well.x, well.y, well.width, well.height,
                    WELL_ARC, WELL_ARC));
            g2.setColor(LauncherTheme.HAIRLINE);
            g2.draw(new RoundRectangle2D.Double(well.x + 0.5, well.y + 0.5,
                    well.width - 1, well.height - 1, WELL_ARC, WELL_ARC));
        }

        Rectangle test = testBounds(bodyBottom);
        g2.setColor(testHovered ? LauncherTheme.SURFACE_HOVER : LauncherTheme.SURFACE);
        g2.fill(new RoundRectangle2D.Double(test.x, test.y, test.width, test.height,
                WELL_ARC, WELL_ARC));
        g2.setFont(LABEL_FONT);
        FontMetrics bfm = g2.getFontMetrics();
        g2.setColor(LauncherTheme.TEXT_PRIMARY);
        g2.drawString(labels.test(),
                test.x + (test.width - bfm.stringWidth(labels.test())) / 2,
                test.y + (test.height + bfm.getAscent() - bfm.getDescent()) / 2);

        // The verdict sits beside the button; before the first test the slot
        // carries the hint instead, so the row is never a bare button.
        String line = result.isEmpty() ? labels.hint() : result;
        g2.setFont(RESULT_FONT);
        FontMetrics rfm = g2.getFontMetrics();
        int room = host.getWidth() - PAD_LEFT() - (test.x + test.width + 10);
        g2.setColor(result.isEmpty() ? LauncherTheme.TEXT_DIM : resultColor);
        g2.drawString(ellipsize(line, rfm, room), test.x + test.width + 10,
                test.y + (test.height + rfm.getAscent() - rfm.getDescent()) / 2);

        if (warnOpen) paintWarnCallout(g2, bodyBottom);

        g2.setClip(oldClip);
    }

    /** The alarm triangle beside the title - hollow, small, unmissable in red. */
    private void paintWarnGlyph(Graphics2D g2, Rectangle b) {
        double cx = b.getCenterX();
        double cy = b.getCenterY();
        double r = 7;
        Path2D t = new Path2D.Double();
        t.moveTo(cx, cy - r);
        t.lineTo(cx + r, cy + r * 0.8);
        t.lineTo(cx - r, cy + r * 0.8);
        t.closePath();
        g2.setColor(warnHovered || warnOpen ? FAIL_RED.brighter() : FAIL_RED);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(t);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new java.awt.geom.Line2D.Double(cx, cy - r * 0.25, cx, cy + r * 0.2));
        g2.fill(new java.awt.geom.Ellipse2D.Double(cx - 0.8, cy + r * 0.42, 1.6, 1.6));
    }

    /**
     * The callout the glyph opens: two sentences over the sheet, dismissed by
     * the next click anywhere. Painted rather than laid out, because it exists
     * for a moment and must not push a single field out of place.
     */
    private void paintWarnCallout(Graphics2D g2, int bodyBottom) {
        int left = ChoiceScreen.PAD_X + 8;
        int width = host.getWidth() - 2 * left;
        int top = top(bodyBottom) + 30;

        g2.setFont(LABEL_FONT);
        FontMetrics fm = g2.getFontMetrics();
        java.util.List<String> lines = wrap(labels.warning(), fm, width - 24);
        int height = 18 + lines.size() * (fm.getHeight() + 1);

        RoundRectangle2D box = new RoundRectangle2D.Double(left, top, width, height, 10, 10);
        g2.setColor(new Color(30, 18, 18));
        g2.fill(box);
        g2.setColor(FAIL_RED);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(box);

        int y = top + 12 + fm.getAscent();
        g2.setColor(new Color(0xF0, 0xC0, 0xC0));
        for (String line : lines) {
            g2.drawString(line, left + 12, y);
            y += fm.getHeight() + 1;
        }
    }

    /** Word wrap for the callout - the sentence is translated and grows. */
    private static java.util.List<String> wrap(String text, FontMetrics fm, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) > width && !line.isEmpty()) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    private void paintCloseChevron(Graphics2D g2, Rectangle b) {
        double cx = b.getCenterX();
        double cy = b.getCenterY();
        Path2D p = new Path2D.Double();
        p.moveTo(cx - 5, cy - 2.5);
        p.lineTo(cx, cy + 2.5);
        p.lineTo(cx + 5, cy - 2.5);
        g2.setColor(closeHovered ? LauncherTheme.TEXT_PRIMARY : LauncherTheme.TEXT_DIM);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(p);
    }

    /** Trims a line to the room it has - a probe error can be a paragraph. */
    private static String ellipsize(String text, FontMetrics fm, int room) {
        if (room <= 0 || fm.stringWidth(text) <= room) return text;
        String cut = text;
        while (cut.length() > 1 && fm.stringWidth(cut + "...") > room) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }
}
