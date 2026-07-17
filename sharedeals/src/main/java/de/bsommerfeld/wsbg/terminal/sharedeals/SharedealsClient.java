package de.bsommerfeld.wsbg.terminal.sharedeals;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * sharedeals.de: German retail OPINION on stocks - daily chart analyses and
 * "Kurspotenzial" pieces from a promoter-adjacent community venue, blue chips
 * beside heavily-run small caps. NOT a facts desk: coverage cadence IS the
 * signal (five Almonty pieces in a week = German retail attention), the
 * content is opinion with price targets - so this source rides the sentiment
 * fan ({@link #socialSentiment()}), never the press loom.
 *
 * <p>Transport is the site's own open WordPress REST API
 * ({@code /wp-json/wp/v2/posts}, keyless, no wall, full article bodies -
 * probed 2026-07-17): {@code search=} matches the FULL TEXT server-side and
 * {@code after}/{@code before} give exact date windows, which also makes this
 * a multi-year ARCHIVE source ({@link #newsForNameWindow}) - 18.9k posts back
 * to 2010, deep German small-cap opinion history no other archive leg carries.
 *
 * <p><b>Precision:</b> because the server search matches full text, a Valneva
 * chart note that merely mentions BioNTech would answer a BioNTech query
 * (probed 2026-07-17: six window hits, none titled) - so both fans keep only
 * items whose TITLE names the company (the house precision filter). The site
 * tags no ISINs and no ticker symbols: name-addressed only.
 *
 * <p>The site's own paid-placement drawer (wp category "Anzeigen") is carried
 * as the {@code sponsored} flag, so paid promotion never masquerades as
 * organic room attention.
 */
@Singleton
public class SharedealsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(SharedealsClient.class);

    private static final String API_URL =
            "https://www.sharedeals.de/wp-json/wp/v2/posts?search=%s&per_page=%d"
                    + "&_fields=id,date_gmt,link,title.rendered,excerpt.rendered,categories";
    /**
     * Fetched per query before the title-precision cut - the full-text search
     * over-returns (passing mentions), so the pool must be deeper than the
     * emitted limit. Server cap is 100.
     */
    private static final int FETCH_SIZE = 50;
    /** wp category 146 = "Anzeigen" - the site's own paid-placement drawer. */
    private static final int CATEGORY_ANZEIGEN = 146;
    private static final String PUBLISHER = "sharedeals.de";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The house name-precision stop list (generic legal-form/name words). */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#[xX]([0-9a-fA-F]+);");

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport (the endpoint carries no wall). */
    public SharedealsClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public SharedealsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "sharedeals";
    }

    /** Chart-opinion and promotion venue - rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** No-op: a German name-addressed venue - ticker symbols mean nothing to it. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> nameWords = significantWords(companyName);
        if (nameWords.isEmpty()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem item : fetchSearch(companyName, "")) {
            if (!titleMatches(item.title(), nameWords)) continue;
            out.add(item);
            if (out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
            String fromIsoDate, String toIsoDateExclusive, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> nameWords = significantWords(companyName);
        if (nameWords.isEmpty()) return List.of();
        try {
            Instant from = LocalDate.parse(fromIsoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = LocalDate.parse(toIsoDateExclusive)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            // The server window pre-filters (site-local time); the exact
            // [from, to) cut on date_gmt happens client-side below.
            String window = "&after=" + fromIsoDate + "T00:00:00&before="
                    + toIsoDateExclusive + "T00:00:00";
            List<RawNewsItem> out = new ArrayList<>();
            for (RawNewsItem item : fetchSearch(companyName, window)) {
                if (!titleMatches(item.title(), nameWords)) continue;
                Instant at = item.publishedAt();
                if (at == null || at.isBefore(from) || !at.isBefore(to)) continue;
                out.add(item);
                if (out.size() >= limit) break;
            }
            if (!out.isEmpty()) {
                LOG.info("[sharedeals] '{}' → {} opinion piece(s) in {}..{}", companyName,
                        out.size(), fromIsoDate, toIsoDateExclusive);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[sharedeals] window for '{}' failed: {}", companyName, e.getMessage());
            return List.of();
        }
    }

    /** One search request against the posts API; any failure answers empty. */
    private List<RawNewsItem> fetchSearch(String name, String windowParams) {
        try {
            String url = String.format(API_URL,
                    URLEncoder.encode(name.strip(), StandardCharsets.UTF_8), FETCH_SIZE)
                    + windowParams;
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[sharedeals] search '{}' answered status {}", name,
                        resp == null ? "null" : resp.status());
                return List.of();
            }
            return parse(resp.body());
        } catch (Exception e) {
            LOG.debug("[sharedeals] search '{}' failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    /**
     * The wp/v2 posts array → items, unfiltered (the precision/window cuts are
     * applied per fan). A non-array answer (WP error object, HTML shell) or
     * garbage yields empty, never throws. Package-private for tests.
     */
    static List<RawNewsItem> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode post : root) {
                String title = decodeEntities(post.path("title").path("rendered").asText(null));
                if (title == null || title.isBlank()) continue;
                String link = post.path("link").asText(null);
                String summary = stripHtml(post.path("excerpt").path("rendered").asText(null));
                boolean sponsored = false;
                for (JsonNode cat : post.path("categories")) {
                    if (cat.asInt() == CATEGORY_ANZEIGEN) sponsored = true;
                }
                out.add(new RawNewsItem(
                        "sharedeals-" + post.path("id").asText(String.valueOf(title.hashCode())),
                        title.strip(), PUBLISHER, link,
                        parseStamp(post.path("date_gmt").asText(null)),
                        List.of(), null,
                        summary == null || summary.isBlank() ? null : summary,
                        sponsored));
            }
        } catch (Exception e) {
            LOG.debug("[sharedeals] parse failed: {}", e.getMessage());
        }
        return List.copyOf(out);
    }

    /** {@code date_gmt} ("2026-07-16T15:43:10", zoneless UTC) → Instant; unparseable → null. */
    static Instant parseStamp(String dateGmt) {
        if (dateGmt == null || dateGmt.isBlank()) return null;
        try {
            return LocalDateTime.parse(dateGmt.strip()).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (nameWords.isEmpty()) return true;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
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

    /** The excerpt's HTML tags stripped, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return decodeEntities(html.replaceAll("<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * HTML entities → text: numeric/hex first, then the named set WordPress
     * actually emits in {@code rendered} fields, {@code &amp;} last.
     */
    static String decodeEntities(String s) {
        if (s == null) return null;
        String out = NUMERIC_ENTITY.matcher(s).replaceAll(
                (MatchResult m) -> new String(Character.toChars(Integer.parseInt(m.group(1)))));
        out = HEX_ENTITY.matcher(out).replaceAll(
                (MatchResult m) -> new String(Character.toChars(Integer.parseInt(m.group(1), 16))));
        return out
                .replace("&hellip;", "…").replace("&ndash;", "–").replace("&mdash;", "—")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }
}
