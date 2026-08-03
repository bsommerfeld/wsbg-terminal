package de.bsommerfeld.wsbg.terminal.finanzennet;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
 * The PRESS leg of finanzen.net - the German dpa-AFX wire, reachable without a
 * key behind the Akamai header set ({@link FinanzenNetHttp}). Three surfaces,
 * all verified live 2026-08-02:
 *
 * <ul>
 *   <li><b>{@code /rss/analysen}</b> - the global dpa-AFX <i>Analyser</i> feed:
 *       EVERY single analyst action, house by house, with the old and the new
 *       price target in the teaser. There is no cheaper way to watch the whole
 *       German rating tape.</li>
 *   <li><b>{@code /rss/news}</b> - the general news ticker. Pooled with the
 *       analyser feed into one firehose.</li>
 *   <li><b>{@code /rss/<slug>-rss-feed}</b> - PER-INSTRUMENT RSS. This is the
 *       clean instrument-addressed door: resolve the slug through
 *       {@link FinanzenNetResolver}, then read the instrument's own feed rather
 *       than string-matching a name against the firehose.</li>
 * </ul>
 *
 * <h3>Analyser full text ({@code /analyse/<slug>_<rating>-<haus>_<id>})</h3>
 * Each analyser item links a page that carries the dpa-AFX study text AND a
 * structured summary block: analyst NAME, the house, rating before → rating
 * now, the price target, the distance to the current price and the consensus
 * target. {@link #analysis(String)} reads that block, so a rating change
 * arrives as data, not as a sentence to be re-parsed downstream.
 *
 * <h3>Dead ends deliberately NOT wired</h3>
 * <ul>
 *   <li>{@code /rss/aktien}, {@code /rss/fonds}, {@code /rss/etf},
 *       {@code /rss/devisen}, {@code /rss/rohstoffe}, {@code /rss/adhoc},
 *       {@code /rss/ipos} all answer 200 with ZERO items. A dead feed costs a
 *       fetch and returns nothing.</li>
 *   <li>{@code /news/<slug>-news} does NOT paginate server-side - the archive
 *       there is flat. So this source does NOT implement
 *       {@link #newsForNameWindow}: faking a multi-year window over a page that
 *       only ever shows the newest handful would quietly poison a dossier.</li>
 * </ul>
 *
 * <p>Name-addressed matches go through the house TITLE-precision cut, because
 * the firehose is a general German wire where a stem overlap ("Rheinmetall" /
 * "Rheinpegel") is a real risk.
 */
@Singleton
public class FinanzenNetNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(FinanzenNetNewsClient.class);

    static final String PUBLISHER = "finanzen.net";

    static final String FEED_NEWS = "https://www.finanzen.net/rss/news";
    static final String FEED_ANALYSEN = "https://www.finanzen.net/rss/analysen";
    static final String FEED_INSTRUMENT = "https://www.finanzen.net/rss/%s-rss-feed";

    /**
     * The feeds that answer 200 with an EMPTY channel (probed 2026-08-02).
     * Kept as documentation so nobody wires them back in on a hunch.
     */
    static final Set<String> DEAD_FEEDS = Set.of(
            "aktien", "fonds", "etf", "devisen", "rohstoffe", "adhoc", "ipos");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    /** {@code Sun, 02 Aug 2026 23:34:39 +0200}. */
    private static final DateTimeFormatter RSS_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** Generic words that must never carry a title match on their own. */
    private static final Set<String> NAME_STOP = Set.of(
            "der", "die", "das", "und", "the", "and", "inc", "incorporated", "corp",
            "corporation", "co", "company", "ag", "se", "kgaa", "gmbh", "plc", "ltd",
            "limited", "nv", "sa", "spa", "holding", "holdings", "group", "aktie",
            "aktien", "international");

    private static final Pattern SUMMARY_CELL =
            Pattern.compile("<td\\b[^>]*>(.*?)</td>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern CELL_BREAK =
            Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARAGRAPH =
            Pattern.compile("<p>(.*?)</p>", Pattern.DOTALL);
    private static final Pattern ANALYSIS_LINK =
            Pattern.compile("https://www\\.finanzen\\.net/analyse/[^\"'<>\\s]+");

    private final FinanzenNetHttp.Identity identity = FinanzenNetHttp.randomIdentity();
    private final WebFetcher fetcher;
    private final FinanzenNetResolver resolver;

    private volatile Cached pool;

    private record Cached(Instant fetchedAt, List<RawNewsItem> items) {}

    /**
     * One parsed dpa-AFX Analyser study.
     *
     * @param company      the company as the page names it
     * @param analystHouse the issuing house
     * @param analystName  the individual analyst, or null
     * @param ratingNow    the rating this study sets
     * @param ratingBefore the rating it replaces (equal to {@code ratingNow}
     *                     when only the target moved)
     * @param priceTarget  the new price target
     * @param currency     currency of the target
     * @param consensusTarget the street's average target at publication, or null
     * @param upsidePercent distance to the price at publication, in percent
     * @param body         the study text as dpa-AFX wrote it
     * @param url          the page this was read from
     */
    public record AnalystStudy(String company, String analystHouse, String analystName,
                               String ratingNow, String ratingBefore, Double priceTarget,
                               String currency, Double consensusTarget, Double upsidePercent,
                               String body, String url) {}

    /** Test/default: plain direct transport. */
    public FinanzenNetNewsClient() {
        this(new DirectWebFetcher(), new FinanzenNetResolver());
    }

    /**
     * Production: the shared standard {@link WebFetcher} chain (browser →
     * direct). Browser-first is the safe default in front of an Akamai wall,
     * even though the complete header set answers 200 on the direct leg today.
     */
    @Inject
    public FinanzenNetNewsClient(WebFetcher fetcher, FinanzenNetResolver resolver) {
        this.fetcher = fetcher;
        this.resolver = resolver;
    }

    @Override
    public String sourceName() {
        return "finanzen-net";
    }

    /**
     * Dossier leg. The analyser feed is a firehose of every single sell-side
     * action - exactly the material a dossier weighs and exactly the volume
     * that buries a day's catalyst on the wire. The resolver behind it stays
     * available to everyone (2026-08-03).
     */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    // ------------------------------------------------------- instrument fans

    /**
     * By SYMBOL: resolves the ticker to a slug and reads the instrument's own
     * RSS feed - the clean per-instrument door, no name matching involved.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) return List.of();
        return resolver.resolveByTicker(symbol)
                .map(m -> instrumentFeed(m.slug(), limit))
                .orElseGet(List::of);
    }

    /**
     * By ISIN: the strict resolver path, so a same-named twin can never answer.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        if (isin == null || isin.isBlank() || limit <= 0) return List.of();
        return resolver.resolveByIsin(isin)
                .map(m -> instrumentFeed(m.slug(), limit))
                .orElseGet(List::of);
    }

    /**
     * By NAME: the instrument's own feed where the name resolves, merged with
     * the title-precision-matched pool. The pool leg matters because the
     * instrument feed carries only pieces finanzen.net tagged to that paper,
     * while a wire item naming the company can ride the general ticker.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> fromInstrument = resolver.resolveOne(companyName)
                .map(m -> instrumentFeed(m.slug(), limit))
                .orElseGet(List::of);
        List<RawNewsItem> fromPool = pool().stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList();

        return mergeByLink(fromInstrument, fromPool).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    // ----------------------------------------------------------- general leg

    /**
     * GENERAL stream: the freshest items across the news ticker AND the analyser
     * feed, newest first - the instrument-free door into this source. Served
     * from the same TTL pool the name fan uses, so it costs no extra fetch.
     *
     * <p>Currently unused by the terminal; it stands ready so the general wire
     * can be mobilised without reopening this source (house mandate 2026-08-02:
     * every source is connected per instrument AND generally).
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return pool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL stream, analyst actions only: {@code /rss/analysen}, the complete
     * dpa-AFX rating tape. Every item is one house changing (or confirming) one
     * rating, with the old → new target in the teaser.
     *
     * <p>Currently unused - see {@link #latest}.
     */
    public List<RawNewsItem> analystCalls(int limit) {
        if (limit <= 0) return List.of();
        return fetchFeed(FEED_ANALYSEN).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL stream, news ticker only: {@code /rss/news}.
     *
     * <p>Currently unused - see {@link #latest}.
     */
    public List<RawNewsItem> newsTicker(int limit) {
        if (limit <= 0) return List.of();
        return fetchFeed(FEED_NEWS).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The per-instrument feed for an already-known slug - the door for callers
     * that resolved the instrument themselves.
     *
     * <p>Currently unused - see {@link #latest}.
     */
    public List<RawNewsItem> newsForSlug(String slug, int limit) {
        if (slug == null || slug.isBlank() || limit <= 0) return List.of();
        return instrumentFeed(slug.trim(), limit);
    }

    /**
     * Reads one dpa-AFX Analyser study page in full: the study text plus the
     * structured summary (analyst name, rating before → now, target, consensus).
     * {@code url} is an {@code /analyse/…} link as the analyser feed hands it out.
     *
     * <p>Currently unused - see {@link #latest}.
     */
    public Optional<AnalystStudy> analysis(String url) {
        if (url == null || !ANALYSIS_LINK.matcher(url).find()) return Optional.empty();
        String html = fetch(url);
        return html.isEmpty() ? Optional.empty() : parseAnalysis(html, url);
    }

    // --------------------------------------------------------------- parsers

    /** Package-private for tests. */
    static List<RawNewsItem> parseFeed(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, pubDate = null, description = null, guid = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = pubDate = description = guid = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = textOf(r);
                                case "link" -> link = textOf(r);
                                case "pubDate" -> pubDate = textOf(r);
                                case "description" -> description = textOf(r);
                                case "guid" -> guid = textOf(r);
                                default -> { /* nothing else is carried */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(r.getLocalName())) {
                        inItem = false;
                        if (title == null || title.isEmpty() || link == null || link.isEmpty()) {
                            continue;
                        }
                        out.add(new RawNewsItem(
                                guid != null && !guid.isEmpty() ? guid : link,
                                HtmlTables.unescape(title),
                                PUBLISHER,
                                link,
                                parseRssDate(pubDate),
                                List.of(),
                                null,
                                blankToNull(HtmlTables.text(description)),
                                false,
                                null));
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable finanzen.net feed: {}", e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * Parses an {@code /analyse/…} page: the summary table is a grid of
     * {@code Label:<br>value} cells, the study itself the first paragraph.
     *
     * <p>⚠️ The labels are NOT uniformly marked up - most sit in a
     * {@code <strong>}, but {@code Ø Kursziel} is a link to the target page and
     * several carry a footnote asterisk ({@code Kurs*}, {@code Abst. Kursziel*}).
     * So the split runs on the {@code <br>} inside each cell, not on a tag, and
     * the label is normalised free of {@code :} and {@code *}. Package-private
     * for tests.
     */
    static Optional<AnalystStudy> parseAnalysis(String html, String url) {
        if (html == null || html.isBlank()) return Optional.empty();
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher m = SUMMARY_CELL.matcher(html);
        while (m.find()) {
            String[] parts = CELL_BREAK.split(m.group(1), 2);
            if (parts.length != 2) continue;
            String label = FinanzenNetHttp.lower(HtmlTables.text(parts[0]))
                    .replace(":", "").replace("*", "").trim();
            String value = HtmlTables.text(parts[1]);
            if (!label.isEmpty() && !value.isEmpty()) fields.putIfAbsent(label, value);
        }
        if (fields.isEmpty()) return Optional.empty();

        String body = null;
        Matcher p = PARAGRAPH.matcher(html);
        while (p.find()) {
            String text = HtmlTables.text(p.group(1));
            if (text.length() > 120 && !FinanzenNetHttp.lower(text).startsWith("hinweis")) {
                body = text;
                break;
            }
        }
        String target = fields.get("kursziel");
        return Optional.of(new AnalystStudy(
                fields.get("unternehmen"),
                fields.get("analyst"),
                fields.get("analyst name"),
                fields.get("rating jetzt"),
                fields.get("rating vorher"),
                HtmlTables.germanNumber(target),
                HtmlTables.currency(target),
                HtmlTables.germanNumber(fields.get("ø kursziel")),
                HtmlTables.germanNumber(fields.get("abst. kursziel")),
                body,
                url));
    }

    // ------------------------------------------------------------- internals

    private List<RawNewsItem> instrumentFeed(String slug, int limit) {
        return fetchFeed(String.format(FEED_INSTRUMENT, slug)).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /** The merged news+analyser pool; refreshed at most once per TTL. */
    private List<RawNewsItem> pool() {
        Cached c = pool;
        if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) return c.items();
        synchronized (this) {
            c = pool;
            if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) return c.items();
            List<RawNewsItem> merged = mergeByLink(fetchFeed(FEED_NEWS), fetchFeed(FEED_ANALYSEN));
            if (merged.isEmpty() && c != null) merged = c.items(); // outage: keep the stale pool
            pool = new Cached(Instant.now(), merged);
            return merged;
        }
    }

    private List<RawNewsItem> fetchFeed(String url) {
        String body = fetch(url);
        return body.isEmpty() ? List.of() : parseFeed(body);
    }

    private String fetch(String url) {
        try {
            WebResponse resp = fetcher.fetch(url, FinanzenNetHttp.headers(identity, false),
                    REQUEST_TIMEOUT);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("finanzen.net {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("finanzen.net {} failed: {}", url, e.getMessage());
        }
        return "";
    }

    /** Dedupes by article URL, first occurrence wins. Package-private for tests. */
    static List<RawNewsItem> mergeByLink(List<RawNewsItem> first, List<RawNewsItem> second) {
        Map<String, RawNewsItem> byLink = new LinkedHashMap<>();
        for (RawNewsItem it : first) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (RawNewsItem it : second) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    private static String haystack(RawNewsItem it) {
        return it.summary() == null ? it.title() : it.title() + " " + it.summary();
    }

    /** {@code Sun, 02 Aug 2026 23:34:39 +0200} → {@link Instant}; junk → null. */
    static Instant parseRssDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), RSS_DATE).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the text carries at least one significant word of the name. */
    static boolean titleMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (Pattern.compile("\\b" + Pattern.quote(w) + "\\b").matcher(t).find()) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
