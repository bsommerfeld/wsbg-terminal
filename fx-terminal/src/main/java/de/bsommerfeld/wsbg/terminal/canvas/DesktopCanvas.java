package de.bsommerfeld.wsbg.terminal.canvas;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.ZoomEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The work surface: an endless matte ground with a dot grid, the widgets on it
 * and the {@link ToolPill} anchored to the selected area.
 * <ul>
 *   <li>Drag on empty ground pans the view; scrolling does too. Shift +
 *       scroll or a pinch zooms around the pointer.</li>
 *   <li>Drag on a widget moves it - the whole selection, if it is part of
 *       one - snapping to the grid. Drag inside the selected area moves the
 *       area with everything marked in it.</li>
 *   <li>Shift + drag draws the selection frame; every widget it touches is
 *       marked live, and the frame stays as the selected area.</li>
 *   <li>A click on a widget selects it, Shift + click adds or removes it -
 *       marked only, no area: a box around scattered widgets would take in
 *       unmarked ones. With an area drawn, Shift + click marks without
 *       touching the area, and a click inside it changes nothing. A click on
 *       empty ground clears the selection; so does Esc, the canvas's "back".</li>
 *   <li>Ctrl/Cmd + X, C, V - or the pill's buttons - cut, copy and paste the
 *       marked widgets. A paste lands one grid step further each time (after
 *       a cut the first lands in place) and becomes the selection. Delete or
 *       Backspace removes them.</li>
 *   <li>Ctrl/Cmd + Z undoes the last change to the widgets - a move, cut,
 *       paste or delete; Ctrl/Cmd + Shift + Z or Ctrl + Y redoes it.</li>
 *   <li>The pill sits above the selected area's top left corner - without an
 *       area, above the first marked widget; below it where there is no room,
 *       pulled in at the edges - and hides while a frame is drawn or widgets
 *       move.</li>
 * </ul>
 * Canvas coordinates are the widgets' own; the view shows them scaled by the
 * zoom and shifted by the pan offset - the widgets and the frame sit on
 * layers that carry both, the frame's under the dots, the widgets' above; the
 * dots and the pill stay in the view.
 * {@link #getWidgets()} follows every move, cut, paste and delete.
 */
public final class DesktopCanvas extends Region {

    /** Distance between the grid dots; the dots sit half a pitch in, so widget edges on the grid pass between them. */
    public static final double GRID_PITCH = 32;

    private static final double DOT_RADIUS = 1;
    /**
     * The corner of a macOS window (AppKit's own, 16pt on macOS 27), so that in
     * zen mode, filling the window, the canvas takes exactly its shape; the
     * stylesheet rounds the ground and rim to match.
     */
    private static final double CORNER_RADIUS = 16;
    private static final double PILL_GAP = 8;
    /** How far the pointer may wander before a click becomes a drag; a trackpad click shakes a few pixels. */
    private static final double CLICK_SLOP = 5;
    private static final int HISTORY_LIMIT = 100;
    private static final double MIN_ZOOM = 0.3;
    private static final double MAX_ZOOM = 3;
    /** Scroll pixels to zoom: 100px of wheel or swipe is a factor of e^0.25. */
    private static final double SCROLL_ZOOM_RATE = 0.0025;
    /** Below this spacing on screen the dots would only be noise; they go. */
    private static final double MIN_DOT_SPACING = 10;

    private enum Gesture { NONE, PAN, MOVE, SELECT }

    private final ObservableList<WidgetSpec> widgets = FXCollections.observableArrayList();
    private final List<CanvasWidget> widgetNodes = new ArrayList<>();
    private final Set<String> selection = new LinkedHashSet<>();

    private final Path dots = new Path();
    /** Carries the frame alone, under the dots, so the grid stays visible inside the selected area. */
    private final Pane areaLayer = new Pane();
    private final Pane content = new Pane();
    private final Translate pan = new Translate();
    private final Scale zoom = new Scale(1, 1, 0, 0);
    private final Region frame = new Region();
    private final ToolPill pill = new ToolPill();
    private final ParallelTransition pillIn;

    /** The frame drawn with Shift + drag, in canvas coordinates; null for a selection made by clicks. */
    private Rectangle2D area;

    private Gesture gesture = Gesture.NONE;
    /** Where the canvas's top left corner sat in the window last; null before it is shown. */
    private Point2D windowOrigin;
    private Point2D pressedAt;
    private boolean moved;
    private double panStartX;
    private double panStartY;
    private CanvasWidget pressedWidget;
    private final Map<CanvasWidget, Point2D> moveStarts = new HashMap<>();
    private Rectangle2D areaStart;
    private Set<String> selectBase = Set.of();

    /** The widget list before each change, newest first; what an undo returns to. */
    private final Deque<List<WidgetSpec>> undoStack = new ArrayDeque<>();
    private final Deque<List<WidgetSpec>> redoStack = new ArrayDeque<>();

    private List<WidgetSpec> clipboard = List.of();
    /** Pastes since the last cut or copy; the next paste lands this many grid steps further, plus one. */
    private int pasteCount;
    private int nextId;

    public DesktopCanvas() {
        getStyleClass().add("desktop-canvas");
        setFocusTraversable(true);
        setCursor(Cursor.OPEN_HAND);

        dots.getStyleClass().add("dc-dots");
        dots.setMouseTransparent(true);
        dots.setManaged(false);
        frame.getStyleClass().add("dc-marquee");
        frame.setMouseTransparent(true);
        frame.setManaged(false);
        frame.setVisible(false);
        pill.setManaged(false);
        pill.setVisible(false);
        // The frame lies under the dots and the widgets: the selected area is a zone they stand in.
        areaLayer.getChildren().add(frame);
        areaLayer.setManaged(false);
        areaLayer.setMouseTransparent(true);
        areaLayer.getTransforms().addAll(pan, zoom);
        content.setManaged(false);
        content.getTransforms().addAll(pan, zoom);
        getChildren().addAll(areaLayer, dots, content, pill);

        // Rounded like the window; the widgets and dots stay inside the corners.
        Rectangle clip = new Rectangle();
        clip.setArcWidth(CORNER_RADIUS * 2);
        clip.setArcHeight(CORNER_RADIUS * 2);
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        FadeTransition fade = new FadeTransition(Duration.millis(120));
        fade.setFromValue(0);
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(120));
        rise.setFromY(3);
        rise.setToY(0);
        pillIn = new ParallelTransition(pill, fade, rise);

        widgets.addListener((ListChangeListener<WidgetSpec>) _ -> syncWidgets());
        widthProperty().addListener((_, _, _) -> rebuildDots());
        heightProperty().addListener((_, _, _) -> rebuildDots());
        localToSceneTransformProperty().subscribe(_ -> holdInWindow());

        addEventHandler(MouseEvent.MOUSE_PRESSED, this::pressed);
        addEventHandler(MouseEvent.MOUSE_DRAGGED, this::dragged);
        addEventHandler(MouseEvent.MOUSE_RELEASED, this::released);
        addEventHandler(MouseEvent.MOUSE_MOVED, this::hovered);
        addEventHandler(ScrollEvent.SCROLL, this::scrolled);
        addEventHandler(ZoomEvent.ZOOM, e -> zoomAt(zoom.getX() * e.getZoomFactor(), e.getX(), e.getY()));
        addEventHandler(KeyEvent.KEY_PRESSED, this::keyPressed);
        pill.cut().setOnAction(_ -> cut());
        pill.copy().setOnAction(_ -> copy());
        pill.paste().setOnAction(_ -> paste());
        refresh();
    }

    /** The widgets on the canvas; the consumer supplies them, the canvas keeps them current. */
    public ObservableList<WidgetSpec> getWidgets() {
        return widgets;
    }

    /** The pill at the selection. */
    public ToolPill pill() {
        return pill;
    }

    // --- keys and clipboard -----------------------------------------------------

    /** Ctrl as asked, and the platform's shortcut key (Cmd on macOS) alike. */
    private void keyPressed(KeyEvent e) {
        if (e.isShortcutDown() || e.isControlDown()) {
            switch (e.getCode()) {
                case X -> cut();
                case C -> copy();
                case V -> paste();
                case Z -> {
                    if (e.isShiftDown()) {
                        redo();
                    } else {
                        undo();
                    }
                }
                case Y -> redo();
                default -> {
                    return;
                }
            }
        } else {
            switch (e.getCode()) {
                case ESCAPE -> clear();
                case DELETE, BACK_SPACE -> delete();
                default -> {
                    return;
                }
            }
        }
        e.consume();
    }

    private void delete() {
        if (!selection.isEmpty()) {
            remember();
            widgets.removeIf(spec -> selection.contains(spec.id()));
        }
    }

    /** Keeps the widget list as it stands, before a change; a new change ends what could be redone. */
    private void remember() {
        undoStack.push(List.copyOf(widgets));
        if (undoStack.size() > HISTORY_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    private void undo() {
        if (!undoStack.isEmpty()) {
            redoStack.push(List.copyOf(widgets));
            restore(undoStack.pop());
        }
    }

    private void redo() {
        if (!redoStack.isEmpty()) {
            undoStack.push(List.copyOf(widgets));
            restore(redoStack.pop());
        }
    }

    /** Puts a kept list back; the drawn area goes, it would frame the old places. */
    private void restore(List<WidgetSpec> kept) {
        area = null;
        widgets.setAll(kept);
    }

    private void copy() {
        if (selection.isEmpty()) {
            return;
        }
        clipboard = widgets.stream().filter(spec -> selection.contains(spec.id())).toList();
        pasteCount = 0;
        refresh();
    }

    private void cut() {
        if (selection.isEmpty()) {
            return;
        }
        copy();
        pasteCount = -1;
        remember();
        widgets.removeIf(spec -> selection.contains(spec.id()));
    }

    private void paste() {
        if (clipboard.isEmpty()) {
            return;
        }
        pasteCount++;
        double offset = pasteCount * GRID_PITCH;
        List<WidgetSpec> pasted = clipboard.stream()
                .map(spec -> new WidgetSpec(freshId(), spec.x() + offset, spec.y() + offset,
                        spec.width(), spec.height(), spec.lines()))
                .toList();
        remember();
        widgets.addAll(pasted);
        selection.clear();
        pasted.forEach(spec -> selection.add(spec.id()));
        area = null;
        refresh();
    }

    private String freshId() {
        Set<String> taken = new HashSet<>();
        widgets.forEach(spec -> taken.add(spec.id()));
        String id;
        do {
            id = "w" + ++nextId;
        } while (taken.contains(id));
        return id;
    }

    // --- pointer ----------------------------------------------------------------

    private void pressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY || isInPill(e)) {
            return;
        }
        requestFocus();
        pressedAt = new Point2D(e.getX(), e.getY());
        moved = false;
        pressedWidget = widgetAt(e.getX(), e.getY());

        if (e.isShiftDown()) {
            gesture = Gesture.SELECT;
            selectBase = Set.copyOf(selection);
        } else if (pressedWidget != null || isInArea(e.getX(), e.getY())) {
            gesture = Gesture.MOVE;
            if (pressedWidget != null && !selection.contains(pressedWidget.id())) {
                selection.clear();
                selection.add(pressedWidget.id());
                area = null;
            }
            areaStart = area;
            moveStarts.clear();
            for (CanvasWidget node : widgetNodes) {
                if (selection.contains(node.id())) {
                    moveStarts.put(node, new Point2D(node.canvasBounds().getMinX(), node.canvasBounds().getMinY()));
                    node.toFront();
                }
            }
        } else {
            gesture = Gesture.PAN;
            panStartX = pan.getX();
            panStartY = pan.getY();
            setCursor(Cursor.CLOSED_HAND);
        }
        refresh();
    }

    private void dragged(MouseEvent e) {
        if (gesture == Gesture.NONE) {
            return;
        }
        double dx = e.getX() - pressedAt.getX();
        double dy = e.getY() - pressedAt.getY();
        moved |= Math.abs(dx) > CLICK_SLOP || Math.abs(dy) > CLICK_SLOP;
        if (!moved) {
            return;
        }
        switch (gesture) {
            case PAN -> {
                pan.setX(panStartX + dx);
                pan.setY(panStartY + dy);
            }
            case MOVE -> {
                // One snapped offset for all: widgets stand on the grid, so they stay on it.
                double sx = snap(dx / zoom.getX());
                double sy = snap(dy / zoom.getY());
                setCursor(Cursor.MOVE);
                moveStarts.forEach((node, start) -> node.moveTo(start.getX() + sx, start.getY() + sy));
                if (areaStart != null) {
                    area = new Rectangle2D(areaStart.getMinX() + sx, areaStart.getMinY() + sy,
                            areaStart.getWidth(), areaStart.getHeight());
                }
            }
            case SELECT -> {
                Point2D from = toCanvas(pressedAt.getX(), pressedAt.getY());
                Point2D to = toCanvas(clamp(e.getX(), getWidth()), clamp(e.getY(), getHeight()));
                area = new Rectangle2D(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                        Math.abs(to.getX() - from.getX()), Math.abs(to.getY() - from.getY()));
                selection.clear();
                selection.addAll(selectBase);
                for (CanvasWidget node : widgetNodes) {
                    if (node.canvasBounds().intersects(area)) {
                        selection.add(node.id());
                    }
                }
            }
            default -> {
            }
        }
        refresh();
    }

    private void released(MouseEvent e) {
        if (gesture == Gesture.NONE) {
            return;
        }
        Gesture ended = gesture;
        gesture = Gesture.NONE;
        if (!moved) {
            switch (ended) {
                case PAN -> {
                    selection.clear();
                    area = null;
                }
                case MOVE -> {
                    if (pressedWidget != null && !isInArea(pressedAt.getX(), pressedAt.getY())) {
                        selection.clear();
                        selection.add(pressedWidget.id());
                        area = null;
                    }
                }
                case SELECT -> {
                    if (pressedWidget != null && !selection.remove(pressedWidget.id())) {
                        selection.add(pressedWidget.id());
                    }
                }
                default -> {
                }
            }
        }
        if (ended == Gesture.MOVE && moved) {
            commitMoves();
        }
        moveStarts.clear();
        areaStart = null;
        pressedWidget = null;
        hovered(e);
        refresh();
    }

    private void hovered(MouseEvent e) {
        if (gesture == Gesture.NONE) {
            if (isInArea(e.getX(), e.getY())) {
                setCursor(Cursor.MOVE);
            } else {
                setCursor(widgetAt(e.getX(), e.getY()) != null ? Cursor.DEFAULT : Cursor.OPEN_HAND);
            }
        }
    }

    /**
     * Writes the moved widgets' new places into the widget list, all in one
     * change - one at a time, each change would set the others back to the
     * places the list still holds for them.
     */
    private void commitMoves() {
        Map<String, WidgetSpec> moved = new HashMap<>();
        moveStarts.keySet().forEach(node -> moved.put(node.id(), node.currentSpec()));
        List<WidgetSpec> next = widgets.stream().map(spec -> moved.getOrDefault(spec.id(), spec)).toList();
        // A drag that snapped back to where it started is no change to undo.
        if (!next.equals(widgets)) {
            remember();
            widgets.setAll(next);
        }
    }

    // --- view -------------------------------------------------------------------

    private void scrolled(ScrollEvent e) {
        if (e.isShiftDown()) {
            // With Shift held, macOS turns a wheel's vertical scroll into a horizontal one.
            double delta = e.getDeltaY() != 0 ? e.getDeltaY() : e.getDeltaX();
            zoomAt(zoom.getX() * Math.exp(delta * SCROLL_ZOOM_RATE), e.getX(), e.getY());
        } else {
            pan.setX(pan.getX() + e.getDeltaX());
            pan.setY(pan.getY() + e.getDeltaY());
            requestLayout();
        }
        e.consume();
    }

    /** Zooms to the given scale, keeping the canvas point under the view point where it is. */
    private void zoomAt(double scale, double x, double y) {
        double next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, scale));
        Point2D anchor = toCanvas(x, y);
        zoom.setX(next);
        zoom.setY(next);
        pan.setX(x - anchor.getX() * next);
        pan.setY(y - anchor.getY() * next);
        rebuildDots();
        requestLayout();
    }

    /**
     * Keeps the view where it is in the window when the canvas itself moves in
     * it - the frame closing or opening around it in zen mode: the pan takes
     * the opposite of the move, so the widgets and dots stay put on screen and
     * only the canvas's edges go.
     */
    private void holdInWindow() {
        Point2D origin = localToScene(0, 0);
        if (windowOrigin != null) {
            double dx = origin.getX() - windowOrigin.getX();
            double dy = origin.getY() - windowOrigin.getY();
            pan.setX(pan.getX() - dx);
            pan.setY(pan.getY() - dy);
            // A drag under way measures from where it began; that point stays put in the window too.
            if (pressedAt != null) {
                pressedAt = pressedAt.subtract(dx, dy);
            }
            panStartX -= dx;
            panStartY -= dy;
            requestLayout();
        }
        windowOrigin = origin;
    }

    private Point2D toCanvas(double x, double y) {
        return new Point2D((x - pan.getX()) / zoom.getX(), (y - pan.getY()) / zoom.getY());
    }

    private Rectangle2D toView(Rectangle2D r) {
        double scale = zoom.getX();
        return new Rectangle2D(pan.getX() + r.getMinX() * scale, pan.getY() + r.getMinY() * scale,
                r.getWidth() * scale, r.getHeight() * scale);
    }

    private void clear() {
        gesture = Gesture.NONE;
        moveStarts.clear();
        area = null;
        selection.clear();
        refresh();
    }

    /** Whether a point in view coordinates lies in the selected area. */
    private boolean isInArea(double x, double y) {
        return area != null && area.contains(toCanvas(x, y));
    }

    private boolean isInPill(MouseEvent e) {
        return pill.isVisible() && pill.getBoundsInParent().contains(e.getX(), e.getY());
    }

    /** The topmost widget under a point in view coordinates. */
    private CanvasWidget widgetAt(double x, double y) {
        Point2D at = toCanvas(x, y);
        CanvasWidget top = null;
        for (CanvasWidget node : widgetNodes) {
            if (node.canvasBounds().contains(at)
                    && (top == null || content.getChildren().indexOf(node) > content.getChildren().indexOf(top))) {
                top = node;
            }
        }
        return top;
    }

    /** What the pill stands at: the drawn area, else the first marked widget, else nothing. */
    private Rectangle2D pillAnchor() {
        if (area != null) {
            return area;
        }
        for (String id : selection) {
            for (CanvasWidget node : widgetNodes) {
                if (node.id().equals(id)) {
                    return node.canvasBounds();
                }
            }
        }
        return null;
    }

    private static double snap(double value) {
        return Math.round(value / GRID_PITCH) * GRID_PITCH;
    }

    private static double clamp(double value, double max) {
        return Math.max(0, Math.min(value, max));
    }

    // --- state onto the nodes -----------------------------------------------------

    private void refresh() {
        for (CanvasWidget node : widgetNodes) {
            node.setSelected(selection.contains(node.id()));
        }
        frame.setVisible(area != null);
        // Away only while widgets move or a frame is drawn - a press alone changes nothing.
        boolean pillShown = pillAnchor() != null && !(moved && (gesture == Gesture.MOVE || gesture == Gesture.SELECT));
        if (pillShown && !pill.isVisible()) {
            pillIn.playFromStart();
        }
        pill.setVisible(pillShown);
        pill.cut().setDisable(selection.isEmpty());
        pill.copy().setDisable(selection.isEmpty());
        pill.paste().setDisable(clipboard.isEmpty());
        requestLayout();
    }

    /**
     * Brings the nodes in line with the widget list: a widget that stays keeps
     * its node and its place in the stacking order, a new one goes on top, a
     * removed one leaves the selection.
     */
    private void syncWidgets() {
        Map<String, CanvasWidget> existing = new HashMap<>();
        widgetNodes.forEach(node -> existing.put(node.id(), node));
        List<CanvasWidget> next = new ArrayList<>();
        for (WidgetSpec spec : widgets) {
            CanvasWidget node = existing.remove(spec.id());
            if (node == null) {
                node = new CanvasWidget(spec);
                node.setManaged(false);
                // Above the other widgets.
                content.getChildren().add(node);
            } else {
                node.moveTo(spec.x(), spec.y());
            }
            next.add(node);
        }
        content.getChildren().removeAll(existing.values());
        widgetNodes.clear();
        widgetNodes.addAll(next);
        if (selection.retainAll(widgets.stream().map(WidgetSpec::id).toList()) && selection.isEmpty()) {
            area = null;
        }
        refresh();
    }

    /**
     * The dots at the zoomed pitch, one pitch more than the view on each side;
     * panning only shifts the path by less than a pitch. Zoomed far out they
     * give way.
     */
    private void rebuildDots() {
        double pitch = GRID_PITCH * zoom.getX();
        List<PathElement> elements = new ArrayList<>();
        if (pitch >= MIN_DOT_SPACING) {
            double radius = DOT_RADIUS * Math.min(1.5, Math.max(0.6, zoom.getX()));
            for (double y = -pitch / 2; y < getHeight() + pitch; y += pitch) {
                for (double x = -pitch / 2; x < getWidth() + pitch; x += pitch) {
                    elements.add(new MoveTo(x - radius, y));
                    elements.add(new ArcTo(radius, radius, 0, x + radius, y, false, true));
                    elements.add(new ArcTo(radius, radius, 0, x - radius, y, false, true));
                }
            }
        }
        dots.getElements().setAll(elements);
    }

    @Override
    protected void layoutChildren() {
        double pitch = GRID_PITCH * zoom.getX();
        dots.relocate(0, 0);
        dots.setTranslateX(floorMod(pan.getX(), pitch));
        dots.setTranslateY(floorMod(pan.getY(), pitch));
        areaLayer.relocate(0, 0);
        content.relocate(0, 0);
        for (CanvasWidget node : widgetNodes) {
            Rectangle2D b = node.canvasBounds();
            node.resizeRelocate(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
        }
        if (area != null) {
            frame.resizeRelocate(area.getMinX(), area.getMinY(), area.getWidth(), area.getHeight());
        }
        Rectangle2D anchor = pillAnchor();
        if (anchor != null) {
            layoutPill(toView(anchor));
        }
    }

    private static double floorMod(double value, double modulus) {
        return value - Math.floor(value / modulus) * modulus;
    }

    /** Above the area's top left corner; below the area without room above; kept inside the view. */
    private void layoutPill(Rectangle2D view) {
        pill.autosize();
        double width = pill.getWidth();
        double height = pill.getHeight();
        double top = view.getMinY() - height - PILL_GAP;
        if (top < PILL_GAP) {
            top = view.getMaxY() + PILL_GAP;
        }
        if (top + height > getHeight() - PILL_GAP) {
            top = Math.max(PILL_GAP, view.getMinY() + PILL_GAP);
        }
        double left = Math.max(PILL_GAP, Math.min(view.getMinX(), getWidth() - width - PILL_GAP));
        pill.relocate(left, top);
    }
}
