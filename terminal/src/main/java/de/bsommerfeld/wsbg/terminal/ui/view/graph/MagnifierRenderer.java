package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Edge;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Node;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.ArcType;

import java.util.List;
import java.util.Set;

/**
 * Renders the glass disc magnifier and ambient glow effects for the graph.
 * Stateless — all visual state is passed in through {@link CameraState}.
 */
class MagnifierRenderer {

    private MagnifierRenderer() {
    }

    /**
     * Snapshot of the camera and visual state needed for magnifier rendering.
     * Avoids coupling this renderer to GraphView internals.
     */
    record CameraState(
            double scale,
            double offsetX,
            double offsetY,
            double magnifierRadius,
            double magnifierZoom,
            Set<String> highlightedNodeIds,
            String selectedThreadId,
            LifelinessFunction liveliness,
            DisplayPositionFunction displayPosition) {
    }

    @FunctionalInterface
    interface LifelinessFunction {
        double apply(Node n, long now);
    }

    @FunctionalInterface
    interface DisplayPositionFunction {
        double[] apply(Node n, long now);
    }

    /**
     * Radial ambient glow centered on the content centroid. Purple-blue
     * gradient extending toward the window edges, giving depth to the
     * dark background.
     */
    static void drawAmbientGlow(GraphicsContext gc, double w, double h,
            double offsetX, double offsetY, boolean hasNodes) {
        if (!hasNodes)
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
     * Glass disc magnifier at cursor position. Renders a 1:1 view inside
     * a simple, clean glass pane with a uniform rim and subtle gradient.
     */
    static void drawIceMagnifier(GraphicsContext gc, List<Node> nodes, List<Edge> edges,
            double w, double h, long now,
            double mx, double my, CameraState cam) {

        if (mx < 0 || my < 0 || mx > w || my > h)
            return;

        double r = cam.magnifierRadius();

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
        LinearGradient glassBody = new LinearGradient(
                mx - r, my - r, mx + r, my + r,
                false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(255, 255, 255, 0.08)),
                new Stop(1.0, Color.rgb(200, 220, 255, 0.02)));

        gc.setFill(glassBody);
        gc.fillOval(mx - r, my - r, r * 2, r * 2);

        // 3. Render Graph Content (1:1 scale)
        double magScale = cam.scale() * cam.magnifierZoom();
        double ox = w / 2.0 + cam.offsetX();
        double oy = h / 2.0 + cam.offsetY();

        double worldX = (mx - ox) / cam.scale();
        double worldY = (my - oy) / cam.scale();

        gc.save();
        gc.translate(mx, my);
        gc.scale(magScale, magScale);
        gc.translate(-worldX, -worldY);

        // Edges in magnifier
        gc.setLineWidth(1.0 / magScale);
        for (Edge e : edges) {
            if (e.source == null || e.target == null)
                continue;

            double targetLife = cam.liveliness().apply(e.target, now);
            if (targetLife <= 0.001)
                continue;

            double[] targetPos = cam.displayPosition().apply(e.target, now);

            gc.setGlobalAlpha(Math.max(0.4, Math.min(targetLife, 0.8)));
            gc.setStroke(Color.rgb(200, 200, 220, 0.6));
            gc.setLineWidth(1.5 / magScale);
            gc.strokeLine(e.source.x, e.source.y, targetPos[0], targetPos[1]);
        }

        // Nodes in magnifier
        gc.setGlobalAlpha(1.0);
        for (Node n : nodes) {
            double life = cam.liveliness().apply(n, now);
            if (life <= 0.001)
                continue;

            double[] pos = cam.displayPosition().apply(n, now);
            double dotAlpha = Math.max(0, (life - 0.2) / 0.8);
            double dotRadius = n.isCenterNode ? 20.0 : n.isThread ? 20.0 : 8.0;
            double activeR = dotRadius * (0.4 + 0.6 * dotAlpha);

            // Ensure nodes are visible as clickable targets inside the magnifier
            activeR = Math.max(activeR, 5.0);

            Color baseColor;
            if (n.isCenterNode) {
                baseColor = Color.RED;
            } else if (n.isThread) {
                baseColor = Color.web("#FF8C00");
            } else {
                baseColor = n.color != null ? n.color : Color.web("#4488FF");
            }

            boolean isHighlighted = cam.highlightedNodeIds().contains(n.id);
            boolean isSelected = cam.selectedThreadId() != null && n.threadId != null
                    && n.threadId.equals(cam.selectedThreadId());

            gc.setGlobalAlpha(1.0);

            // Selection indicator halo
            gc.setFill(Color.rgb(255, 255, 255, 0.25));
            gc.fillOval(pos[0] - activeR * 1.6, pos[1] - activeR * 1.6, activeR * 3.2, activeR * 3.2);

            if (isHighlighted) {
                gc.setFill(Color.web("#FFD700", 0.6));
                gc.fillOval(pos[0] - activeR * 1.8, pos[1] - activeR * 1.8, activeR * 3.6, activeR * 3.6);
            } else if (isSelected) {
                gc.setFill(Color.web("#FFFFFF", 0.5));
                gc.fillOval(pos[0] - activeR * 1.6, pos[1] - activeR * 1.6, activeR * 3.2, activeR * 3.2);
            }

            // Base Body
            gc.setFill(baseColor);
            gc.fillOval(pos[0] - activeR, pos[1] - activeR, activeR * 2, activeR * 2);

            // Inner Lit Glow
            gc.setFill(Color.rgb(255, 255, 255, 0.2));
            gc.fillOval(pos[0] - activeR, pos[1] - activeR, activeR * 2, activeR * 2);

            // Sharp Specular Highlight
            gc.setFill(Color.rgb(255, 255, 255, 0.9));
            double shineR = activeR * 0.45;
            gc.fillOval(pos[0] - activeR * 0.5, pos[1] - activeR * 0.6, shineR, shineR * 0.7);

            // Glass Rim / Border
            gc.setStroke(Color.rgb(200, 240, 255, 0.8));
            gc.setLineWidth(1.5);
            gc.strokeOval(pos[0] - activeR, pos[1] - activeR, activeR * 2, activeR * 2);
        }

        gc.setGlobalAlpha(1.0);
        gc.restore(); // restore local transform
        gc.restore(); // restore clip/save

        // --- Simple Glass Rim (Screen Space) ---

        // Uniform clean rim
        gc.setLineWidth(1.5);
        gc.setStroke(Color.rgb(200, 230, 255, 0.25));
        gc.strokeOval(mx - r, my - r, r * 2, r * 2);

        // Subtle reflection gradient (top-left) hinting at glossiness
        gc.setLineWidth(1.0);
        gc.setStroke(new LinearGradient(
                mx - r, my - r, mx, my,
                false, CycleMethod.NO_CYCLE,
                new Stop(0.0, Color.rgb(255, 255, 255, 0.4)),
                new Stop(1.0, Color.TRANSPARENT)));
        gc.strokeArc(mx - r, my - r, r * 2, r * 2, 110, 80, ArcType.OPEN);
    }
}
