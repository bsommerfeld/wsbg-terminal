package de.bsommerfeld.wsbg.terminal.web.impl.gateway;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.gateway.InquiryResult;
import de.bsommerfeld.wsbg.terminal.web.gateway.SourceOutcome;
import de.bsommerfeld.wsbg.terminal.web.gateway.WebGateway;
import de.bsommerfeld.wsbg.terminal.web.impl.exec.WebExecutor;
import de.bsommerfeld.wsbg.terminal.web.instrument.InstrumentRegister;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The gateway: one inquiry in, three pipelines out, one bundle back.
 *
 * <p>{@code searchInstrument} resolves the query against the register, then
 * runs in parallel: the POOL query (local, the collectors already brought the
 * world in), the live INSTRUMENT fan (every instrument source as its own
 * future), and the FACTS graze. Articles from pool and fan merge into ONE
 * de-duplicated, freshest-first collection; sentiment stays its own stream;
 * every source contributes an outcome line. Source failures never fail the
 * future — they become outcomes.
 *
 * <p>{@code search} fans the search engines fire-and-forget; their yield
 * pours into the pool through the same seam the collectors use.
 */
@Singleton
public final class HouseWebGateway implements WebGateway {

    private static final Logger LOG = LoggerFactory.getLogger(HouseWebGateway.class);

    /** Per-source ceiling on the live fan — breadth over depth. */
    /**
     * NOT a result ceiling — the REQUEST SIZE handed to each live source
     * ("bring your freshest N"). The contract stays "ALL found articles":
     * everything every source returns reaches the caller unclipped; this
     * number only phrases how deep one source is asked to reach per call.
     */
    static final int LIVE_FAN_LIMIT = 25;
    /**
     * The fan ledger's freshness window — matches the pool's live-entry TTL:
     * a source that answered CLEANLY (even empty) for an instrument inside
     * this window is not asked again; its yield is still in the basin. An
     * EMPTY answer must stamp too (it leaves no articles to read freshness
     * off), a FAILED one must not (so it retries on the next inquiry while
     * the fetcher's cooldown protects the host).
     */
    static final java.time.Duration FAN_FRESH = java.time.Duration.ofMinutes(15);
    /** Same nature as {@link #LIVE_FAN_LIMIT}, for the free-text engines. */
    static final int SEARCH_LIMIT = 30;
    /**
     * Same nature again, for the archive fan — deeper because a window walk
     * IS a depth question; still a request size, never a result ceiling.
     */
    static final int ARCHIVE_FAN_LIMIT = 100;

    /** (source × instrument) → last clean answer; lazily purged. */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> fanLedger =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final InstrumentRegister register;
    private final ArticlePool pool;
    private final Set<InstrumentSource> instrumentSources;
    private final Set<SearchEngine> engines;
    private final Set<de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource> archives;
    private final FactsPipeline facts;
    private final WebExecutor executor;

    @Inject
    public HouseWebGateway(InstrumentRegister register, ArticlePool pool,
            Set<InstrumentSource> instrumentSources, Set<SearchEngine> engines,
            Set<de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource> archives,
            FactsPipeline facts, WebExecutor executor) {
        this.register = register;
        this.pool = pool;
        this.instrumentSources = instrumentSources;
        this.engines = engines;
        this.archives = archives;
        this.facts = facts;
        this.executor = executor;
    }

    @Override
    public CompletableFuture<InquiryResult> searchInstrument(String query) {
        return executor.supply(() -> register.resolve(query))
                .exceptionally(e -> {
                    LOG.info("register could not place '{}': {}", query, e.toString());
                    return ResolvedInstrument.ofName(query);
                })
                .thenCompose(this::inquire);
    }

