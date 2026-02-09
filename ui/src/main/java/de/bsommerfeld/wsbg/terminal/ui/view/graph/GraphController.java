package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.ui.view.news.NewsViewModel;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.ToggleGraphViewEvent;
import de.bsommerfeld.wsbg.terminal.agent.service.TopicExtractionService;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Node;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Edge;

import javafx.animation.AnimationTimer;
import javafx.scene.paint.Color;
import javafx.collections.ListChangeListener;
import javafx.scene.shape.Circle;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import java.util.ArrayList;
import java.util.Comparator;

@Singleton
public class GraphController {

    private final GraphView graphView;
    private final GraphSimulation simulation;
    private final NewsViewModel newsViewModel;
    private final ApplicationEventBus eventBus;
    private final TopicExtractionService topicService;
    private AnimationTimer timer;
    private boolean isRunning = false;

    // Dynamic State
    private Map<String, Integer> activeTopics = new HashMap<>(); // Topic -> Count config
    // We keep nextThreadIndex for incremental stability if needed, though we sort
    // now.
    private int nextThreadIndex = 0;

    private final java.util.Queue<Node> pendingNodes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<Edge> pendingEdges = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<String> pendingRemovals = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Tracking
    private final de.bsommerfeld.wsbg.terminal.db.RedditRepository repository;
    private final Set<String> processedThreads = new HashSet<>();
    private final Set<String> knownNodeIds = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Global ID Tracker
    private final Set<String> knownEdgeIds = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Global Edge Tracker

    @Inject
    public GraphController(NewsViewModel newsViewModel, ApplicationEventBus eventBus,
            TopicExtractionService topicService, de.bsommerfeld.wsbg.terminal.db.RedditRepository repository) {
        this.newsViewModel = newsViewModel;
        this.eventBus = eventBus;
        this.topicService = topicService;
        this.repository = repository;
        this.graphView = new GraphView();
        this.simulation = new GraphSimulation();

        // Initial Loop Setup
        setupLoop();

        eventBus.register(this);

        // Listen to new threads -> Full Rebuild (Cleanest for dynamic clustering)
        this.newsViewModel.getThreads().addListener((ListChangeListener<RedditThread>) c -> {
            recalculateGraph();
        });

        // Initial Load - from DB directly for "Entire Database" view
        recalculateGraph();
    }

