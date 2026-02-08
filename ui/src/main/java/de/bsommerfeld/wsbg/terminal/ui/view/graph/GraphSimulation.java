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
        // Constants tuned for "Universe" Cluster behavior
        double repulsion = 10_000_000.0;
        double damping = 0.85;
        double maxVelocity = 50.0;
        double maxForce = 1000.0;

        // Snapshot nodes to avoid ConcurrentModification & allow safe Parallel
        // processing
        List<Node> nodeSnapshot;
        synchronized (nodes) {
            nodeSnapshot = new ArrayList<>(nodes);
        }

        if (nodeSnapshot.isEmpty())
            return;

        // 1. Build Spatial Grid (Sequential - fast enough)
        Map<Long, Region> grid = new HashMap<>();
        for (Node n : nodeSnapshot) {
            long key = getRegionKey(n.x, n.y);
            long gx = (long) Math.floor(n.x / CELL_SIZE);
            long gy = (long) Math.floor(n.y / CELL_SIZE);
            grid.computeIfAbsent(key, k -> new Region(gx, gy)).addNode(n);
        }

        // 2. Calculate Repulsion Forces (Parallel - Distribute Workload)
        nodeSnapshot.parallelStream().forEach(n1 -> {
            double fx = 0;
            double fy = 0;

            // Gravity to center (Global containment)
            double distToCenter = Math.sqrt(n1.x * n1.x + n1.y * n1.y);
            if (distToCenter > 1.0) {
                double gravity = 0.0001;
                fx -= n1.x * gravity;
                fy -= n1.y * gravity;
            }

            long myGx = (long) Math.floor(n1.x / CELL_SIZE);
            long myGy = (long) Math.floor(n1.y / CELL_SIZE);

            // Iterate all populated regions
            for (Region region : grid.values()) {
                long dx = Math.abs(region.gx - myGx);
                long dy = Math.abs(region.gy - myGy);

                if (dx <= 1 && dy <= 1) {
                    // Neighbor Region: Exact Collision
                    for (Node n2 : region.nodes) {
                        if (n1 == n2)
                            continue;

                        double rx = n1.x - n2.x;
                        double ry = n1.y - n2.y;
                        double distSq = rx * rx + ry * ry;
                        double dist = Math.sqrt(distSq);

                        if (dist < 0.1) {
                            dist = 0.1;
                            rx = 0.1;
                        }

                        // Local Collision Repulsion
                        double effectiveDist = dist;
                        if (effectiveDist < 100.0)
                            effectiveDist = 100.0;

                        double force = repulsion / (effectiveDist * effectiveDist);
                        if (force > maxForce)
                            force = maxForce;

                        fx += (rx / dist) * force;
                        fy += (ry / dist) * force;
                    }
                } else {
                    // Distant Region: Center of Mass Approximation (Barnes-Hut simplification)
                    double rCx = region.getCenterX();
                    double rCy = region.getCenterY();

                    double rx = n1.x - rCx;
                    double ry = n1.y - rCy;
                    double distSq = rx * rx + ry * ry;
                    double dist = Math.sqrt(distSq);

                    if (dist < 0.1)
                        dist = 0.1;

                    double effectiveDist = dist;
                    if (effectiveDist < 100.0)
                        effectiveDist = 100.0;

                    // Force weighted by region mass (number of nodes)
                    double force = (repulsion * region.totalMass) / (effectiveDist * effectiveDist);
                    if (force > maxForce * region.totalMass)
                        force = maxForce * region.totalMass;

                    fx += (rx / dist) * (force);
                    fy += (ry / dist) * (force);
                }
            }

            // Apply accumulated force to velocity
            n1.vx += fx / n1.mass;
            n1.vy += fy / n1.mass;

            // Cap Velocity
            double vSq = n1.vx * n1.vx + n1.vy * n1.vy;
            if (vSq > maxVelocity * maxVelocity) {
                double v = Math.sqrt(vSq);
                n1.vx = (n1.vx / v) * maxVelocity;
                n1.vy = (n1.vy / v) * maxVelocity;
            }
        });

        // 3. Spring Forces (Sequential to avoid race conditions on edge updates)
        synchronized (edges) {
            for (Edge edge : edges) {
                Node n1 = edge.source;
                Node n2 = edge.target;

                double dx = n2.x - n1.x;
                double dy = n2.y - n1.y;
                double dist = Math.sqrt(dx * dx + dy * dy);
                if (dist < 0.1)
                    dist = 0.1;

                double displacement = dist - edge.length;
                double force = displacement * edge.strength;

                double fx = (dx / dist) * force;
                double fy = (dy / dist) * force;

                n1.vx += fx / n1.mass;
                n1.vy += fy / n1.mass;
                n2.vx -= fx / n2.mass;
                n2.vy -= fy / n2.mass;
            }
        }

        // 4. Update Positions (Parallel)
        nodeSnapshot.parallelStream().forEach(n -> {
            if (n.isDragging) {
                n.x = n.userX;
                n.y = n.userY;
                n.vx = 0;
                n.vy = 0;
            } else {
                n.vx *= damping;
                n.vy *= damping;
                n.x += n.vx;
                n.y += n.vy;
            }
        });
    }
}