    private CompletableFuture<InquiryResult> inquire(ResolvedInstrument instrument) {
        List<SourceOutcome> outcomes = new CopyOnWriteArrayList<>();

        // Pipeline 1: the basin — local, no sockets, and UNCAPPED: the
        // contract is a collection of ALL found articles (user mandate);
        // the basin's own size ceiling is the only bound.
        CompletableFuture<List<Article>> poolHits = executor.supply(() -> {
            List<Article> hits = pool.queryInstrument(instrument, Integer.MAX_VALUE);
            outcomes.add(hits.isEmpty()
                    ? SourceOutcome.empty("pool")
                    : SourceOutcome.delivered("pool", hits.size()));
            return hits;
        });
        CompletableFuture<List<Article>> poolSentiment =
                executor.supply(() -> pool.querySentiment(instrument, Integer.MAX_VALUE));

        // Pipeline 2: the live instrument fan — one future per source, but the
        // basin is asked FIRST: a source whose ledger stamp is still fresh for
        // this instrument answered inside the window and its yield sits in the
        // pool — it is not asked again (the same pattern the collectors ride,
        // applied to on-demand resolution; user decision 2026-08-12).
        purgeLedger();
        String ledgerScope = ledgerKey(instrument);
        Instant freshAfter = Instant.now().minus(FAN_FRESH);
        List<CompletableFuture<List<Article>>> fan = new ArrayList<>();
        for (InstrumentSource source : instrumentSources) {
            String key = source.sourceName() + '|' + ledgerScope;
            Instant last = fanLedger.get(key);
            if (last != null && last.isAfter(freshAfter)) {
                outcomes.add(SourceOutcome.skipped(source.sourceName(),
                        "pool-fresh (" + java.time.Duration.between(last, Instant.now()).toSeconds()
                                + " s ago)"));
                continue;
            }
            fan.add(executor.supply(() -> {
                List<Article> items = source.newsFor(instrument, LIVE_FAN_LIMIT);
                return items == null ? List.<Article>of() : items;
            }).handle((items, error) -> {
                if (error != null) {
                    // No stamp: a failed source retries on the next inquiry.
                    outcomes.add(SourceOutcome.failed(source.sourceName(), error.toString()));
                    return List.<Article>of();
                }
                List<Article> stamped = items.stream()
                        .map(a -> a.withOrigin(source.origin())).toList();
                outcomes.add(stamped.isEmpty()
                        ? SourceOutcome.empty(source.sourceName())
                        : SourceOutcome.delivered(source.sourceName(), stamped.size()));
                // The fan's yield is knowledge the house paid for — it joins
                // the basin too, so the next inquiry starts warmer.
                pool.add(source, stamped);
                fanLedger.put(key, Instant.now());
                return stamped;
            }));
        }
        CompletableFuture<Void> fanDone =
                CompletableFuture.allOf(fan.toArray(CompletableFuture[]::new));

        // Pipeline 3: the facts graze.
        CompletableFuture<FactsPipeline.Graze> graze = facts.graze(instrument);

        return CompletableFuture.allOf(poolHits, poolSentiment, fanDone, graze)
                .thenApply(v -> {
                    Map<String, Article> merged = new LinkedHashMap<>();
                    for (Article a : poolHits.join()) merged.putIfAbsent(identity(a), a);
                    for (CompletableFuture<List<Article>> f : fan) {
                        for (Article a : f.join()) {
                            if (!a.sponsored()) merged.putIfAbsent(identity(a), a);
                        }
                    }
                    List<Article> articles = new ArrayList<>(merged.values());
                    articles.sort(Comparator.comparing(
                            (Article a) -> a.publishedAt() == null ? Instant.EPOCH : a.publishedAt())
                            .reversed());
                    List<SourceOutcome> all = new ArrayList<>(outcomes);
                    all.addAll(graze.join().outcomes());
                    return new InquiryResult(instrument, articles, poolSentiment.join(),
                            graze.join().facts(), List.copyOf(all));
                });
    }

