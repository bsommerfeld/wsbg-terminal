package de.bsommerfeld.wsbg.terminal.signals.wire;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Topic contagion: personalized PageRank over the co-mention graph to find the
 * second-round candidates of a burning topic.
 *
 * <p><b>Method:</b> personalized PageRank (Page/Brin et al., "The PageRank
 * Citation Ranking", 1999; topic-centered per Haveliwala, "Topic-Sensitive
 * PageRank", 2002): damping 0.85, the restart mass sits entirely on the seed
 * node, edge weights are normalized per node into transition probabilities
 * (undirected graph, both directions). Iterates until L1 convergence 1e-9 or at
 * most 200 iterations. The finding is the mass concentration of the three
 * strongest non-seed nodes: the higher, the tighter and clearer the contagion
 * path from the seed into its surroundings.
 *
 * <p><b>Inputs in the terminal:</b> the newsroom's co-mention clusters - two
 * names appearing in the same story or cluster form an undirected edge, the
 * frequency of joint mention its weight. The seed is the topic or name that is
 * burning right now.
 */
public final class CoMentionDiffusion {

    /** Undirected co-mention edge between two nodes with a weight (e.g. mention frequency). */
    public record Edge(String a, String b, double weight) {
    }

    /** Stable machine key of this signal. */
    public static final String ID = "co-mention-diffusion";

    private static final String TITLE = "Topic contagion (personalized PageRank)";

    private static final double DAMPING = 0.85;
    private static final double CONVERGENCE = 1e-9;
    private static final int MAX_ITERATIONS = 200;
    private static final int MIN_NODES = 3;
    private static final int COMFORTABLE_NODES = 5;
    private static final int COMFORTABLE_EDGES = 4;
    private static final int CONCENTRATION_TOP = 3;

    private CoMentionDiffusion() {
    }

    /**
     * @param edges co-mention edges (undirected, only weights &gt; 0 are scored)
     * @param seed  the burning node carrying the restart mass
     * @param topN  how many second-round candidates are listed in the interpretation
     */
    public static Optional<SignalReading> measure(List<Edge> edges, String seed, int topN) {
        if (edges == null || edges.isEmpty() || seed == null) {
            return Optional.empty();
        }

        // Build adjacency: deterministically sorted, positive weights only, no self-loops.
        Map<String, Map<String, Double>> adjacency = new TreeMap<>();
        int validEdges = 0;
        for (Edge e : edges) {
            if (e == null || e.a() == null || e.b() == null
                    || e.a().equals(e.b()) || !(e.weight() > 0) || !Double.isFinite(e.weight())) {
                continue;
            }
            adjacency.computeIfAbsent(e.a(), k -> new TreeMap<>()).merge(e.b(), e.weight(), Double::sum);
            adjacency.computeIfAbsent(e.b(), k -> new TreeMap<>()).merge(e.a(), e.weight(), Double::sum);
            validEdges++;
        }
        if (!adjacency.containsKey(seed) || adjacency.size() < MIN_NODES) {
            return Optional.empty();
        }

        // Personalized PageRank.
        Map<String, Double> rank = new TreeMap<>();
        for (String node : adjacency.keySet()) {
            rank.put(node, node.equals(seed) ? 1.0 : 0.0);
        }
        Map<String, Double> outWeight = new TreeMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : adjacency.entrySet()) {
            double sum = 0;
            for (double w : entry.getValue().values()) {
                sum += w;
            }
            outWeight.put(entry.getKey(), sum);
        }
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            Map<String, Double> next = new TreeMap<>();
            for (String node : adjacency.keySet()) {
                next.put(node, node.equals(seed) ? 1.0 - DAMPING : 0.0);
            }
            for (Map.Entry<String, Map<String, Double>> entry : adjacency.entrySet()) {
                double mass = rank.get(entry.getKey());
                double total = outWeight.get(entry.getKey());
                if (mass == 0 || total == 0) {
                    continue;
                }
                for (Map.Entry<String, Double> neighbor : entry.getValue().entrySet()) {
                    next.merge(neighbor.getKey(), DAMPING * mass * (neighbor.getValue() / total), Double::sum);
                }
            }
            double delta = 0;
            for (String node : adjacency.keySet()) {
                delta += Math.abs(next.get(node) - rank.get(node));
            }
            rank = next;
            if (delta < CONVERGENCE) {
                break;
            }
        }

        // Non-seed nodes descending by mass, ties broken by name.
        List<Map.Entry<String, Double>> nonSeed = new ArrayList<>();
        for (Map.Entry<String, Double> entry : rank.entrySet()) {
            if (!entry.getKey().equals(seed)) {
                nonSeed.add(entry);
            }
        }
        nonSeed.sort(Comparator
                .comparingDouble((Map.Entry<String, Double> e) -> -e.getValue())
                .thenComparing(Map.Entry::getKey));

        double value = 0;
        for (int i = 0; i < Math.min(CONCENTRATION_TOP, nonSeed.size()); i++) {
            value += nonSeed.get(i).getValue();
        }

        StringBuilder listed = new StringBuilder();
        int limit = Math.min(Math.max(1, topN), nonSeed.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                listed.append(", ");
            }
            listed.append(nonSeed.get(i).getKey())
                    .append(" (").append(MathKit.fmt(nonSeed.get(i).getValue(), 2)).append(")");
        }

        String lead = "When " + seed + " burns, these stand in the smoke next: " + listed + ". ";
        String interpretation;
        if (value >= 0.5) {
            interpretation = lead + "Tight, clear contagion path - the second-round candidates"
                    + " are solid before the press draws the connection.";
        } else if (value >= 0.25) {
            interpretation = lead + "Medium coupling - the candidates are plausible,"
                    + " but the path is not compelling.";
        } else {
            interpretation = lead + "Diffuse surroundings - the mass spreads wide,"
                    + " the contagion direction is unclear.";
        }
        if (adjacency.size() < COMFORTABLE_NODES || validEdges < COMFORTABLE_EDGES) {
            interpretation += " Caution: only thin data (small graph)"
                    + " - read the finding as a weak hint only.";
        }

        String formattedValue = MathKit.fmt(value, 2)
                + " (scale 0-1, PageRank mass of the top-3 non-seed nodes)";
        String definition = "Personalized PageRank from the seed over the co-mention graph;"
                + " measures how strongly the attention mass concentrates on the three"
                + " nearest non-seed nodes.";

        return Optional.of(new SignalReading(ID, TITLE, value, formattedValue, definition, interpretation));
    }
}
