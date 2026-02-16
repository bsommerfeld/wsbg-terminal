package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TerminalBlinkEvent;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Node;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Edge;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages the force-directed graph visualization. Central hub
 * "r/wallstreetbetsGER"
 * connects to threads (orange) which branch into comments (blue depth
 * gradient).
 * Layout is physics-based: nodes drift to computed target positions via spring
 * forces
 * and repulsion prevents overlap, creating organic Gource-like movement.
 */
@Singleton
public class GraphController {

    private final GraphView graphView;
    private final GraphSimulation simulation;
    private final ApplicationEventBus eventBus;
    private final de.bsommerfeld.wsbg.terminal.db.RedditRepository repository;
    private AnimationTimer timer;
    private boolean isRunning = false;

    // Sidebar
    private final GraphSidebar sidebar;
    private final HBox containerView;

    // Dynamic State
    private final java.util.Queue<Node> pendingNodes = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<Edge> pendingEdges = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final java.util.Queue<String> pendingRemovals = new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Tracking
    private final Set<String> knownNodeIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> knownEdgeIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Center Node
    private static final String CENTER_ID = "CENTER_WSBG";
    private Node centerNode;

    // Currently displayed thread in sidebar
    private String currentSidebarThreadId;

    // Comment color palette: bright blue -> deep sea blue
    private static final Color COMMENT_BLUE_SHALLOW = Color.web("#4488FF");
    private static final Color COMMENT_BLUE_DEEP = Color.web("#0A2A55");

