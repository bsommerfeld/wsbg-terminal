package de.bsommerfeld.updater.launcher;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.function.Consumer;

/**
 * A choice with a handful of options, one row each: the option's name on the
 * left, a quiet side note on the right. The rows sit as one block, vertically
 * centred in the space the header and the footer leave - two options look
 * settled rather than stranded at the top.
 *
 * <p>Used by {@link LanguageChoicePanel} - the launcher's one remaining
 * question. The shape is deliberately kept general: a second list-shaped
 * question would only have to fill in the two texts.
 */
abstract class ListChoiceScreen extends ChoiceScreen {

    /** One selectable option, fully pre-localized by the caller. */
    record Option(String value, String name, String sideNote) {
    }

    private static final int ROW_H = 46;
    private static final int ROW_GAP = 8;

    private static final Font NAME_FONT = new Font("SansSerif", Font.BOLD, 15);
    private static final Font SIDE_FONT = new Font("SansSerif", Font.PLAIN, 12);

    private final List<Option> options;

    private int selected;
    private int hovered = -1;

    ListChoiceScreen(List<Option> options, String preselectValue,
            BufferedImage logo, Consumer<String> onOk) {
        super(logo, onOk);
        this.options = options;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value().equals(preselectValue)) selected = i;
        }
    }

    /** The index of the option the confirm button would hand back. */
    protected int selectedIndex() {
        return selected;
    }

    @Override
    protected String confirmedValue() {
        return options.get(selected).value();
    }

    /** Top of the row block - centred between header and footer. */
    private int rowsTop() {
        int block = options.size() * ROW_H + (options.size() - 1) * ROW_GAP;
        return HEADER_H + (bodyBottom() - HEADER_H - block) / 2;
    }

    private int rowAt(int y) {
        int top = rowsTop();
        for (int i = 0; i < options.size(); i++) {
            int rowY = top + i * (ROW_H + ROW_GAP);
            if (y >= rowY && y < rowY + ROW_H) return i;
        }
        return -1;
    }

    @Override
    protected boolean bodyHitsControl(int x, int y) {
        return x >= PAD_X && x <= getWidth() - PAD_X && rowAt(y) >= 0;
    }

    @Override
    protected void pressBody(int x, int y) {
        int row = bodyHitsControl(x, y) ? rowAt(y) : -1;
        if (row >= 0 && row != selected) {
            selected = row;
            repaint();
        }
    }

    @Override
    protected boolean hoverBody(int x, int y) {
        int row = bodyHitsControl(x, y) ? rowAt(y) : -1;
        if (row == hovered) return false;
        hovered = row;
        return true;
    }

    @Override
    protected void paintBody(Graphics2D g2) {
        int top = rowsTop();
        int fullW = getWidth() - 2 * PAD_X;
        for (int i = 0; i < options.size(); i++) {
            int y = top + i * (ROW_H + ROW_GAP);
            paintSurface(g2, new RoundRectangle2D.Double(PAD_X, y, fullW, ROW_H, ROW_ARC, ROW_ARC),
                    i == selected, i == hovered, ROW_ARC);

            Option option = options.get(i);
            g2.setFont(NAME_FONT);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(LauncherTheme.TEXT_PRIMARY);
            g2.drawString(option.name(), PAD_X + 22,
                    y + (ROW_H + fm.getAscent() - fm.getDescent()) / 2);

            g2.setFont(SIDE_FONT);
            FontMetrics sfm = g2.getFontMetrics();
            g2.setColor(LauncherTheme.TEXT_DIM);
            g2.drawString(option.sideNote(),
                    getWidth() - PAD_X - 22 - sfm.stringWidth(option.sideNote()),
                    y + (ROW_H + sfm.getAscent() - sfm.getDescent()) / 2);
        }
    }
}
