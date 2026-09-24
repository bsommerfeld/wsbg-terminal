package de.bsommerfeld.wsbg.terminal.chrome;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;

import java.util.Locale;

/**
 * A run of text laid out glyph by glyph, so it can carry letter-spacing - JavaFX
 * CSS has none. The tracking is given in em and follows the glyphs' CSS font
 * size; the glyphs are uppercased, which the title bar's CSS did in the web UI.
 * Every glyph carries the style classes handed in, so a stylesheet can address
 * them ({@code .tb-title .tb-text}); {@link #glyph(int)} reaches a single one.
 */
public final class TrackedText extends HBox {

    private final double trackingEm;
    private final String[] glyphClasses;

    public TrackedText(String text, double trackingEm, String... glyphClasses) {
        this.trackingEm = trackingEm;
        this.glyphClasses = glyphClasses;
        setAlignment(Pos.CENTER_LEFT);
        setText(text);
    }

    public void setText(String text) {
        getChildren().clear();
        spacingProperty().unbind();

        text.toUpperCase(Locale.ROOT).codePoints().forEach(cp -> {
            Text glyph = new Text(Character.toString(cp));
            glyph.getStyleClass().addAll(glyphClasses);
            getChildren().add(glyph);
        });

        if (!getChildren().isEmpty()) {
            Text first = glyph(0);
            spacingProperty().bind(Bindings.createDoubleBinding(
                    () -> first.getFont().getSize() * trackingEm, first.fontProperty()));
        }
    }

    public Text glyph(int index) {
        return (Text) getChildren().get(index);
    }
}
