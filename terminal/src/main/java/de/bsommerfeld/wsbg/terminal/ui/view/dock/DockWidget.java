package de.bsommerfeld.wsbg.terminal.ui.view.dock;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

public class DockWidget extends StackPane {

    private final String title;
    private final VBox layoutContainer;
    private final StackPane centerContent;
    private final StackPane footerContent;
    private final StackPane closeButtonPane;
    
    private double dragStartX;
    private double dragStartY;
    private boolean closeable = true;
    private DockPanel parentPanel;

    public DockWidget(String title) {
        this.title = title;
        this.getStyleClass().add("dock-widget");
        // Modern 2026 aesthetics: lighter than panel background (#202225) -> #2b2d31
        // Rounded corners for widgets. Dropshadow for depth.
        this.setStyle("-fx-background-color: #2b2d31; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 15, 0, 0, 6);");

        this.centerContent = new StackPane();
        VBox.setVgrow(this.centerContent, Priority.ALWAYS);
        this.centerContent.setPadding(new Insets(15)); // Good breathing room

        this.footerContent = new StackPane();
        this.footerContent.setPadding(new Insets(10));
        this.footerContent.setStyle("-fx-background-color: transparent;");
        this.footerContent.setVisible(false);
        this.footerContent.setManaged(false);

        this.layoutContainer = new VBox(this.centerContent, this.footerContent);
        this.layoutContainer.setPickOnBounds(false);

        this.closeButtonPane = createCloseButton();
        StackPane.setAlignment(this.closeButtonPane, Pos.TOP_RIGHT);
        StackPane.setMargin(this.closeButtonPane, new Insets(10, 10, 0, 0));
        this.closeButtonPane.setVisible(false); // Hide until hover

        this.getChildren().addAll(this.layoutContainer);

        this.setOnMouseEntered(e -> {
            if (closeable) {
                this.closeButtonPane.setVisible(true);
            }
        });
        
        this.setOnMouseExited(e -> {
            this.closeButtonPane.setVisible(false);
        });

        setupDragging();
    }

    private StackPane createCloseButton() {
        StackPane pane = new StackPane();
        pane.setPadding(new Insets(6));
        pane.setCursor(Cursor.HAND);
        SVGPath svg = new SVGPath();
        svg.setContent("M18.3 5.71a.9959.9959 0 00-1.41 0L12 10.59 7.11 5.7a.9959.9959 0 00-1.41 0c-.39.39-.39 1.02 0 1.41L10.59 12 5.7 16.89c-.39.39-.39 1.02 0 1.41.39.39 1.02.39 1.41 0L12 13.41l4.89 4.89c.39.39 1.02.39 1.41 0 .39-.39.39-1.02 0-1.41L13.41 12l4.89-4.89c.38-.38.38-1.02 0-1.4z");
        svg.setFill(Color.web("#949ba4"));
        svg.setScaleX(0.7);
        svg.setScaleY(0.7);
        pane.getChildren().add(svg);

        pane.setStyle("-fx-background-color: transparent; -fx-background-radius: 6;");

        pane.setOnMouseEntered(e -> {
            pane.setStyle("-fx-background-color: #ed4245; -fx-background-radius: 6;");
            svg.setFill(Color.WHITE);
        });
        pane.setOnMouseExited(e -> {
            pane.setStyle("-fx-background-color: transparent; -fx-background-radius: 6;");
            svg.setFill(Color.web("#949ba4"));
        });
        pane.setOnMouseClicked(e -> {
            if (closeable && parentPanel != null) {
                parentPanel.removeWindow(this);
            }
        });
        return pane;
    }

    private boolean resizing = false;
    private Cursor resizeCursor = Cursor.DEFAULT;
    private double startBoundsX, startBoundsY, startBoundsW, startBoundsH;

