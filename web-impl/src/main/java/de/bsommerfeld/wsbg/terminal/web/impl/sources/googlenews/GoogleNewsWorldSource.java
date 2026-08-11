package de.bsommerfeld.wsbg.terminal.web.impl.sources.googlenews;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.feed.FeedParser;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Google News beyond the home edition - the SAME keyless RSS search as
 * {@link GoogleNewsSource}, asked once per world EDITION
 * ({@code hl}/{@code gl}/{@code ceid}), which is the whole point: Google
 * indexes each language area separately, so the German edition is not a
 * smaller view of the world press but a different one. A company's Chinese
 * suppliers, a Russian counterparty's court date, a Korean order book - these
 * are carried by houses the German and English indexes never surface
 * (editions live-probed 2026-08-11).
 *
 * <p>Each edition carries its own {@link SourceOrigin}, stamped onto the items
 * it produced - not because the outlet is judged here, but because "the
 * Chinese-language index carried it" is a fact about the query. That stamp is
 * what lets the deep dive count independent SPHERES instead of domains, and it
 * is why the Chinese and the Taiwanese edition are two spheres rather than one
 * language: two indexes under different gatekeeping, reporting the same firm.
 * Where they differ, the difference is the finding.
 *
 * <p>An {@link InstrumentSource} and, via {@code after:}/{@code before:}
 * query operators, an {@link ArchiveSource}: thirteen editions per query is
 * research depth (what the old {@code dossierOnly()} flag said, the contract
 * kind says now) - the home edition keeps the wire and the free-search door.
 *
 * <p>The finance-bias suffix travels WITH the edition. The measured lesson
 * that the bare name returns general press while the "Aktie" suffix pulls the
 * finance desk does not transfer through translation of the query - each
 * edition names its own word for it, exactly as it names its own host
 * parameters.
 */
