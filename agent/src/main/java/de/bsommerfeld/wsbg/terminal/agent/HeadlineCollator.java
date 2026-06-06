package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.Model;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collation (#2): merges similar headlines instead of stacking a near-duplicate
 * row. Every freshly-published headline is embedded ({@code embeddinggemma}) and
 * compared to the last few still-editable headlines; a high cosine means "same
 * story, refined" → the new line REPLACES the matched one in place (the UI later
 * wipes-and-replaces with a golden shimmer) rather than appending a new row.
 *
 * <p>Cross-unit by design: two subject units that drift into the same line
 * (a temporary duplicate, two angles on one event) collapse to a single row. The
 * window is bounded to the last {@link #EDITABLE_WINDOW} headlines because only
 * those are still on screen / editable — a line that has scrolled away is locked
 * in, so a later near-duplicate becomes a fresh row rather than rewriting history.
 *
 * <p>Yahoo-independent: works purely on the headline text + the local embedder.
 */
@Singleton
public final class HeadlineCollator {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineCollator.class);

    /** Only the last few headlines are still editable/replaceable (a UI constraint). */
    private static final int EDITABLE_WINDOW = 5;
    /** Cosine at/above which two headlines are "the same story" and collate. Tunable. */
    private static final double COLLATE_THRESHOLD = 0.86;

    private final EmbeddingModel embeddingModel;
    private final List<Entry> recent = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();

    @Inject
    public HeadlineCollator() {
        this.embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(AgentBrain.OLLAMA_BASE_URL)
                .modelName(Model.EMBEDDING.getModelName())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /**
     * Offers a freshly-published headline for collation. If it is ≥ threshold
     * similar to one of the last {@link #EDITABLE_WINDOW} headlines, that row is
     * replaced in place and the result is {@code collated}; otherwise it becomes a
     * new row. Returns the row id, whether it collated, the text it replaced (if
     * any) and the similarity that drove the decision.
     */
    public synchronized Decision offer(String unitId, String text) {
        if (text == null || text.isBlank()) {
            return new Decision("", false, null, 0.0);
        }
        Embedding emb = embeddingModel.embed(text).content();

        Entry best = null;
        double bestSim = -1.0;
        for (Entry e : recent) {
            double sim = CosineSimilarity.between(emb, e.embedding);
            if (sim > bestSim) {
                bestSim = sim;
                best = e;
            }
        }
        if (best != null && bestSim >= COLLATE_THRESHOLD) {
            String replaced = best.text;
            best.text = text;       // replace in place, keep the row id
            best.embedding = emb;
            recent.remove(best);    // and move it to the most-recent end of the window
            recent.add(best);
            LOG.debug("[COLLATE] '{}' merged into row {} (sim {})", text, best.id, bestSim);
            return new Decision(best.id, true, replaced, bestSim);
        }
        String id = "h" + seq.incrementAndGet();
        recent.add(new Entry(id, unitId, text, emb));
        while (recent.size() > EDITABLE_WINDOW) recent.remove(0);
        return new Decision(id, false, null, bestSim);
    }

    /** Wipes the recent window (lab "Reset"). */
    public synchronized void clear() {
        recent.clear();
    }

    private static final class Entry {
        final String id;
        final String unitId;
        String text;
        Embedding embedding;

        Entry(String id, String unitId, String text, Embedding embedding) {
            this.id = id;
            this.unitId = unitId;
            this.text = text;
            this.embedding = embedding;
        }
    }

    /**
     * Result of offering a headline. {@code collated} = it replaced a recent
     * similar row ({@code id} is that row, {@code replacedText} is what it wiped);
     * otherwise it is a new row with a fresh {@code id}. {@code similarity} is the
     * best cosine seen, for tuning/visibility.
     */
    public record Decision(String id, boolean collated, String replacedText, double similarity) {}
}
