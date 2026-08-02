package de.bsommerfeld.wsbg.terminal.finanzennet;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The strongest keyless instrument resolver found in the 2026-08-02 source
 * survey: ONE call maps a name, an ISIN, a WKN or a ticker onto all the others
 * AND onto finanzen.net's slug, from which every sub-page is built
 * deterministically ({@code /boersenplaetze/<slug>}, {@code /kursziele/<slug>},
 * {@code /rss/<slug>-rss-feed}, …).
 *
 * <pre>
 * https://www.finanzen.net/ajax/SearchController_Suggest?max_results=25&amp;query=&lt;q&gt;
 * </pre>
 *
 * <h3>The payload</h3>
 * JSONP-shaped, not JSON: a {@code mmSuggestDeliver(0, new Array("Name",
 * "Category", "Keywords", "Bias", "Extension", "IDs"), new Array(new Array(…),
 * …), n, 0)} call. Every hit is a six-slot array; two of the slots are
 * pipe-separated sub-records:
 *
 * <ul>
 *   <li>{@code Keywords} = {@code WKN|ISIN|ticker|ticker|ticker} - the WKN and
 *       ISIN slots can be EMPTY (unsponsored ADRs carry no WKN), which is why
 *       the parser addresses them positionally and never by "first token".</li>
 *   <li>{@code IDs} = {@code slug|WKN|type|internalId}</li>
 * </ul>
 *
 * <h3>Two things that will bite a naive parser</h3>
 * <ol>
 *   <li><b>Advertising rides in the result array.</b> Entries with
 *       {@code Category = "Anzeige"} carry HTML markup in the name slot and a
 *       full {@code https://g.finanzen.net/…} tracking URL where the slug
 *       belongs. They are dropped, not resolved.</li>
 *   <li><b>The list is a SUGGEST list, not a match.</b> "NVDA" answers NVIDIA
 *       first and then Thai Oil, Nedap, Noda … The caller-facing
 *       {@link #resolve} therefore re-ranks: an exact ISIN/WKN/ticker/name hit
 *       wins over the server's own {@code Bias} ordering, and
 *       {@link #resolveByIsin} refuses anything whose ISIN slot does not match.</li>
 * </ol>
 *
 * <h3>robots.txt</h3>
 * ⚠️ finanzen.net's robots.txt carries {@code Disallow: /ajax/} - and this
 * resolver lives exactly there (also {@code Disallow: /suchergebnis.asp}). The
 * instrument and analysis pages the slug leads to are NOT disallowed. The
 * project owner released this source deliberately; the restriction is recorded
 * here so the decision stays visible at the call site rather than buried in a
 * research note. The ToS additionally forbid archiving the content in a
 * database - this module reads, it does not build a store.
 *
 * <p>Everything above verified live 2026-08-02, small caps included (artec
 * technologies, Nynomic, Vita 34 all resolve with every sub-page answering 200).
 */
@Singleton
public class FinanzenNetResolver {

    private static final Logger LOG = LoggerFactory.getLogger(FinanzenNetResolver.class);

    static final String SUGGEST_URL =
            "https://www.finanzen.net/ajax/SearchController_Suggest?max_results=%d&query=%s";

    /** finanzen.net's own marker for a paid slot in the suggest list. */
    static final String CATEGORY_AD = "Anzeige";

    private static final int DEFAULT_MAX_RESULTS = 25;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration CACHE_TTL = Duration.ofHours(6);
    /** A resolver answer is stable for hours; a bounded LRU keeps the fetches down. */
    private static final int CACHE_CAPACITY = 256;

    private static final Pattern INNER_ARRAY =
            Pattern.compile("new Array\\(((?:\"(?:\\\\.|[^\"\\\\])*\"\\s*,?\\s*)+)\\)");
    private static final Pattern QUOTED =
            Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern ISIN_SHAPE = Pattern.compile("^[A-Z]{2}[A-Z0-9]{9}\\d$");

    private final FinanzenNetHttp.Identity identity = FinanzenNetHttp.randomIdentity();
    private final WebFetcher fetcher;

    private final Map<String, Cached> cache =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_CAPACITY;
                }
            });

    private record Cached(java.time.Instant at, List<InstrumentMatch> hits) {}

    /**
     * One resolved instrument.
     *
     * @param name       the display name as finanzen.net prints it
     * @param slug       the URL slug every sub-page is built from, e.g. {@code sap}
     * @param wkn        German securities number, or null when the paper has none
     * @param isin       ISIN, or null (a few depositary receipts carry none)
     * @param tickers    every ticker slot the suggest record filled, in order
     * @param category   finanzen.net's own bucket ({@code Aktien}, {@code ETF}, …)
     * @param internalId the site-internal numeric id (last slot of {@code IDs})
     */
    public record InstrumentMatch(String name, String slug, String wkn, String isin,
                                  List<String> tickers, String category, int internalId) {

        public InstrumentMatch {
            tickers = tickers == null ? List.of() : List.copyOf(tickers);
        }

        /** {@code https://www.finanzen.net/<section>/<slug>} for any sub-page. */
        public String pageUrl(String section) {
            return "https://www.finanzen.net/" + section + "/" + slug;
        }

        /** The per-instrument RSS feed URL. */
        public String rssUrl() {
            return "https://www.finanzen.net/rss/" + slug + "-rss-feed";
        }
    }

    /** Test/default: plain direct transport (the header set alone opens the wall). */
    public FinanzenNetResolver() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: rides the shared standard {@link WebFetcher} chain
     * (browser → direct). The complete header set answers 200 on its own, but
     * against an Akamai wall browser-first is the safer default - if the
     * fingerprint rules ever tighten, the joker already carries a real session
     * and the direct leg stays as the cheap fallback.
     */
    @Inject
    public FinanzenNetResolver(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Resolves a name, ISIN, WKN or ticker. The best match first: an exact
     * identifier hit outranks finanzen.net's own suggest order, which is
     * prefix-driven and will happily lead with a same-stem foreign paper.
     *
     * @return the ranked matches, ads removed - empty when nothing resolves
     */
    public List<InstrumentMatch> resolve(String query) {
        if (query == null || query.isBlank()) return List.of();
        String q = query.trim();
        List<InstrumentMatch> hits = suggest(q, DEFAULT_MAX_RESULTS);
        if (hits.isEmpty()) return List.of();
        List<InstrumentMatch> ranked = new ArrayList<>(hits);
        ranked.sort((a, b) -> Integer.compare(score(b, q), score(a, q)));
        return List.copyOf(ranked);
    }

    /** The single best match for {@code query}, or empty. */
    public Optional<InstrumentMatch> resolveOne(String query) {
        List<InstrumentMatch> hits = resolve(query);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    /**
     * ISIN → instrument, STRICT: only a hit whose own ISIN slot equals the
     * queried one is returned. The ISIN is the one identity a listed paper must
     * have, so a near-miss here would be a wrong instrument, never a weaker one.
     */
    public Optional<InstrumentMatch> resolveByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String wanted = isin.trim().toUpperCase(Locale.ROOT);
        if (!ISIN_SHAPE.matcher(wanted).matches()) return Optional.empty();
        return suggest(wanted, DEFAULT_MAX_RESULTS).stream()
                .filter(m -> wanted.equalsIgnoreCase(m.isin()))
                .findFirst();
    }

    /** WKN → instrument, strict on the WKN slot. */
    public Optional<InstrumentMatch> resolveByWkn(String wkn) {
        if (wkn == null || wkn.isBlank()) return Optional.empty();
        String wanted = wkn.trim().toUpperCase(Locale.ROOT);
        return suggest(wanted, DEFAULT_MAX_RESULTS).stream()
                .filter(m -> wanted.equalsIgnoreCase(m.wkn()))
                .findFirst();
    }

    /** Ticker → instrument, strict on the ticker slots. */
    public Optional<InstrumentMatch> resolveByTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) return Optional.empty();
        String wanted = ticker.trim().toUpperCase(Locale.ROOT);
        return suggest(wanted, DEFAULT_MAX_RESULTS).stream()
                .filter(m -> m.tickers().stream().anyMatch(wanted::equalsIgnoreCase))
                .findFirst();
    }

    /** Convenience for the news/market clients: query → slug. */
    public Optional<String> slugFor(String query) {
        return resolveOne(query).map(InstrumentMatch::slug);
    }

    /**
     * GENERAL surface: the raw suggest list for a free query, in the server's
     * own order and with no re-ranking - a type-ahead exactly as the site's own
     * search box shows it. Ads are still removed.
     *
     * <p>Currently unused by the terminal; it stands ready so a search UI can
     * be wired without reopening this source (house mandate 2026-08-02: every
     * source is connected per instrument AND generally).
     */
    public List<InstrumentMatch> suggestions(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        List<InstrumentMatch> hits = suggest(query.trim(), Math.min(limit, 50));
        return hits.size() <= limit ? hits : List.copyOf(hits.subList(0, limit));
    }

    /** One suggest call, TTL-cached per query. */
    private List<InstrumentMatch> suggest(String query, int maxResults) {
        String key = FinanzenNetHttp.lower(query) + "#" + maxResults;
        Cached c = cache.get(key);
        if (c != null && c.at().plus(CACHE_TTL).isAfter(java.time.Instant.now())) {
            return c.hits();
        }
        String url = String.format(SUGGEST_URL, maxResults,
                URLEncoder.encode(query, StandardCharsets.UTF_8));
        List<InstrumentMatch> hits = List.of();
        try {
            WebResponse resp = fetcher.fetch(url, FinanzenNetHttp.headers(identity, true),
                    REQUEST_TIMEOUT);
            if (resp != null && resp.status() == 200) {
                hits = parseSuggest(resp.body());
            } else {
                LOG.debug("finanzen.net suggest '{}' answered status {}", query,
                        resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            LOG.debug("finanzen.net suggest '{}' failed: {}", query, e.getMessage());
        }
        if (!hits.isEmpty()) cache.put(key, new Cached(java.time.Instant.now(), hits));
        return hits;
    }

    /**
     * Parses the {@code mmSuggestDeliver(…)} envelope. The first inner array is
     * the COLUMN HEADER ({@code "Name", "Category", …}) and is skipped; every
     * following six-slot array is a hit. Package-private for tests.
     */
    static List<InstrumentMatch> parseSuggest(String body) {
        if (body == null || body.isBlank() || !body.contains("mmSuggestDeliver")) return List.of();
        List<InstrumentMatch> out = new ArrayList<>();
        try {
            Matcher m = INNER_ARRAY.matcher(body);
            boolean first = true;
            while (m.find()) {
                List<String> slots = quotedSlots(m.group(1));
                if (first) {
                    first = false;
                    if (!slots.isEmpty() && "Name".equals(slots.get(0))) continue;
                }
                if (slots.size() < 6) continue;
                InstrumentMatch match = toMatch(slots);
                if (match != null) out.add(match);
            }
        } catch (RuntimeException e) {
            LOG.warn("Unparseable finanzen.net suggest payload: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /** One six-slot record → a match, or null for ads / unusable rows. */
    private static InstrumentMatch toMatch(List<String> slots) {
        String name = unescapeJs(slots.get(0));
        String category = unescapeJs(slots.get(1));
        String keywords = unescapeJs(slots.get(2));
        String ids = unescapeJs(slots.get(5));

        if (CATEGORY_AD.equalsIgnoreCase(category)) return null;   // paid slot
        if (name.contains("<")) return null;                        // markup = ad shape
        if (ids.isBlank() || ids.startsWith("http")) return null;    // tracking URL, no slug

        String[] idParts = ids.split("\\|", -1);
        String slug = idParts.length > 0 ? idParts[0].trim() : "";
        if (slug.isEmpty()) return null;
        int internalId = idParts.length > 3 ? parseIntOrZero(idParts[3]) : 0;

        // Keywords = WKN | ISIN | ticker | ticker | ticker ; slots may be empty.
        String[] kw = keywords.split("\\|", -1);
        String wkn = blankToNull(kw.length > 0 ? kw[0] : null);
        String isin = blankToNull(kw.length > 1 ? kw[1] : null);
        if (isin != null && !ISIN_SHAPE.matcher(isin.toUpperCase(Locale.ROOT)).matches()) {
            isin = null;
        }
        if (wkn == null && idParts.length > 1) wkn = blankToNull(idParts[1]);

        Set<String> tickers = new LinkedHashSet<>();
        for (int i = 2; i < kw.length; i++) {
            String t = blankToNull(kw[i]);
            // The last keyword slot sometimes carries a venue-qualified code
            // ("2595089,FRA") or a numeric local code - not a ticker.
            if (t == null || t.contains(",") || t.chars().allMatch(Character::isDigit)) continue;
            tickers.add(t.toUpperCase(Locale.ROOT));
        }
        return new InstrumentMatch(name, slug, wkn,
                isin == null ? null : isin.toUpperCase(Locale.ROOT),
                List.copyOf(tickers), category, internalId);
    }

    /** Exact-identifier hits rank above name hits, name hits above the rest. */
    static int score(InstrumentMatch m, String query) {
        String q = query.trim();
        String qu = q.toUpperCase(Locale.ROOT);
        if (qu.equalsIgnoreCase(m.isin())) return 100;
        if (qu.equalsIgnoreCase(m.wkn())) return 90;
        if (m.tickers().stream().anyMatch(qu::equalsIgnoreCase)) return 80;
        String name = FinanzenNetHttp.lower(m.name());
        String ql = FinanzenNetHttp.lower(q);
        if (name.equals(ql)) return 70;
        if (name.startsWith(ql)) return 60;
        if (name.contains(ql)) return 40;
        return 10;
    }

    private static List<String> quotedSlots(String arrayBody) {
        List<String> out = new ArrayList<>();
        Matcher q = QUOTED.matcher(arrayBody);
        while (q.find()) out.add(q.group(1));
        return out;
    }

    /** Undoes the JS string escaping the payload uses ({@code \"} inside markup). */
    private static String unescapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/").trim();
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static int parseIntOrZero(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Exposed for tests: the identifier slots a match carries, flattened. */
    static List<String> identifiers(InstrumentMatch m) {
        List<String> out = new ArrayList<>(Arrays.asList(m.wkn(), m.isin()));
        out.addAll(m.tickers());
        out.removeIf(java.util.Objects::isNull);
        return out;
    }
}
