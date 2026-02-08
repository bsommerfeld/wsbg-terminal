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
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

public class GraphView extends Pane {

    private final Canvas canvas;
    private final GraphicsContext gc;

    // Camera Transform
    private double offsetX = 0;
    private double offsetY = 0;
    private double scale = 0.15; // Default to zoomed out view
    // Tracking State
    private Node trackedNode = null;
    private Double targetScale = null;

    // Dynamic Zoom Limits
    private double minScaleLimit = 0.02; // Will be updated based on content
    private static final double MAX_SCALE_LIMIT = 5.0; // Fixed max zoom (close-up)

    // Interaction State
    private double lastMouseX, lastMouseY;
    private boolean validInteraction = false;
    private Node draggedNode = null;
    private Set<String> highlightedNodeIds = new HashSet<>();

    private List<Node> nodesRef; // Reference for hit testing
    private List<Edge> edgesRef; // Reference for traversal

    // Font Cache
    private final Font titleFont = Font.font("Fira Code", FontWeight.BOLD, 10);
    private final Font statsFont = Font.font("Fira Code", FontWeight.NORMAL, 9);

    public GraphView() {
        this.setStyle("-fx-background-color: #050505;");

        // Initialize Canvas
        canvas = new Canvas();
        gc = canvas.getGraphicsContext2D();
        this.getChildren().add(canvas);

        // Resize Logic
        this.widthProperty().addListener((obs, old, val) -> {
            canvas.setWidth(val.doubleValue());
            if (old.doubleValue() == 0)
                centerView(); // Initial Center
        });
        this.heightProperty().addListener((obs, old, val) -> canvas.setHeight(val.doubleValue()));

        // Event Listeners
        setupEvents();
    }

    // Helper to calculate total bounding box of a structure using BFS (Connected
    // Component)
    private double[] calculateClusterBounds(Node startNode) {
        Set<Node> component = new HashSet<>();
        Queue<Node> queue = new ArrayDeque<>();

        component.add(startNode);
        queue.add(startNode);

        // Build Adjacency Graph safely from edgesRef
        // This ensures we find ALL connected parts of the structure, including
        // "secondary roots"
        Map<Node, List<Node>> adj = new HashMap<>();
        if (edgesRef != null) {
            synchronized (edgesRef) {
                for (Edge e : edgesRef) {
                    if (e.source != null && e.target != null) {
                        adj.computeIfAbsent(e.source, k -> new ArrayList<>()).add(e.target);
                        adj.computeIfAbsent(e.target, k -> new ArrayList<>()).add(e.source);
                    }
                }
            }
        }

        // BFS Traversal
        while (!queue.isEmpty()) {
            Node u = queue.poll();
            List<Node> neighbors = adj.get(u);
            if (neighbors != null) {
                for (Node v : neighbors) {
                    if (component.add(v)) {
                        queue.add(v);
                    }
                }
            }
        }

        // Calculate Stats
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        for (Node n : component) {
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
            minX = startNode.x;
            maxX = startNode.x;
            minY = startNode.y;
            maxY = startNode.y;
        }

        // Add padding for node radius (approx 250px each side)
        return new double[] { minX - 250, maxX + 250, minY - 250, maxY + 250 };
    }

