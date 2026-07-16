package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reply-tree topology: classifies the growth form of a comment forest.
 * Flat-broad threads are shout-along/agreement, deep-narrow threads are
 * back-and-forth argument - the distinction follows the cascade analysis of
 * discussion trees (structural virality, Goel/Anderson/Hofman/Watts 2016:
 * broad vs deep diffusion trees).
 *
 * <p>Numerics: the forest is built from the parentId edges (comments with an
 * unknown or missing parent count as roots), depths are determined via BFS
 * (root = depth 1). The conflict index is the mean leaf depth divided by
 * log2(n+1) - the expectation yardstick of a balanced binary tree; values
 * clearly above it mean the thread grows in argument chains instead of
 * spreading in breadth.
 *
 * <p>Terminal input: the comment ids with parentId from the Reddit scraper
 * (the reply tree RSS cannot see - the OAuth/.json path delivers it,
 * ReportBuilder already uses it for attribution).
 */
public final class ReplyTreeShape {

    /** A comment as an edge in the reply forest; parentId null = top-level. */
    public record Comment(String id, String parentId) {
    }

    private static final String ID = "reply-tree-shape";
    private static final String TITLE = "Reply-tree topology (conflict index)";
    private static final String DEFINITION =
            "Measures whether a thread grew flat-broad (shout-along/agreement)"
                    + " or deep-narrow (back-and-forth argument).";

    private static final int MIN_COMMENTS = 10;
    private static final int THIN_COMMENTS = 20;

    private ReplyTreeShape() {
    }

    /**
     * Computes the conflict index over the comment forest.
     * At least {@value #MIN_COMMENTS} comments, otherwise {@link Optional#empty()}.
     */
    public static Optional<SignalReading> measure(List<Comment> comments) {
        if (comments == null || comments.size() < MIN_COMMENTS) {
            return Optional.empty();
        }
        Set<String> knownIds = new HashSet<>();
        for (Comment c : comments) {
            if (c != null && c.id() != null) {
                knownIds.add(c.id());
            }
        }
        Map<String, List<String>> children = new HashMap<>();
        List<String> roots = new ArrayList<>();
        for (Comment c : comments) {
            if (c == null || c.id() == null) {
                continue;
            }
            if (c.parentId() == null || !knownIds.contains(c.parentId())) {
                roots.add(c.id());
            } else {
                children.computeIfAbsent(c.parentId(), k -> new ArrayList<>()).add(c.id());
            }
        }
        if (roots.isEmpty()) {
            // Only cyclic/broken edges - no evaluable tree structure.
            return Optional.empty();
        }

        // BFS from the roots (root = depth 1); the visited set guards against broken edges.
        Map<String, Integer> depth = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (String root : roots) {
            depth.put(root, 1);
            queue.add(root);
        }
        int maxDepth = 1;
        long leafDepthSum = 0;
        int leafCount = 0;
        while (!queue.isEmpty()) {
            String id = queue.poll();
            int d = depth.get(id);
            maxDepth = Math.max(maxDepth, d);
            List<String> kids = children.get(id);
            if (kids == null || kids.isEmpty()) {
                leafDepthSum += d;
                leafCount++;
            } else {
                for (String kid : kids) {
                    if (depth.putIfAbsent(kid, d + 1) == null) {
                        queue.add(kid);
                    }
                }
            }
        }

        int n = depth.size();
        double meanLeafDepth = leafCount == 0 ? 0 : (double) leafDepthSum / leafCount;
        double rootBreadth = roots.size();
        double value = meanLeafDepth / (Math.log(n + 1) / Math.log(2));

        String interpretation = interpret(value, n);
        String formatted = MathKit.fmt(value, 2)
                + " (conflict index = mean leaf depth / log2(n+1); max depth " + maxDepth
                + ", " + (int) rootBreadth + " top-level branches)";
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION, interpretation));
    }

    private static String interpret(double value, int commentCount) {
        String band;
        if (value < 0.5) {
            band = "AGREEMENT ECHO: many shouting the same thing into the room,"
                    + " high consensus, little new information.";
        } else if (value <= 1.0) {
            band = "MIXED growth form: partly broad shout-along, partly genuine"
                    + " reply chains.";
        } else {
            band = "CONTESTED: deep argument chains, genuine dissent over the position -"
                    + " volatility candidate, the story is not settled.";
        }
        if (commentCount < THIN_COMMENTS) {
            band += " Caution: only n=" + commentCount + " comments - the tree shape"
                    + " can still flip.";
        }
        return band;
    }
}
