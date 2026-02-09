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
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;

public class GraphView extends Pane {

    private final Canvas canvas;
    private final GraphicsContext gc;

    // Camera Transform
    private double offsetX = 0;
    private double offsetY = 0;
    private double scale = 0.15; // Default to zoomed out view

    // Dynamic Zoom Limits
    private double minScaleLimit = 0.02; // Will be updated based on content
    private static final double MAX_SCALE_LIMIT = 5.0; // Fixed max zoom (close-up)

    // Interaction State
    private double lastMouseX, lastMouseY;
    private boolean validInteraction = false;
    private Set<String> highlightedNodeIds = new HashSet<>();

    private List<Node> nodesRef; // Reference for hit testing
    private List<Edge> edgesRef; // Reference for drawing

    // Font Cache
    private final Font titleFont = Font.font("Fira Code", FontWeight.BOLD, 16);
    private final Font statsFont = Font.font("Fira Code", FontWeight.NORMAL, 12);

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

    private void setupEvents() {
        // Scroll to Zoom
        this.setOnScroll((ScrollEvent e) -> {
            e.consume();
            if (e.getDeltaY() == 0)
                return;

            double zoomFactor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
            double oldScale = scale;
            scale *= zoomFactor;

            // Apply Hard Limits
            scale = Math.max(minScaleLimit, Math.min(MAX_SCALE_LIMIT, scale));

            // Zoom towards mouse pointer
            double f = (scale / oldScale) - 1;
            double dx = e.getX() - (this.getWidth() / 2.0 + offsetX);
            double dy = e.getY() - (this.getHeight() / 2.0 + offsetY);

            offsetX -= dx * f;
            offsetY -= dy * f;
        });

        // Mouse Press (Pan Only)
        this.setOnMousePressed((MouseEvent e) -> {
            validInteraction = true;
            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

        this.setOnMouseDragged((MouseEvent e) -> {
            if (!validInteraction)
                return;

            double deltaX = e.getX() - lastMouseX;
            double deltaY = e.getY() - lastMouseY;

            // Simple Pan
            offsetX += deltaX;
            offsetY += deltaY;

            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

        this.setOnMouseReleased((MouseEvent e) -> {
            validInteraction = false;
        });
    }

    public void setNodes(List<Node> nodes) {
        this.nodesRef = nodes;
        // Recalculate limits when nodes change
        updateZoomLimits();
    }

    private void updateZoomLimits() {
        if (nodesRef == null || nodesRef.isEmpty()) {
            minScaleLimit = 0.001; // Allow deep zoom out if empty
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

        double contentW = (maxX - minX) + 2000;
        double contentH = (maxY - minY) + 2000;

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
        offsetX = 0;
        offsetY = 0;
    }

    // --- VISUAL EFFECTS SYSTEM ---
    private static class VisualEffect {
        double x, y;
        Color color;
        double progress = 1.0; // 1.0 -> 0.0
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
        // Only show effect if we are NOT in card view (zoomed out)
        if (350 * scale > 60)
            return;

        double size = isThread ? 90.0 : 36.0; // 4.5x normal size (20 or 8)
        activeEffects.add(new VisualEffect(x, y, Color.RED, size));
    }

    public void addNodeCreationEffect(double x, double y, boolean isThread) {
        // Only show effect if we are NOT in card view (zoomed out)
        if (350 * scale > 60)
            return;

        double size = isThread ? 90.0 : 36.0; // 4.5x normal size
        activeEffects.add(new VisualEffect(x, y, Color.LIMEGREEN, size));
    }

    private void renderEffects() {
        if (activeEffects.isEmpty())
            return;

        double decayRate = 0.02; // Adjust speed here

        for (VisualEffect effect : activeEffects) {
            effect.progress -= decayRate;
            if (effect.progress <= 0) {
                activeEffects.remove(effect);
                continue;
            }

            double currentRadius = effect.initialSize * effect.progress;

            // Draw
            gc.save();
            gc.translate(effect.x, effect.y);

            // Opacity fades with size
            gc.setGlobalAlpha(effect.progress);
            gc.setFill(effect.color);
            gc.fillOval(-currentRadius, -currentRadius, currentRadius * 2, currentRadius * 2);

            gc.restore();
        }
    }

    public void render(List<Node> nodes, List<Edge> edges) {
        this.edgesRef = edges;
        updateZoomLimits();

        double w = canvas.getWidth();
        double h = canvas.getHeight();

        gc.clearRect(0, 0, w, h);
        drawGrid(w, h);

        // Apply Transform
        gc.save();
        gc.translate(w / 2.0 + offsetX, h / 2.0 + offsetY);
        gc.scale(scale, scale);

        // Visual Scale Logic - Greatly reduced to prevent "fat" look
        double visualScale = 1.0;

        // Calculate bounds for culling
        double ox = w / 2.0 + offsetX;
        double oy = h / 2.0 + offsetY;
        double viewLeft = -ox / scale;
        double viewRight = (w - ox) / scale;
        double viewTop = -oy / scale;
        double viewBottom = (h - oy) / scale;

        // Tighten culling to ensure only visible items are processed
        double cullingBuffer = 500.0;

        // 1. Draw Edges
        gc.setLineWidth(1.0 / scale); // Hairline width

        for (Edge e : edges) {
            if (e.source == null || e.target == null)
                continue;

            double x1 = e.source.x;
            double y1 = e.source.y;
            double x2 = e.target.x;
            double y2 = e.target.y;

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

            gc.setStroke(Color.gray(brightness, 0.6));
            gc.strokeLine(x1, y1, x2, y2);
        }

        // 2. Draw Nodes
        double baseNodeWidth = 350; // Larger for readability

        for (Node n : nodes) {
            if (n.x < viewLeft - cullingBuffer || n.x > viewRight + cullingBuffer ||
                    n.y < viewTop - cullingBuffer || n.y > viewBottom + cullingBuffer) {
                continue;
            }

            double screenPixelWidth = baseNodeWidth * scale; // True screen size
            boolean showText = screenPixelWidth > 60; // Only show text if wide enough
            boolean showShape = screenPixelWidth > 2; // Show dot if visible

            if (!showShape)
                continue;

            double layoutX = n.x;
            double layoutY = n.y;

            gc.save();
            gc.translate(layoutX, layoutY);

            if (showText) {
                // Detailed Card View
                List<String> lines = null;
                String textContent = n.label != null ? n.label : "";
                lines = wrapText(textContent, 35); // Wrap to ~35 chars

                double lineHeight = 20; // Increased for font size 16
                double padding = 24;
                double titleHeight = lines.size() * lineHeight;
                double height = titleHeight + padding + 30;
                if (height < 80)
                    height = 80;

                double lx = -baseNodeWidth / 2;
                double ly = -height / 2;

                boolean isHighlighted = highlightedNodeIds.contains(n.id);

                if (isHighlighted) {
                    RadialGradient glow = new RadialGradient(0, 0, 0, 0, baseNodeWidth / 1.5, false,
                            CycleMethod.NO_CYCLE,
                            new Stop(0, Color.web("#FFD700", 0.4)), new Stop(1, Color.TRANSPARENT));
                    gc.setFill(glow);
                    gc.fillOval(-baseNodeWidth / 1.5, -baseNodeWidth / 1.5, baseNodeWidth * 1.33, baseNodeWidth * 1.33);
                }

                gc.setFill(Color.rgb(35, 35, 45, 0.95));
                gc.fillRect(lx, ly, baseNodeWidth, height);

                gc.setStroke(isHighlighted ? Color.GOLD : Color.web("#666666"));
                gc.setLineWidth(1.0 / scale); // Thin border
                gc.strokeRect(lx, ly, baseNodeWidth, height);

                // Text
                gc.setTextBaseline(javafx.geometry.VPos.TOP);
                gc.setFont(titleFont);
                gc.setFill(isHighlighted ? Color.GOLD : Color.WHITE);
                double cy = ly + 14;
                double paddingX = 14;
                for (String s : lines) {
                    gc.fillText(s, lx + paddingX, cy);
                    cy += 20;
                }

                // Stats
                double currentY = ly + height - 24;
                gc.setFont(statsFont);
                String statsStr = n.isThread ? String.format("^%d  *%d", n.score, n.commentCount)
                        : String.format("^%d", n.score);
                gc.setFill(Color.web("#AAAAAA"));
                gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
                gc.fillText(statsStr, lx + paddingX, currentY);

                if (n.author != null) {
                    gc.setFill(Color.web("#FFFFFF", 0.4));
                    gc.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
                    gc.fillText(n.author.startsWith("u/") ? n.author : "u/" + n.author, lx + baseNodeWidth - paddingX,
                            currentY);
                    gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
                }

            } else {
                // Dot View
                double dotRadius = n.isThread ? 20.0 : 8.0;
                double activeRadius = dotRadius;

                boolean isHighlighted = highlightedNodeIds.contains(n.id);

                if (isHighlighted) {
                    activeRadius *= 4.5; // Pop out heavily

                    RadialGradient glow = new RadialGradient(0, 0, 0, 0, activeRadius * 1.5, false,
                            CycleMethod.NO_CYCLE,
                            new Stop(0, Color.web("#FFD700", 0.6)), new Stop(1, Color.TRANSPARENT));
                    gc.setFill(glow);
                    gc.fillOval(-activeRadius * 1.5, -activeRadius * 1.5, activeRadius * 3, activeRadius * 3);

                    gc.setFill(Color.GOLD);
                } else {
                    gc.setFill(n.isThread ? Color.GOLD : Color.web("#555555"));
                }

                gc.fillOval(-activeRadius, -activeRadius, activeRadius * 2, activeRadius * 2);
            }

            gc.restore();
        }

        // Draw Effects on top
        renderEffects();

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
