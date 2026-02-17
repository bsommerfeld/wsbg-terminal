package de.bsommerfeld.wsbg.terminal.ui.view.graph;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Edge;
import de.bsommerfeld.wsbg.terminal.ui.view.graph.GraphSimulation.Node;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the radial tree layout for graph nodes. Distributes threads
 * on a spiral around the center hub and recursively positions comments
 * along angular sectors proportional to their subtree weight.
 */
class GraphLayoutEngine {

    // Comment color palette: bright blue → deep sea blue
    private static final Color COMMENT_BLUE_SHALLOW = Color.web("#4488FF");
    private static final Color COMMENT_BLUE_DEEP = Color.web("#0A2A55");

    // Graph density cap — only affects visual layout, not sidebar content
    private static final int MAX_CHILDREN_PER_NODE = 15;

    private GraphLayoutEngine() {
    }

    static class LayoutNode {
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
     * Recursively builds the layout tree by attaching child comments
     * (capped at {@link #MAX_CHILDREN_PER_NODE} per parent, sorted by score).
     */
    static void buildLayoutTree(LayoutNode parent, Map<String, List<RedditComment>> map,
            Map<String, RedditComment> allComments, String threadId,
            NodeResolver resolver) {
        String pId = parent.rawComment == null ? parent.node.id : parent.rawComment.id();
        List<RedditComment> kids = map.get(pId);
        if (kids == null)
            return;

        kids.sort(Comparator.comparingInt(RedditComment::score).reversed());

        int added = 0;
        for (RedditComment c : kids) {
            if (added >= MAX_CHILDREN_PER_NODE)
                break;
            String cId = "CMT_" + c.id();
            // Deleted comments retain their node so attached images aren't lost
            String body = c.body() != null ? c.body() : "[deleted]";
            Node cn = resolver.find(cId);
            if (cn == null) {
                String bodySnippet = body.length() > 100 ? body.substring(0, 100) + "..." : body;
                cn = new Node(cId, bodySnippet, 0, 0);
                cn.isThread = false;
                cn.mass = 5;
            }

            String authorText = c.author() != null ? " u/" + c.author() : "";
            cn.fullText = body + authorText;
            cn.author = c.author();
            cn.score = c.score();
            cn.isThread = false;
            cn.parent = parent.node;
            cn.rootNode = parent.node.rootNode != null ? parent.node.rootNode : parent.node;
            cn.level = parent.node.level + 1;
            cn.commentDepth = parent.node.commentDepth + 1;
            cn.threadId = threadId;

            LayoutNode childLayout = new LayoutNode(cn, c);
            parent.children.add(childLayout);

            buildLayoutTree(childLayout, map, allComments, threadId, resolver);
            added++;
        }
    }

    static void calculateSubtreeSizes(LayoutNode node) {
        node.subtreeSize = 0;
        for (LayoutNode child : node.children) {
            calculateSubtreeSizes(child);
            node.subtreeSize += 1 + child.subtreeSize;
        }
    }

    static int calculateMaxDepth(LayoutNode node) {
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
    static void applyBranchLayout(LayoutNode layoutNode, double branchAngle, double parentRadius,
            double levelSpacing, double angularBudget, List<Node> nodesOut, List<Edge> edgesOut,
            Set<String> validIds, int maxDepth, String threadId, Set<String> knownEdgeIds) {

        if (layoutNode.children.isEmpty())
            return;

        int childCount = layoutNode.children.size();

        int totalChildWeight = 0;
        for (LayoutNode child : layoutNode.children) {
            totalChildWeight += 1 + child.subtreeSize;
        }

        // Cap angular spread per level. Deep nodes must stay narrow to avoid fan-out.
        // 0.6 decay forces each level to use at most 60% of the parent's budget,
        // pushing growth radially outward instead of laterally.
        double effectiveBudget = Math.min(angularBudget * 0.6, Math.PI * 0.4);

        double maxPerChild = Math.PI * 0.15;
        if (childCount > 1) {
            double totalIfMaxed = maxPerChild * childCount;
            if (totalIfMaxed < effectiveBudget) {
                effectiveBudget = totalIfMaxed;
            }
        }

        // Bias angular start outward: 30% center-facing, 70% outward.
        // Symmetric centering caused comments to spill between thread and center.
        double childAngleStart = branchAngle - effectiveBudget * 0.3;

        for (int i = 0; i < childCount; i++) {
            LayoutNode child = layoutNode.children.get(i);
            int childWeight = 1 + child.subtreeSize;

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
                    childSlice, nodesOut, edgesOut, validIds, maxDepth, threadId, knownEdgeIds);

            childAngleStart += childSlice;
        }
    }

    /**
     * Interpolates between bright blue and deep sea blue based on comment depth.
     */
    static Color getCommentColor(int depth, int maxDepth) {
        if (maxDepth <= 1)
            return COMMENT_BLUE_SHALLOW;
        double t = Math.min(1.0, (double) (depth - 1) / Math.max(1, maxDepth - 1));
        return COMMENT_BLUE_SHALLOW.interpolate(COMMENT_BLUE_DEEP, t);
    }

    /**
     * Callback for resolving existing nodes by ID from the simulation.
     */
    @FunctionalInterface
    interface NodeResolver {
        Node find(String id);
    }
}
