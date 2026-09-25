package de.bsommerfeld.wsbg.terminal.canvas;

import de.bsommerfeld.wsbg.orb.MarbleOrb;
import de.bsommerfeld.wsbg.orb.OrbPalette;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;


/**
 * The button pill at the selection: orb | cut, copy, paste. Its buttons are
 * functions, not toggles; the orb always comes first - a ring at rest, the
 * marble while the pointer is on its button. The line icons are drawn on a
 * 24 grid with a 1.6 stroke and come alive under the pointer as the orb does.
 * What the buttons do is set by whoever owns the pill.
 */
public final class ToolPill extends HBox {

    private static final double ORB_SIZE = 28;
    private static final double ICON = 24;
    private static final double SNIP_ANGLE = 14;
    private static final Duration SNIP = Duration.millis(110);
    private static final Duration SLIDE = Duration.millis(280);
    private static final Color ORB_RING = Color.web("#8f8e8d"); // -mute, as the line icons

    private final Button orb;
    private final Button cut;
    private final Button copy;
    private final Button paste;

    public ToolPill() {
        getStyleClass().add("dc-pill");

        MarbleOrb marble = new MarbleOrb();
        marble.setSize(ORB_SIZE);
        marble.setRingColor(ORB_RING);
        marble.setPalette(OrbPalette.HERBST);
        orb = button("Orb", marble);
        // The whole button reveals it, not only the orb's own square.
        marble.revealedProperty().bind(orb.hoverProperty());

        cut = button("Ausschneiden", scissors());
        copy = button("Kopieren", sheets());
        paste = button("Einfügen", clipboard());

        Region separator = new Region();
        separator.getStyleClass().add("dc-pill-sep");

        getChildren().addAll(orb, separator, cut, copy, paste);
    }

    public Button orb() {
        return orb;
    }

    public Button cut() {
        return cut;
    }

    public Button copy() {
        return copy;
    }

    public Button paste() {
        return paste;
    }

    private static Button button(String label, AnimatedIcon icon) {
        Button button = button(label, icon.graphic());
        button.hoverProperty().addListener((_, _, hovered) -> {
            Timeline animation = icon.animation();
            if (hovered) {
                animation.setRate(1);
                animation.playFromStart();
            } else if (icon.reverseOnExit()) {
                animation.setRate(-1);
                animation.play();
            }
        });
        return button;
    }

    private static Button button(String label, Node graphic) {
        Button button = new Button(null, graphic);
        button.getStyleClass().setAll("dc-pill-btn");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(label));
        button.setAccessibleText(label);
        return button;
    }

    // --- icons ------------------------------------------------------------------
    // Line icons on a 24 grid, each in parts that move under the pointer.

    /**
     * A line icon and what it does under the pointer: played when the pointer
     * comes onto the button, run back when it leaves if {@code reverseOnExit}.
     */
    private record AnimatedIcon(Node graphic, Timeline animation, boolean reverseOnExit) {
    }

    /** Two blades crossing at the pivot; on hover they snip twice. */
    private static AnimatedIcon scissors() {
        SVGPath upper = line("M9 6a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M8.12 8.12L12 12 M14.47 14.48L20 20");
        SVGPath lower = line("M9 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0z M20 4L8.12 15.88");
        Rotate upperTurn = new Rotate(0, 12, 12);
        Rotate lowerTurn = new Rotate(0, 12, 12);
        upper.getTransforms().add(upperTurn);
        lower.getTransforms().add(lowerTurn);

        Timeline snip = new Timeline();
        for (int i = 0; i <= 4; i++) {
            double angle = i % 2 == 0 ? 0 : SNIP_ANGLE;
            snip.getKeyFrames().add(new KeyFrame(SNIP.multiply(i),
                    new KeyValue(upperTurn.angleProperty(), -angle, Interpolator.EASE_BOTH),
                    new KeyValue(lowerTurn.angleProperty(), angle, Interpolator.EASE_BOTH)));
        }
        return new AnimatedIcon(icon(upper, lower), snip, false);
    }

    /** The sheet behind and the copy in front; on hover the copy slides out of it. */
    private static AnimatedIcon sheets() {
        SVGPath back = line("M4 16V6a2 2 0 0 1 2-2h10");
        SVGPath front = line("M10 8h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z");

        Timeline slide = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(front.translateXProperty(), -4),
                        new KeyValue(front.translateYProperty(), -4),
                        new KeyValue(front.opacityProperty(), 0.3)),
                new KeyFrame(SLIDE,
                        new KeyValue(front.translateXProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(front.translateYProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(front.opacityProperty(), 1, Interpolator.EASE_OUT)));
        return new AnimatedIcon(icon(back, front), slide, false);
    }

    /** The board and its clip; on hover the clip gives and lines slide onto the board, off again after. */
    private static AnimatedIcon clipboard() {
        SVGPath board = line("M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2");
        SVGPath clip = line("M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V3a1 1 0 0 1 1-1z");
        SVGPath lines = line("M8 11h8 M8 15h5");
        lines.setOpacity(0);

        Timeline fill = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(clip.translateYProperty(), 0),
                        new KeyValue(lines.translateYProperty(), -5),
                        new KeyValue(lines.opacityProperty(), 0)),
                new KeyFrame(SLIDE.divide(3),
                        new KeyValue(clip.translateYProperty(), 1.2, Interpolator.EASE_OUT)),
                new KeyFrame(SLIDE,
                        new KeyValue(clip.translateYProperty(), 0, Interpolator.EASE_BOTH),
                        new KeyValue(lines.translateYProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(lines.opacityProperty(), 1, Interpolator.EASE_OUT)));
        return new AnimatedIcon(icon(board, clip, lines), fill, true);
    }

    private static SVGPath line(String path) {
        SVGPath shape = new SVGPath();
        shape.setContent(path);
        shape.getStyleClass().add("dc-icon");
        return shape;
    }

    /** The parts at their grid coordinates in a fixed 24 × 24 box, so moving parts never shift the button's layout. */
    private static Node icon(Node... parts) {
        Pane box = new Pane(parts);
        box.setMinSize(ICON, ICON);
        box.setPrefSize(ICON, ICON);
        box.setMaxSize(ICON, ICON);
        return box;
    }
}
