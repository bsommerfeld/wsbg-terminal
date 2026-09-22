package de.bsommerfeld.wsbg.terminal.chrome;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

/**
 * The title bar's line icons: 24-unit drawings (the web UI's SVGs, verbatim) shown
 * at 18px, stroked in the button's glyph ink by CSS ({@code .iconbtn .glyph}).
 * A rectangle or circle in the source is written out as path arcs, since
 * {@link SVGPath} takes path data only.
 */
public final class Icons {

    private static final double VIEWBOX = 24;
    private static final double SIZE = 18;

    private Icons() {
    }

    /** Four rounded squares: the widget overview. */
    public static Node grid() {
        return glyph("grid-glyph",
                roundedRect(3.5, 3.5, 7, 7, 1.6),
                roundedRect(13.5, 3.5, 7, 7, 1.6),
                roundedRect(3.5, 13.5, 7, 7, 1.6),
                roundedRect(13.5, 13.5, 7, 7, 1.6));
    }

    /** The settings cog. */
    public static Node gear() {
        return glyph("gear-glyph",
                "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0z",
                "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 "
                        + "1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33"
                        + "l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3"
                        + "a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83"
                        + "l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 "
                        + "1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9"
                        + "a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z");
    }

    /** The curved back arrow the cog turns into while the settings are open. */
    public static Node back() {
        return glyph("back-glyph",
                "M10 19l-7-7 7-7",
                "M3 12h11a7 7 0 0 1 7 7v1");
    }

    private static Node glyph(String styleClass, String... paths) {
        Group drawing = new Group();
        for (String d : paths) {
            SVGPath path = new SVGPath();
            path.setContent(d);
            path.getStyleClass().add("glyph");
            drawing.getChildren().add(path);
        }
        drawing.getTransforms().add(new Scale(SIZE / VIEWBOX, SIZE / VIEWBOX));

        Pane box = new Pane(drawing);
        box.getStyleClass().add(styleClass);
        box.setMinSize(SIZE, SIZE);
        box.setPrefSize(SIZE, SIZE);
        box.setMaxSize(SIZE, SIZE);
        box.setMouseTransparent(true);
        return box;
    }

    private static String roundedRect(double x, double y, double w, double h, double r) {
        return "M%s %sh%sa%s %s 0 0 1 %s %sv%sa%s %s 0 0 1 -%s %sh-%sa%s %s 0 0 1 -%s -%sv-%sa%s %s 0 0 1 %s -%sz"
                .formatted(x + r, y, w - 2 * r, r, r, r, r, h - 2 * r, r, r, r, r, w - 2 * r, r, r, r, r, h - 2 * r, r, r, r, r);
    }
}
