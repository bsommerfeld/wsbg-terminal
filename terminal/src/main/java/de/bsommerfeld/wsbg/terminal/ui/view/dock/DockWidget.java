package de.bsommerfeld.wsbg.terminal.ui.view.dock;

import javafx.animation.ScaleTransition;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * Base abstract class for specific dock widgets.
 * Implementations provide node elements for structural layout regions via
 * explicit methods.
 */
public abstract class DockWidget extends StackPane {

    private final BorderPane layoutContainer;
    private final BorderPane backLayoutContainer;
    private boolean isFlipped = false;

    private double dragStartX;
    private double dragStartY;
    private DockPanel parentPanel;

    public DockWidget() {
        this.getStyleClass().add("dock-widget");

        this.layoutContainer = new BorderPane();
        this.layoutContainer.setPickOnBounds(false);
        this.layoutContainer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        this.backLayoutContainer = new BorderPane();
        this.backLayoutContainer.setPickOnBounds(false);
        this.backLayoutContainer.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        this.backLayoutContainer.setVisible(false);

        this.getChildren().addAll(this.backLayoutContainer, this.layoutContainer);

        setupDragging();
    }

    public void flip() {
        if (isFlipped) {
            animateFlip(backLayoutContainer, layoutContainer);
            isFlipped = false;
        } else {
            animateFlip(layoutContainer, backLayoutContainer);
            isFlipped = true;
        }
    }

    private void animateFlip(Node hideNode, Node showNode) {
        ScaleTransition hideTransition = new ScaleTransition(Duration.millis(250), hideNode);
        hideTransition.setFromX(1.0);
        hideTransition.setToX(0.0);
        
        hideTransition.setOnFinished(e -> {
            hideNode.setVisible(false);
            showNode.setScaleX(0.0);
            showNode.setVisible(true);

            ScaleTransition showTransition = new ScaleTransition(Duration.millis(250), showNode);
            showTransition.setFromX(0.0);
            showTransition.setToX(1.0);
            showTransition.play();
        });

        hideTransition.play();
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

            if (top && left)
                setCursor(Cursor.NW_RESIZE);
            else if (top && right)
                setCursor(Cursor.NE_RESIZE);
            else if (bot && left)
                setCursor(Cursor.SW_RESIZE);
            else if (bot && right)
                setCursor(Cursor.SE_RESIZE);
            else if (top)
                setCursor(Cursor.N_RESIZE);
            else if (bot)
                setCursor(Cursor.S_RESIZE);
            else if (left)
                setCursor(Cursor.W_RESIZE);
            else if (right)
                setCursor(Cursor.E_RESIZE);
            else
                setCursor(Cursor.DEFAULT);
        });

        this.setOnMousePressed(e -> {
            if (parentPanel == null)
                return;
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
            if (parentPanel == null || parentPanel.getLayout() != DockLayout.STACK)
                return;

            if (resizing) {
                double dx = e.getSceneX() - dragStartX;
                double dy = e.getSceneY() - dragStartY;
                double nx = startBoundsX, ny = startBoundsY, nw = startBoundsW, nh = startBoundsH;

                if (resizeCursor == Cursor.E_RESIZE || resizeCursor == Cursor.NE_RESIZE
                        || resizeCursor == Cursor.SE_RESIZE)
                    nw += dx;
                if (resizeCursor == Cursor.S_RESIZE || resizeCursor == Cursor.SW_RESIZE
                        || resizeCursor == Cursor.SE_RESIZE)
                    nh += dy;
                if (resizeCursor == Cursor.W_RESIZE || resizeCursor == Cursor.NW_RESIZE
                        || resizeCursor == Cursor.SW_RESIZE) {
                    nx += dx;
                    nw -= dx;
                }
                if (resizeCursor == Cursor.N_RESIZE || resizeCursor == Cursor.NW_RESIZE
                        || resizeCursor == Cursor.NE_RESIZE) {
                    ny += dy;
                    nh -= dy;
                }

                // Bounds clamping
                nw = Math.max(100, Math.min(nw, parentPanel.getWidth() - nx));
                nh = Math.max(100, Math.min(nh, parentPanel.getHeight() - ny));
                if (nx < 0) {
                    nw += nx;
                    nx = 0;
                }
                if (ny < 0) {
                    nh += ny;
                    ny = 0;
                }

                setLayoutX(nx);
                setLayoutY(ny);
                setPrefSize(nw, nh);
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

    protected void buildWidget() {
        Node top = topPane();
        if (top != null) {
            applyStretchConstraints(top, true, false);
            this.layoutContainer.setTop(top);
        }

        Node left = leftPane();
        if (left != null) {
            applyStretchConstraints(left, false, true);
            this.layoutContainer.setLeft(left);
        }

        Node center = centerPane();
        if (center != null) {
            applyStretchConstraints(center, true, true);
            this.layoutContainer.setCenter(center);
        }

        Node right = rightPane();
        if (right != null) {
            applyStretchConstraints(right, false, true);
            this.layoutContainer.setRight(right);
        }

        Node bottom = bottomPane();
        if (bottom != null) {
            applyStretchConstraints(bottom, true, false);
            this.layoutContainer.setBottom(bottom);
        }

        Node backTop = backTopPane();
        if (backTop != null) {
            applyStretchConstraints(backTop, true, false);
            this.backLayoutContainer.setTop(backTop);
        }

        Node backLeft = backLeftPane();
        if (backLeft != null) {
            applyStretchConstraints(backLeft, false, true);
            this.backLayoutContainer.setLeft(backLeft);
        }

        Node backCenter = backCenterPane();
        if (backCenter != null) {
            applyStretchConstraints(backCenter, true, true);
            this.backLayoutContainer.setCenter(backCenter);
        }

        Node backRight = backRightPane();
        if (backRight != null) {
            applyStretchConstraints(backRight, false, true);
            this.backLayoutContainer.setRight(backRight);
        }

        Node backBottom = backBottomPane();
        if (backBottom != null) {
            applyStretchConstraints(backBottom, true, false);
            this.backLayoutContainer.setBottom(backBottom);
        }
    }

    private void applyStretchConstraints(Node node, boolean hGrow, boolean vGrow) {
        if (node instanceof Region region) {
            if (hGrow) region.setMaxWidth(Double.MAX_VALUE);
            if (vGrow) region.setMaxHeight(Double.MAX_VALUE);
        }
    }

    protected abstract Node topPane();

    protected abstract Node leftPane();

    protected abstract Node rightPane();

    protected abstract Node bottomPane();

    protected abstract Node centerPane();

    protected Node backTopPane() { return null; }

    protected Node backLeftPane() { return null; }

    protected Node backRightPane() { return null; }

    protected Node backBottomPane() { return null; }

    protected Node backCenterPane() { return null; }

    public abstract String getIdentifier();
}