    @Override
    public void search(String query) {
        if (query == null || query.isBlank()) return;
        for (SearchEngine engine : engines) {
            executor.execute("search:" + engine.sourceName(), () -> {
                try {
                    List<Article> items = engine.search(query, SEARCH_LIMIT);
                    int fresh = pool.add(engine, items == null ? List.of() : items);
                    LOG.debug("search '{}' via '{}': {} item(s), {} fresh",
                            query, engine.sourceName(),
                            items == null ? 0 : items.size(), fresh);
                } catch (Exception e) {
                    LOG.info("search '{}' via '{}' failed: {}",
                            query, engine.sourceName(), e.toString());
                }
            });
        }
    }

    @Override
    public CompletableFuture<InquiryResult> searchInstrumentWindow(String query,
            java.time.LocalDate fromDate, java.time.LocalDate toDateExclusive) {
        return executor.supply(() -> register.resolve(query))
                .exceptionally(e -> ResolvedInstrument.ofName(query))
                .thenCompose(instrument -> inquireWindow(instrument, fromDate, toDateExclusive));
    }

    /**
     * The archive fan: only the self-declared {@code ArchiveSource}s, one
     * future each, request size {@link #ARCHIVE_FAN_LIMIT} per source. No
     * ledger — every window is its own question. The yield still joins the
     * basin (live-entry clock), so an immediate follow-up inquiry sees it.
     */
    private CompletableFuture<InquiryResult> inquireWindow(ResolvedInstrument instrument,
            java.time.LocalDate fromDate, java.time.LocalDate toDateExclusive) {
        List<SourceOutcome> outcomes = new CopyOnWriteArrayList<>();
        List<CompletableFuture<List<Article>>> fan = new ArrayList<>();
        for (var source : archives) {
            fan.add(executor.supply(() -> {
                List<Article> items = source.newsForWindow(instrument, fromDate,
                        toDateExclusive, ARCHIVE_FAN_LIMIT);
                return items == null ? List.<Article>of() : items;
            }).handle((items, error) -> {
                if (error != null) {
                    outcomes.add(SourceOutcome.failed(source.sourceName(), error.toString()));
                    return List.<Article>of();
                }
                List<Article> stamped = items.stream()
                        .map(a -> a.withOrigin(source.origin())).toList();
                outcomes.add(stamped.isEmpty()
                        ? SourceOutcome.empty(source.sourceName())
                        : SourceOutcome.delivered(source.sourceName(), stamped.size()));
                pool.add(source, stamped);
                return stamped;
            }));
        }
        return CompletableFuture.allOf(fan.toArray(CompletableFuture[]::new))
                .thenApply(v -> {
                    Map<String, Article> merged = new LinkedHashMap<>();
                    for (CompletableFuture<List<Article>> f : fan) {
                        for (Article a : f.join()) {
                            if (!a.sponsored()) merged.putIfAbsent(identity(a), a);
                        }
                    }
                    List<Article> articles = new ArrayList<>(merged.values());
                    articles.sort(Comparator.comparing(
                            (Article a) -> a.publishedAt() == null ? Instant.EPOCH : a.publishedAt())
                            .reversed());
                    return new InquiryResult(instrument, articles, List.of(),
                            de.bsommerfeld.wsbg.terminal.web.facts.MarketFacts.EMPTY,
                            List.copyOf(outcomes));
                });
    }

    @Override
    public ArticlePool pool() {
        return pool;
    }

    private static String identity(Article a) {
        if (a.uuid() != null && !a.uuid().isBlank()) return a.uuid();
        return a.link() == null ? "" : a.link();
    }

    /** The instrument's ledger identity: hardest key wins. */
    private static String ledgerKey(ResolvedInstrument instrument) {
        if (instrument.isin().isPresent()) return instrument.isin().get().value();
        if (instrument.ticker().isPresent()) return instrument.ticker().get().value();
        return instrument.name().strip().toLowerCase(java.util.Locale.ROOT);
    }

    private void purgeLedger() {
        Instant cutoff = Instant.now().minus(FAN_FRESH);
        fanLedger.values().removeIf(at -> at.isBefore(cutoff));
    }
}
