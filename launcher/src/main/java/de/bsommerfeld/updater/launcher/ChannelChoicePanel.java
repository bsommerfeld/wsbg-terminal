package de.bsommerfeld.updater.launcher;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The update-channel choice - asked once per install, right after the language
 * is known and before anything checks GitHub for the first time.
 *
 * <p>Each row states plainly what it is (stable / experimental) and carries the
 * room's own reading of that decision on the right, where the language screen
 * puts the ISO code: risk management on one side, maximum leverage on the
 * other. Stable arrives preselected - the cautious answer is a single click,
 * and the adventurous one has to be picked on purpose.
 */
final class ChannelChoicePanel extends ListChoiceScreen {

    /**
     * One selectable channel.
     *
     * @param answer   the value persisted as {@code user.experimental-updates}
     * @param name     the channel's plain name ("Stabil" / "Experimentell")
     * @param sideNote the room's characterisation, set beside the name
     */
    record Row(String answer, String name, String sideNote) {
    }

    /**
     * The screen's fixed chrome, pre-translated by the caller.
     *
     * @param title  the question
     * @param hint   the footer note
     * @param okText the confirm button
     */
    record Labels(String title, String hint, String okText) {
    }

    private final Labels labels;

    ChannelChoicePanel(List<Row> rows, String preselectAnswer, Labels labels,
            BufferedImage logo, Consumer<String> onOk) {
        super(toOptions(rows), preselectAnswer, logo, onOk);
        this.labels = labels;
    }

    private static List<ListChoiceScreen.Option> toOptions(List<Row> rows) {
        List<ListChoiceScreen.Option> options = new ArrayList<>(rows.size());
        for (Row row : rows) {
            options.add(new ListChoiceScreen.Option(row.answer(), row.name(), row.sideNote()));
        }
        return options;
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
}
