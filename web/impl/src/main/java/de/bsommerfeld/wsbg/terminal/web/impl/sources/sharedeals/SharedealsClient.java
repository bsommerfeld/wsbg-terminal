package de.bsommerfeld.wsbg.terminal.web.impl.sources.sharedeals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
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
 * a multi-year ARCHIVE source ({@link #newsForWindow}) - 18.9k posts back
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
public class SharedealsClient extends AbstractWebSource
        implements InstrumentSource, ArchiveSource {

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

    private final Duration requestTimeout = Duration.ofSeconds(12);

    /**
     * The endpoint carries no wall of any kind - direct-first is exactly the
     * transport order the old {@code @DirectFirst} binding recorded.
     */
    @Inject
    public SharedealsClient(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "sharedeals";
    }

    /** German retail venue - German language, German press sphere. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("de", "DE");
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** Chart-opinion and promotion venue - rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /**
     * By NAME only - a German name-addressed venue; ticker symbols and ISINs
     * mean nothing to it. The title-precision cut is MANDATORY here (the server
     * search matches full text and over-returns).
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> nameWords = significantWords(companyName);
        if (nameWords.isEmpty()) return List.of();
        List<Article> out = new ArrayList<>();
        for (Article item : fetchSearch(companyName, "")) {
            if (!titleMatches(item.title(), nameWords)) continue;
            out.add(item);
            if (out.size() >= limit) break;
        }
        return List.copyOf(out);
    }

    /**
     * The ARCHIVE door: the same full-text search narrowed to a date window
     * via the API's {@code after}/{@code before} parameters. Name-addressed
     * like the live fan (the site tags no ISINs); same title-precision cut.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> nameWords = significantWords(companyName);
        if (nameWords.isEmpty()) return List.of();
        try {
            Instant from = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = toDateExclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
            // The server window pre-filters (site-local time); the exact
            // [from, to) cut on date_gmt happens client-side below.
            String window = "&after=" + fromDate + "T00:00:00&before="
                    + toDateExclusive + "T00:00:00";
            List<Article> out = new ArrayList<>();
            for (Article item : fetchSearch(companyName, window)) {
                if (!titleMatches(item.title(), nameWords)) continue;
                Instant at = item.publishedAt();
                if (at == null || at.isBefore(from) || !at.isBefore(to)) continue;
                out.add(item);
                if (out.size() >= limit) break;
            }
            if (!out.isEmpty()) {
                LOG.info("[sharedeals] '{}' → {} opinion piece(s) in {}..{}", companyName,
                        out.size(), fromDate, toDateExclusive);
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[sharedeals] window for '{}' failed: {}", companyName, e.getMessage());
            return List.of();
        }
    }

    /** One search request against the posts API; any failure answers empty. */
    private List<Article> fetchSearch(String name, String windowParams) {
        try {
            String url = String.format(API_URL,
                    URLEncoder.encode(name.strip(), StandardCharsets.UTF_8), FETCH_SIZE)
                    + windowParams;
            WebResponse resp = get(url, Map.of("Accept", "application/json"), requestTimeout);
            if (resp.status() != 200 || resp.body() == null) {
                LOG.debug("[sharedeals] search '{}' answered status {}", name, resp.status());
                return List.of();
            }
            return parse(resp.body());
        } catch (Exception e) {
            LOG.debug("[sharedeals] search '{}' failed: {}", name, e.getMessage());
            return List.of();
        }
    }

    /**
     * The wp/v2 posts array → items, unfiltered (the precision cut is applied
     * by the fan). A non-array answer (WP error object, HTML shell) or garbage
     * yields empty, never throws. Package-private for tests.
     */
    static List<Article> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
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
                out.add(new Article(
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
