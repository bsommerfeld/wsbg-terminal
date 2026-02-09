package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javafx.scene.paint.Color;
import java.util.Map;
import java.util.HashMap;

public class GraphSimulation {

    public static class Node {
        public String id;
        public String label; // Kept for legacy/debug
        // Rich Data
        public String fullText;
        public String author;
        public int score;
        public int commentCount;
        public boolean isThread;
        public Node parent; // For hierarchy traversal (Counting)

        // Hierarchy Info
        public int level = 0;
        public Node rootNode; // Reference to the thread root
        public int maxSubtreeLevel = 0; // Only meaningful if this node is a root (isThread=true)
        public int childrenCount = 0; // For deterministic placement

        public double x, y;
        public double prevX, prevY; // Optimization
        public double vx, vy;
        public double mass = 1.0;
        public double radius = 10.0;
        public Color color = Color.GRAY;
        public java.util.Set<Color> affiliations = new java.util.HashSet<>();
        public boolean isTopic = false;

        // Interaction
        public boolean isDragging = false;
        public double userX, userY;

        public Node(String id, String label, double x, double y) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
        }
    }

    public static class Edge {
        public String id;
        public Node source;
        public Node target;
        public double length = 100.0;
        public double strength = 0.05;
        public Color color = Color.web("#555555", 0.6);

        public Edge(String id, Node source, Node target) {
            this.id = id;
            this.source = source;
            this.target = target;
        }
    }

    private final List<Node> nodes = Collections.synchronizedList(new ArrayList<>());
    private final List<Edge> edges = Collections.synchronizedList(new ArrayList<>());

    public void addNode(Node node) {
        synchronized (nodes) {
            nodes.add(node);
        }
    }

    public void addEdge(Edge edge) {
        synchronized (edges) {
            edges.add(edge);
        }
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public void clear() {
        nodes.clear();
        edges.clear();
    }

    public void clearEdges() {
        edges.clear();
    }

    private static final double CELL_SIZE = 800.0; // Optimized Region Size for spatial partitioning

    private static class Region {
        long gx, gy;
        double totalMass = 0;
        double sumMassX = 0;
        double sumMassY = 0;
        List<Node> nodes = new ArrayList<>();

        Region(long gx, long gy) {
            this.gx = gx;
            this.gy = gy;
        }

        void addNode(Node n) {
            nodes.add(n);
            totalMass += n.mass;
            sumMassX += n.x * n.mass;
            sumMassY += n.y * n.mass;
        }

        double getCenterX() {
            return (totalMass > 0) ? sumMassX / totalMass : 0;
        }

        double getCenterY() {
            return (totalMass > 0) ? sumMassY / totalMass : 0;
        }
    }

    private long getRegionKey(double x, double y) {
        long gx = (long) Math.floor(x / CELL_SIZE);
        long gy = (long) Math.floor(y / CELL_SIZE);
        return (gx << 32) | (gy & 0xFFFFFFFFL); // Pack coordinates
    }

    public void tick() {
        // Static Graph: No Physics Simulation
    }
}
