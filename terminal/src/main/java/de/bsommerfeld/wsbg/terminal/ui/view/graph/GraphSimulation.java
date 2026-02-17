package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.scene.paint.Color;

/**
 * Force-directed graph simulation. Nodes drift towards target positions
 * via spring forces while maintaining separation through repulsion.
 * Edge connections apply additional spring attraction between linked nodes.
 */
public class GraphSimulation {

    /** Particle in the simulation. Mutable physics state is updated each tick. */
    public static class Node {
        public String id;
        public String label;
        public String fullText;
        public String author;
        public int score;
        public boolean isThread;
        public Node parent;

        // Hierarchy Info
        public int level = 0;
        public Node rootNode;
        public int maxSubtreeLevel = 0;

        // Comment depth within thread (0 = thread, 1 = direct comment, 2+ = deeper)
        public int commentDepth = 0;

        // Thread ID reference for sidebar lookup
        public String threadId;

        // Physics State
        public double x, y;
        public double targetX, targetY;
        public double vx, vy;
        public double mass = 1.0;
        public double radius = 10.0;
        public Color color = Color.GRAY;

        // Central hub flag
        public boolean isCenterNode = false;

        // Birth/death animation. birthNano records creation time; the renderer
        // progressively reveals the node (line grows → dot fades in).
        // deathNano is set when the node starts dying; the renderer plays the
        // reverse animation (comments fade → dot fades → line shrinks).
        public long birthNano = System.nanoTime();
        public long deathNano = -1;

        public Node(String id, String label, double x, double y) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.targetX = x;
            this.targetY = y;
        }
    }

    /**
     * Directed connection between two nodes with configurable rest length and
     * spring strength.
     */
    public static class Edge {
        public String id;
        public Node source;
        public Node target;
        public double length = 150.0;
        public double strength = 0.02;
        public Color color = Color.web("#555555", 0.6);

        public Edge(String id, Node source, Node target) {
            this.id = id;
            this.source = source;
            this.target = target;
        }
    }

    // Simulation collections — synchronized wrappers allow the physics thread
    // to iterate while the data-load future appends.
    private final List<Node> nodes = Collections.synchronizedList(new ArrayList<>());
    private final List<Edge> edges = Collections.synchronizedList(new ArrayList<>());

    // Repulsion force between all node pairs, prevents overlap
    private static final double REPULSION_STRENGTH = 2000.0;
    private static final double MIN_DISTANCE = 60.0;

    // Spring force pulling nodes towards their target position
    private static final double TARGET_SPRING = 0.015;

    // Edge spring force keeping connected nodes at desired distance
    private static final double EDGE_SPRING = 0.008;

    // Velocity damping to prevent oscillation
    private static final double DAMPING = 0.85;

    // Center gravity pulling stragglers back
    private static final double CENTER_GRAVITY = 0.0005;

    /** Thread-safe node insertion. */
    public void addNode(Node node) {
        synchronized (nodes) {
            nodes.add(node);
        }
    }

    /** Thread-safe edge insertion. */
    public void addEdge(Edge edge) {
        synchronized (edges) {
            edges.add(edge);
        }
    }

    /**
     * Returns the backing synchronized node list. Callers should synchronize on it
     * for iteration.
     */
    public List<Node> getNodes() {
        return nodes;
    }

    /**
     * Returns the backing synchronized edge list. Callers should synchronize on it
     * for iteration.
     */
    public List<Edge> getEdges() {
        return edges;
    }

    /**
     * Runs one physics tick. Applies target spring, edge spring, repulsion, and
     * damping.
     * Center node is pinned at origin.
     */
    public void tick() {
        List<Node> snapshot;
        synchronized (nodes) {
            snapshot = new ArrayList<>(nodes);
        }

        int count = snapshot.size();
        if (count == 0)
            return;

        // 1. Target Spring: pull toward target position
        for (Node n : snapshot) {
            if (n.isCenterNode)
                continue;

            double dx = n.targetX - n.x;
            double dy = n.targetY - n.y;
            n.vx += dx * TARGET_SPRING;
            n.vy += dy * TARGET_SPRING;
        }

        // 2. Edge Spring: attraction along edges
        List<Edge> edgeSnapshot;
        synchronized (edges) {
            edgeSnapshot = new ArrayList<>(edges);
        }

        for (Edge e : edgeSnapshot) {
            if (e.source == null || e.target == null)
                continue;

            double dx = e.target.x - e.source.x;
            double dy = e.target.y - e.source.y;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist < 1.0)
                dist = 1.0;

            double force = (dist - e.length) * EDGE_SPRING;
            double fx = (dx / dist) * force;
            double fy = (dy / dist) * force;

            if (!e.source.isCenterNode) {
                e.source.vx += fx;
                e.source.vy += fy;
            }
            if (!e.target.isCenterNode) {
                e.target.vx -= fx;
                e.target.vy -= fy;
            }
        }

        // 3. Node Repulsion: push overlapping nodes apart
        for (int i = 0; i < count; i++) {
            Node a = snapshot.get(i);
            if (a.isCenterNode)
                continue;

            for (int j = i + 1; j < count; j++) {
                Node b = snapshot.get(j);
                if (b.isCenterNode)
                    continue;

                double dx = b.x - a.x;
                double dy = b.y - a.y;
                double distSq = dx * dx + dy * dy;

                double minDist = MIN_DISTANCE;
                double threshold = minDist * 12.0;
                if (distSq > threshold * threshold)
                    continue;

                if (distSq < 1.0) {
                    // Jitter to break deadlock
                    dx = (Math.random() - 0.5) * 2.0;
                    dy = (Math.random() - 0.5) * 2.0;
                    distSq = dx * dx + dy * dy;
                }

                double dist = Math.sqrt(distSq);
                double force = REPULSION_STRENGTH / (distSq + 100.0);

                double fx = (dx / dist) * force;
                double fy = (dy / dist) * force;

                a.vx -= fx;
                a.vy -= fy;
                b.vx += fx;
                b.vy += fy;
            }
        }

        // 4. Center Gravity: gentle pull towards graph center
        double cx = 0, cy = 0;
        for (Node n : snapshot) {
            cx += n.x;
            cy += n.y;
        }
        cx /= count;
        cy /= count;

        for (Node n : snapshot) {
            if (n.isCenterNode)
                continue;
            n.vx += (cx - n.x) * CENTER_GRAVITY;
            n.vy += (cy - n.y) * CENTER_GRAVITY;
        }

        // 5. Apply velocity + damping
        for (Node n : snapshot) {
            if (n.isCenterNode) {
                n.x = 0;
                n.y = 0;
                n.vx = 0;
                n.vy = 0;
                continue;
            }

            n.vx *= DAMPING;
            n.vy *= DAMPING;
            n.x += n.vx;
            n.y += n.vy;
        }
    }
}
