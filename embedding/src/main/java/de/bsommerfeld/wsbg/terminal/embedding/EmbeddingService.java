package de.bsommerfeld.wsbg.terminal.embedding;

/**
 * A small, vendor-neutral seam over the embedding model. Every editorial feature
 * that needs semantic similarity — cluster centroids, headline collation, ticker
 * resolution (tier 2), similar-name attribution — goes through this one interface
 * instead of building its own {@code OllamaEmbeddingModel}.
 *
 * <p>The point is twofold: <b>one</b> place owns the embedding plumbing, and the
 * consuming <b>logic</b> becomes unit-testable — a {@code FakeEmbeddingService}
 * with deterministic similarities lets the collation window, the tier-2 ranking,
 * the name matcher all be tested in plain {@code mvn test} without Ollama.
 *
 * <p>Lives in its own module so the editorial pipeline (agent) and the news
 * aggregator can both depend on the seam without either depending on the other.
 */
public interface EmbeddingService {

    /** Cosine similarity of two texts, clamped to {@code [0,1]}. Blank texts → 0. */
    double similarity(String a, String b);

    /**
     * Picks the candidate most similar to {@code query}, or {@code -1} if none
     * clears {@code minSimilarity}. The shared "best semantic match" primitive used
     * by tier-2 resolution (best ticker name) and similar-name attribution (best
     * evidence line). Returns the index into {@code candidates}.
     */
    default int bestMatch(String query, java.util.List<String> candidates, double minSimilarity) {
        int best = -1;
        double bestSim = minSimilarity;
        for (int i = 0; i < candidates.size(); i++) {
            double sim = similarity(query, candidates.get(i));
            if (sim >= bestSim) {
                bestSim = sim;
                best = i;
            }
        }
        return best;
    }

    /** The raw embedding vector, for callers that keep/average vectors (e.g. centroids). */
    float[] embed(String text);
}
