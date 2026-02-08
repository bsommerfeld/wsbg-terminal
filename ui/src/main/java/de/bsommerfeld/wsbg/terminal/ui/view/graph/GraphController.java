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
    private int nextThreadIndex = 0; // Tracks spiral position for new threads

    private final java.util.Queue<Node> pendingNodes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<Edge> pendingEdges = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<String> pendingRemovals = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Tracking
    private final de.bsommerfeld.wsbg.terminal.db.RedditRepository repository; // Changed
    private final Set<String> processedThreads = new HashSet<>();
    private final Set<String> knownNodeIds = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Global ID Tracker
    private final Set<String> knownEdgeIds = java.util.concurrent.ConcurrentHashMap.newKeySet(); // Global Edge Tracker

    @Inject
    public GraphController(NewsViewModel newsViewModel, ApplicationEventBus eventBus,
            TopicExtractionService topicService, de.bsommerfeld.wsbg.terminal.db.RedditRepository repository) { // Changed
        this.newsViewModel = newsViewModel;
        this.eventBus = eventBus;
        this.topicService = topicService;
        this.repository = repository; // Changed
        this.graphView = new GraphView();
        this.simulation = new GraphSimulation();

        // Initial Loop Setup
        setupLoop();

        eventBus.register(this);

        // Listen to new threads -> Full Rebuild (Cleanest for dynamic clustering)
        this.newsViewModel.getThreads().addListener((ListChangeListener<RedditThread>) c -> {
            recalculateGraph();
        });

        // Listen for DB Cleanup or meaningful updates if available.
        // For now, relies on NewsViewModel or explicit reload triggers.
        // User requested listening to Cleanup.
        // If DatabaseService has an event, use it. If not, maybe just periodic refresh
        // or manual?
        // Let's assume re-calculation on NewsViewModel updates covers "new" stuff.
        // For "cleanup", we might need to poll or listen to a generic event.

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
        // --- SOLAR SYSTEM VISUALIZATION (Threads as Cards, Comments as Satellites) ---

        // Batch Lookups for this update cycle
        Map<String, Node> batchLookup = new HashMap<>();

        // 1. Process Threads (Independent Solar System Centers)
        for (RedditThread t : threads) {
            String tId = t.getId();

            // Handle existing nodes
            if (knownNodeIds.contains(tId)) {
                Node existing = findNode(tId);
                if (existing != null) {
                    existing.commentCount = 0; // Will be recalculated in Phase 4
                    existing.score = t.getScore();
                    // Update fullText dynamically in case content changed
                    String authorText = t.getAuthor() != null ? " u/" + t.getAuthor() : "";
                    existing.fullText = t.getTitle() + " " + (t.getTextContent() != null ? t.getTextContent() : "")
                            + authorText;

                    batchLookup.put(tId, existing);
                    batchLookup.put("t3_" + tId, existing);
                }
                continue;
            }

            // Thread Visuals - Card Style
            Node tn = new Node(tId, t.getTitle(), 0, 0); // Pos set below
            String authorText = t.getAuthor() != null ? " u/" + t.getAuthor() : "";
            tn.fullText = t.getTitle() + " " + (t.getTextContent() != null ? t.getTextContent() : "") + authorText;
            tn.author = t.getAuthor();
            tn.score = t.getScore();
            tn.commentCount = 0; // Will be calc in Phase 4
            tn.isThread = true;

            // Phyllotaxis Spiral Placement (Deterministic & Spaced)
            // Golden Angle = ~137.5 degrees = ~2.39996 radians
            double spacing = 600.0; // Space for the solar system
            double angle = nextThreadIndex * 2.39996;
            double dist = spacing * Math.sqrt(nextThreadIndex + 1);

            tn.x = Math.cos(angle) * dist;
            tn.y = Math.sin(angle) * dist;

            nextThreadIndex++;
            tn.mass = 80.0;
            tn.radius = 80;
            tn.color = Color.web("#FFD700");

            // Root Init
            tn.level = 0;
            tn.rootNode = tn;
            tn.childrenCount = 0;
            tn.maxSubtreeLevel = 0;

            // Register multiple keys for robustness
            batchLookup.put(tId, tn);
            batchLookup.put("t3_" + tId, tn);

            knownNodeIds.add(tId);
            pendingNodes.add(tn);
        }

        // 2. Process Comments (Satellites)
        for (RedditComment c : comments) {
            String cId = "CMT_" + c.getId();

            // Handle existing
            if (knownNodeIds.contains(cId)) {
                Node existing = findNode(cId);
                if (existing != null) {
                    existing.score = c.getScore(); // Update score
                    // Update fullText
                    String authorText = c.getAuthor() != null ? " u/" + c.getAuthor() : "";
                    existing.fullText = c.getBody() + authorText;

                    batchLookup.put(cId, existing);
                    batchLookup.put("t1_" + c.getId(), existing);
                    batchLookup.put(c.getId(), existing);
                }
                continue;
            }

            // Fix: Truncate longer logic
            String bodySnippet = c.getBody().length() > 100 ? c.getBody().substring(0, 100) + "..." : c.getBody();

            Node cn = new Node(cId, bodySnippet, 0, 0); // Temp pos
            String authorText = c.getAuthor() != null ? " u/" + c.getAuthor() : "";
            cn.fullText = c.getBody() + authorText;
            cn.author = c.getAuthor();
            cn.score = c.getScore();
            cn.commentCount = 0; // TODO: Calculate children count if possible?
            cn.isThread = false;

            cn.mass = 10.0; // Light mass for comments
            cn.radius = 20;
            // Neutral Card Color (lines will carry the hierarchy)
            cn.color = Color.web("#4CAF50"); // Keep for now, but view will ignore for border if needed

            batchLookup.put(cId, cn);
            batchLookup.put("t1_" + c.getId(), cn);
            batchLookup.put(c.getId(), cn);

            knownNodeIds.add(cId);
            pendingNodes.add(cn);
        }

        // 3. ROBUST LINKING PASS
        for (RedditComment c : comments) {
            String cId = "CMT_" + c.getId();
            Node child = batchLookup.get(cId);
            if (child == null)
                continue; // Should be in lookup

            String parentRef = c.getParentId();
            if (parentRef == null || parentRef.equals("null") || parentRef.isEmpty()) {
                // Orphan Handling
                if (child.x == 0 && child.y == 0) {
                    child.x = (Math.random() - 0.5) * 5000;
                    child.y = (Math.random() - 0.5) * 5000;
                }
                continue;
            }

            // Attempt to resolve Parent Node using multiple strategies
            Node parent = null;

            // Strategy A: Direct Lookup (e.g. "t3_12345" or "t1_67890")
            parent = batchLookup.get(parentRef);

            // Strategy B: Strip Prefix (e.g. "t3_12345" -> "12345")
            if (parent == null && parentRef.length() > 3) {
                parent = batchLookup.get(parentRef.substring(3));
            }

            // Strategy C: Assume Comment Prefix (e.g. "12345" -> "CMT_12345")
            if (parent == null) {
                parent = batchLookup.get("CMT_" + parentRef);
            }

            // Strategy D: Global Search (if somehow not in batchLookup but exists)
            if (parent == null) {
                parent = findNode(parentRef);
                if (parent == null && parentRef.length() > 3)
                    parent = findNode(parentRef.substring(3));
                if (parent == null)
                    parent = findNode("CMT_" + parentRef);
            }

            if (parent == null) {
                // Orphan child (Parent not found in this batch or globally)
                // Just let it float or handle differently.
                continue;
            }

            // Hierarchy Tracking for Counting & Level
            child.parent = parent;
            child.level = parent.level + 1;
            child.rootNode = parent.rootNode != null ? parent.rootNode : parent; // Parent is root if null

            // Update Max Level on Root
            if (child.rootNode != null) {
                child.rootNode.maxSubtreeLevel = Math.max(child.rootNode.maxSubtreeLevel, child.level);
            }

            // ORBIT PLACEMENT (Deterministic Unique Angles)
            if (child.x == 0 && child.y == 0) {
                parent.childrenCount++;
                int idx = parent.childrenCount;

                // "Mitte von 2 Punkten" (Bit-Reversal / Van der Corput Base 2)
                // 1 -> 0.5 (180), 2 -> 0.25 (90), 3 -> 0.75 (270), etc.
                double fraction = 0.0;
                double v = 0.5;
                int n = idx;
                while (n > 0) {
                    if ((n & 1) == 1)
                        fraction += v;
                    n >>= 1;
                    v *= 0.5;
                }

                double angle = fraction * 2.0 * Math.PI;

                // Check if parent is Thread (heavy) or Comment (light) to decide orbit
                double orbitDist = parent.isThread ? 250 : 80;

                child.x = parent.x + Math.cos(angle) * orbitDist;
                child.y = parent.y + Math.sin(angle) * orbitDist;
            }

            // EDGE
            String edgeId = "E_" + cId;
            if (!knownEdgeIds.contains(edgeId)) {
                Edge e = new Edge(edgeId, parent, child);
                e.length = parent.isThread ? 250.0 : 80.0;
                e.strength = parent.isThread ? 0.05 : 0.2;
                // Color is calculated dynamically in GraphView now

                pendingEdges.add(e);
                knownEdgeIds.add(edgeId);
            }
        }

        // 4. RECURSIVE COUNTING (Fix for "0" counts)
        // Iterate only unique nodes to avoid double counting
        java.util.Set<Node> uniqueNodes = new java.util.HashSet<>(batchLookup.values());
        for (Node n : uniqueNodes) {

            if (n.isThread)
                continue; // Only count comments

            // Trace up to thread
            Node root = n;
            int depth = 0;
            while (root != null && !root.isThread && depth < 50) {
                root = root.parent;
                depth++;
            }

            if (root != null && root.isThread) {
                root.commentCount++;
            }
        }

        // 5. MARK AND SWEEP CLEANUP
        // Identify nodes that are currently in the simulation (knownNodeIds) but NOT in
        // the new batch (validIds)
        // usage of 'batchLookup' keys is tricky because it has multiple aliases.
        // We rely on the sets we explicitly added to knownNodeIds:
        // Threads: just the ID (e.g. "123")
        // Comments: "CMT_" + ID

        Set<String> validIds = new HashSet<>();
        for (RedditThread t : threads) {
            validIds.add(t.getId());
        }
        for (RedditComment c : comments) {
            validIds.add("CMT_" + c.getId());
        }

        List<String> toRemove = new ArrayList<>();
        for (String existingId : knownNodeIds) {
            if (!validIds.contains(existingId)) {
                toRemove.add(existingId);
            }
        }

        if (!toRemove.isEmpty()) {
            pendingRemovals.addAll(toRemove);
            System.out.println("[Graph Cleanup] Marking " + toRemove.size() + " nodes for removal.");
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
        // Deterministic Hash to Color or predefined
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
                // Hash
                int hash = topic.hashCode();
                return Color.hsb(Math.abs(hash) % 360, 0.7, 0.9);
        }
    }

    private void addThreadToGraph(RedditThread thread) {
        // Now handled by recalculateGraph for correct clustering
    }

    private long lastRefreshTime = 0;

    private void setupLoop() {
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!isRunning)
                    return;

                // Periodic Data Refresh (Every 5 seconds)
                if (now - lastRefreshTime > 5_000_000_000L) {
                    recalculateGraph();
                    lastRefreshTime = now;
                }

                try {
                    // Drain Batch Queues (Limit per tick)
                    int nodesAdded = 0;
                    while (!pendingNodes.isEmpty() && nodesAdded < 50) {
                        Node n = pendingNodes.poll();
                        if (n != null)
                            simulation.addNode(n);
                        nodesAdded++;
                    }
                    int edgesAdded = 0;
                    while (!pendingEdges.isEmpty() && edgesAdded < 50) {
                        Edge e = pendingEdges.poll();
                        if (e != null)
                            simulation.addEdge(e);
                        edgesAdded++;
                    }

                    // Process Removals
                    int removalsProcessed = 0;
                    while (!pendingRemovals.isEmpty() && removalsProcessed < 50) {
                        String idToRemove = pendingRemovals.poll();
                        if (idToRemove != null) {
                            removeNodeAndEdges(idToRemove);
                        }
                        removalsProcessed++;
                    }

                    simulation.tick();

                    // Update View (Canvas Direct Render)
                    graphView.setNodes(simulation.getNodes());
                    graphView.render(simulation.getNodes(), simulation.getEdges());

                } catch (Exception ex) {
                    System.err.println("[GRAPH-RENDER-ERROR]: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        };

    }

    // Bridge for dragging - We will use the Properties map on the JavaFX node in
    // GraphView
    // Helper to sync drag state is missing.
    // Let's simplify: GraphView simply renders X/Y.
    // If we want drag, we need to update the Simulation Node's X/Y from UI events.
    // I will add a cleaner drag handler in GraphView that accepts a Consumer.

    public GraphView getView() {
        return graphView;
    }

    public void start() {
        isRunning = true;
        timer.start();

        // Setup Drag callback
        // We need to fetch the visual nodes to attach listeners properly if GraphView
        // didn't already
        // Actually GraphView sets properties "dragging", "userX", "userY".
        // Let's iterate visual nodes and update simulation state in the loop.

        // Override the tick loop in GraphController to read from View?
        // No, GraphView is passive.

        // I will modify GraphView to allow passing a callback for drag.
    }

    public void stop() {
        isRunning = false;
        timer.stop();
    }

    private void removeNodeAndEdges(String nodeId) {
        // 1. Remove from Simulation
        // We need to synchronize access to the lists inside Simulation
        synchronized (simulation.getNodes()) {
            simulation.getNodes().removeIf(n -> n.id.equals(nodeId));
        }

        // 2. Remove associated Edges
        // Edge IDs are "E_" + nodeId usually for child links, but we must check
        // source/target
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

        // 3. Remove from trackers
        knownNodeIds.remove(nodeId);
    }
}
