package de.bsommerfeld.wsbg.terminal.wallstreetonline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * wallstreet-online.de instrument resolver — a <b>name → ISIN/WKN</b> bridge. The
 * EUR price venues (Lang &amp; Schwarz by name, Deutsche Börse by ISIN) both key off
 * matching that is fragile by name; WSO's keyless search returns the ISIN + WKN of
 * the German listing directly, so it lets the chain price the official Xetra quote
 * ISIN-exactly and cross-check L&amp;S's fuzzy name-pick against a structured source
 * (killing wrong-twin matches like „Mullen Automotive" → „Mullen Group").
 *
 * <p>Endpoint (keyless JSON, no bot wall on a plain client):
 * {@code www.wallstreet-online.de/_rpc/json/search/auto/searchInst/<name>} →
 * {@code {status, result:[{instId, isin, wkn, label}]}}. The {@code label} is HTML
 * (an anchor) → the display name is the stripped text.
 *
 * <p>Returns {@link Optional#empty()} on any failure or when no result confidently
 * matches the query, so the caller falls back to name-only resolution.
 */
@Singleton
public class WallstreetOnlineClient {

    private static final Logger LOG = LoggerFactory.getLogger(WallstreetOnlineClient.class);

    private static final String SEARCH_URL =
            "https://www.wallstreet-online.de/_rpc/json/search/auto/searchInst/";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Minimum share of the query's significant name tokens a hit must cover to be trusted. */
    static final double MIN_NAME_COVERAGE = 0.5;
    /** A non-coverage hit overrides the coverage pick only if its popularity ≥ this × the coverage pick's… */
    static final long POP_DOMINANCE = 20;
    /** …and is itself at least this popular (so obscure-vs-obscure never triggers the override). */
    static final long POP_DOMINANCE_FLOOR = 1000;

    private static final Set<String> NAME_STOP = Set.of(
            "etf", "ucits", "the", "and", "und", "com",
            "inc", "incorporated", "corp", "corporation", "co", "company",
            "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** name (lowercased) → resolved instrument. Loaded from disk on startup; an ISIN is fixed. */
    private final Map<String, WsoInstrument> cache = new ConcurrentHashMap<>();
    /** Append-only on-disk memory of name→ISIN (an ISIN never changes). */
    private final WsoIsinStore store;

    /** Test/default: plain direct transport, in-memory only (no disk). */
    public WallstreetOnlineClient() {
        this(new DirectWebFetcher(), null);
    }

    /** Production: rides the shared {@link WebFetcher}; persists the ISIN cache to app-data. */
    @Inject
    public WallstreetOnlineClient(WebFetcher fetcher) {
        this(fetcher, StorageUtils.getAppDataDir().resolve("wso-isin.jsonl"));
    }

    WallstreetOnlineClient(WebFetcher fetcher, Path cacheFile) {
        this.fetcher = fetcher;
        this.store = new WsoIsinStore(cacheFile, WallstreetOnlineClient::looksLikeDerivative);
        cache.putAll(store.load());
    }

    /**
     * Resolves preferring the stable TICKER key, then the name. The ticker (OTLK) is an
     * unambiguous identity, so once known it serves instantly and is remembered too — over
     * time every name variant of the same instrument funnels through one cached ISIN.
     */
    public Optional<WsoInstrument> resolve(String name, String ticker) {
        String tk = tickerKey(ticker);
        if (tk != null) {
            WsoInstrument hit = cache.get(tk);
            if (hit != null) return Optional.of(hit);
        }
        Optional<WsoInstrument> r = resolve(name);
        // Alias the ticker → the same instrument so the next lookup by ticker is instant.
        if (tk != null) r.ifPresent(w -> { if (cache.putIfAbsent(tk, w) == null) store.append(tk, w); });
        return r;
    }

    private static String tickerKey(String ticker) {
        return ticker == null || ticker.isBlank() ? null : "ticker:" + ticker.trim().toUpperCase(Locale.ROOT);
    }

    /** Resolves a subject name to a WSO instrument (ISIN + WKN), cached, or empty. */
    public Optional<WsoInstrument> resolve(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String key = name.trim().toLowerCase(Locale.ROOT);
        WsoInstrument cached = cache.get(key);
        if (cached != null) return Optional.of(cached);
        try {
            // The full Yahoo name ("NVIDIA Corporation", "Amazon.com, Inc.") makes WSO rank
            // same-named DERIVATIVES first and miss the stock (named just "NVIDIA"); the cleaned
            // name hits the share. Try cleaned, then raw. Ranking is still against the FULL name.
            Optional<WsoInstrument> inst = Optional.empty();
            for (String q : queryCandidates(name)) {
                WebResponse resp = fetcher.fetch(
                        SEARCH_URL + URLEncoder.encode(q, StandardCharsets.UTF_8),
                        Map.of("User-Agent", userAgent, "Accept", "application/json",
                                "X-Requested-With", "XMLHttpRequest"),
                        requestTimeout);
                if (resp == null || resp.status() != 200) continue;
                inst = parse(resp.body(), name);
                if (inst.isPresent()) break;
            }
            inst.ifPresent(w -> {
                if (cache.putIfAbsent(key, w) == null) {
                    store.append(key, w); // first resolution → remember it on disk (ISIN is fixed)
                    LOG.info("[WSO] '{}' → isin={} wkn={} '{}'", name, w.isin(), w.wkn(), w.name());
                }
            });
            return inst;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("WSO resolve failed for {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /** The instrument classes WSO returns that ARE the underlying — never a derivative/index. */
    private static final Set<String> ALLOWED_CLASS = Set.of("stock", "etf", "fund");

    /**
     * Picks the actual underlying: a {@code class} of stock/ETF/fund (NEVER a Knock-Out,
     * Faktor-/Mini-Future-Zertifikat or a leverage index — those carry the company name too
     * and would anchor the wrong ISIN), with a valid ISIN and a covering {@code name}, taking
     * the most-traded ({@code popularity}) match. Package-private, network-free.
     */
    Optional<WsoInstrument> parse(String body, String query) {
        try {
            JsonNode results = JSON.readTree(body).path("result");
            if (!results.isArray() || results.isEmpty()) return Optional.empty();
            JsonNode best = pickBest(results, nameTokens(query));
            if (best == null) return Optional.empty();
            return Optional.of(new WsoInstrument(
                    best.path("isin").asText("").trim().toUpperCase(Locale.ROOT),
                    blankToNull(best.path("wkn").asText("")), best.path("name").asText("")));
        } catch (Exception e) {
            LOG.debug("WSO parse failure: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The popularity-dominance ranking policy: among the eligible results (allowed class, valid
     * ISIN, non-derivative name) normally take the conservative coverage pick — the most-popular
     * hit whose name covers the query. BUT WSO's {@code popularity} is the strongest
     * disambiguator, and a company's official short name needn't share words with the room's
     * descriptive long name — "SpaceX" (US84615Q1031, pop 992371) carries ZERO coverage of
     * "Space Exploration Technologies" and was being discarded for an obscure same-named Canadian
     * twin (pop 201). So when one instrument's popularity DOMINATES (≥{@value #POP_DOMINANCE}× the
     * coverage pick) and is itself substantial, trust it. Returns the winning node, or {@code null}.
     */
    private static JsonNode pickBest(JsonNode results, List<String> want) {
        JsonNode covBest = null; long covBestPop = -1;   // most-popular that PASSES name coverage
        JsonNode popBest = null; long popBestPop = -1;   // most-popular overall (any coverage)
        for (JsonNode r : results) {
            if (!ALLOWED_CLASS.contains(r.path("class").asText("").toLowerCase(Locale.ROOT))) continue;
            String isin = r.path("isin").asText("").trim().toUpperCase(Locale.ROOT);
            if (!isValidIsin(isin)) continue;
            if (looksLikeDerivative(r.path("name").asText(""))) continue; // belt: a 'stock'-class derivative
            long pop = r.path("popularity").asLong(0);
            if (pop > popBestPop) { popBestPop = pop; popBest = r; }
            if (coverage(want, nameTokens(r.path("name").asText(""))) >= MIN_NAME_COVERAGE
                    && pop > covBestPop) { covBestPop = pop; covBest = r; }
        }
        JsonNode best = covBest;
        if (popBest != null && popBestPop >= POP_DOMINANCE_FLOOR
                && (covBest == null || popBestPop >= covBestPop * POP_DOMINANCE)) {
            best = popBest;
        }
        return best;
    }

    private static boolean isValidIsin(String s) {
        return s.length() == 12 && Character.isLetter(s.charAt(0)) && Character.isLetter(s.charAt(1));
    }

    /** Derivative/leverage product name markers — never the underlying we want to anchor. */
    private static boolean looksLikeDerivative(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("mini future") || n.contains("faktor") || n.contains("knock")
                || n.contains("zertifikat") || n.contains("optionsschein") || n.contains("turbo")
                || n.contains("open end") || n.contains("open-end") || n.contains(" etp")
                || n.contains("hebel") || n.contains("discount") || n.contains("bonus")
                || n.contains("wave") || n.contains("endlos");
    }

    /** Search queries to try, cleanest first: legal-suffix/.com-stripped name, then the raw name. */
    static List<String> queryCandidates(String name) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String cleaned = cleanForSearch(name);
        if (!cleaned.isBlank()) out.add(cleaned);
        out.add(name.trim());
        out.removeIf(String::isBlank);
        return new ArrayList<>(out);
    }

    /** Strips a comma tail (", Inc."), a trailing ".com" and a trailing legal/corporate token. */
    static String cleanForSearch(String name) {
        String s = name == null ? "" : name.trim();
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma).trim();
        s = s.replaceAll("(?i)\\.com$", "").trim();
        s = s.replaceAll("(?i)\\s+(Inc|Incorporated|Corp|Corporation|Company|Co|Holdings?|Group|"
                + "PLC|Ltd|Limited|AG|SE|NV|N\\.V\\.|SA|S\\.A\\.|ADR)\\.?$", "").trim();
        return s.isEmpty() ? (name == null ? "" : name.trim()) : s;
    }

    /** Significant (≥3-char, non-stop, non-numeric) lower-case tokens of a name. */
    static List<String> nameTokens(String s) {
        if (s == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")) {
            if (t.length() < 3 || NAME_STOP.contains(t)) continue;
            if (t.chars().allMatch(Character::isDigit)) continue;
            out.add(t);
        }
        return out;
    }

    /** Fraction of {@code want} tokens that prefix-match (≥3 chars) some {@code have} token. */
    static double coverage(List<String> want, List<String> have) {
        if (want.isEmpty()) return 0.0;
        int hit = 0;
        for (String w : want) {
            for (String h : have) {
                int min = Math.min(w.length(), h.length());
                if (w.equals(h) || (min >= 3 && w.regionMatches(0, h, 0, min))) { hit++; break; }
            }
        }
        return (double) hit / want.size();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
