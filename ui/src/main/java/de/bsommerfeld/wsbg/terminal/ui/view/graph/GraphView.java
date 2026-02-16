package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Edge;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Canvas-based graph renderer. Nodes are dots only (no text cards) arranged
 * in a spiral — threads (orange) at the core, comments (blue) outward.
 *
 * <p>
 * Birth animation grows nodes organically from the center outward along
 * their edge. Death reverses this, shrinking from the outside inward.
 * An ice disc magnifier follows the cursor at all times, including during
 * drag/pan operations.
 * </p>
 */
public class GraphView extends Pane {

    private final Canvas canvas;
    private final GraphicsContext gc;

    // Camera Transform
    private double offsetX = 0;
    private double offsetY = 0;
    private double scale = 0.5;

    // Content Bounds
    private double contentMinX = -5000;
    private double contentMaxX = 5000;
    private double contentMinY = -5000;
    private double contentMaxY = 5000;

    // Dynamic Zoom Limits
    private double minScaleLimit = 0.02;
    private static final double MAX_SCALE_LIMIT = 5.0;

    // Content Center
    private double contentCenterX = 0;
    private double contentCenterY = 0;

    // Interaction State
    private double lastMouseX, lastMouseY;
    private boolean validInteraction = false;
    private boolean dragged = false;
    private Set<String> highlightedNodeIds = new HashSet<>();

    // ... lines 59-224 (no change needed here, context matching is key) ...
    // Note: I will use a larger block to ensure context matching for clampOffset
    // and updateZoomLimits.

    // I'll target the block containing clampOffset and updateZoomLimits directly.

    private List<Node> nodesRef;
    private List<Edge> edgesRef;
    private Consumer<String> threadClickHandler;

    // Mouse position for magnifier (screen coordinates).
    // Updated on move AND drag so the lens follows during panning.
    private double mouseScreenX = -1;
    private double mouseScreenY = -1;
    private boolean mouseInView = false;

    // Birth: nodes grow along their edge from parent outward.
    // Death: nodes shrink from their position toward parent (reverse).
    private static final long BIRTH_DURATION_NS = 800_000_000L;
    private static final long DEATH_DURATION_NS = 2_500_000_000L;

    // Glass Disc Magnifier
    private static final double MAGNIFIER_RADIUS = 75.0;
    private static final double MAGNIFIER_ZOOM = 1.0;

    public GraphView() {
        this.setStyle("-fx-background-color: #050505;");

        canvas = new Canvas();
        gc = canvas.getGraphicsContext2D();
        this.getChildren().add(canvas);

        this.widthProperty().addListener((obs, old, val) -> {
            canvas.setWidth(val.doubleValue());
            if (old.doubleValue() == 0)
                centerView();
        });
        this.heightProperty().addListener((obs, old, val) -> canvas.setHeight(val.doubleValue()));

        setupEvents();
    }

    private void setupEvents() {
        this.setOnScroll((ScrollEvent e) -> {
            e.consume();
            if (e.getDeltaY() == 0)
                return;

            double zoomFactor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
            double oldScale = scale;
            scale *= zoomFactor;

            scale = Math.max(minScaleLimit, Math.min(MAX_SCALE_LIMIT, scale));

            double f = (scale / oldScale) - 1;
            double dx = e.getX() - (this.getWidth() / 2.0 + offsetX);
            double dy = e.getY() - (this.getHeight() / 2.0 + offsetY);

            offsetX -= dx * f;
            offsetY -= dy * f;

            clampOffset();
        });

        this.setOnMousePressed((MouseEvent e) -> {
            validInteraction = true;
            dragged = false;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            mouseScreenX = e.getX();
            mouseScreenY = e.getY();
            // Only show drag cursor when panning is actually possible.
            // At minimum zoom the offset is locked — CLOSED_HAND would be misleading.
            if (isPanningAllowed()) {
                setCursor(javafx.scene.Cursor.CLOSED_HAND);
            }
        });

        this.setOnMouseDragged((MouseEvent e) -> {
            if (!validInteraction)
                return;
            dragged = true;

            double deltaX = e.getX() - lastMouseX;
            double deltaY = e.getY() - lastMouseY;

            offsetX += deltaX;
            offsetY += deltaY;

            clampOffset();

            lastMouseX = e.getX();
            lastMouseY = e.getY();

            // Keep magnifier tracking during drag
            mouseScreenX = e.getX();
            mouseScreenY = e.getY();
        });

        this.setOnMouseReleased((MouseEvent e) -> {
            if (!dragged && threadClickHandler != null) {
                handleMagnifierClick();
            }
            validInteraction = false;
            dragged = false;
            setCursor(javafx.scene.Cursor.DEFAULT);
        });

        this.setOnMouseMoved((MouseEvent e) -> {
            mouseScreenX = e.getX();
            mouseScreenY = e.getY();
            mouseInView = true;
        });

        this.setOnMouseEntered(e -> mouseInView = true);
        this.setOnMouseExited(e -> mouseInView = false);
    }

