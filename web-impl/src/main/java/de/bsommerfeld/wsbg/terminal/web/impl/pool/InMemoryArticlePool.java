package de.bsommerfeld.wsbg.terminal.web.impl.pool;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
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
 * whatever its sources). De-duplication by {@link Article#uuid()} with the
 * link as fallback identity. Press and sentiment are filed apart at the pour,
 * by the source's own {@code socialSentiment()} declaration — the reader can
 * never mix the streams by accident.
 *
 * <p>All access synchronized on {@code this}: pours are batched and queries
 * are read-mostly; contention is not the bottleneck, the network is.
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
     */
    static final java.time.Duration LIVE_TTL = java.time.Duration.ofMinutes(5);

    private record Entry(Article article, String sourceName, boolean sentiment,
            boolean durable, Instant pouredAt) {
    }

    /** key: uuid (blank-guarded) or link. Insertion order = pour order. */
    private final LinkedHashMap<String, Entry> basin = new LinkedHashMap<>(4096, 0.75f, false);

    private final java.time.Clock clock;

    public InMemoryArticlePool() {
        this(java.time.Clock.systemUTC());
    }

    /** Test seam: a pool on a controllable clock. */
    InMemoryArticlePool(java.time.Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized int add(WebSource source, Collection<Article> items) {
        if (items == null || items.isEmpty()) return 0;
        purgeExpired();
        Instant now = clock.instant();
        // Collector pours are the durable stream (they stay until the ceiling
        // pushes them out); everything else is a live answer on the 5-min clock.
        boolean durable = source instanceof de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
        int fresh = 0;
        for (Article raw : items) {
            if (raw == null) continue;
            Article article = raw.withOrigin(source.origin());
            String key = identity(article);
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
            fresh++;
        }
        int overflow = basin.size() - MAX_ITEMS;
        if (overflow > 0) {
            var it = basin.entrySet().iterator();
            for (int i = 0; i < overflow && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        if (fresh > 0) {
            LOG.debug("pool +{} from '{}' ({} in basin)", fresh, source.sourceName(), basin.size());
        }
        return fresh;
    }

    /** Drops live entries whose window has passed. Called under the lock. */
    private void purgeExpired() {
        Instant cutoff = clock.instant().minus(LIVE_TTL);
        basin.values().removeIf(e -> !e.durable() && e.pouredAt().isBefore(cutoff));
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
     * An entry matches an instrument when a HARD key hits (ISIN tag, ticker
     * tag) or a significant name word appears in the text — the same additive
     * fan the sources used to answer, now local.
     */
    private Predicate<Entry> instrumentMatch(ResolvedInstrument instrument) {
        String isin = instrument.isin().map(i -> i.value()).orElse("");
        String ticker = instrument.ticker().map(t -> t.value()).orElse("");
        String base = instrument.ticker().map(t -> t.baseSymbol()).orElse("");
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
                for (String t : a.relatedTickers()) {
                    if (t == null) continue;
                    if (t.equalsIgnoreCase(ticker) || t.equalsIgnoreCase(base)) return true;
                }
            }
            return !nameWords.isEmpty() && TextMatch.matchesAny(haystack(a), nameWords);
        };
    }

    private synchronized List<Article> select(int limit, Predicate<Entry> filter) {
        if (limit <= 0) return List.of();
        purgeExpired();
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

    private static String identity(Article a) {
        if (a.uuid() != null && !a.uuid().isBlank()) return a.uuid();
        return a.link() == null ? "" : a.link();
    }
}
