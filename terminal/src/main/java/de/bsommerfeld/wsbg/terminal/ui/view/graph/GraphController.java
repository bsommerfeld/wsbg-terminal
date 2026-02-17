package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TriggerAgentAnalysisEvent;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.TerminalBlinkEvent;
import de.bsommerfeld.wsbg.terminal.ui.event.UiEvents.SearchEvent;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphLayoutEngine.LayoutNode;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Node;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Edge;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.awt.Desktop;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
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
    private final RedditRepository repository;
    private AnimationTimer timer;
    private volatile boolean isRunning = false;

    // Sidebar
    private final GraphSidebar sidebar;
    /** Container: [GraphView (grows)] [Sidebar (animated width)]. */
    private final HBox containerView;

    // Pending additions/removals. Written by the data-load future,
    // drained by the physics thread — ConcurrentLinkedQueue is
    // lock-free and safe for this single-producer/single-consumer pattern.
    private final Queue<Node> pendingNodes = new ConcurrentLinkedQueue<>();
    private final Queue<Edge> pendingEdges = new ConcurrentLinkedQueue<>();
    private final Queue<String> pendingRemovals = new ConcurrentLinkedQueue<>();

    // De-duplication sets — prevent re-queuing nodes/edges across refresh cycles.
    private final Set<String> knownNodeIds = ConcurrentHashMap.newKeySet();
    private final Set<String> knownEdgeIds = ConcurrentHashMap.newKeySet();

    // Center Node
    private static final String CENTER_ID = "CENTER_WSBG";
    private Node centerNode;

    // Currently displayed thread in sidebar
    private String currentSidebarThreadId;

    /**
     * Wires the view, simulation, sidebar, and event handlers.
     * An initial {@link #recalculateGraph()} populates the graph from
     * the repository on construction.
     */
    @Inject
    public GraphController(ApplicationEventBus eventBus,
            RedditRepository repository) {
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
                Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception ex) {
                System.err.println("Failed to open browser: " + ex.getMessage());
            }
        });

        sidebar.setOnCloseHandler(() -> {
            graphView.setSelectedThreadId(null);
            currentSidebarThreadId = null;
        });

        setupLoop();
        eventBus.register(this);

        recalculateGraph();

    }

    /**
     * Loads the selected thread and its comments from the repository
     * (off-thread) and displays them in the sidebar.
     */
    private void onThreadClicked(String threadId) {
        if (threadId == null)
            return;

        CompletableFuture.supplyAsync(() -> {
            RedditThread thread = repository.getThread(threadId);
            List<RedditComment> comments = repository.getCommentsForThread(threadId, 500);
            return new Object[] { thread, comments };
        }).thenAccept(arr -> {
            Platform.runLater(() -> {
                currentSidebarThreadId = threadId;
                sidebar.showThread((RedditThread) arr[0], (List<RedditComment>) arr[1], null);
                graphView.setSelectedThreadId(threadId);
            });
        });
    }

    /**
     * Builds a German analysis prompt from the current sidebar thread
     * (title, body, top 30 comments) and fires it as a
     * {@link TriggerAgentAnalysisEvent}.
     */
    private void onSummarize() {
        if (currentSidebarThreadId == null)
            return;

        // Load thread for analysis
        CompletableFuture.supplyAsync(() -> {
            RedditThread thread = repository.getThread(currentSidebarThreadId);
            List<RedditComment> comments = repository.getCommentsForThread(currentSidebarThreadId, 500);
            return new Object[] { thread, comments };
        }).thenAccept(arr -> {
            Platform.runLater(() -> {
                RedditThread thread = (RedditThread) arr[0];
                List<RedditComment> comments = (List<RedditComment>) arr[1];
                if (thread == null)
                    return;

                // Build analysis prompt
                StringBuilder prompt = new StringBuilder();
                prompt.append("Analysiere den folgenden Reddit-Thread und fasse zusammen:\n\n");
                prompt.append("Titel: ").append(thread.title()).append("\n");
                if (thread.textContent() != null && !thread.textContent().isEmpty()) {
                    prompt.append("Inhalt: ").append(thread.textContent()).append("\n");
                }
                prompt.append("Score: ").append(thread.score())
                        .append(" | Kommentare: ").append(thread.numComments()).append("\n\n");

                if (comments != null && !comments.isEmpty()) {
                    prompt.append("Kommentare:\n");
                    int limit = Math.min(comments.size(), 30);
                    for (int i = 0; i < limit; i++) {
                        RedditComment c = comments.get(i);
                        prompt.append("- u/").append(c.author() != null ? c.author() : "[deleted]")
                                .append(" (↑").append(c.score()).append("): ")
                                .append(c.body().length() > 200 ? c.body().substring(0, 200) + "..."
                                        : c.body())
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

    /**
     * Matches the search query against all node texts and highlights
     * the matching node IDs in the graph view.
     */
    @Subscribe
    public void onSearchEvent(SearchEvent event) {
        String query = (event.query() == null) ? "" : event.query().trim();

        if (query.isEmpty()) {
            graphView.setHighlightedNodeIds(Collections.emptySet());
            return;
        }

        CompletableFuture.supplyAsync(() -> {
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
        }, ForkJoinPool.commonPool())
                .thenAccept(matches -> Platform.runLater(() -> graphView.setHighlightedNodeIds(matches)));
    }

    /**
     * Asynchronously fetches all threads and comments from the repository
     * and feeds them into {@link #initializeGraphData}.
     */
    private void recalculateGraph() {
        CompletableFuture.supplyAsync(() -> {
            List<RedditThread> threads = repository.getAllThreads();
            List<RedditComment> comments = repository.getAllComments();
            return new Object[] { threads, comments };
        }).thenAccept(arr -> initializeGraphData((List<RedditThread>) arr[0], (List<RedditComment>) arr[1]))
                .exceptionally(ex -> {
                    System.err.println("Graph Data Load Failed: " + ex.getMessage());
                    return null;
                });
    }

    /**
     * Converts raw Reddit data into graph nodes and edges. Builds a
     * per-thread radial layout, queues new/updated nodes for the physics
     * thread, and marks removals for death animation.
     */
    private void initializeGraphData(List<RedditThread> threads, List<RedditComment> comments) {
        Map<String, RedditComment> commentMap = comments.stream()
                .collect(Collectors.toMap(RedditComment::id, c -> c, (a, b) -> a));

        Map<String, List<RedditComment>> commentsByParent = new HashMap<>();
        for (RedditComment c : comments) {
            String pStr = c.parentId();
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
        sortedThreads.sort(Comparator.comparingLong(RedditThread::createdUtc));

        // Pre-build layout trees to know subtree sizes for angular budget
        List<LayoutNode> threadLayouts = new ArrayList<>();
        List<Node> threadNodes = new ArrayList<>();
        int totalSubtreeWeight = 0;

        for (RedditThread t : sortedThreads) {
            String tId = t.id();
            validIds.add(tId);

            Node tn = findNode(tId);
            if (tn == null) {
                tn = new Node(tId, t.title(), 0, 0);
                tn.isThread = true;
                tn.color = Color.web("#FF8C00");
                tn.mass = 20;
            }
            tn.threadId = tId;

            String authorText = t.author() != null ? " u/" + t.author() : "";
            tn.fullText = t.title() + " " + (t.textContent() != null ? t.textContent() : "") + authorText;
            tn.author = t.author();
            tn.score = t.score();
            tn.commentDepth = 0;

            LayoutNode rootLayout = new LayoutNode(tn, null);
            GraphLayoutEngine.buildLayoutTree(rootLayout, commentsByParent, commentMap, tId, this::findNode);
            GraphLayoutEngine.calculateSubtreeSizes(rootLayout);

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

            int maxDepth = GraphLayoutEngine.calculateMaxDepth(rootLayout);
            tn.maxSubtreeLevel = maxDepth;

            // Angular budget proportional to subtree weight, capped to the thread's
            // spiral slot. 1.0x prevents neighbor overlap.
            double rawBudget = (2.0 * Math.PI) * weight / Math.max(totalSubtreeWeight, 1);
            double angularBudget = Math.min(rawBudget, angleStep);

            // Higher floor forces dense threads outward. 150px minimum prevents clustering.
            double levelSpacing = Math.max(150, 220.0 / (1.0 + Math.log1p(rootLayout.subtreeSize) * 0.12));

            GraphLayoutEngine.applyBranchLayout(rootLayout, branchAngle, branchRadius, levelSpacing,
                    angularBudget, finalNodes, finalEdges, validIds, maxDepth, tn.id, knownEdgeIds);

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

    /** Searches the simulation's node list by ID. Returns null if absent. */
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

    /**
     * Creates both loops:
     * <ol>
     * <li><b>Render loop</b> (AnimationTimer on the FX thread) — reads the
     * atomic snapshot and paints the canvas each frame.</li>
     * <li><b>Physics loop</b> (daemon background thread) — drains pending
     * queues, ticks the simulation, and publishes a new snapshot
     * at ~60 Hz.</li>
     * </ol>
     */
    private void setupLoop() {
        // 1. Rendering Loop (JavaFX Thread) - PURE RENDERING
        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (!isRunning)
                    return;

                try {
                    // Just render the current state.
                    graphView.render();

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

                    // Snapshot and Push to UI (Zero Blocking)
                    List<Node> safeNodes;
                    List<Edge> safeEdges;
                    synchronized (simulation.getNodes()) {
                        safeNodes = new ArrayList<>(simulation.getNodes());
                    }
                    synchronized (simulation.getEdges()) {
                        safeEdges = new ArrayList<>(simulation.getEdges());
                    }
                    graphView.setRenderData(safeNodes, safeEdges);

                    // Throttle to ~60Hz (approx 16ms) to save CPU
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

    /**
     * Drains pending node/edge/removal queues (capped per cycle to
     * avoid frame stalls) and reaps fully dead nodes whose death
     * animation has completed.
     */
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

        // Reap fully dead nodes and their edges in one pass.
        // The iterator approach ensures edge cleanup happens atomically per node,
        // unlike a bulk removeIf which would orphan edges.
        long reapThreshold = System.nanoTime() - 2_500_000_000L;
        synchronized (simulation.getNodes()) {
            Iterator<Node> it = simulation.getNodes().iterator();
            while (it.hasNext()) {
                Node n = it.next();
                if (n.deathNano > 0 && n.deathNano < reapThreshold) {
                    it.remove();
                    synchronized (simulation.getEdges()) {
                        simulation.getEdges().removeIf(e -> (e.source != null && e.source.id.equals(n.id)) ||
                                (e.target != null && e.target.id.equals(n.id)));
                    }
                    knownNodeIds.remove(n.id);
                }
            }
        }
    }

    /** Returns the top-level container (graph + sidebar). */
    public Pane getView() {
        return containerView;
    }

    /**
     * Starts the physics thread and render loop.
     * Safe to call repeatedly — recreates the thread if it was previously
     * terminated.
     */
    public void start() {
        isRunning = true;
        if (physicsThread != null && !physicsThread.isAlive()) {
            try {
                physicsThread.start();
            } catch (IllegalThreadStateException e) {
                // Thread already terminated — recreate and start fresh
                setupLoop();
                physicsThread.start();
            }
        } else if (physicsThread == null) {
            setupLoop();
            physicsThread.start();
        }
        timer.start();
    }

    /** Stops the physics thread and render loop. */
    public void stop() {
        isRunning = false;
        timer.stop();
        if (physicsThread != null) {
            physicsThread.interrupt();
        }
    }
}