    private void setupEvents() {
        // Scroll to Zoom
        this.setOnScroll((ScrollEvent e) -> {
            e.consume();
            if (e.getDeltaY() == 0)
                return;

            // BREAK TRACKING immediately on interaction
            trackedNode = null;
            targetScale = null;

            double zoomFactor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
            double oldScale = scale;
            scale *= zoomFactor;

            // Apply Hard Limits
            scale = Math.max(0.01, Math.min(MAX_SCALE_LIMIT, scale));

            // Zoom towards mouse pointer
            double f = (scale / oldScale) - 1;
            double dx = e.getX() - (this.getWidth() / 2.0 + offsetX);
            double dy = e.getY() - (this.getHeight() / 2.0 + offsetY);

            offsetX -= dx * f;
            offsetY -= dy * f;

            // Constrain only if not tracking (tracking handles its own positioning)
            constrainCamera();
        });

        // Mouse Press (Pan or Track)
        this.setOnMousePressed((MouseEvent e) -> {
            validInteraction = true;
            lastMouseX = e.getX();
            lastMouseY = e.getY();

            if (nodesRef != null) {
                Node hit = getNodeAt(e.getX(), e.getY());
                if (hit != null) {
                    // CLICK ON NODE -> START TRACKING
                    draggedNode = hit;
                    hit.isDragging = true;
                    hit.userX = hit.x;
                    hit.userY = hit.y;

                    // Set Tracking Target (Render loop handles the rest)
                    trackedNode = hit;
                    targetScale = null; // Tracking overrides manual target
                }
            }
        });

        this.setOnMouseDragged((MouseEvent e) -> {
            if (!validInteraction)
                return;

            double deltaX = e.getX() - lastMouseX;
            double deltaY = e.getY() - lastMouseY;

            if (draggedNode != null) {
                // Drag Node
                draggedNode.userX += deltaX / scale;
                draggedNode.userY += deltaY / scale;
                draggedNode.isDragging = true;
            } else {
                // Pan World -> BREAK TRACKING
                trackedNode = null;
                targetScale = null;

                offsetX += deltaX;
                offsetY += deltaY;
                constrainCamera();
            }

            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

        this.setOnMouseReleased((MouseEvent e) -> {
            validInteraction = false;
            if (draggedNode != null) {
                draggedNode.isDragging = false;
                draggedNode = null;
            }
        });
    }

    public void setNodes(List<Node> nodes) {
        this.nodesRef = nodes;
        // Recalculate limits when nodes change
        updateZoomLimits();
    }

    private void updateZoomLimits() {
        if (nodesRef == null || nodesRef.isEmpty()) {
            minScaleLimit = 0.02;
            return;
        }

        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        synchronized (nodesRef) {
            for (Node n : nodesRef) {
                if (n.x < minX)
                    minX = n.x;
                if (n.x > maxX)
                    maxX = n.x;
                if (n.y < minY)
                    minY = n.y;
                if (n.y > maxY)
                    maxY = n.y;
            }
        }

        if (minX == Double.MAX_VALUE)
            return;

        double contentW = (maxX - minX) + 1000;
        double contentH = (maxY - minY) + 1000;

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

    private Node getNodeAt(double mx, double my) {
        double visualScale = 1.0;
        double adaptiveThreshold = 0.4;
        if (scale < adaptiveThreshold) {
            visualScale = Math.pow(adaptiveThreshold / scale, 0.8);
        }

        double wcX = this.getWidth() / 2.0;
        double wcY = this.getHeight() / 2.0;
        double worldMouseX = (mx - wcX - offsetX) / scale;
        double worldMouseY = (my - wcY - offsetY) / scale;

        if (nodesRef == null)
            return null;

        for (int i = nodesRef.size() - 1; i >= 0; i--) {
            Node n = nodesRef.get(i);

            double nx = n.x;
            double ny = n.y;

            double drawScale = Math.min(visualScale, 5.0);
            double w = 220 * drawScale;
            double h = 100 * drawScale;

            if (worldMouseX >= nx - w / 2 && worldMouseX <= nx + w / 2 &&
                    worldMouseY >= ny - h / 2 && worldMouseY <= ny + h / 2) {
                return n;
            }
        }
        return null;
    }

    public void setHighlightedNodeIds(Set<String> ids) {
        this.highlightedNodeIds = (ids == null) ? new HashSet<>() : ids;
    }

    private void centerView() {
        offsetX = 0;
        offsetY = 0;
    }

    public void render(List<Node> nodes, List<Edge> edges) {
        this.edgesRef = edges;
        updateZoomLimits();

        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.clearRect(0, 0, w, h);
        drawGrid(w, h);

        // --- CAMERA TRACKING ---
        if (trackedNode != null) {
            // 1. Calculate Bounds (BFS)
            double[] bounds = calculateClusterBounds(trackedNode);
            double minX = bounds[0];
            double maxX = bounds[1];
            double minY = bounds[2];
            double maxY = bounds[3];

            // 2. Fit to Screen
            double clusterW = maxX - minX;
            double clusterH = maxY - minY;

            // Padding
            double padding = 200.0;
            double viewW = w - padding;
            double viewH = h - padding;

            if (viewW < 100)
                viewW = 100;
            if (viewH < 100)
                viewH = 100;
            if (clusterW < 1)
                clusterW = 1;
            if (clusterH < 1)
                clusterH = 1;

            double sX = viewW / clusterW;
            double sY = viewH / clusterH;

            double targetS = Math.min(sX, sY);
            targetS = Math.max(0.01, Math.min(2.5, targetS));

            // 3. Center Target (World Space)
            double targetCx = (minX + maxX) / 2.0;
            double targetCy = (minY + maxY) / 2.0;

            // --- CINEMATIC INTERPOLATION ---
            // Current State derived from Offset/Scale.
            // ScreenCenter(0,0 relative to center) = WorldCenter * Scale + Offset
            // 0 = WorldCenter * Scale + Offset => WorldCenter = -Offset / Scale
            double currentCx = -offsetX / scale;
            double currentCy = -offsetY / scale;

            // Smooth Factor (Lower = Slower/Smoother)
            double alpha = 0.08;

            // Interpolate World Position (Moves camera focus straight to target)
            double newCx = currentCx + (targetCx - currentCx) * alpha;
            double newCy = currentCy + (targetCy - currentCy) * alpha;

            // Interpolate Zoom
            scale += (targetS - scale) * alpha;

            // Re-Apply to Offset (Reverse derivation)
            // Offset = -WorldCenter * Scale
            offsetX = -newCx * scale;
            offsetY = -newCy * scale;

        } else if (targetScale != null) {
            // Manual API Zoom Target (Simple Lerp)
            scale += (targetScale - scale) * 0.1;
            if (Math.abs(scale - targetScale) < 0.001) {
                scale = targetScale;
                targetScale = null;
            }
        }

        // Apply Transform
        gc.save();
        gc.translate(w / 2.0 + offsetX, h / 2.0 + offsetY);
        gc.scale(scale, scale);

        // Calculate Visual Scale derived from camera Zoom
        double visualScale = 1.0;
        double adaptiveThreshold = 0.4;
        if (scale < adaptiveThreshold) {
            visualScale = Math.pow(adaptiveThreshold / scale, 0.8);
        }

        double ox = w / 2.0 + offsetX;
        double oy = h / 2.0 + offsetY;
        double viewLeft = -ox / scale;
        double viewRight = (w - ox) / scale;
        double viewTop = -oy / scale;
        double viewBottom = (h - oy) / scale;

        double cullingBuffer = 1000.0;

        // 1. Draw Edges
        gc.setLineWidth(2.0 * visualScale);

        for (Edge e : edges) {
            if (e.source == null || e.target == null)
                continue;

            double x1 = e.source.x;
            double y1 = e.source.y;
            double x2 = e.target.x;
            double y2 = e.target.y;

            Node target = e.target;
            int maxLvl = (target.rootNode != null) ? target.rootNode.maxSubtreeLevel : 5;
            if (maxLvl < 1)
                maxLvl = 1;

            double ratio = (double) target.level / (double) maxLvl;
            if (ratio > 1.0)
                ratio = 1.0;

            double brightness = 0.9 - (ratio * 0.5);
            if (brightness < 0.3)
                brightness = 0.3;

            gc.setStroke(Color.gray(brightness, 0.8));
            gc.strokeLine(x1, y1, x2, y2);
        }

        // 2. Draw Nodes
        double baseNodeWidth = 220;

        for (Node n : nodes) {
            if (n.x < viewLeft - cullingBuffer || n.x > viewRight + cullingBuffer ||
                    n.y < viewTop - cullingBuffer || n.y > viewBottom + cullingBuffer) {
                continue;
            }

            double drawScale = Math.min(visualScale, 5.0);
            double layoutX = n.x;
            double layoutY = n.y;

            double screenPixelWidth = baseNodeWidth * drawScale * scale;
            boolean showText = screenPixelWidth > 30;

            gc.save();
            gc.translate(layoutX, layoutY);
            gc.scale(drawScale, drawScale);

            double nodeWidth = baseNodeWidth;
            double nodeHeight;
            List<String> lines = null;

            if (showText) {
                String textContent = n.label;
                if (textContent == null)
                    textContent = "";
                lines = wrapText(textContent, 30);

                double lineHeight = 14;
                double padding = 20;
                double titleHeight = lines.size() * lineHeight;
                double footerHeight = 20;
                nodeHeight = titleHeight + footerHeight + padding;
                if (nodeHeight < 60)
                    nodeHeight = 60;
            } else {
                nodeHeight = 60;
            }

            double lx = -nodeWidth / 2;
            double ly = -nodeHeight / 2;

            boolean isHighlighted = highlightedNodeIds.contains(n.id);
            boolean isTracked = (n == trackedNode);

            if (isHighlighted) {
                RadialGradient glow = new RadialGradient(0, 0, 0, 0, nodeWidth / 1.5, false, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#FFD700", 0.4)), new Stop(1, Color.TRANSPARENT));
                gc.setFill(glow);
                gc.fillOval(-nodeWidth / 1.5, -nodeWidth / 1.5, nodeWidth * 1.33, nodeWidth * 1.33);
            }

            gc.setFill(Color.rgb(35, 35, 45, 0.95));
            gc.fillRect(lx, ly, nodeWidth, nodeHeight);

            if (isHighlighted) {
                gc.setStroke(Color.GOLD);
                gc.setLineWidth(2.0);
            } else if (isTracked) {
                gc.setStroke(Color.web("#888888"));
                gc.setLineWidth(1.5);
            } else {
                gc.setStroke(Color.web("#666666"));
                gc.setLineWidth(1.0);
            }
            gc.strokeRect(lx, ly, nodeWidth, nodeHeight);

            if (showText) {
                gc.setTextBaseline(javafx.geometry.VPos.TOP);
                gc.setFont(titleFont);
                gc.setFill(isHighlighted ? Color.GOLD : Color.WHITE);

                double currentY = ly + 10;
                double paddingX = 10;
                for (String line : lines) {
                    gc.fillText(line, lx + paddingX, currentY);
                    currentY += 14;
                }

                currentY = ly + nodeHeight - 18;
                gc.setFont(statsFont);
                String statsStr = n.isThread ? String.format("^%d  *%d", n.score, n.commentCount)
                        : String.format("^%d", n.score);
                gc.setFill(Color.web("#AAAAAA"));
                gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
                gc.fillText(statsStr, lx + paddingX, currentY);

                if (n.author != null) {
                    gc.setFill(Color.web("#FFFFFF", 0.4));
                    gc.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
                    gc.fillText(n.author.startsWith("u/") ? n.author : "u/" + n.author, lx + nodeWidth - paddingX,
                            currentY);
                    gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
                }
            } else {
                if (n.isThread) {
                    gc.setFill(Color.web("#555555"));
                    gc.fillOval(-8, -8, 16, 16);
                }
            }

            gc.restore();
        }

        gc.restore();
    } // End Render

    private List<String> wrapText(String text, int charsPerLine) {
        List<String> lines = new java.util.ArrayList<>();
        String[] words = text.split("\\s+");
        StringBuilder currentLine = new StringBuilder();

        for (String word : words) {
            if (currentLine.length() + word.length() + 1 > charsPerLine) {
                if (currentLine.length() > 0) {
                    lines.add(currentLine.toString());
                    currentLine = new StringBuilder();
                }
                while (word.length() > charsPerLine) {
                    lines.add(word.substring(0, charsPerLine));
                    word = word.substring(charsPerLine);
                }
                currentLine.append(word);
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        return lines;
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

        gc.setStroke(Color.rgb(8, 8, 8));
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

    private void constrainCamera() {
        if (nodesRef == null || nodesRef.isEmpty())
            return;

        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        synchronized (nodesRef) {
            for (Node n : nodesRef) {
                if (n.x < minX)
                    minX = n.x;
                if (n.x > maxX)
                    maxX = n.x;
                if (n.y < minY)
                    minY = n.y;
                if (n.y > maxY)
                    maxY = n.y;
            }
        }

        if (minX == Double.MAX_VALUE)
            return;

        double buffer = 1000.0 * (1.0 / scale);

        double currentWorldX = -offsetX / scale;
        double currentWorldY = -offsetY / scale;

        double clampedX = Math.max(minX - buffer, Math.min(maxX + buffer, currentWorldX));
        double clampedY = Math.max(minY - buffer, Math.min(maxY + buffer, currentWorldY));

        if (clampedX != currentWorldX) {
            offsetX = -clampedX * scale;
        }
        if (clampedY != currentWorldY) {
            offsetY = -clampedY * scale;
        }
    }
}