    private void setupDragging() {
        this.setOnMouseMoved(e -> {
            if (parentPanel == null || parentPanel.getLayout() != DockLayout.STACK) {
                setCursor(null);
                return;
            }
            double edge = 10;
            boolean top = e.getY() < edge;
            boolean bot = e.getY() > getHeight() - edge;
            boolean left = e.getX() < edge;
            boolean right = e.getX() > getWidth() - edge;
            
            if (top && left) setCursor(Cursor.NW_RESIZE);
            else if (top && right) setCursor(Cursor.NE_RESIZE);
            else if (bot && left) setCursor(Cursor.SW_RESIZE);
            else if (bot && right) setCursor(Cursor.SE_RESIZE);
            else if (top) setCursor(Cursor.N_RESIZE);
            else if (bot) setCursor(Cursor.S_RESIZE);
            else if (left) setCursor(Cursor.W_RESIZE);
            else if (right) setCursor(Cursor.E_RESIZE);
            else setCursor(Cursor.DEFAULT);
        });

        this.setOnMousePressed(e -> {
            if (parentPanel == null) return;
            dragStartX = e.getSceneX();
            dragStartY = e.getSceneY();
            startBoundsX = getLayoutX();
            startBoundsY = getLayoutY();
            startBoundsW = getWidth();
            startBoundsH = getHeight();

            if (parentPanel.getLayout() == DockLayout.STACK) {
                this.toFront();
                if (getCursor() != Cursor.DEFAULT && getCursor() != null) {
                    resizing = true;
                    resizeCursor = getCursor();
                    e.consume();
                    return;
                }
            }
            resizing = false;
        });

        this.setOnMouseDragged(e -> {
            if (parentPanel == null || parentPanel.getLayout() != DockLayout.STACK) return;
            
            if (resizing) {
                double dx = e.getSceneX() - dragStartX;
                double dy = e.getSceneY() - dragStartY;
                double nx = startBoundsX, ny = startBoundsY, nw = startBoundsW, nh = startBoundsH;
                
                if (resizeCursor == Cursor.E_RESIZE || resizeCursor == Cursor.NE_RESIZE || resizeCursor == Cursor.SE_RESIZE) nw += dx;
                if (resizeCursor == Cursor.S_RESIZE || resizeCursor == Cursor.SW_RESIZE || resizeCursor == Cursor.SE_RESIZE) nh += dy;
                if (resizeCursor == Cursor.W_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.SW_RESIZE) { nx += dx; nw -= dx; }
                if (resizeCursor == Cursor.N_RESIZE || resizeCursor == Cursor.NW_RESIZE || resizeCursor == Cursor.NE_RESIZE) { ny += dy; nh -= dy; }
                
                // Bounds clamping
                nw = Math.max(100, Math.min(nw, parentPanel.getWidth() - nx));
                nh = Math.max(100, Math.min(nh, parentPanel.getHeight() - ny));
                if (nx < 0) { nw += nx; nx = 0; }
                if (ny < 0) { nh += ny; ny = 0; }
                
                setLayoutX(nx); setLayoutY(ny); setPrefSize(nw, nh);
            } else {
                double newX = startBoundsX + (e.getSceneX() - dragStartX);
                double newY = startBoundsY + (e.getSceneY() - dragStartY);
                
                newX = Math.max(0, Math.min(newX, parentPanel.getWidth() - getWidth()));
                newY = Math.max(0, Math.min(newY, parentPanel.getHeight() - getHeight()));
                
                setLayoutX(newX);
                setLayoutY(newY);
                
                parentPanel.handleWindowDrag(this, e.getSceneX(), e.getSceneY());
            }
        });
        
        this.setOnMouseReleased(e -> {
            if (parentPanel != null && parentPanel.getLayout() == DockLayout.STACK) {
                parentPanel.saveStackBounds(this);
                parentPanel.handleWindowDragEnd(this, e.getSceneX(), e.getSceneY());
            }
            resizing = false;
        });
    }

    public void setParentPanel(DockPanel panel) {
        this.parentPanel = panel;
    }

    public void setCenter(Node content) {
        this.centerContent.getChildren().clear();
        this.centerContent.getChildren().add(content);
    }

    public void setFooter(Node content) {
        this.footerContent.getChildren().clear();
        this.footerContent.getChildren().add(content);
        this.footerContent.setVisible(true);
        this.footerContent.setManaged(true);
    }

    public void setCloseable(boolean closeable) {
        this.closeable = closeable;
        if (!closeable) {
            this.closeButtonPane.setVisible(false);
        }
    }
    
    public String getTitle() {
        return title;
    }
}