    @Subscribe
    public void onSearchEvent(de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.SearchEvent event) {
        String query = (event.query == null) ? "" : event.query.trim();

        if (query.isEmpty()) {
            graphView.setHighlightedNodeIds(java.util.Collections.emptySet());
            return;
        }

        // Async Search to avoid UI lag if checking many items
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Set<String> matches = new HashSet<>();
            String effectiveQuery = query.toLowerCase();

            // Iterate over all nodes in the simulation
            synchronized (simulation.getNodes()) {
                for (Node n : simulation.getNodes()) {
                    // Unified Search: fullText now contains Title + Content + u/Author
                    if (n.fullText != null && n.fullText.toLowerCase().contains(effectiveQuery)) {
                        matches.add(n.id);
                    }
                }
            }
            return matches;
        }, java.util.concurrent.ForkJoinPool.commonPool())
                .thenAccept(matches -> {
                    javafx.application.Platform.runLater(() -> {
                        graphView.setHighlightedNodeIds(matches);
                    });
                });
    }

    private void recalculateGraph() {
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            List<RedditThread> threads = repository.getAllThreads();
            List<RedditComment> comments = repository.getAllComments();
            return new javafx.util.Pair<>(threads, comments);
        }).thenAccept(pair -> {
            initializeGraphData(pair.getKey(), pair.getValue());
        }).exceptionally(ex -> {
            System.err.println("Graph Data Load Failed: " + ex.getMessage());
            ex.printStackTrace();
            return null;
        });
    }

    private void initializeGraphData(List<RedditThread> threads, List<RedditComment> comments) {
        // --- STATIC HIERARCHICAL GRAPH LAYOUT ---
        // 1. Data Indexing
        Map<String, RedditThread> threadMap = threads.stream()
                .collect(Collectors.toMap(RedditThread::getId, t -> t, (a, b) -> a));

        Map<String, RedditComment> commentMap = comments.stream()
                .collect(Collectors.toMap(c -> c.getId(), c -> c, (a, b) -> a));

        // Indexed by MULTIPLE possible parent keys to ensure we find them
        Map<String, List<RedditComment>> commentsByParent = new HashMap<>();
        for (RedditComment c : comments) {
            String pStr = c.getParentId();
            if (pStr == null || pStr.isEmpty() || pStr.equals("null"))
                continue;

            // 1. Index by raw parent ID (e.g. "t3_12345")
            commentsByParent.computeIfAbsent(pStr, k -> new ArrayList<>()).add(c);

            // 2. Index by stripped parent ID (e.g. "12345")
            if (pStr.startsWith("t1_") || pStr.startsWith("t3_")) {
                String stripped = pStr.substring(3);
                // Avoid double adding if pStr was already clean (unlikely but safe)
                if (!stripped.equals(pStr)) {
                    commentsByParent.computeIfAbsent(stripped, k -> new ArrayList<>()).add(c);
                }
            } else {
                // This is less common but if Thread ID is stored as "t3_123" and comment has
                // "123"
                commentsByParent.computeIfAbsent("t3_" + pStr, k -> new ArrayList<>()).add(c);
                commentsByParent.computeIfAbsent("t1_" + pStr, k -> new ArrayList<>()).add(c);
                commentsByParent.computeIfAbsent("CMT_" + pStr, k -> new ArrayList<>()).add(c);
            }
        }

        // Batch Queue for simulation updates (Thread-safe)
        List<Node> finalNodes = new ArrayList<>();
        List<Edge> finalEdges = new ArrayList<>();
        Set<String> validIds = new HashSet<>();

        // 2. Thread Placement (Global Spiral)
        List<RedditThread> sortedThreads = new ArrayList<>(threads);
        // Sort by CreatedUTC for stability
        sortedThreads.sort(Comparator.comparingLong(RedditThread::getCreatedUtc));

        int threadIndex = 0;

        for (RedditThread t : sortedThreads) {
            String tId = t.getId();
            validIds.add(tId);

            Node tn = findNode(tId);
            if (tn == null) {
                tn = new Node(tId, t.getTitle(), 0, 0);
                tn.isThread = true;
                tn.color = Color.web("#FFD700");
                tn.mass = 100;
                tn.radius = 80;
            }

            // Spiral Position (Re-calculated to enforce spacing)
            double spacing = 2500.0;
            double angle = threadIndex * 2.39996; // Golden Angle
            double dist = spacing * Math.sqrt(threadIndex);

            // Force verify/update position
            tn.x = Math.cos(angle) * dist;
            tn.y = Math.sin(angle) * dist;

            threadIndex++;

            // Text Update
            String authorText = t.getAuthor() != null ? " u/" + t.getAuthor() : "";
            tn.fullText = t.getTitle() + " " + (t.getTextContent() != null ? t.getTextContent() : "") + authorText;
            tn.author = t.getAuthor();
            tn.score = t.getScore();

            finalNodes.add(tn);

            // 3. Recursive Comment Layout (Radial Tree)
            LayoutNode rootLayout = new LayoutNode(tn, null);
            buildLayoutTree(rootLayout, commentsByParent, commentMap);

            // Calculate sizes
            calculateSubtreeSizes(rootLayout);

            // Apply Layout
            applyRadialLayout(rootLayout, 0, Math.PI * 2, 600.0, tn.x, tn.y, finalNodes, finalEdges, validIds);

            // Set comment count
            tn.commentCount = rootLayout.subtreeSize;
        }

        // 4. Update Simulation State
        for (Node n : finalNodes) {
            if (!knownNodeIds.contains(n.id)) {
                knownNodeIds.add(n.id);
                pendingNodes.add(n);
            }
        }

        for (Edge e : finalEdges) {
            String eid = e.id;
            if (!knownEdgeIds.contains(eid)) {
                knownEdgeIds.add(eid);
                pendingEdges.add(e);
            }
        }

        // Cleanup Metadata
        Set<String> allIds = new HashSet<>(knownNodeIds);
        allIds.removeAll(validIds);
        for (String id : allIds) {
            pendingRemovals.add(id);
        }
    }

    // --- LAYOUT HELPERS ---

    private static class LayoutNode {
        Node node;
        RedditComment rawComment; // Null if thread
        List<LayoutNode> children = new ArrayList<>();
        int subtreeSize = 0;

        LayoutNode(Node node, RedditComment c) {
            this.node = node;
            this.rawComment = c;
        }
    }

    private void buildLayoutTree(LayoutNode parent, Map<String, List<RedditComment>> map,
            Map<String, RedditComment> allComments) {
        String pId = parent.rawComment == null ? parent.node.id : parent.rawComment.getId();
        List<RedditComment> kids = map.get(pId);
        if (kids == null)
            return;

        // Sort chronologically
        kids.sort(Comparator.comparingLong(RedditComment::getCreatedUtc));

        for (RedditComment c : kids) {
            String cId = "CMT_" + c.getId();
            Node cn = findNode(cId);
            if (cn == null) {
                String bodySnippet = c.getBody().length() > 100 ? c.getBody().substring(0, 100) + "..." : c.getBody();
                cn = new Node(cId, bodySnippet, 0, 0);
                cn.isThread = false;
                cn.mass = 10;
                cn.radius = 20;
                cn.color = Color.web("#4CAF50");
            }

            // Update Data
            String authorText = c.getAuthor() != null ? " u/" + c.getAuthor() : "";
            cn.fullText = c.getBody() + authorText;
            cn.author = c.getAuthor();
            cn.score = c.getScore();
            cn.isThread = false;
            cn.parent = parent.node;
            cn.rootNode = parent.node.rootNode != null ? parent.node.rootNode : parent.node;
            cn.level = parent.node.level + 1;

            LayoutNode childLayout = new LayoutNode(cn, c);
            parent.children.add(childLayout);

            buildLayoutTree(childLayout, map, allComments);
        }
    }

    private void calculateSubtreeSizes(LayoutNode node) {
        node.subtreeSize = 0;
        for (LayoutNode child : node.children) {
            calculateSubtreeSizes(child);
            node.subtreeSize += 1 + child.subtreeSize;
        }
    }

    private void applyRadialLayout(LayoutNode layoutNode, double startAngle, double endAngle,
            double radius, double centerX, double centerY,
            List<Node> nodesOut, List<Edge> edgesOut, Set<String> validIds) {

        if (layoutNode.children.isEmpty())
            return;

        double currentAngle = startAngle;
        double totalWeight = 0;
        for (LayoutNode child : layoutNode.children) {
            totalWeight += (child.subtreeSize + 1);
        }

        for (LayoutNode child : layoutNode.children) {
            double weight = (child.subtreeSize + 1);
            double share = weight / totalWeight;
            double sweep = (endAngle - startAngle) * share;

            double midAngle = currentAngle + sweep / 2.0;

            double cx = centerX + Math.cos(midAngle) * radius;
            double cy = centerY + Math.sin(midAngle) * radius;

            child.node.x = cx;
            child.node.y = cy;
            // Reset velocity just in case
            child.node.vx = 0;
            child.node.vy = 0;

            validIds.add(child.node.id);
            nodesOut.add(child.node);

            String edgeId = "E_" + child.node.id;
            if (!knownEdgeIds.contains(edgeId)) {
                Edge e = new Edge(edgeId, layoutNode.node, child.node);
                e.length = 200; // Visual length only
                edgesOut.add(e);
            }

            // Recurse with larger radius for next generation
            applyRadialLayout(child, currentAngle, currentAngle + sweep, radius + 250, centerX, centerY, nodesOut,
                    edgesOut, validIds);

            currentAngle += sweep;
        }
    }

    private Node findNode(String id) {
        synchronized (simulation.getNodes()) {
            for (Node n : simulation.getNodes()) {
                if (n.id.equals(id))
                    return n;
            }
        }
        return null;
    }

    private Color getColorForTopic(String topic) {
        switch (topic) {
            case "SILBER":
                return Color.web("#C0C0C0");
            case "GOLD":
                return Color.web("#FFD700");
            case "GME":
                return Color.web("#FF4500");
            case "AMC":
                return Color.web("#FF0000");
            case "TESLA":
                return Color.web("#E82127");
            case "NVIDIA":
                return Color.web("#76B900");
            case "BITCOIN":
            case "CRYPTO":
                return Color.web("#F7931A");
            default:
                int hash = topic.hashCode();
                return Color.hsb(Math.abs(hash) % 360, 0.7, 0.9);
        }
    }

    private long lastRefreshTime = 0;

    private void setupLoop() {
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!isRunning)
                    return;

                if (now - lastRefreshTime > 5_000_000_000L) {
                    recalculateGraph();
                    lastRefreshTime = now;
                }

                try {
                    int nodesAdded = 0;
                    while (!pendingNodes.isEmpty() && nodesAdded < 200) {
                        Node n = pendingNodes.poll();
                        if (n != null)
                            simulation.addNode(n);
                        nodesAdded++;
                    }
                    int edgesAdded = 0;
                    while (!pendingEdges.isEmpty() && edgesAdded < 200) {
                        Edge e = pendingEdges.poll();
                        if (e != null)
                            simulation.addEdge(e);
                        edgesAdded++;
                    }

                    int removalsProcessed = 0;
                    while (!pendingRemovals.isEmpty() && removalsProcessed < 50) {
                        String idToRemove = pendingRemovals.poll();
                        if (idToRemove != null) {
                            removeNodeAndEdges(idToRemove);
                        }
                        removalsProcessed++;
                    }

                    // physics tick removed

                    graphView.setNodes(simulation.getNodes());
                    graphView.render(simulation.getNodes(), simulation.getEdges());

                } catch (Exception ex) {
                    System.err.println("[GRAPH-RENDER-ERROR]: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        };
    }

    public GraphView getView() {
        return graphView;
    }

    public void start() {
        isRunning = true;
        timer.start();
    }

    public void stop() {
        isRunning = false;
        timer.stop();
    }

    private void removeNodeAndEdges(String nodeId) {
        synchronized (simulation.getNodes()) {
            simulation.getNodes().removeIf(n -> n.id.equals(nodeId));
        }
        synchronized (simulation.getEdges()) {
            simulation.getEdges().removeIf(e -> {
                boolean match = (e.source != null && e.source.id.equals(nodeId)) ||
                        (e.target != null && e.target.id.equals(nodeId));
                if (match) {
                    knownEdgeIds.remove(e.id);
                }
                return match;
            });
        }
        knownNodeIds.remove(nodeId);
    }
}