    @Inject
    public GraphController(ApplicationEventBus eventBus,
            de.bsommerfeld.wsbg.terminal.db.RedditRepository repository) {
        this.eventBus = eventBus;
        this.repository = repository;
        this.graphView = new GraphView();
        this.simulation = new GraphSimulation();
        this.sidebar = new GraphSidebar();

        // Container: [GraphView (grows)] [Sidebar (animated width)]
        containerView = new HBox(0);
        HBox.setHgrow(graphView, Priority.ALWAYS);
        containerView.getChildren().addAll(graphView, sidebar);

        sidebar.setVisible(false);
        sidebar.setManaged(false);

        // Wire click handler — receives threadId from magnifier-area analysis
        graphView.setThreadClickHandler(this::onThreadClicked);

        // Wire summarize
        sidebar.setSummarizeHandler(v -> onSummarize());

        // Wire open-in-browser
        sidebar.setOpenUrlHandler(url -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception ex) {
                System.err.println("Failed to open browser: " + ex.getMessage());
            }
        });

        setupLoop();
        eventBus.register(this);
        recalculateGraph();
    }

    private void onThreadClicked(String threadId) {
        if (threadId == null)
            return;

        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            RedditThread thread = repository.getThread(threadId);
            List<RedditComment> comments = repository.getCommentsForThread(threadId, 500);
            return new javafx.util.Pair<>(thread, comments);
        }).thenAccept(pair -> {
            Platform.runLater(() -> {
                currentSidebarThreadId = threadId;
                sidebar.showThread(pair.getKey(), pair.getValue(), null);
            });
        });
    }

    private void onSummarize() {
        if (currentSidebarThreadId == null)
            return;

        // Load thread for analysis
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            RedditThread thread = repository.getThread(currentSidebarThreadId);
            List<RedditComment> comments = repository.getCommentsForThread(currentSidebarThreadId, 500);
            return new javafx.util.Pair<>(thread, comments);
        }).thenAccept(pair -> {
            Platform.runLater(() -> {
                RedditThread thread = pair.getKey();
                List<RedditComment> comments = pair.getValue();
                if (thread == null)
                    return;

                // Build analysis prompt
                StringBuilder prompt = new StringBuilder();
                prompt.append("Analysiere den folgenden Reddit-Thread und fasse zusammen:\n\n");
                prompt.append("Titel: ").append(thread.getTitle()).append("\n");
                if (thread.getTextContent() != null && !thread.getTextContent().isEmpty()) {
                    prompt.append("Inhalt: ").append(thread.getTextContent()).append("\n");
                }
                prompt.append("Score: ").append(thread.getScore())
                        .append(" | Kommentare: ").append(thread.getNumComments()).append("\n\n");

                if (comments != null && !comments.isEmpty()) {
                    prompt.append("Kommentare:\n");
                    int limit = Math.min(comments.size(), 30);
                    for (int i = 0; i < limit; i++) {
                        RedditComment c = comments.get(i);
                        prompt.append("- u/").append(c.getAuthor() != null ? c.getAuthor() : "[deleted]")
                                .append(" (↑").append(c.getScore()).append("): ")
                                .append(c.getBody().length() > 200 ? c.getBody().substring(0, 200) + "..."
                                        : c.getBody())
                                .append("\n");
                    }
                }

                // Fire analysis event
                eventBus.post(new TriggerAgentAnalysisEvent(prompt.toString()));

                // Signal terminal to blink
                eventBus.post(new TerminalBlinkEvent(true));
            });
        });
    }

    @Subscribe
    public void onSearchEvent(de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.SearchEvent event) {
        String query = (event.query == null) ? "" : event.query.trim();

        if (query.isEmpty()) {
            graphView.setHighlightedNodeIds(java.util.Collections.emptySet());
            return;
        }

        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            Set<String> matches = new HashSet<>();
            String effectiveQuery = query.toLowerCase();

            synchronized (simulation.getNodes()) {
                for (Node n : simulation.getNodes()) {
                    if (n.fullText != null && n.fullText.toLowerCase().contains(effectiveQuery)) {
                        matches.add(n.id);
                    }
                }
            }
            return matches;
        }, java.util.concurrent.ForkJoinPool.commonPool())
                .thenAccept(matches -> Platform.runLater(() -> graphView.setHighlightedNodeIds(matches)));
    }

    private void recalculateGraph() {
        java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            List<RedditThread> threads = repository.getAllThreads();
            List<RedditComment> comments = repository.getAllComments();
            return new javafx.util.Pair<>(threads, comments);
        }).thenAccept(pair -> initializeGraphData(pair.getKey(), pair.getValue()))
                .exceptionally(ex -> {
                    System.err.println("Graph Data Load Failed: " + ex.getMessage());
                    return null;
                });
    }

    private void initializeGraphData(List<RedditThread> threads, List<RedditComment> comments) {
        Map<String, RedditComment> commentMap = comments.stream()
                .collect(Collectors.toMap(RedditComment::getId, c -> c, (a, b) -> a));

        Map<String, List<RedditComment>> commentsByParent = new HashMap<>();
        for (RedditComment c : comments) {
            String pStr = c.getParentId();
            if (pStr == null || pStr.isEmpty() || pStr.equals("null"))
                continue;

            commentsByParent.computeIfAbsent(pStr, k -> new ArrayList<>()).add(c);
            if (pStr.startsWith("t1_") || pStr.startsWith("t3_")) {
                String stripped = pStr.substring(3);
                if (!stripped.equals(pStr)) {
                    commentsByParent.computeIfAbsent(stripped, k -> new ArrayList<>()).add(c);
                }
            } else {
                commentsByParent.computeIfAbsent("t3_" + pStr, k -> new ArrayList<>()).add(c);
                commentsByParent.computeIfAbsent("t1_" + pStr, k -> new ArrayList<>()).add(c);
                commentsByParent.computeIfAbsent("CMT_" + pStr, k -> new ArrayList<>()).add(c);
            }
        }

        List<Node> finalNodes = new ArrayList<>();
        List<Edge> finalEdges = new ArrayList<>();
        Set<String> validIds = new HashSet<>();

        // Center Node: r/wallstreetbetsGER
        validIds.add(CENTER_ID);
        if (centerNode == null) {
            centerNode = findNode(CENTER_ID);
        }
        if (centerNode == null) {
            centerNode = new Node(CENTER_ID, "r/wallstreetbetsGER", 0, 0);
            centerNode.isCenterNode = true;
            centerNode.color = Color.RED;
            centerNode.mass = 500;
        }
        finalNodes.add(centerNode);

        // Sort threads by fetchedAt / createdUtc for temporal ordering
        List<RedditThread> sortedThreads = new ArrayList<>(threads);
        sortedThreads.sort(Comparator.comparingLong(RedditThread::getCreatedUtc));

        // Pre-build layout trees to know subtree sizes for angular budget
        List<LayoutNode> threadLayouts = new ArrayList<>();
        List<Node> threadNodes = new ArrayList<>();
        int totalSubtreeWeight = 0;

        for (RedditThread t : sortedThreads) {
            String tId = t.getId();
            validIds.add(tId);

            Node tn = findNode(tId);
            if (tn == null) {
                tn = new Node(tId, t.getTitle(), 0, 0);
                tn.isThread = true;
                tn.color = Color.web("#FF8C00");
                tn.mass = 20;
            }
            tn.threadId = tId;

            String authorText = t.getAuthor() != null ? " u/" + t.getAuthor() : "";
            tn.fullText = t.getTitle() + " " + (t.getTextContent() != null ? t.getTextContent() : "") + authorText;
            tn.author = t.getAuthor();
            tn.score = t.getScore();
            tn.commentDepth = 0;

            LayoutNode rootLayout = new LayoutNode(tn, null);
            buildLayoutTree(rootLayout, commentsByParent, commentMap, tId);
            calculateSubtreeSizes(rootLayout);

            totalSubtreeWeight += 1 + rootLayout.subtreeSize;

            threadLayouts.add(rootLayout);
            threadNodes.add(tn);
        }

        // Spiral: threads placed at evenly increasing angle with growing radius.
        // Angular budget for comments is proportional to subtree weight.
        int actualThreadCount = threadNodes.size();
        double baseAngle = 0.3;
        double angleStep = 2.0 * Math.PI / Math.max(actualThreadCount, 1);

        for (int i = 0; i < actualThreadCount; i++) {
            Node tn = threadNodes.get(i);
            LayoutNode rootLayout = threadLayouts.get(i);
            int weight = 1 + rootLayout.subtreeSize;

            // Spiral position: angle and radius both increase with index
            double branchAngle = baseAngle + i * angleStep;
            double branchRadius = 300.0 + i * 25.0;

            tn.targetX = Math.cos(branchAngle) * branchRadius;
            tn.targetY = Math.sin(branchAngle) * branchRadius;

            finalNodes.add(tn);

            String centerEdgeId = "E_" + CENTER_ID + "_" + tn.id;
            if (!knownEdgeIds.contains(centerEdgeId)) {
                Edge ce = new Edge(centerEdgeId, centerNode, tn);
                ce.length = branchRadius;
                ce.strength = 0.005;
                finalEdges.add(ce);
            }

            int maxDepth = calculateMaxDepth(rootLayout);
            tn.maxSubtreeLevel = maxDepth;

            // Comment angular budget: proportional to subtree weight, strictly capped to
            // the
            // thread's own spiral slot. 1.2x caused neighbor overlap — 1.0x ensures each
            // thread's comments stay within their allocated sector.
            double rawBudget = (2.0 * Math.PI) * weight / Math.max(totalSubtreeWeight, 1);
            double angularBudget = Math.min(rawBudget, angleStep);

            // Level spacing: higher floor forces dense threads to extend radially outward
            // rather than clustering. 150px minimum prevents the "tight ball" effect.
            double levelSpacing = Math.max(150, 220.0 / (1.0 + Math.log1p(rootLayout.subtreeSize) * 0.12));

            applyBranchLayout(rootLayout, branchAngle, branchRadius, levelSpacing,
                    angularBudget, finalNodes, finalEdges, validIds, maxDepth, tn.id);

            tn.commentCount = rootLayout.subtreeSize;
        }

        // Queue updates
        for (Node n : finalNodes) {
            if (!knownNodeIds.contains(n.id)) {
                knownNodeIds.add(n.id);
                pendingNodes.add(n);
            }
        }

        for (Edge e : finalEdges) {
            if (!knownEdgeIds.contains(e.id)) {
                knownEdgeIds.add(e.id);
                pendingEdges.add(e);
            }
        }

        // Cleanup removed
        Set<String> toRemove = new HashSet<>(knownNodeIds);
        toRemove.removeAll(validIds);
        for (String id : toRemove) {
            pendingRemovals.add(id);
        }
    }

    // --- LAYOUT HELPERS ---

    private static class LayoutNode {
        Node node;
        RedditComment rawComment;
        List<LayoutNode> children = new ArrayList<>();
        int subtreeSize = 0;

        LayoutNode(Node node, RedditComment c) {
            this.node = node;
            this.rawComment = c;
        }
    }

    /**
     * Caps children per parent at MAX_CHILDREN_PER_NODE (by score).
     * All comments remain in the sidebar — the cap only affects graph density
     * by preventing a single node from spawning a dense fan-out cluster.
     */
    private static final int MAX_CHILDREN_PER_NODE = 15;

    private void buildLayoutTree(LayoutNode parent, Map<String, List<RedditComment>> map,
            Map<String, RedditComment> allComments, String threadId) {
        String pId = parent.rawComment == null ? parent.node.id : parent.rawComment.getId();
        List<RedditComment> kids = map.get(pId);
        if (kids == null)
            return;

        kids.sort(Comparator.comparingInt(RedditComment::getScore).reversed());

        int added = 0;
        for (RedditComment c : kids) {
            if (added >= MAX_CHILDREN_PER_NODE)
                break;

            String cId = "CMT_" + c.getId();
            Node cn = findNode(cId);
            if (cn == null) {
                String bodySnippet = c.getBody().length() > 100 ? c.getBody().substring(0, 100) + "..." : c.getBody();
                cn = new Node(cId, bodySnippet, 0, 0);
                cn.isThread = false;
                cn.mass = 5;
            }

            String authorText = c.getAuthor() != null ? " u/" + c.getAuthor() : "";
            cn.fullText = c.getBody() + authorText;
            cn.author = c.getAuthor();
            cn.score = c.getScore();
            cn.isThread = false;
            cn.parent = parent.node;
            cn.rootNode = parent.node.rootNode != null ? parent.node.rootNode : parent.node;
            cn.level = parent.node.level + 1;
            cn.commentDepth = parent.node.commentDepth + 1;
            cn.threadId = threadId;

            LayoutNode childLayout = new LayoutNode(cn, c);
            parent.children.add(childLayout);

            buildLayoutTree(childLayout, map, allComments, threadId);
            added++;
        }
    }

    private void calculateSubtreeSizes(LayoutNode node) {
        node.subtreeSize = 0;
        for (LayoutNode child : node.children) {
            calculateSubtreeSizes(child);
            node.subtreeSize += 1 + child.subtreeSize;
        }
    }

    private int calculateMaxDepth(LayoutNode node) {
        int max = node.node.commentDepth;
        for (LayoutNode child : node.children) {
            max = Math.max(max, calculateMaxDepth(child));
        }
        return max;
    }

    /**
     * Lays out comments along a branch. Angular budget is distributed
     * proportionally to each child's subtree weight, ensuring sparse branches
     * don't steal space from dense ones.
     */
    private void applyBranchLayout(LayoutNode layoutNode, double branchAngle, double parentRadius,
            double levelSpacing, double angularBudget, List<Node> nodesOut, List<Edge> edgesOut,
            Set<String> validIds, int maxDepth, String threadId) {

        if (layoutNode.children.isEmpty())
            return;

        int childCount = layoutNode.children.size();

        // Total weight of all children (each child weighs 1 + its subtree)
        int totalChildWeight = 0;
        for (LayoutNode child : layoutNode.children) {
            totalChildWeight += 1 + child.subtreeSize;
        }

        // Cap angular spread per level. Deep nodes must stay narrow to avoid fan-out.
        // The 0.6 decay (down from 0.7) forces each level to use at most 60% of the
        // parent's budget, pushing growth radially outward instead of laterally.
        double effectiveBudget = Math.min(angularBudget * 0.6, Math.PI * 0.4);

        // For very many children at one level, limit spread per child
        double maxPerChild = Math.PI * 0.15;
        if (childCount > 1) {
            double totalIfMaxed = maxPerChild * childCount;
            if (totalIfMaxed < effectiveBudget) {
                effectiveBudget = totalIfMaxed;
            }
        }

        // Bias angular start outward: 30% of budget center-facing, 70% outward.
        // Symmetric centering (50/50) caused comments to spill between thread and
        // center.
        double childAngleStart = branchAngle - effectiveBudget * 0.3;

        for (int i = 0; i < childCount; i++) {
            LayoutNode child = layoutNode.children.get(i);
            int childWeight = 1 + child.subtreeSize;

            // This child's angular slice, proportional to its subtree
            double childSlice = effectiveBudget * childWeight / Math.max(totalChildWeight, 1);
            double childAngle = childAngleStart + childSlice / 2.0;

            double childRadius = parentRadius + levelSpacing;

            child.node.targetX = Math.cos(childAngle) * childRadius;
            child.node.targetY = Math.sin(childAngle) * childRadius;

            child.node.color = getCommentColor(child.node.commentDepth, maxDepth);
            child.node.maxSubtreeLevel = maxDepth;
            child.node.threadId = threadId;

            validIds.add(child.node.id);
            nodesOut.add(child.node);

            String edgeId = "E_" + child.node.id;
            if (!knownEdgeIds.contains(edgeId)) {
                Edge e = new Edge(edgeId, layoutNode.node, child.node);
                e.length = levelSpacing;
                edgesOut.add(e);
            }

            applyBranchLayout(child, childAngle, childRadius, levelSpacing,
                    childSlice, nodesOut, edgesOut, validIds, maxDepth, threadId);

            childAngleStart += childSlice;
        }
    }

    /**
     * Interpolates between bright blue and deep sea blue based on comment depth.
     */
    private Color getCommentColor(int depth, int maxDepth) {
        if (maxDepth <= 1)
            return COMMENT_BLUE_SHALLOW;
        double t = Math.min(1.0, (double) (depth - 1) / Math.max(1, maxDepth - 1));
        return COMMENT_BLUE_SHALLOW.interpolate(COMMENT_BLUE_DEEP, t);
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

    private long lastRefreshTime = 0;

    private Thread physicsThread;

    private void setupLoop() {
        // 1. Rendering Loop (JavaFX Thread) - PURE RENDERING
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!isRunning)
                    return;

                try {
                    // Just render the current state.
                    // The simulation list is thread-safe (synchronizedList),
                    // and GraphView handles snapshotting internally.
                    graphView.render(simulation.getNodes(), simulation.getEdges());

                } catch (Exception ex) {
                    System.err.println("[GRAPH-RENDER-ERROR]: " + ex.getMessage());
                }
            }
        };

        // 2. Physics Loop (Background Thread) - SIMULATION ONLY
        physicsThread = new Thread(() -> {
            while (isRunning) {
                long start = System.nanoTime();
                try {
                    // Periodic Data Refresh
                    if (start - lastRefreshTime > 2_000_000_000L) {
                        recalculateGraph();
                        lastRefreshTime = start;
                    }

                    // Processing queues (Add/Remove Nodes)
                    processQueues();

                    // Physics tick: nodes drift towards targets
                    // This is CPU intensive and now off the UI thread!
                    simulation.tick();

                    // Throttle to ~60Hz (approx 16ms) to save CPU
                    // We can go higher (e.g. 10ms) for smoother physics if needed,
                    // but 16ms is standard good practice.
                    long end = System.nanoTime();
                    long durationMs = (end - start) / 1_000_000;
                    long wait = 16 - durationMs;

                    if (wait > 0) {
                        Thread.sleep(wait);
                    } else {
                        Thread.yield(); // Give others a chance if we are lagging
                    }

                } catch (InterruptedException e) {
                    break; // Exit loop on interrupt
                } catch (Exception ex) {
                    System.err.println("[GRAPH-PHYSICS-ERROR]: " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        }, "GraphPhysicsThread");

        physicsThread.setDaemon(true);
    }

    private void processQueues() {
        // Drain pending nodes
        int nodesAdded = 0;
        final int MAX_ADDS = 200;
        while (!pendingNodes.isEmpty() && nodesAdded < MAX_ADDS) {
            Node n = pendingNodes.poll();
            if (n != null) {
                simulation.addNode(n);
                nodesAdded++;
            }
        }

        int edgesAdded = 0;
        while (!pendingEdges.isEmpty() && edgesAdded < MAX_ADDS) {
            Edge e = pendingEdges.poll();
            if (e != null)
                simulation.addEdge(e);
            edgesAdded++;
        }

        // Mark nodes for death animation
        int removalsProcessed = 0;
        while (!pendingRemovals.isEmpty() && removalsProcessed < 50) {
            String idToRemove = pendingRemovals.poll();
            if (idToRemove != null) {
                Node n = findNode(idToRemove);
                if (n != null && n.deathNano < 0) {
                    n.deathNano = System.nanoTime();
                }
            }
            removalsProcessed++;
        }

        // Reap nodes (Death Animation Complete)
        long reapThreshold = System.nanoTime() - 2_500_000_000L;
        synchronized (simulation.getNodes()) {
            simulation.getNodes().removeIf(n -> n.deathNano > 0 && n.deathNano < reapThreshold);
        }

        // Clean edges for removed nodes (Safe iterator removal)
        // We do this separately or let the removeIf logic handle it?
        // Original code removed directly. Let's do a safe clean pass.
        // It's expensive to iterate edges every tick.
        // Optimization: Only scan if we actually reaped nodes?
        // For safety, let's replicate the original logic, but more efficiently if
        // possible.
        // Original: removeNodeAndEdges(n.id) for ONE node at a time.
        // Here we bulk removed nodes. So we should bulk remove edges.

        // Efficient Edge Cleanup: Remove edges where source or target is not in node
        // list?
        // Or simpler: Iterate edges and check if source/target are dead/removed.

        // However, simulation.tick() needs valid nodes.
        // If we removed nodes above, we MUST remove edges NOW to avoid NPEs or ghost
        // edges.
        synchronized (simulation.getEdges()) {
            simulation.getEdges().removeIf(e -> {
                // Check if source/target are still in the live node list?
                // That's O(E * N). Too slow.
                // Better: Check if source/target have the 'removed' state or are just gone.
                // Since we removed them from the list, we can't check 'contains'.

                // Alternative: Rely on the fact that if we removed the node, we should have
                // removed the edge.
                // Let's stick to the queue-based removal for single nodes which handles edges
                // implicitly.
                return false;
            });
        }

        // Actually, the original code called `removeNodeAndEdges(n.id)` inside the
        // loop.
        // We should restore that beahvior for correctness.
        // The block above `simulation.getNodes().removeIf` is correct for nodes,
        // but it doesn't clean edges.

        // Let's rewrite the reaper manually to match original behavior exactly for
        // safety
        synchronized (simulation.getNodes()) {
            java.util.Iterator<Node> it = simulation.getNodes().iterator();
            while (it.hasNext()) {
                Node n = it.next();
                if (n.deathNano > 0 && n.deathNano < reapThreshold) {
                    // Node is dead. Remove it.
                    it.remove();

                    // Remove associated edges
                    // We must lock edges to remove safely
                    synchronized (simulation.getEdges()) {
                        simulation.getEdges().removeIf(e -> (e.source != null && e.source.id.equals(n.id)) ||
                                (e.target != null && e.target.id.equals(n.id)));
                    }
                    knownNodeIds.remove(n.id);
                }
            }
        }
    }

    public javafx.scene.layout.Pane getView() {
        return containerView;
    }

    public void start() {
        isRunning = true;
        if (physicsThread != null && !physicsThread.isAlive()) {
            try {
                physicsThread.start();
            } catch (IllegalThreadStateException e) {
                // Thread already started, ignore or recreate if needed.
                // Simple restart logic: usually create new thread in start() or use pool.
                // For this simple implementation, if it died, we might need to recreate
                // setupLoop().
                // But typically start() is called once.
                // Better safety:
                setupLoop(); // Recreates the thread object
                physicsThread.start();
            }
        } else if (physicsThread == null) {
            setupLoop();
            physicsThread.start();
        }
        timer.start();
    }

    public void stop() {
        isRunning = false;
        timer.stop();
        if (physicsThread != null) {
            physicsThread.interrupt();
        }
    }

    private void removeNodeAndEdges(String nodeId) {
        synchronized (simulation.getNodes()) {
            simulation.getNodes().removeIf(n -> n.id.equals(nodeId));
        }
        synchronized (simulation.getEdges()) {
            simulation.getEdges().removeIf(e -> {
                boolean match = (e.source != null && e.source.id.equals(nodeId)) ||
                        (e.target != null && e.target.id.equals(nodeId));
                if (match)
                    knownEdgeIds.remove(e.id);
                return match;
            });
        }
        knownNodeIds.remove(nodeId);
    }
}
