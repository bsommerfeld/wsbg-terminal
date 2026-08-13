package de.bsommerfeld.wsbg.terminal.web.impl.pool;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticleTagger;
import de.bsommerfeld.wsbg.terminal.web.source.WebSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The in-memory Sammelbecken. Insertion-ordered and bounded: when the basin
 * is full the oldest POUR falls out first (the freshest window survives,
 * whatever its sources). De-duplication by {@link Article#identity()}. Press
 * and sentiment are filed apart at the pour, by the source's own
 * {@code socialSentiment()} declaration — the reader can never mix the streams
 * by accident.
 *
 * <p><b>Instrument assignment is judged, not guessed.</b> With an
 * {@link ArticleTagger} installed (production), every pour is handed over for
 * a one-time per-article judgment and {@link #queryInstrument} becomes a set
 * lookup over those verdicts. Without a tagger (tests, degraded boot) a
 * deliberately conservative fallback answers: hard keys (ISIN, ticker tag)
 * plus the FULL significant name — never the old any-word guess that let
 * "Canopy Growth" catch every growth story in the basin.
 *
 * <p>Mutations are synchronized on {@code this}; the tagger is only ever
 * called OUTSIDE that lock ({@code ingest}/{@code forget} are cheap hand-offs,
 * {@code articlesFor} does real work), so no lock ordering exists between pool
 * and tagger.
 */
@Singleton
public final class InMemoryArticlePool implements ArticlePool {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryArticlePool.class);

    /** The basin ceiling — a bounded window, never unbounded growth. */
    static final int MAX_ITEMS = 25_000;

    /**
     * How long a LIVE pour (instrument fan, search engine) stays in the basin.
     * Deliberate architecture (user decision 2026-08-12): collectors are
     * fetched consequently on their own clock and their entries simply STAY —
     * live results are on-demand answers, and expiring them is what guarantees
     * a repeat inquiry after this window sees the CURRENT state of the world
     * instead of a stale echo. Within the window, repeats are answered from
     * RAM and cost the outside world nothing.
     *
     * <p>Must stay COUPLED to {@code HouseWebGateway.FAN_FRESH}: the ledger
     * suppresses a re-fan exactly as long as this window keeps the previous
     * yield readable. Fifteen minutes rather than five — with the fan now on
     * the subject-resolution path, the shorter window meant a busy name could
     * be re-fanned twelve times an hour across ~28 sources for news that had
     * not changed.
     */
    static final java.time.Duration LIVE_TTL = java.time.Duration.ofMinutes(15);

    private record Entry(Article article, String sourceName, boolean sentiment,
            boolean durable, Instant pouredAt) {
    }

    /** key: {@link Article#identity()}. Insertion order = pour order. */
    private final LinkedHashMap<String, Entry> basin = new LinkedHashMap<>(4096, 0.75f, false);

    private final java.time.Clock clock;

    /**
     * The judgment seam — absent in tests and until the agent module is wired,
     * installed once via optional injection. Volatile: set from the injector
     * thread, read from pour/query threads.
     */
    private volatile ArticleTagger tagger;

    public InMemoryArticlePool() {
        this(java.time.Clock.systemUTC());
    }

    /** Test seam: a pool on a controllable clock. */
    InMemoryArticlePool(java.time.Clock clock) {
        this.clock = clock;
    }

    /**
     * Installs the article→instrument judge. Optional Guice method injection —
     * the binding lives with the agent wiring; web-impl only knows the
     * {@code web-api} interface.
     */
    @com.google.inject.Inject(optional = true)
    public void setTagger(ArticleTagger tagger) {
        this.tagger = tagger;
        // Whatever poured before the wiring arrived is backfilled — a
        // collector that beat the agent module to the basin must not stay
        // invisible to instrument queries.
        List<Article> standing = new ArrayList<>();
        synchronized (this) {
            for (Entry e : basin.values()) standing.add(e.article());
        }
        if (!standing.isEmpty()) tagger.ingest(standing);
        LOG.info("pool: article tagger installed — instrument queries answer from judgments"
                + (standing.isEmpty() ? "" : " (" + standing.size() + " backfilled)"));
    }

    @Override
    public int add(WebSource source, Collection<Article> items) {
        if (items == null || items.isEmpty()) return 0;
        List<Article> fresh = new ArrayList<>();
        List<String> evicted = new ArrayList<>();
        int poured = pour(source, items, fresh, evicted);
        // Hand-offs happen OUTSIDE the basin lock: ingest/forget are cheap
        // enqueues by contract, but the tagger stays free to take its own
        // locks without ever nesting inside ours.
        ArticleTagger t = tagger;
        if (t != null) {
            if (!fresh.isEmpty()) t.ingest(fresh);
            if (!evicted.isEmpty()) t.forget(evicted);
        }
        return poured;
    }

    private synchronized int pour(WebSource source, Collection<Article> items,
            List<Article> freshOut, List<String> evictedOut) {
        purgeExpired(evictedOut);
        Instant now = clock.instant();
        // Collector pours are the durable stream (they stay until the ceiling
        // pushes them out); everything else is a live answer on the 15-min clock.
        boolean durable = source instanceof de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
        int fresh = 0;
        for (Article raw : items) {
            if (raw == null) continue;
            Article article = raw.withOrigin(source.origin());
            String key = article.identity();
            if (key.isEmpty()) continue;
            Entry existing = basin.get(key);
            if (existing != null) {
                // A collector confirming an item a live fan brought first
                // PROMOTES it to durable — the story earned its place on the
                // wire, it must not vanish with the live window.
                if (durable && !existing.durable()) {
                    basin.put(key, new Entry(existing.article(), existing.sourceName(),
                            existing.sentiment(), true, existing.pouredAt()));
                }
                continue;
            }
            basin.put(key, new Entry(article, source.sourceName(), source.socialSentiment(),
                    durable, now));
            freshOut.add(article);
            fresh++;
        }
        int overflow = basin.size() - MAX_ITEMS;
        if (overflow > 0) {
            var it = basin.entrySet().iterator();
            for (int i = 0; i < overflow && it.hasNext(); i++) {
                evictedOut.add(it.next().getKey());
                it.remove();
            }
        }
        if (fresh > 0) {
            LOG.debug("pool +{} from '{}' ({} in basin)", fresh, source.sourceName(), basin.size());
        }
        return fresh;
    }

    /** Drops live entries whose window has passed. Called under the lock. */
    private void purgeExpired(List<String> evictedOut) {
        Instant cutoff = clock.instant().minus(LIVE_TTL);
        basin.entrySet().removeIf(e -> {
            boolean gone = !e.getValue().durable() && e.getValue().pouredAt().isBefore(cutoff);
            if (gone) evictedOut.add(e.getKey());
            return gone;
        });
    }

    @Override
    public List<Article> query(String freetext, int limit) {
        Set<String> words = TextMatch.significantWords(freetext);
        if (words.isEmpty()) return List.of();
        return select(limit, e -> !e.sentiment()
                && TextMatch.matchesAll(haystack(e.article()), words));
    }

    @Override
    public List<Article> queryInstrument(ResolvedInstrument instrument, int limit) {
        Predicate<Entry> match = instrumentMatch(instrument);
        return select(limit, e -> !e.sentiment() && match.test(e));
    }

    @Override
    public List<Article> querySentiment(ResolvedInstrument instrument, int limit) {
        Predicate<Entry> match = instrumentMatch(instrument);
        return select(limit, e -> e.sentiment() && match.test(e));
    }

    @Override
    public List<Article> recent(int limit) {
        return select(limit, e -> !e.sentiment());
    }

    @Override
    public synchronized int size() {
        return basin.size();
    }

    /**
     * The instrument membership test. With a tagger: the judged set — computed
     * here, OUTSIDE the basin lock, then a plain contains inside it. Without:
     * hard keys plus the full significant name (all words), the conservative
     * floor that keeps tests and degraded boots honest instead of generous.
     */
    private Predicate<Entry> instrumentMatch(ResolvedInstrument instrument) {
        ArticleTagger t = tagger;
        if (t != null) {
            Set<String> judged = t.articlesFor(instrument);
            return e -> judged.contains(e.article().identity());
        }
        String isin = instrument.isin().map(i -> i.value()).orElse("");
        String ticker = instrument.ticker().map(tk -> tk.value()).orElse("");
        String base = instrument.ticker().map(tk -> tk.baseSymbol()).orElse("");
        Set<String> nameWords = TextMatch.significantWords(instrument.name());
        return e -> {
            Article a = e.article();
            if (!isin.isEmpty()) {
                if (isin.equalsIgnoreCase(a.isin())) return true;
                // Disclosure-grade documents print the ISIN verbatim.
                if (a.title() != null && a.title().toUpperCase(java.util.Locale.ROOT).contains(isin)) return true;
                if (a.summary() != null && a.summary().toUpperCase(java.util.Locale.ROOT).contains(isin)) return true;
            }
            if (!ticker.isEmpty() && a.relatedTickers() != null) {
                for (String rt : a.relatedTickers()) {
                    if (rt == null) continue;
                    if (rt.equalsIgnoreCase(ticker) || rt.equalsIgnoreCase(base)) return true;
                }
            }
            return !nameWords.isEmpty() && TextMatch.matchesAll(haystack(a), nameWords);
        };
    }

    private List<Article> select(int limit, Predicate<Entry> filter) {
        if (limit <= 0) return List.of();
        List<String> evicted = new ArrayList<>();
        List<Article> hits = selectLocked(limit, filter, evicted);
        if (!evicted.isEmpty()) {
            ArticleTagger t = tagger;
            if (t != null) t.forget(evicted);
        }
        return hits;
    }

    private synchronized List<Article> selectLocked(int limit, Predicate<Entry> filter,
            List<String> evictedOut) {
        purgeExpired(evictedOut);
        List<Article> hits = new ArrayList<>();
        for (Entry e : basin.values()) {
            if (filter.test(e)) hits.add(e.article());
        }
        hits.sort(Comparator.comparing(
                (Article a) -> a.publishedAt() == null ? Instant.EPOCH : a.publishedAt())
                .reversed());
        return hits.size() <= limit ? hits : List.copyOf(hits.subList(0, limit));
    }

    private static String haystack(Article a) {
        String title = a.title() == null ? "" : a.title();
        String summary = a.summary() == null ? "" : a.summary();
        return summary.isEmpty() ? title : title + ' ' + summary;
    }
}
