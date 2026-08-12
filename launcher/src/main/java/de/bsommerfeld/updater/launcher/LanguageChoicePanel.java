package de.bsommerfeld.updater.launcher;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The language choice - the very first thing a fresh install shows, before
 * anything else has a language to be shown in. It carries no translated chrome
 * of its own: every option is labelled in ITS OWN language (native name, plus
 * the title, hint and button as that language would word them), so the screen
 * is readable no matter which language the user actually speaks. Title, hint
 * and button therefore re-render live as the selection moves.
 *
 * <p>German arrives preselected - the app's primary language - so confirming
 * the default is a single click.
 */
final class LanguageChoicePanel extends ListChoiceScreen {

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

    private final List<Row> rows;

    LanguageChoicePanel(List<Row> rows, String preselectCode, BufferedImage logo,
            Consumer<String> onOk) {
        super(toOptions(rows), preselectCode, logo, onOk);
        this.rows = rows;
    }

    /** The ISO code doubles as the side note - recognisable even when the name is not. */
    private static List<ListChoiceScreen.Option> toOptions(List<Row> rows) {
        List<ListChoiceScreen.Option> options = new ArrayList<>(rows.size());
        for (Row row : rows) {
            options.add(new ListChoiceScreen.Option(row.code(), row.nativeName(),
                    row.code().toUpperCase(Locale.ROOT)));
        }
        return options;
    }

    @Override
    protected String title() {
        return rows.get(selectedIndex()).title();
    }

    @Override
    protected String hint() {
        return rows.get(selectedIndex()).hint();
    }

    @Override
    protected String okText() {
        return rows.get(selectedIndex()).okText();
    }
}
