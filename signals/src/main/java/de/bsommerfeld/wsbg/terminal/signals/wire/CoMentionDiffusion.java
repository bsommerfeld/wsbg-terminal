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
 * Themen-Ansteckung: personalisierter PageRank ueber den Ko-Erwähnungs-Graphen,
 * um die Zweitrunden-Kandidaten eines brennenden Themas zu finden.
 *
 * <p><b>Methode:</b> Personalized PageRank (Page/Brin et al., "The PageRank
 * Citation Ranking", 1999; themenzentriert nach Haveliwala, "Topic-Sensitive
 * PageRank", 2002): Dämpfung 0.85, die Restart-Masse liegt vollständig auf dem
 * Seed-Knoten, die Kantengewichte werden pro Knoten zu Übergangswahrscheinlich-
 * keiten normalisiert (ungerichteter Graph, beide Richtungen). Iteriert wird bis
 * zur L1-Konvergenz 1e-9 oder maximal 200 Iterationen. Der Befund ist die
 * Masse-Konzentration der drei stärksten Nicht-Seed-Knoten: je höher, desto
 * enger und klarer der Ansteckungspfad vom Seed in sein Umfeld.
 *
 * <p><b>Inputs im Terminal:</b> die Ko-Erwähnungs-Cluster der Redaktion - zwei
 * Namen, die in derselben Story bzw. demselben Cluster auftauchen, bilden eine
 * ungerichtete Kante, die Häufigkeit der gemeinsamen Nennung das Gewicht. Der
 * Seed ist das Thema bzw. der Name, der gerade brennt.
 */
public final class CoMentionDiffusion {

    /** Ungerichtete Ko-Erwähnungs-Kante zwischen zwei Knoten mit Gewicht (z.B. Nennungs-Häufigkeit). */
    public record Edge(String a, String b, double weight) {
    }

    /** Stabiler Maschinen-Schluessel dieses Signals. */
    public static final String ID = "co-mention-diffusion";

    private static final String TITLE = "Themen-Ansteckung (Personalisierter PageRank)";

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
     * @param edges Ko-Erwähnungs-Kanten (ungerichtet, Gewichte &gt; 0 werden gewertet)
     * @param seed  der brennende Knoten, auf dem die Restart-Masse liegt
     * @param topN  wie viele Zweitrunden-Kandidaten in der Deutung gelistet werden
     */
    public static Optional<SignalReading> measure(List<Edge> edges, String seed, int topN) {
        if (edges == null || edges.isEmpty() || seed == null) {
            return Optional.empty();
        }

        // Adjazenz aufbauen: deterministisch sortiert, nur positive Gewichte, keine Selbstschleifen.
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

        // Personalisierter PageRank.
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

        // Nicht-Seed-Knoten absteigend nach Masse, bei Gleichstand nach Name.
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

        String lead = "Wenn " + seed + " brennt, stehen diese als Nächstes im Rauch: " + listed + ". ";
        String interpretation;
        if (value >= 0.5) {
            interpretation = lead + "Enger, klarer Ansteckungspfad - die Zweitrunden-Kandidaten"
                    + " sind belastbar, bevor die Presse den Zusammenhang zieht.";
        } else if (value >= 0.25) {
            interpretation = lead + "Mittlere Kopplung - die Kandidaten sind plausibel,"
                    + " aber der Pfad ist nicht zwingend.";
        } else {
            interpretation = lead + "Diffuses Umfeld - die Masse verteilt sich breit,"
                    + " die Ansteckungsrichtung ist unklar.";
        }
        if (adjacency.size() < COMFORTABLE_NODES || validEdges < COMFORTABLE_EDGES) {
            interpretation += " Vorsicht: dünne Datenlage (kleiner Graph)"
                    + " - Befund nur als schwaches Indiz lesen.";
        }

        String formattedValue = MathKit.fmt(value, 2)
                + " (Skala 0-1, PageRank-Masse der Top-3 Nicht-Seed-Knoten)";
        String definition = "Personalisierter PageRank vom Seed über den Ko-Erwähnungs-Graphen;"
                + " gemessen wird, wie stark sich die Aufmerksamkeits-Masse auf die drei"
                + " nächstliegenden Nicht-Seed-Knoten konzentriert.";

        return Optional.of(new SignalReading(ID, TITLE, value, formattedValue, definition, interpretation));
    }
}