    public void setThreadClickHandler(Consumer<String> handler) {
        this.threadClickHandler = handler;
    }

    /**
     * Determines which thread has the most nodes inside the magnifier lens
     * and fires the handler with that threadId. Ignores the center node.
     */
    private void handleMagnifierClick() {
        if (nodesRef == null)
            return;

        double w = getWidth(), h = getHeight();
        double ox = w / 2.0 + offsetX;
        double oy = h / 2.0 + offsetY;
        double mx = mouseScreenX;
        double my = mouseScreenY;

        // World coordinate of magnifier center
        double worldX = (mx - ox) / scale;
        double worldY = (my - oy) / scale;
        // World-space radius visible through the magnifier
        double worldRadius = MAGNIFIER_RADIUS / (scale * MAGNIFIER_ZOOM);
        double worldRadiusSq = worldRadius * worldRadius;

        // Count nodes per thread inside the lens
        Map<String, Integer> threadHits = new java.util.HashMap<>();
        synchronized (nodesRef) {
            for (Node n : nodesRef) {
                if (n.isCenterNode || n.threadId == null)
                    continue;
                double dx = n.x - worldX;
                double dy = n.y - worldY;
                if (dx * dx + dy * dy <= worldRadiusSq) {
                    threadHits.merge(n.threadId, 1, Integer::sum);
                }
            }
        }

        // Pick the thread with the highest node count inside the lens
        String bestThread = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : threadHits.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestThread = entry.getKey();
            }
        }

        if (bestThread != null) {
            threadClickHandler.accept(bestThread);
        }
    }

    private boolean isPanningAllowed() {
        return scale > minScaleLimit * 1.05;
    }

    private void clampOffset() {
        double viewW = this.getWidth();
        double viewH = this.getHeight();
        if (viewW == 0 || viewH == 0)
            return;

        // Calculate the ideal centered offset for the current scale
        double idealOffsetX = -contentCenterX * scale;
        double idealOffsetY = -contentCenterY * scale;

        // At minimum zoom (fit-to-screen), we lock the view to the content center.
        if (!isPanningAllowed()) {
            offsetX = idealOffsetX;
            offsetY = idealOffsetY;
            return;
        }

        // When zoomed in, we allow panning but constrain the camera so the user
        // cannot pan the content completely out of view.
        // The constraints are based on the "world range" visible at minimum zoom.

        // World-space dimensions visible at minimum zoom
        double visibleWorldW = viewW / minScaleLimit;
        double visibleWorldH = viewH / minScaleLimit;

        // World-space dimensions visible at current zoom
        double currWorldW = viewW / scale;
        double currWorldH = viewH / scale;

        // The maximum distance the camera center can drift from the content center
        double maxDriftX = (visibleWorldW - currWorldW) / 2.0;
        double maxDriftY = (visibleWorldH - currWorldH) / 2.0;

        // Current camera center in world coordinates
        // screenCenter = worldCenter * scale + viewCenter + offset => worldCenter = ...
        // Actually simpler: offsetX is the translation applied to the visual.
        // Visual center is at screen (w/2, h/2).
        // The world point at visual center is ( -offsetX / scale ).
        // We want this world point to be within [contentCenterX - drift, contentCenterX
        // + drift]

        double currentWorldCenterX = -offsetX / scale;
        double currentWorldCenterY = -offsetY / scale;

        double dx = currentWorldCenterX - contentCenterX;
        double dy = currentWorldCenterY - contentCenterY;

        dx = Math.max(-maxDriftX, Math.min(maxDriftX, dx));
        dy = Math.max(-maxDriftY, Math.min(maxDriftY, dy));

        // Re-calculate offset based on clamped world center
        offsetX = -(contentCenterX + dx) * scale;
        offsetY = -(contentCenterY + dy) * scale;
    }

    public void setNodes(List<Node> nodes) {
        this.nodesRef = nodes;
        // Sync here because setNodes presumably takes the live list
        synchronized (nodes) {
            updateZoomLimits(nodes);
        }
    }

    private void updateZoomLimits(List<Node> currentNodes) {
        if (currentNodes == null || currentNodes.isEmpty()) {
            minScaleLimit = 0.001;
            contentCenterX = 0;
            contentCenterY = 0;
            return;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        // No need to synchronize here if we pass a snapshot
        for (Node n : currentNodes) {
            if (n.x < minX)
                minX = n.x;
            if (n.x > maxX)
                maxX = n.x;
            if (n.y < minY)
                minY = n.y;
            if (n.y > maxY)
                maxY = n.y;
        }

        if (minX == Double.MAX_VALUE) {
            contentCenterX = 0;
            contentCenterY = 0;
            return;
        }

        this.contentMinX = minX;
        this.contentMaxX = maxX;
        this.contentMinY = minY;
        this.contentMaxY = maxY;

        this.contentCenterX = (minX + maxX) / 2.0;
        this.contentCenterY = (minY + maxY) / 2.0;

        // Padding
        double padding = 400.0;
        double contentW = (maxX - minX) + padding;
        double contentH = (maxY - minY) + padding;

        double viewW = this.getWidth();
        double viewH = this.getHeight();

        if (viewW == 0 || viewH == 0)
            return;

        double scaleW = viewW / contentW;
        double scaleH = viewH / contentH;

        minScaleLimit = Math.min(scaleW, scaleH);

        if (minScaleLimit > 1.0)
            minScaleLimit = 1.0;
        if (minScaleLimit < 0.0001)
            minScaleLimit = 0.0001;
    }

    public void setHighlightedNodeIds(Set<String> ids) {
        this.highlightedNodeIds = (ids == null) ? new HashSet<>() : ids;
    }

    private void centerView() {
        if (nodesRef != null) {
            synchronized (nodesRef) {
                updateZoomLimits(nodesRef);
            }
        } else {
            updateZoomLimits(null);
        }

        // Clamp initial scale so the view never opens more zoomed out
        // than the computed minimum.
        if (scale < minScaleLimit)
            scale = minScaleLimit;

        // Reset to center of content
        // (Offset will be recalculated by clampOffset)
        offsetX = -contentCenterX * scale;
        offsetY = -contentCenterY * scale;

        clampOffset();
    }

    // --- VISUAL EFFECTS SYSTEM ---
    private static class VisualEffect {
        double x, y;
        Color color;
        double progress = 1.0;
        double initialSize;

        VisualEffect(double x, double y, Color color, double size) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.initialSize = size;
        }
    }

    private final List<VisualEffect> activeEffects = new java.util.concurrent.CopyOnWriteArrayList<>();

    public void addNodeCleanupEffect(double x, double y, boolean isThread) {
        if (350 * scale > 60)
            return;
        double size = isThread ? 90.0 : 36.0;
        activeEffects.add(new VisualEffect(x, y, Color.RED, size));
    }

    public void addNodeCreationEffect(double x, double y, boolean isThread) {
        if (350 * scale > 60)
            return;
        double size = isThread ? 90.0 : 36.0;
        activeEffects.add(new VisualEffect(x, y, Color.LIMEGREEN, size));
    }

    private void renderEffects() {
        if (activeEffects.isEmpty())
            return;

        double decayRate = 0.02;

        for (VisualEffect effect : activeEffects) {
            effect.progress -= decayRate;
            if (effect.progress <= 0) {
                activeEffects.remove(effect);
                continue;
            }

            double currentRadius = effect.initialSize * effect.progress;

            gc.save();
            gc.translate(effect.x, effect.y);

            gc.setGlobalAlpha(effect.progress);
            gc.setFill(effect.color);
            gc.fillOval(-currentRadius, -currentRadius, currentRadius * 2, currentRadius * 2);

            gc.restore();
        }
    }

    // --- BIRTH / DEATH ANIMATION ---

    /**
     * Birth progress: 0.0 → 1.0 over BIRTH_DURATION_NS.
     * During birth the node slides outward from its parent (edge source)
     * to its final position — organic "growing from the center".
     */
    private double birthProgress(Node n, long now) {
        long elapsed = now - n.birthNano;
        if (elapsed >= BIRTH_DURATION_NS)
            return 1.0;
        if (elapsed <= 0)
            return 0.0;
        double t = (double) elapsed / BIRTH_DURATION_NS;
        // Ease-out cubic for organic deceleration
        return 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t);
    }

    /**
     * Death progress: 1.0 → 0.0 over DEATH_DURATION_NS.
     * During death the node slides back toward its parent (edge source)
     * — organic "shrinking toward the center".
     */
    private double deathProgress(Node n, long now) {
        if (n.deathNano < 0)
            return 1.0;
        long elapsed = now - n.deathNano;
        if (elapsed >= DEATH_DURATION_NS)
            return 0.0;
        if (elapsed <= 0)
            return 1.0;
        double t = (double) elapsed / DEATH_DURATION_NS;
        // Ease-in cubic for organic acceleration toward center
        return 1.0 - t * t * t;
    }

    /**
     * Combined life factor. A dying node that was still birthing fades
     * smoothly without conflict.
     */
    private double liveliness(Node n, long now) {
        return Math.min(birthProgress(n, now), deathProgress(n, now));
    }

    /**
     * Returns the display position of a node, interpolated between its
     * parent (edge source) and actual position based on its liveliness.
     * Birth: slides from parent to self. Death: slides from self to parent.
     */
    private double[] displayPosition(Node n, long now) {
        double life = liveliness(n, now);
        if (life >= 1.0)
            return new double[] { n.x, n.y };

        // Find parent position (edge source). If no parent, use origin
        // (center node). This gives the "growing from center outward" look.
        double parentX = 0;
        double parentY = 0;
        if (n.parent != null) {
            parentX = n.parent.x;
            parentY = n.parent.y;
        }

        // Interpolate between parent and actual position
        double dx = parentX + (n.x - parentX) * life;
        double dy = parentY + (n.y - parentY) * life;
        return new double[] { dx, dy };
    }

    public void render(List<Node> nodes, List<Edge> edges) {
        // Defines snapshots for thread-safe rendering without blocking simulation
        List<Node> nodesSnapshot;
        synchronized (nodes) {
            nodesSnapshot = new ArrayList<>(nodes);
        }
        List<Edge> edgesSnapshot;
        synchronized (edges) {
            edgesSnapshot = new ArrayList<>(edges);
        }

        this.nodesRef = nodes; // Keep ref to original for interaction
        this.edgesRef = edges;

        // Update Zoom Limits using the snapshot to avoid deadlocks
        updateZoomLimits(nodesSnapshot);

        double w = canvas.getWidth();
        double h = canvas.getHeight();
        long now = System.nanoTime();

        gc.clearRect(0, 0, w, h);
        drawGrid(w, h);
        drawAmbientGlow(w, h);

        gc.save();
        gc.translate(w / 2.0 + offsetX, h / 2.0 + offsetY);
        gc.scale(scale, scale);

        double ox = w / 2.0 + offsetX;
        double oy = h / 2.0 + offsetY;
        double viewLeft = -ox / scale;
        double viewRight = (w - ox) / scale;
        double viewTop = -oy / scale;
        double viewBottom = (h - oy) / scale;
        double cullingBuffer = 500.0;

        // 1. Draw Edges — use snapshot
        gc.setLineWidth(1.0 / scale);

        for (Edge e : edgesSnapshot) {
            if (e.source == null || e.target == null)
                continue;

            double x1 = e.source.x;
            double y1 = e.source.y;

            double[] targetPos = displayPosition(e.target, now);
            double x2 = targetPos[0];
            double y2 = targetPos[1];

            if ((x1 < viewLeft - cullingBuffer && x2 < viewLeft - cullingBuffer) ||
                    (x1 > viewRight + cullingBuffer && x2 > viewRight + cullingBuffer) ||
                    (y1 < viewTop - cullingBuffer && y2 < viewTop - cullingBuffer) ||
                    (y1 > viewBottom + cullingBuffer && y2 > viewBottom + cullingBuffer)) {
                continue;
            }

            Node target = e.target;
            int maxLvl = (target.rootNode != null) ? target.rootNode.maxSubtreeLevel : 5;
            if (maxLvl < 1)
                maxLvl = 1;

            double ratio = (double) target.level / (double) maxLvl;
            if (ratio > 1.0)
                ratio = 1.0;

            double brightness = 0.6 - (ratio * 0.3);
            double targetLife = liveliness(e.target, now);

            gc.setGlobalAlpha(Math.max(0, Math.min(targetLife, 0.6)));
            gc.setStroke(Color.gray(brightness, 0.6));
            gc.strokeLine(x1, y1, x2, y2);
            gc.setGlobalAlpha(1.0);
        }

        // 2. Draw Nodes — use snapshot
        for (Node n : nodesSnapshot) {
            double life = liveliness(n, now);
            if (life <= 0.001)
                continue;

            double[] pos = displayPosition(n, now);
            double px = pos[0];
            double py = pos[1];

            if (px < viewLeft - cullingBuffer || px > viewRight + cullingBuffer ||
                    py < viewTop - cullingBuffer || py > viewBottom + cullingBuffer) {
                continue;
            }

            gc.save();
            gc.translate(px, py);

            double dotAlpha = Math.max(0, (life - 0.2) / 0.8);
            double dotScale = 0.4 + 0.6 * dotAlpha;

            double dotRadius = n.isCenterNode ? 20.0 : n.isThread ? 20.0 : 8.0;
            double activeRadius = dotRadius * dotScale;

            gc.setGlobalAlpha(dotAlpha);

            boolean isHighlighted = highlightedNodeIds.contains(n.id);

            if (isHighlighted) {
                activeRadius *= 1.8;

                RadialGradient glow = new RadialGradient(0, 0, 0, 0, activeRadius * 1.5, false,
                        CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#FFD700", 0.6)), new Stop(1, Color.TRANSPARENT));
                gc.setFill(glow);
                gc.fillOval(-activeRadius * 1.5, -activeRadius * 1.5, activeRadius * 3, activeRadius * 3);
                gc.setFill(Color.GOLD);
            } else if (n.isCenterNode) {
                gc.setFill(Color.RED);
            } else {
                gc.setFill(n.color != null ? n.color : (n.isThread ? Color.web("#FF8C00") : Color.web("#4488FF")));
            }

            gc.fillOval(-activeRadius, -activeRadius, activeRadius * 2, activeRadius * 2);
            gc.setGlobalAlpha(1.0);

            gc.restore();
        }

        renderEffects();
        gc.restore();

        // Magnifier is drawn in screen space, always follows cursor
        if (mouseInView) {
            drawIceMagnifier(nodesSnapshot, edgesSnapshot, w, h, now);
        }
    }

    // --- VOLUMETRIC AMBIENT GLOW ---

    /**
     * Radial ambient glow centered on the content centroid. Purple-blue
     * gradient that extends toward the window edges, giving depth to the
     * dark background.
     */
    private void drawAmbientGlow(double w, double h) {
        if (nodesRef == null || nodesRef.isEmpty())
            return;

        double cx = w / 2.0 + offsetX;
        double cy = h / 2.0 + offsetY;
        double maxDim = Math.max(w, h);
        double glowRadius = maxDim * 0.9;

        RadialGradient glow = new RadialGradient(0, 0,
                cx, cy, glowRadius, false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(30, 10, 50, 0.25)),
                new Stop(0.2, Color.rgb(15, 15, 45, 0.20)),
                new Stop(0.5, Color.rgb(8, 8, 25, 0.12)),
                new Stop(0.8, Color.rgb(3, 3, 10, 0.06)),
                new Stop(1.0, Color.TRANSPARENT));

        gc.setFill(glow);
        gc.fillRect(0, 0, w, h);
    }

    /**
     * Glass disc magnifier at cursor position.
     * Renders a 1:1 view inside a simple, clean glass pane.
     * Minimalist design: Uniform rim, subtle gradient, no complex 3D effects.
     */
    private void drawIceMagnifier(List<Node> nodes, List<Edge> edges,
            double w, double h, long now) {
        double mx = mouseScreenX;
        double my = mouseScreenY;

        if (mx < 0 || my < 0 || mx > w || my > h)
            return;

        double r = MAGNIFIER_RADIUS;

        // 0. Drop Shadow (Soft, centered)
        gc.save();
        gc.setGlobalAlpha(0.3);
        gc.setFill(new RadialGradient(0, 0, mx, my + 2, r * 1.1, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.BLACK), new Stop(1, Color.TRANSPARENT)));
        gc.fillOval(mx - r, my - r, r * 2.2, r * 2.2);
        gc.setGlobalAlpha(1.0);
        gc.restore();

        gc.save();

        // 1. Clip content to circle
        gc.beginPath();
        gc.arc(mx, my, r, r, 0, 360);
        gc.closePath();
        gc.clip();

        // 2. Simple Glass Body
        // Linear gradient: Subtle white tint Top-Left -> Transparent Bottom-Right
        javafx.scene.paint.LinearGradient glassBody = new javafx.scene.paint.LinearGradient(
                mx - r, my - r, mx + r, my + r,
                false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(255, 255, 255, 0.08)),
                new Stop(1.0, Color.rgb(200, 220, 255, 0.02)));

        gc.setFill(glassBody);
        gc.fillOval(mx - r, my - r, r * 2, r * 2);

        // 3. Render Graph Content (1:1 scale)
        double magScale = scale * MAGNIFIER_ZOOM;
        double ox = w / 2.0 + offsetX;
        double oy = h / 2.0 + offsetY;

        double worldX = (mx - ox) / scale;
        double worldY = (my - oy) / scale;

        gc.save();
        gc.translate(mx, my);
        gc.scale(magScale, magScale);
        gc.translate(-worldX, -worldY);

        // Edges in magnifier
        gc.setLineWidth(1.0 / magScale);
        for (Edge e : edges) {
            if (e.source == null || e.target == null)
                continue;

            double targetLife = liveliness(e.target, now);
            if (targetLife <= 0.001)
                continue;

            double[] targetPos = displayPosition(e.target, now);

            // High visible edges in magnifier
            gc.setGlobalAlpha(Math.max(0.4, Math.min(targetLife, 0.8)));
            gc.setStroke(Color.rgb(200, 200, 220, 0.6)); // Brighter edges
            gc.setLineWidth(1.5 / magScale); // Slightly thicker
            gc.strokeLine(e.source.x, e.source.y, targetPos[0], targetPos[1]);
        }

        // Nodes in magnifier
        gc.setGlobalAlpha(1.0);
        for (Node n : nodes) {
            double life = liveliness(n, now);
            if (life <= 0.001)
                continue;

            double[] pos = displayPosition(n, now);
            double dotAlpha = Math.max(0, (life - 0.2) / 0.8);
            double dotRadius = n.isCenterNode ? 20.0 : n.isThread ? 20.0 : 8.0;
            double activeR = dotRadius * (0.4 + 0.6 * dotAlpha);

            // SIZE BOOST: Ensure nodes are visible as targets even when small
            // Inside the magnifier, we want them to look like "clickable targets"
            activeR = Math.max(activeR, 5.0);

            // Refined "High Visibility" Glass Nodes (Performance Optimized)
            Color baseColor;
            if (n.isCenterNode) {
                baseColor = Color.RED;
            } else if (n.isThread) {
                baseColor = Color.web("#FF8C00");
            } else {
                baseColor = n.color != null ? n.color : Color.web("#4488FF");
            }

            boolean isHighlighted = highlightedNodeIds.contains(n.id);

            // Boost visibility inside magnifier
            gc.setGlobalAlpha(1.0); // Full opacity for "Target" indicators

            // 0. SELECTION INDICATOR (The "Glow" you requested)
            // A large, soft halo behind the node to say "I am in the lens"
            gc.setFill(Color.rgb(255, 255, 255, 0.25));
            gc.fillOval(pos[0] - activeR * 1.6, pos[1] - activeR * 1.6, activeR * 3.2, activeR * 3.2);

            // Replicate specific highlighting glow (Golden for search hits)
            if (isHighlighted) {
                gc.setFill(Color.web("#FFD700", 0.6));
                gc.fillOval(pos[0] - activeR * 1.8, pos[1] - activeR * 1.8, activeR * 3.6, activeR * 3.6);
            }

            // 1. Base Body (Solid Color)
            gc.setFill(baseColor);
            gc.fillOval(pos[0] - activeR, pos[1] - activeR, activeR * 2, activeR * 2);

            // 2. Inner "Lit" Glow
            gc.setFill(Color.rgb(255, 255, 255, 0.2));
            gc.fillOval(pos[0] - activeR, pos[1] - activeR, activeR * 2, activeR * 2);

            // 3. Sharp Specular Highlight
            gc.setFill(Color.rgb(255, 255, 255, 0.9));
            double shineR = activeR * 0.45;
            gc.fillOval(pos[0] - activeR * 0.5, pos[1] - activeR * 0.6, shineR, shineR * 0.7);

            // 4. Glass Rim / Border
            gc.setStroke(Color.rgb(200, 240, 255, 0.8)); // Very bright rim
            gc.setLineWidth(1.5);
            gc.strokeOval(pos[0] - activeR, pos[1] - activeR, activeR * 2, activeR * 2);
        }

        gc.setGlobalAlpha(1.0);
        gc.restore(); // restore local transform
        gc.restore(); // restore clip/save

        // --- Simple Glass Rim (Screen Space) ---

        // 1. Uniform Clean Rim
        // A single, elegant line to define the circle.
        gc.setLineWidth(1.5);
        gc.setStroke(Color.rgb(200, 230, 255, 0.25)); // Subtle Ice/White
        gc.strokeOval(mx - r, my - r, r * 2, r * 2);

        // 2. Subtle Reflection Gradient (Top-Left)
        // A very faint arc to hint at glossiness without being a distinct "feature".
        gc.setLineWidth(1.0);
        gc.setStroke(new javafx.scene.paint.LinearGradient(
                mx - r, my - r, mx, my,
                false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(255, 255, 255, 0.4)),
                new Stop(1.0, Color.TRANSPARENT)));
        gc.strokeArc(mx - r, my - r, r * 2, r * 2, 110, 80, javafx.scene.shape.ArcType.OPEN);
    }

    private void drawGrid(double w, double h) {
        double targetVisualSize = 60.0 + (scale * 40.0);
        double fit = targetVisualSize / scale;
        double p = Math.round(Math.log(fit) / Math.log(2));
        double gridWorldSpacing = Math.pow(2, p);

        if (gridWorldSpacing < 1.0)
            gridWorldSpacing = 1.0;

        double ox = w / 2.0 + offsetX;
        double oy = h / 2.0 + offsetY;

        gc.setStroke(Color.rgb(13, 13, 13));
        gc.setLineWidth(1.0);

        double startWorldX = -ox / scale;
        double endWorldX = (w - ox) / scale;
        double startWorldY = -oy / scale;
        double endWorldY = (h - oy) / scale;

        long startKX = (long) Math.floor(startWorldX / gridWorldSpacing);
        long endKX = (long) Math.ceil(endWorldX / gridWorldSpacing);
        long startKY = (long) Math.floor(startWorldY / gridWorldSpacing);
        long endKY = (long) Math.ceil(endWorldY / gridWorldSpacing);

        for (long k = startKX; k <= endKX; k++) {
            double screenX = Math.round((k * gridWorldSpacing * scale) + ox);
            if (screenX >= 0 && screenX <= w) {
                gc.strokeLine(screenX, 0, screenX, h);
            }
        }

        for (long k = startKY; k <= endKY; k++) {
            double screenY = Math.round((k * gridWorldSpacing * scale) + oy);
            if (screenY >= 0 && screenY <= h) {
                gc.strokeLine(0, screenY, w, screenY);
            }
        }
    }
}
