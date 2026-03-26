package de.bsommerfeld.wsbg.terminal.ui.view.dock;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Cursor;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public class DockPanel extends Pane {

    private DockLayout dockLayout = DockLayout.FILL;
    private final ObservableList<DockWidget> windows = FXCollections.observableArrayList();
    private final Rectangle dockingOverlay;
    private DockPosition potentialDockPosition = null;
    
    private final double GAP = 15;
    private final double THRESHOLD = 0.02; // 2% tolerance for hover
    
    private List<DockWidget> leftX = new ArrayList<>();
    private List<DockWidget> rightX = new ArrayList<>();
    private List<DockWidget> topY = new ArrayList<>();
    private List<DockWidget> bottomY = new ArrayList<>();
    private double startDragX, startDragY;

    public DockPanel() {
        this.setStyle("-fx-background-color: #202225;");
        
        dockingOverlay = new Rectangle();
        dockingOverlay.setFill(Color.web("#5865F2", 0.3));
        dockingOverlay.setStroke(Color.web("#5865F2"));
        dockingOverlay.setStrokeWidth(2);
        dockingOverlay.setVisible(false);
        dockingOverlay.setMouseTransparent(true);
        
        this.getChildren().add(dockingOverlay);

        this.setOnMouseMoved(e -> updateCursor(e.getX(), e.getY()));
        this.setOnMousePressed(e -> startResize(e.getX(), e.getY()));
        this.setOnMouseDragged(e -> doResize(e.getX(), e.getY()));
        this.setOnMouseReleased(e -> stopResize());
    }
    
    private double getPx(DockWidget w) { return (Double) w.getProperties().getOrDefault("px", -1.0); }
    private double getPy(DockWidget w) { return (Double) w.getProperties().getOrDefault("py", -1.0); }
    private double getPw(DockWidget w) { return (Double) w.getProperties().getOrDefault("pw", 1.0); }
    private double getPh(DockWidget w) { return (Double) w.getProperties().getOrDefault("ph", 1.0); }

    private void setPx(DockWidget w, double v) { w.getProperties().put("px", v); }
    private void setPy(DockWidget w, double v) { w.getProperties().put("py", v); }
    private void setPw(DockWidget w, double v) { w.getProperties().put("pw", v); }
    private void setPh(DockWidget w, double v) { w.getProperties().put("ph", v); }

    public void rebuildGridState() {
        int n = windows.size();
        if (n == 0) return;

        // If explicitly requested or missing coordinates, distribute evenly
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        
        double cw = 1.0 / cols;
        double ch = 1.0 / rows;
        
        for (int i = 0; i < n; i++) {
            DockWidget w = windows.get(i);
            if (getPx(w) != -1.0) continue; // Keep manual/re-docked
            
            int r = i / cols;
            int c = i % cols;
            
            double px = c * cw;
            double py = r * ch;
            double pw = cw;
            double ph = ch;
            
            if (r == rows - 1 && i == n - 1) { 
                pw = 1.0 - px; 
            }
            
            setPx(w, px); setPy(w, py); setPw(w, pw); setPh(w, ph);
        }
    }

    public void reloadUniformGrid() {
        for (DockWidget w : windows) w.getProperties().remove("px");
        rebuildGridState();
        requestLayout();
    }

    public void setDockLayout(DockLayout layout) {
        this.dockLayout = layout;
        if (layout == DockLayout.STACK) {
            for (DockWidget window : windows) {
                Timeline t = new Timeline(
                    new KeyFrame(Duration.millis(300), 
                        new KeyValue(window.layoutXProperty(), 100 + (windows.indexOf(window) * 20)),
                        new KeyValue(window.layoutYProperty(), 100 + (windows.indexOf(window) * 20)),
                        new KeyValue(window.prefWidthProperty(), 400),
                        new KeyValue(window.prefHeightProperty(), 300)
                    )
                );
                t.setOnFinished(ev -> saveStackBounds(window));
                t.play();
            }
        } else {
            rebuildGridState(); // ONLY fills in missing ones now! Keeps dock topology!
        }
        requestLayout();
    }

    public DockLayout getLayout() {
        return dockLayout;
    }

    public void addWindow(DockWidget window) {
        window.setParentPanel(this);
        windows.add(window);
        this.getChildren().add(window);
        dockingOverlay.toFront();
        
        if (dockLayout == DockLayout.STACK) {
            window.setLayoutX(100 + windows.size() * 10);
            window.setLayoutY(100 + windows.size() * 10);
            window.setPrefSize(300, 250);
        } else {
            if (getPx(window) == -1.0) reloadUniformGrid(); // Safe fallback, new unplaced window
        }
        requestLayout();
    }

    public void removeWindow(DockWidget window) {
        window.setParentPanel(null);
        windows.remove(window);
        this.getChildren().remove(window);
        
        if (dockLayout != DockLayout.STACK) {
            reloadUniformGrid(); // We must rebuild, otherwise hole is left
        }
        requestLayout();
    }

    private double lastW = 0, lastH = 0;

    public void saveStackBounds(DockWidget w) {
        if (getWidth() <= 0 || getHeight() <= 0) return;
        w.getProperties().put("spx", w.getLayoutX() / getWidth());
        w.getProperties().put("spy", w.getLayoutY() / getHeight());
        w.getProperties().put("spw", w.getPrefWidth() / getWidth());
        w.getProperties().put("sph", w.getPrefHeight() / getHeight());
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        dockingOverlay.toFront();

        double currentW = getWidth();
        double currentH = getHeight();

        if (dockLayout == DockLayout.STACK && lastW > 0 && lastH > 0 && currentW > 0 && currentH > 0) {
            if (Math.abs(currentW - lastW) > 0.01 || Math.abs(currentH - lastH) > 0.01) {
                for (DockWidget w : windows) {
                    if (w.getProperties().containsKey("spx")) {
                        double spx = (double) w.getProperties().get("spx");
                        double spy = (double) w.getProperties().get("spy");
                        double spw = (double) w.getProperties().get("spw");
                        double sph = (double) w.getProperties().get("sph");
                        w.setLayoutX(spx * currentW);
                        w.setLayoutY(spy * currentH);
                        w.setPrefSize(spw * currentW, sph * currentH);
                    }
                }
            }
        }

        lastW = currentW;
        lastH = currentH;

        if (dockLayout == DockLayout.FILL) {
            if (windows.isEmpty()) return;
            double totalW = getWidth();
            double totalH = getHeight();

            for (DockWidget w : windows) {
                double px = getPx(w); double py = getPy(w);
                double pw = getPw(w); double ph = getPh(w);
                
                double x = px * totalW + GAP/2;
                double y = py * totalH + GAP/2;
                double wWidth = pw * totalW - GAP;
                double wHeight = ph * totalH - GAP;
                
                w.resizeRelocate(x, y, Math.max(10, wWidth), Math.max(10, wHeight));
            }
        }
    }

    // --- Custom Drag to Resize API ---

    private void updateCursor(double mx, double my) {
        if (dockLayout != DockLayout.FILL) {
            setCursor(Cursor.DEFAULT);
            return;
        }
        
        double x = mx / getWidth();
        double y = my / getHeight();
        
        boolean hResize = false;
        boolean vResize = false;
        
        for (DockWidget w : windows) {
            double px = getPx(w); double py = getPy(w);
            double pw = getPw(w); double ph = getPh(w);
            
            boolean overXEdge = Math.abs(x - px) < THRESHOLD || Math.abs(x - (px + pw)) < THRESHOLD;
            boolean overYEdge = Math.abs(y - py) < THRESHOLD || Math.abs(y - (py + ph)) < THRESHOLD;
            
            boolean insideY = y >= py - THRESHOLD && y <= py + ph + THRESHOLD;
            boolean insideX = x >= px - THRESHOLD && x <= px + pw + THRESHOLD;
            
            if (overXEdge && insideY) hResize = true;
            if (overYEdge && insideX) vResize = true;
        }
        
        if (hResize && vResize) setCursor(Cursor.CROSSHAIR);
        else if (hResize) setCursor(Cursor.H_RESIZE);
        else if (vResize) setCursor(Cursor.V_RESIZE);
        else setCursor(null);
    }

    private void startResize(double mx, double my) {
        if (dockLayout != DockLayout.FILL) return;
        
        double x = mx / getWidth();
        double y = my / getHeight();
        
        leftX.clear(); rightX.clear(); topY.clear(); bottomY.clear();
        double eps = THRESHOLD;
        
        boolean isCrosshair = getCursor() == Cursor.CROSSHAIR || getCursor() == Cursor.NW_RESIZE;
        boolean isH = getCursor() == Cursor.H_RESIZE || isCrosshair;
        boolean isV = getCursor() == Cursor.V_RESIZE || isCrosshair;
        
        for (DockWidget w : windows) {
            double px = getPx(w); double py = getPy(w);
            double pw = getPw(w); double ph = getPh(w);
            
            // Global resizer! Selects ALL widgets intersecting the same global axis!
            if (isH) {
                if (Math.abs(x - (px + pw)) < eps) leftX.add(w);
                if (Math.abs(x - px) < eps) rightX.add(w);
            }
            if (isV) {
                if (Math.abs(y - (py + ph)) < eps) topY.add(w);
                if (Math.abs(y - py) < eps) bottomY.add(w);
            }
        }
        
        startDragX = x; startDragY = y;
    }

    private void doResize(double mx, double my) {
        if (dockLayout != DockLayout.FILL) return;
        if (leftX.isEmpty() && rightX.isEmpty() && topY.isEmpty() && bottomY.isEmpty()) return;
        
        double x = mx / getWidth();
        double y = my / getHeight();
        
        double dx = x - startDragX;
        double dy = y - startDragY;
        
        double mindx = -999, maxdx = 999;
        for (DockWidget w : leftX) mindx = Math.max(mindx, -(getPw(w) - 0.05));
        for (DockWidget w : rightX) maxdx = Math.min(maxdx, getPw(w) - 0.05);
        dx = Math.max(mindx, Math.min(maxdx, dx));
        
        double mindy = -999, maxdy = 999;
        for (DockWidget w : topY) mindy = Math.max(mindy, -(getPh(w) - 0.05));
        for (DockWidget w : bottomY) maxdy = Math.min(maxdy, getPh(w) - 0.05);
        dy = Math.max(mindy, Math.min(maxdy, dy));
        
        if (leftX.isEmpty() || rightX.isEmpty()) dx = 0;
        if (topY.isEmpty() || bottomY.isEmpty()) dy = 0;
        
        if (dx != 0 || dy != 0) {
            for (DockWidget w : leftX) setPw(w, getPw(w) + dx);
            for (DockWidget w : rightX) { setPx(w, getPx(w) + dx); setPw(w, getPw(w) - dx); }
            
            for (DockWidget w : topY) setPh(w, getPh(w) + dy);
            for (DockWidget w : bottomY) { setPy(w, getPy(w) + dy); setPh(w, getPh(w) - dy); }
            
            startDragX += dx; startDragY += dy;
            requestLayout();
        }
    }

    private void stopResize() {
        leftX.clear(); rightX.clear(); topY.clear(); bottomY.clear();
        updateCursor(0, 0); 
    }

    // --- Docking Engine ---

    private enum DockPosition {
        TOP, BOTTOM, LEFT, RIGHT,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }

    public void handleWindowDrag(DockWidget window, double sceneX, double sceneY) {
        if (dockLayout != DockLayout.STACK) return;
        double localX = sceneX - getLayoutX();
        double localY = sceneY - getLayoutY();

        double W = getWidth(); double H = getHeight();
        double edgeThreshold = 100;
        
        boolean isL = localX < edgeThreshold;
        boolean isR = localX > W - edgeThreshold;
        boolean isT = localY < edgeThreshold;
        boolean isB = localY > H - edgeThreshold;

        // 8 zones exactly
        if (isT && isL) showDockOverlay(DockPosition.TOP_LEFT);
        else if (isT && isR) showDockOverlay(DockPosition.TOP_RIGHT);
        else if (isB && isL) showDockOverlay(DockPosition.BOTTOM_LEFT);
        else if (isB && isR) showDockOverlay(DockPosition.BOTTOM_RIGHT);
        else if (isT) showDockOverlay(DockPosition.TOP);
        else if (isB) showDockOverlay(DockPosition.BOTTOM);
        else if (isL) showDockOverlay(DockPosition.LEFT);
        else if (isR) showDockOverlay(DockPosition.RIGHT);
        else hideDockOverlay();
    }

    public void handleWindowDragEnd(DockWidget window, double sceneX, double sceneY) {
        if (dockLayout != DockLayout.STACK) return;
        if (potentialDockPosition != null) {
            executeDocking(window, potentialDockPosition);
            hideDockOverlay();
        }
    }

    private void showDockOverlay(DockPosition pos) {
        this.potentialDockPosition = pos;
        dockingOverlay.setVisible(true);

        double W = getWidth(), H = getHeight();
        double tx = GAP, ty = GAP, tw = W - GAP*2, th = H - GAP*2;

        switch (pos) {
            case LEFT: tw = W/2 - GAP*1.5; break;
            case RIGHT: tx = W/2 + GAP*0.5; tw = W/2 - GAP*1.5; break;
            case TOP: th = H/2 - GAP*1.5; break;
            case BOTTOM: ty = H/2 + GAP*0.5; th = H/2 - GAP*1.5; break;
            case TOP_LEFT: tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
            case TOP_RIGHT: tx = W/2 + GAP*0.5; tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
            case BOTTOM_LEFT: ty = H/2 + GAP*0.5; tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
            case BOTTOM_RIGHT: tx = W/2 + GAP*0.5; ty = H/2 + GAP*0.5; tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
        }
        dockingOverlay.setX(tx); dockingOverlay.setY(ty);
        dockingOverlay.setWidth(tw); dockingOverlay.setHeight(th);
    }

    private void hideDockOverlay() {
        this.potentialDockPosition = null;
        dockingOverlay.setVisible(false);
    }

    private void executeDocking(DockWidget incoming, DockPosition pos) {
        double W = getWidth(), H = getHeight();
        double tx = GAP, ty = GAP, tw = W - GAP*2, th = H - GAP*2;
        
        switch (pos) {
            case LEFT: tw = W/2 - GAP*1.5; break;
            case RIGHT: tx = W/2 + GAP*0.5; tw = W/2 - GAP*1.5; break;
            case TOP: th = H/2 - GAP*1.5; break;
            case BOTTOM: ty = H/2 + GAP*0.5; th = H/2 - GAP*1.5; break;
            case TOP_LEFT: tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
            case TOP_RIGHT: tx = W/2 + GAP*0.5; tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
            case BOTTOM_LEFT: ty = H/2 + GAP*0.5; tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
            case BOTTOM_RIGHT: tx = W/2 + GAP*0.5; ty = H/2 + GAP*0.5; tw = W/2 - GAP*1.5; th = H/2 - GAP*1.5; break;
        }

        animateWindow(incoming, tx, ty, tw, th);

        for (DockWidget w : windows) {
            if (w == incoming) continue;
            
            boolean isLeft = Math.abs(w.getLayoutX() - GAP) < 5 && Math.abs(w.getPrefWidth() - (W/2 - GAP*1.5)) < 5;
            boolean isRight = Math.abs(w.getLayoutX() - (W/2 + GAP*0.5)) < 5 && Math.abs(w.getPrefWidth() - (W/2 - GAP*1.5)) < 5;
            boolean isTop = Math.abs(w.getLayoutY() - GAP) < 5 && Math.abs(w.getPrefHeight() - (H/2 - GAP*1.5)) < 5;
            boolean isBottom = Math.abs(w.getLayoutY() - (H/2 + GAP*0.5)) < 5 && Math.abs(w.getPrefHeight() - (H/2 - GAP*1.5)) < 5;
            
            boolean isFullHeight = Math.abs(w.getPrefHeight() - (H - GAP*2)) < 5;
            boolean isFullWidth = Math.abs(w.getPrefWidth() - (W - GAP*2)) < 5;

            double ox = w.getLayoutX(), oy = w.getLayoutY(), ow = w.getPrefWidth(), oh = w.getPrefHeight();
            boolean push = false;

            if (pos == DockPosition.TOP_LEFT) {
                if (isLeft && isFullHeight) { oy = H/2 + GAP*0.5; oh = H/2 - GAP*1.5; push = true; }
                else if (isTop && isFullWidth) { ox = W/2 + GAP*0.5; ow = W/2 - GAP*1.5; push = true; }
            } else if (pos == DockPosition.BOTTOM_LEFT) {
                if (isLeft && isFullHeight) { oy = GAP; oh = H/2 - GAP*1.5; push = true; }
                else if (isBottom && isFullWidth) { ox = W/2 + GAP*0.5; ow = W/2 - GAP*1.5; push = true; }
            } else if (pos == DockPosition.TOP_RIGHT) {
                if (isRight && isFullHeight) { oy = H/2 + GAP*0.5; oh = H/2 - GAP*1.5; push = true; }
                else if (isTop && isFullWidth) { ox = GAP; ow = W/2 - GAP*1.5; push = true; }
            } else if (pos == DockPosition.BOTTOM_RIGHT) {
                if (isRight && isFullHeight) { oy = GAP; oh = H/2 - GAP*1.5; push = true; }
                else if (isBottom && isFullWidth) { ox = GAP; ow = W/2 - GAP*1.5; push = true; }
            } else if (pos == DockPosition.LEFT) {
                if (isFullWidth && isFullHeight) { ox = W/2 + GAP*0.5; ow = W/2 - GAP*1.5; push = true; }
            } else if (pos == DockPosition.RIGHT) {
                if (isFullWidth && isFullHeight) { ox = GAP; ow = W/2 - GAP*1.5; push = true; }
            }

            if (push) {
                animateWindow(w, ox, oy, ow, oh);
            }
        }
    }

    private void animateWindow(DockWidget w, double tx, double ty, double tw, double th) {
        Timeline t = new Timeline(
            new KeyFrame(Duration.millis(250),
                new KeyValue(w.layoutXProperty(), tx),
                new KeyValue(w.layoutYProperty(), ty),
                new KeyValue(w.prefWidthProperty(), tw),
                new KeyValue(w.prefHeightProperty(), th)
            )
        );
        t.setOnFinished(e -> saveStackBounds(w));
        t.play();
    }

    public String exportLayout() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            DockLayoutState state = new DockLayoutState();
            state.layoutMode = dockLayout.name();
            state.windows = windows.stream().map(w -> {
                DockLayoutState.WidgetState ws = new DockLayoutState.WidgetState();
                ws.identifier = w.getIdentifier();
                ws.x = getPx(w); ws.y = getPy(w); ws.width = getPw(w); ws.height = getPh(w);
                return ws;
            }).toList();
            return mapper.writeValueAsString(state);
        } catch(Exception e) { e.printStackTrace(); return "{}"; }
    }

    public void importLayout(String json) {
        if (json == null || json.trim().isEmpty()) return;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            DockLayoutState state = mapper.readValue(json, DockLayoutState.class);
            if (state == null) return;
            
            new java.util.ArrayList<>(windows).forEach(this::removeWindow);
            
            for(DockLayoutState.WidgetState ws : state.windows) {
                DockWidget w = de.bsommerfeld.wsbg.terminal.ui.view.dock.widgets.WidgetRegistry.create(ws.identifier);
                if (w != null) {
                    setPx(w, ws.x); setPy(w, ws.y); setPw(w, ws.width); setPh(w, ws.height);
                    addWindow(w);
                }
            }
            this.setDockLayout(DockLayout.valueOf(state.layoutMode));
        } catch(Exception e) { e.printStackTrace(); }
    }
}
