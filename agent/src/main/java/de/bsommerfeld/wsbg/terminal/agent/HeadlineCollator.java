package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collation (#2): merges similar headlines instead of stacking a near-duplicate
 * row. A freshly-published headline is compared (semantically, via the shared
 * {@link EmbeddingService}) to the last few still-editable headlines; a high
 * similarity means "same story, refined" → the new line REPLACES the matched one
 * in place (the UI later wipes-and-replaces with a golden shimmer) rather than
 * appending a new row.
 *
 * <p>Cross-unit by design: two subject units that drift into the same line (a
 * temporary duplicate, two angles on one event) collapse to a single row. The
 * window is bounded to the last {@link #EDITABLE_WINDOW} headlines because only
 * those are still on screen / editable.
 *
 * <p>Yahoo-independent. Behind the {@link EmbeddingService} seam, so the collation
 * logic is unit-testable with a fake embedder (no Ollama).
 */
@Singleton
public final class HeadlineCollator {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlineCollator.class);

    /** Only the last few headlines are still editable/replaceable (a UI constraint). */
    private static final int EDITABLE_WINDOW = 5;
    /** Similarity at/above which two headlines are "the same story" and collate. Tunable. */
    private static final double COLLATE_THRESHOLD = 0.86;

    private final EmbeddingService embeddings;
    private final List<Entry> recent = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();

    @Inject
    public HeadlineCollator(EmbeddingService embeddings) {
        this.embeddings = embeddings;
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
        Entry best = null;
        double bestSim = -1.0;
        for (Entry e : recent) {
            double sim = embeddings.similarity(text, e.text);
            if (sim > bestSim) {
                bestSim = sim;
                best = e;
            }
        }
        if (best != null && bestSim >= COLLATE_THRESHOLD) {
            String replaced = best.text;
            best.text = text;       // replace in place, keep the row id
            recent.remove(best);    // and move it to the most-recent end of the window
            recent.add(best);
            LOG.debug("[COLLATE] '{}' merged into row {} (sim {})", text, best.id, bestSim);
            return new Decision(best.id, true, replaced, bestSim);
        }
        String id = "h" + seq.incrementAndGet();
        recent.add(new Entry(id, unitId, text));
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

        Entry(String id, String unitId, String text) {
            this.id = id;
            this.unitId = unitId;
            this.text = text;
        }
    }

    /**
     * Result of offering a headline. {@code collated} = it replaced a recent
     * similar row ({@code id} is that row, {@code replacedText} is what it wiped);
     * otherwise it is a new row with a fresh {@code id}. {@code similarity} is the
     * best similarity seen, for tuning/visibility.
     */
    public record Decision(String id, boolean collated, String replacedText, double similarity) {}
}