@Singleton
public final class GoogleNewsWorldSource extends AbstractWebSource
        implements InstrumentSource, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleNewsWorldSource.class);

    private static final String SEARCH_URL = "https://news.google.com/rss/search";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    /** How many editions are asked at once - one request per host, per edition. */
    private static final int FAN_WIDTH = 6;

    /**
     * One Google News edition: its three host parameters, the word that biases
     * a query towards the finance desk in that language, and the origin every
     * item out of it carries.
     *
     * @param id     short stable id for logging ({@code "cn"})
     * @param hl     interface language ({@code "zh-CN"})
     * @param gl     country ({@code "CN"})
     * @param ceid   edition id ({@code "CN:zh-Hans"})
     * @param suffix finance-desk bias appended to a name query
     */
    record Edition(String id, String hl, String gl, String ceid, String suffix,
            SourceOrigin origin) {

        String url(String query) {
            return SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&hl=" + hl + "&gl=" + gl + "&ceid=" + ceid;
        }
    }

    private static Edition edition(String id, String hl, String gl, String ceid,
            String suffix, String language, String sphere) {
        return new Edition(id, hl, gl, ceid, suffix, SourceOrigin.of(language, sphere));
    }

    /**
     * The world editions, home edition excluded (that one is
     * {@link GoogleNewsSource}'s). Chinese appears TWICE on purpose - the
     * mainland and the Taiwanese index are separate spheres, and a pair of
     * spheres in one language is the cleanest triangulation the net can offer.
     */
    static final List<Edition> EDITIONS = List.of(
            edition("us", "en-US", "US", "US:en", "stock", "en", "US"),
            // The UK edition stands in for a door the house could not open:
            // the London exchange's own regulatory news wire has no keyless
            // reader (its refresh endpoint answers 200 with an empty list for
            // every parameter tried, 2026-08-11). This is not that wire - it
            // is the British press reporting on it - and it is what there is.
            edition("gb", "en-GB", "GB", "GB:en", "share price", "en", "GB"),
            edition("cn", "zh-CN", "CN", "CN:zh-Hans", "股票", "zh", "CN"),
            edition("tw", "zh-TW", "TW", "TW:zh-Hant", "股價", "zh", "TW"),
            edition("ru", "ru", "RU", "RU:ru", "акции", "ru", "RU"),
            edition("jp", "ja", "JP", "JP:ja", "株価", "ja", "JP"),
            edition("kr", "ko", "KR", "KR:ko", "주가", "ko", "KR"),
            edition("in", "en-IN", "IN", "IN:en", "share price", "en", "IN"),
            edition("es", "es", "ES", "ES:es", "acciones", "es", "ES"),
            edition("br", "pt-BR", "BR", "BR:pt-419", "ações", "pt", "BR"),
            edition("fr", "fr", "FR", "FR:fr", "action bourse", "fr", "FR"),
            edition("ar", "ar", "EG", "EG:ar", "سهم", "ar", "AR"),
            edition("tr", "tr", "TR", "TR:tr", "hisse", "tr", "TR"));

    /** Per edition|query politeness cache: parsed, origin-stamped, uncapped items. */
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
    private volatile ExecutorService fanPool;

    private record CachedResult(Instant fetchedAt, List<Article> items) {}

    @Inject
    public GoogleNewsWorldSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "google-news-world";
    }

    /** Same door as the home edition: the direct RSS path is what delivers. */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /**
     * Additive key fan across the editions: the NAME leg asks each edition its
     * own finance-biased form of the name; the ISIN leg asks the one key that
     * reads identically in every script - the query that survives translation
     * untouched, precise by construction, so it rides with no title filter.
     * A ticker symbol means nothing to Google's search; it takes no leg.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (limit <= 0) return List.of();
        Map<String, Article> merged = new LinkedHashMap<>();
        String name = instrument.name();
        if (name != null && !name.isBlank()) {
            String cleaned = GoogleNewsSource.cleanName(name);
            for (Article a : fan(e -> cleaned + " " + e.suffix(), name, limit)) {
                merged.putIfAbsent(a.uuid(), a);
            }
        }
        instrument.isin().ifPresent(isin -> {
            String q = isin.value();
            for (Article a : fan(e -> q, null, limit)) {
                merged.putIfAbsent(a.uuid(), a);
            }
        });
        List<Article> out = new ArrayList<>(merged.values());
        return out.size() <= limit ? out : List.copyOf(out.subList(0, limit));
    }

    /**
     * The multi-year press history, per edition. The years are where the
     * language areas diverge most: an event covered everywhere today was
     * covered in one sphere only when it happened. Name-addressed like the
     * live fan's name leg - the archive leg takes no ISIN query.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        String name = instrument.name();
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        String cleaned = GoogleNewsSource.cleanName(name);
        return fan(e -> cleaned + " " + e.suffix() + " after:" + fromDate
                + " before:" + toDateExclusive, name, limit);
    }

    /**
     * Asks every edition its own form of the query, side by side, and returns
     * the union in edition order - each item stamped with the origin of the
     * index that carried it. One edition failing costs that edition, never the
     * fan.
     */
    private List<Article> fan(Function<Edition, String> query, String relevanceName, int limit) {
        List<Future<List<Article>>> futures = new ArrayList<>(EDITIONS.size());
        for (Edition e : EDITIONS) {
            futures.add(pool().submit(() -> search(e, query.apply(e), relevanceName, limit)));
        }
        List<Article> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                for (Article item : futures.get(i).get()) {
                    if (seen.add(item.link() == null ? item.uuid() : item.link())) out.add(item);
                }
            } catch (InterruptedException ie) {
                for (int j = i; j < futures.size(); j++) futures.get(j).cancel(true);
                Thread.currentThread().interrupt();
                return out;
            } catch (Exception ex) {
                LOG.debug("google-news edition {} failed: {}",
                        EDITIONS.get(i).id(), ex.getMessage());
            }
        }
        return out;
    }

    private List<Article> search(Edition edition, String query, String relevanceName,
            int limit) {
        String cacheKey = edition.id() + "|" + query.toLowerCase(Locale.ROOT);
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = get(edition.url(query),
                    Map.of("Accept", "application/rss+xml, application/xml, text/xml"),
                    REQUEST_TIMEOUT);
            if (resp == null || resp.status() != 200) {
                LOG.debug("google-news [{}] answered status {} for '{}'", edition.id(),
                        resp == null ? "null" : resp.status(), query);
                return List.of();
            }
            if (!FeedParser.looksLikeFeed(resp.body())) {
                LOG.warn("google-news [{}] answered a 200 that is not RSS for '{}' — "
                        + "consent/captcha wall, this edition is starving",
                        edition.id(), query);
                return List.of();
            }
            List<Article> parsed = GoogleNewsSource.parse(resp.body(), relevanceName);
            List<Article> items = new ArrayList<>(parsed.size());
            for (Article item : parsed) items.add(item.withOrigin(edition.origin()));
            cache.put(cacheKey, new CachedResult(Instant.now(), items));
            if (!items.isEmpty()) {
                LOG.info("[google-news-world] {} '{}' → {} item(s)",
                        edition.ceid(), query, items.size());
            }
            return cap(items, limit);
        } catch (Exception e) {
            LOG.debug("google-news [{}] failed for '{}': {}", edition.id(), query,
                    e.getMessage());
            return List.of();
        }
    }

    private static List<Article> cap(List<Article> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    private ExecutorService pool() {
        ExecutorService p = fanPool;
        if (p != null) return p;
        synchronized (this) {
            if (fanPool == null) {
                AtomicInteger n = new AtomicInteger();
                fanPool = Executors.newFixedThreadPool(FAN_WIDTH, r -> {
                    Thread t = new Thread(r, "gnews-world-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
            }
            return fanPool;
        }
    }
}
