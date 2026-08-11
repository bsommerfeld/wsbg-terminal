package de.bsommerfeld.wsbg.terminal.web.impl.sources.cnbc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * CNBC's queryly full-text search as the US financial PRESS leg - keyless, no
 * wall, no cookie, no key (probed 2026-08-02). The instrument-addressed door
 * AND the multi-year archive in one endpoint
 * ({@code api.queryly.com/cnbc/json.aspx}): "nvidia" answers 15.795 hits,
 * {@code endindex} pages deep (probed to 15.000) back to at least 2016,
 * {@code sort=date} makes it chronological. The {@code queryly_key} is
 * hardcoded in CNBC's own frontend, not a secret. (The section RSS firehose
 * lives in the curated feed catalog, not here.)
 *
 * <p><b>Paywall handling.</b> CNBC Pro articles answer 200 with an EMPTY body,
 * so a downstream full-text reader would burn a fetch on nothing. Results are
 * marked BEFORE any body fetch: they carry
 * {@code cn:contentClassification} ({@code premium}, {@code investingClub},
 * {@code registeredOnly}, {@code exclusive}) and such items get the
 * {@link #PUBLISHER_GATED} publisher, so a reader can skip the body and keep
 * the headline - the headline is the part that carries the signal anyway.
 *
 * <p><b>Video items are dropped.</b> {@code cn:type: cnbcvideo} has no article
 * text; a headline pointing at a player is noise in a press loom.
 *
 * <p>Name-addressed only as an instrument source: CNBC tags no tickers and no
 * ISINs, so the symbol and ISIN keys stay unused rather than guessing a bare
 * symbol into a full-text query (a two-letter ticker would drag in every
 * passing mention). The instrument fan is server-side date-sorted and
 * additionally precision-cut against title AND teaser; the free
 * {@link #search} query stays uncut - a theme query ("tariffs") has no name
 * to cut against.
 *
 * <p>Transport {@code BROWSER,DIRECT}: CNBC carries no wall today (12 search
 * calls in a burst all answered 200), but the shared chain costs nothing at
 * this cadence and survives one growing.
 */
@Singleton
public class CnbcSearchSource extends AbstractWebSource
        implements InstrumentSource, SearchEngine, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(CnbcSearchSource.class);

    static final String PUBLISHER = "CNBC";
    /** Publisher marker for Pro/gated pieces - a body fetch on these yields 0 chars. */
    static final String PUBLISHER_GATED = "CNBC Pro";

    static final String SEARCH_URL =
            "https://api.queryly.com/cnbc/json.aspx?queryly_key=31a35d40a9a64ab3"
                    + "&query=%s&endindex=%d&batchsize=%d&sort=%s&timezoneoffset=0";

    /** Search pages to walk at most when filling an instrument fan or an archive window. */
    private static final int MAX_PAGES = 12;
    private static final int SEARCH_BATCH = 50;

    /** Content classifications that mean "body is behind a wall". */
    private static final Set<String> GATED = Set.of(
            "premium", "investingclub", "registeredonly", "exclusive");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** {@code 2026-07-27T21:10:17+0000} - queryly's offset carries no colon. */
    private static final DateTimeFormatter SEARCH_DATE = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ssZ")
            .toFormatter(Locale.ROOT);

    private final Duration requestTimeout = Duration.ofSeconds(12);

    @Inject
    public CnbcSearchSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "cnbc-search";
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.BROWSER, FetchUtil.DIRECT};
    }

    /**
     * By name over the full-text search, {@code sort=date}, precision-cut
     * against title AND teaser (CNBC headlines abbreviate, the teaser prints
     * the full name), freshest first. Pages {@code endindex} forward until the
     * limit is filled or a page comes back empty.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (instrument == null || limit <= 0) return List.of();
        String name = instrument.name();
        if (name == null || name.isBlank()) return List.of();
        Set<String> words = TextMatch.significantWords(name);
        if (words.isEmpty()) return List.of();

        Map<String, Article> out = new java.util.LinkedHashMap<>();
        for (int page = 0; page < MAX_PAGES && out.size() < limit; page++) {
            List<Article> batch = searchPage(name, page * SEARCH_BATCH, SEARCH_BATCH, "date");
            if (batch.isEmpty()) break;
            for (Article it : batch) {
                if (TextMatch.matchesAny(haystack(it), words)) out.putIfAbsent(it.uuid(), it);
            }
        }
        return out.values().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * ARCHIVE window over the search leg: {@code sort=date} makes the result
     * chronological, so paging walks backwards until the window's lower bound
     * falls out of view (capped at {@link #MAX_PAGES} - a name with a
     * five-figure hit count must not turn one dossier into a crawl). CNBC tags
     * no ISINs, so the name is the one key the window fan can be asked with.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        if (instrument == null || limit <= 0) return List.of();
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank()) return List.of();
        Instant from = startOfDay(fromDate);
        Instant toExcl = startOfDay(toDateExclusive);
        if (from == null || toExcl == null) return List.of();
        Set<String> words = TextMatch.significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<Article> out = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES && out.size() < limit; page++) {
            List<Article> batch = searchPage(companyName, page * SEARCH_BATCH, SEARCH_BATCH, "date");
            if (batch.isEmpty()) break;
            boolean reachedBefore = false;
            for (Article it : batch) {
                Instant at = it.publishedAt();
                if (at == null) continue;
                if (at.isBefore(from)) {
                    reachedBefore = true;
                    continue;
                }
                if (!at.isBefore(toExcl)) continue; // still newer than the window
                if (TextMatch.matchesAny(haystack(it), words)) out.add(it);
            }
            if (reachedBefore) break; // paged past the window's lower bound
        }
        return out.stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL full-text search, relevance-ranked - the raw search leg without
     * the name-precision cut {@link #newsFor} applies. Useful for themes
     * rather than instruments ("tariffs", "rate cut"), which is why it takes a
     * free query and does no title filtering.
     */
    @Override
    public List<Article> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return searchPage(query, 0, Math.min(limit, SEARCH_BATCH), "").stream()
                .limit(limit)
                .toList();
    }

    /** One search page; {@code sort} is {@code date} or blank for relevance. */
    private List<Article> searchPage(String query, int endindex, int batchsize, String sort) {
        String url = String.format(SEARCH_URL,
                URLEncoder.encode(query, StandardCharsets.UTF_8), endindex, batchsize, sort);
        try {
            WebResponse resp = get(url, Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parseSearch(resp.body());
            }
            LOG.debug("CNBC search answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("CNBC search failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Parses a queryly search page ({@code results[]}). Gated pieces keep their
     * headline but are published under {@link #PUBLISHER_GATED} so a body
     * reader can skip them; video entries drop out. Package-private for tests.
     */
    static List<Article> parseSearch(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
        try {
            JsonNode results = MAPPER.readTree(json).path("results");
            for (JsonNode n : results) {
                String title = text(n, "cn:title");
                String url = text(n, "url");
                if (url == null) url = text(n, "cn:liveURL");
                if (title == null || url == null) continue;
                if (isVideo(text(n, "cn:type"))) continue;

                String id = text(n, "@id");
                String summary = text(n, "summary");
                if (summary == null) summary = text(n, "description");
                out.add(new Article(
                        id != null ? id : url,
                        title,
                        isGated(n) ? PUBLISHER_GATED : PUBLISHER,
                        url,
                        parseSearchDate(text(n, "datePublished")),
                        List.of(),
                        null,
                        summary,
                        false,
                        text(n, "cn:promoImage")));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable CNBC search payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /** True when {@code cn:contentClassification} marks the body as walled. */
    static boolean isGated(JsonNode item) {
        JsonNode cls = item.path("cn:contentClassification");
        if (cls.isArray()) {
            for (JsonNode c : cls) {
                if (GATED.contains(c.asText("").toLowerCase(Locale.ROOT))) return true;
            }
        }
        // A PRO section carries the same wall even when the classification is empty.
        String section = item.path("section").asText("");
        return section.startsWith("PRO:") || section.equals("Investing Club");
    }

    static boolean isVideo(String type) {
        return type != null && type.toLowerCase(Locale.ROOT).contains("video");
    }

    /** Title plus teaser - CNBC headlines abbreviate, the teaser prints the full name. */
    private static String haystack(Article it) {
        return it.summary() == null ? it.title() : it.title() + " " + it.summary();
    }

    /** {@code 2026-07-27T21:10:17+0000} → {@link Instant}; unparseable → null. */
    static Instant parseSearchDate(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            return OffsetDateTime.parse(v, SEARCH_DATE).toInstant();
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** UTC start of day, or null for a missing bound. */
    static Instant startOfDay(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }
}
