package de.bsommerfeld.wsbg.terminal.instruments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The auto-updating local instrument list — the GROUND the resolver's identity
 * judge stands on instead of training-time memory: every US listing (SEC, daily
 * official) plus every German instrument the room ever surfaced (the learned
 * wallstreet-online ISIN memory). Persisted to
 * {@code <app-data>/instruments/instruments.jsonl}; refreshed asynchronously
 * when older than {@link #REFRESH_AFTER} (a failed refresh keeps the previous
 * snapshot — stale ground truth beats none).
 *
 * <p><b>Retrieval is deliberately LEXICAL, not embedding-based</b>: the judge
 * must never see the whole corpus, so {@link #search} returns the top-K entries
 * by token/prefix overlap with the query — deterministic, model-free, and
 * sufficient for name lookup over ~10-15k entries. (An embedding retrieval
 * layer stays a possible upgrade for cross-language recall — as an INDEX only;
 * the judgment itself stays with the LLM judge.)
 *
 * <p>The searchable structure + its ranking live in {@link InstrumentIndex} and
 * the JSONL persistence in {@link CorpusStore}; this class owns lifecycle,
 * staleness, the async refresh thread, and the multi-source dedup-merge.
 */
public final class InstrumentCorpus {

    private static final Logger LOG = LoggerFactory.getLogger(InstrumentCorpus.class);
    /** Corpus age at which a background refresh is kicked off. */
    static final Duration REFRESH_AFTER = Duration.ofDays(7);

    private final CorpusStore store;
    private final List<CorpusSource> sources;
    private final AtomicReference<InstrumentIndex> index = new AtomicReference<>(InstrumentIndex.EMPTY);

    public InstrumentCorpus(Path instrumentsFile, List<CorpusSource> sources) {
        this.store = new CorpusStore(instrumentsFile);
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /**
     * Loads the persisted corpus (if any) and kicks an async refresh when it's
     * stale or absent. Never blocks the caller on the network.
     */
    public void start() {
        List<InstrumentEntry> loaded = store.load();
        if (!loaded.isEmpty()) {
            index.set(InstrumentIndex.build(loaded));
            LOG.info("[CORPUS] loaded {} instruments from disk", loaded.size());
        }
        boolean stale = store.lastModified()
                .map(modified -> Instant.now().isAfter(modified.plus(REFRESH_AFTER)))
                .orElse(true);
        if (stale && !sources.isEmpty()) {
            Thread t = new Thread(this::refresh, "instrument-corpus-refresh");
            t.setDaemon(true);
            t.start();
        }
    }

    /** Entries currently indexed (0 until loaded/refreshed). */
    public int size() {
        return index.get().size();
    }

    /**
     * Top-{@code k} entries whose NAME or SYMBOL lexically matches the query.
     * Delegates to the current {@link InstrumentIndex}.
     */
    public List<InstrumentEntry> search(String query, int k) {
        return index.get().search(query, k);
    }

    // -- refresh --

    /** Pulls every source, merges (ISIN/symbol-deduped), persists, re-indexes. Synchronous. */
    public void refresh() {
        List<InstrumentEntry> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int failed = 0;
        for (CorpusSource s : sources) {
            try {
                mergeSource(s, merged, seen);
            } catch (Exception e) {
                failed++;
                LOG.warn("[CORPUS] source '{}' failed: {} — keeping previous snapshot for it",
                        s.name(), e.getMessage());
            }
        }
        if (merged.isEmpty()) {
            LOG.warn("[CORPUS] refresh yielded nothing ({} source(s) failed) — keeping previous corpus", failed);
            return;
        }
        store.save(merged);
        index.set(InstrumentIndex.build(merged));
        LOG.info("[CORPUS] refreshed: {} instruments indexed", merged.size());
    }

    /**
     * Folds one source's instruments into {@code merged}, deduping by ISIN
     * (symbol as fallback) — first source wins (source order is priority).
     * Returns how many new entries this source contributed.
     */
    private int mergeSource(CorpusSource s, List<InstrumentEntry> merged, Set<String> seen) throws Exception {
        List<InstrumentEntry> got = s.fetch();
        int kept = 0;
        for (InstrumentEntry e : got) {
            String key = (e.isin() != null && !e.isin().isBlank())
                    ? "isin:" + e.isin()
                    : "sym:" + e.symbol().toUpperCase(Locale.ROOT);
            if (seen.add(key)) {
                merged.add(e);
                kept++;
            }
        }
        LOG.info("[CORPUS] source '{}' → {} instruments ({} new)", s.name(), got.size(), kept);
        return kept;
    }
}
