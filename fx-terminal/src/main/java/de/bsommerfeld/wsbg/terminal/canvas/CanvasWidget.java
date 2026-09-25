package de.bsommerfeld.wsbg.terminal.canvas;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Rectangle2D;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * A widget on the canvas, built like an IDE tool window: a header strip and a
 * body, content left out on purpose - a title bar in the header and one to
 * three skeleton lines in the body. Marked, it wears the selection blue as rim
 * and tint. It knows where it stands on the canvas; the canvas moves it.
 */
public final class CanvasWidget extends VBox {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final WidgetSpec spec;
    private double canvasX;
    private double canvasY;
    private final BooleanProperty selected = new SimpleBooleanProperty(this, "selected") {
        @Override
        protected void invalidated() {
            pseudoClassStateChanged(SELECTED, get());
        }
    };

    public CanvasWidget(WidgetSpec spec) {
        this.spec = spec;
        this.canvasX = spec.x();
        this.canvasY = spec.y();
        getStyleClass().add("dc-widget");

        Region title = new Region();
        title.getStyleClass().addAll("dc-skel", "title");
        HBox header = new HBox(title);
        header.getStyleClass().add("dc-widget-header");
        title.prefWidthProperty().bind(widthProperty().multiply(0.4));

        VBox body = new VBox();
        body.getStyleClass().add("dc-widget-body");
        VBox.setVgrow(body, Priority.ALWAYS);
        for (int i = 0; i < spec.lines(); i++) {
            Region line = new Region();
            line.getStyleClass().add("dc-skel");
            line.maxWidthProperty().bind(widthProperty().multiply((88 - i * 18) / 100.0));
            body.getChildren().add(line);
        }
        getChildren().addAll(header, body);
    }

    public String id() {
        return spec.id();
    }

    /** Where it stands on the canvas, independent of how far the view is panned. */
    public Rectangle2D canvasBounds() {
        return new Rectangle2D(canvasX, canvasY, spec.width(), spec.height());
    }

    /** Its spec as it stands now, moves included. */
    public WidgetSpec currentSpec() {
        return new WidgetSpec(spec.id(), canvasX, canvasY, spec.width(), spec.height(), spec.lines());
    }

    void moveTo(double x, double y) {
        canvasX = x;
        canvasY = y;
    }

    public BooleanProperty selectedProperty() { return selected; }
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean value) { selected.set(value); }
}
