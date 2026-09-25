package de.bsommerfeld.wsbg.terminal.chrome;

import javafx.css.PseudoClass;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * The title bar's sign that an update is there: a green arrow into a tray,
 * hidden until {@link #offer offered}. Drawn like the zen switch - line icon,
 * 24 grid, 1.6 stroke - and alive under the pointer: the arrow sinks into the
 * tray. A click applies the update; while the terminal prepares the handoff
 * the sign dims and takes no second click, and should the handoff fail it is
 * offered again.
 */
public final class UpdateNotice extends Button {

    private static final String ARROW = "M12 4v10 M8 10l4 4 4-4";
    private static final String TRAY = "M5 15v2.5a2.5 2.5 0 0 0 2.5 2.5h9a2.5 2.5 0 0 0 2.5-2.5V15";

    private static final String HINT = "Update verfügbar - installieren und neu starten";
    private static final String FAILED_HINT = "Update ließ sich nicht starten - erneut versuchen";

    private static final PseudoClass PREPARING = PseudoClass.getPseudoClass("preparing");

    UpdateNotice() {
        getStyleClass().setAll("tb-update");
        setGraphic(new Group(new Rectangle(24, 24, Color.TRANSPARENT),
                line(TRAY, "tb-update-tray"), line(ARROW, "tb-update-arrow")));
        setFocusTraversable(false);
        setVisible(false);
        setManaged(false);
    }

    /** Shows the sign; a click runs {@code apply}. */
    public void offer(Runnable apply) {
        setTooltip(new Tooltip(HINT));
        setOnAction(_ -> {
            preparing(true);
            apply.run();
        });
        preparing(false);
        setVisible(true);
        setManaged(true);
    }

    /** The handoff did not happen: the update is offered again. */
    public void failed() {
        setTooltip(new Tooltip(FAILED_HINT));
        preparing(false);
    }

    private void preparing(boolean preparing) {
        setDisable(preparing);
        pseudoClassStateChanged(PREPARING, preparing);
    }

    private static SVGPath line(String content, String styleClass) {
        SVGPath path = new SVGPath();
        path.setContent(content);
        path.getStyleClass().add(styleClass);
        return path;
    }
}
