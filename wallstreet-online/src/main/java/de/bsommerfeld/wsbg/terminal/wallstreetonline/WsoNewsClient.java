package de.bsommerfeld.wsbg.terminal.wallstreetonline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * German-stock news via wallstreet-online's keyless news search
 * ({@code /_rpc/json/search/auto/searchNews/<name>}) — the quickest fix for the
 * German small-cap news GAP: Yahoo carries no XETRA small-cap catalysts (live
 * proof: Meta Wolf AG ran +25.8 % on its CERAM-TECH transformation; XETRA/WSO
 * had the news, Yahoo had nothing), so the compose brief said "ohne klaren
 * Katalysator". WSO's search covers exactly that segment and is addressed by
 * NAME, so this source implements {@link #newsForName} and leaves the
 * symbol-addressed {@link #newsFor} a no-op.
 *
 * <p>Precision over recall: WSO's search is relevance-ordered and includes
 * multi-ticker promo roundups, so only items whose TITLE actually carries a
 * significant word of the queried name are returned — a wrong-company headline
 * in the brief is worse than a missing one.
 *
 * <p>The publish date rides inside the result's {@code label} HTML: a
 * {@code previous-day} span with {@code dd.MM.yy} for older items, a bare
 * {@code HH:mm:ss} for today's. Unparseable dates yield {@code null}
 * (the aggregator sorts those last), never a guessed timestamp.
 */
@Singleton
public class WsoNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(WsoNewsClient.class);

    private static final String NEWS_SEARCH_URL =
            "https://www.wallstreet-online.de/_rpc/json/search/auto/searchNews/";
    private static final String BASE = "https://www.wallstreet-online.de";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Test/default: plain direct transport. */
    public WsoNewsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the shared {@link WebFetcher} chain (browser → direct). */
    @Inject
    public WsoNewsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "wallstreet-online";
    }

    /** WSO is name-addressed — a ticker symbol means nothing to its search. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        try {
            // The full Yahoo legal name ("NVIDIA Corporation", "Amazon.com, Inc.")
            // misses on WSO — search the cleaned short form first, raw as fallback
            // (same lesson as WallstreetOnlineClient's ISIN search).
            for (String q : queryCandidates(companyName)) {
                WebResponse resp = fetcher.fetch(
                        NEWS_SEARCH_URL + URLEncoder.encode(q, StandardCharsets.UTF_8),
                        Map.of("User-Agent", userAgent, "Accept", "application/json",
                                "X-Requested-With", "XMLHttpRequest"),
                        requestTimeout);
                if (resp == null || resp.status() != 200) continue;
                List<RawNewsItem> items = parse(resp.body(), companyName, limit);
                if (!items.isEmpty()) return items;
            }
        } catch (Exception e) {
            LOG.debug("WSO news search failed for '{}': {}", companyName, e.getMessage());
        }
        return List.of();
    }

    /** Cleaned short form first (comma-cut, trailing legal suffixes stripped), raw as fallback. */
    static List<String> queryCandidates(String name) {
        String raw = name.trim();
        String cut = raw.contains(",") ? raw.substring(0, raw.indexOf(',')).trim() : raw;
        String[] words = cut.split("\\s+");
        int end = words.length;
        while (end > 1 && NAME_STOP.contains(
                words[end - 1].toLowerCase(Locale.ROOT).replaceAll("[^a-zäöüß0-9]", ""))) {
            end--;
        }
        String cleaned = String.join(" ", java.util.Arrays.copyOfRange(words, 0, end)).trim();
        List<String> out = new ArrayList<>();
        if (!cleaned.isEmpty()) out.add(cleaned);
        if (!raw.equals(cleaned)) out.add(raw);
        return out;
    }

    /** Parses the searchNews reply, keeping only title-relevant items. Package-private for tests. */
    static List<RawNewsItem> parse(String body, String companyName, int limit) throws Exception {
        JsonNode root = JSON.readTree(body);
        Set<String> words = significantWords(companyName);
        List<RawNewsItem> out = new ArrayList<>();
        for (JsonNode r : root.path("result")) {
            if (out.size() >= limit) break;
            String title = r.path("title").asText("").trim();
            String link = r.path("link").asText("").trim();
            long id = r.path("id").asLong(0);
            if (title.isEmpty() || link.isEmpty() || id == 0) continue;
            if (!titleMatches(title, words)) continue; // precision: the title must name the company
            out.add(new RawNewsItem(
                    "wso-" + id,
                    title,
                    "wallstreet-online",
                    link.startsWith("http") ? link : BASE + link,
                    WsoLabelDate.publishedAt(r.path("label").asText("")),
                    List.of()));
        }
        return out;
    }

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (nameWords.isEmpty()) return false;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }
}
