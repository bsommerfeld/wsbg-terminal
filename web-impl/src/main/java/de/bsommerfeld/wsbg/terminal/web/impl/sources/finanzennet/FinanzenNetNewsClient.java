package de.bsommerfeld.wsbg.terminal.web.impl.sources.finanzennet;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The PRESS leg of finanzen.net - the German dpa-AFX wire. The Akamai wall in
 * front of it grades header COMPLETENESS as the fingerprint; that identity set
 * (User-Agent, client hints, {@code Accept-Encoding}, decompression) is now the
 * TRANSPORT's job, so this client only names WHAT it wants ({@code Accept}).
 * Three surfaces, all verified live 2026-08-02:
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
 * <h3>Dead ends deliberately NOT wired</h3>
 * <ul>
 *   <li>{@code /rss/aktien}, {@code /rss/fonds}, {@code /rss/etf},
 *       {@code /rss/devisen}, {@code /rss/rohstoffe}, {@code /rss/adhoc},
 *       {@code /rss/ipos} all answer 200 with ZERO items. A dead feed costs a
 *       fetch and returns nothing.</li>
 *   <li>{@code /news/<slug>-news} does NOT paginate server-side - the archive
 *       there is flat.</li>
 * </ul>
 *
 * <p>Name-addressed matches go through the house TITLE-precision cut, because
 * the firehose is a general German wire where a stem overlap ("Rheinmetall" /
 * "Rheinpegel") is a real risk.
 */
@Singleton
public class FinanzenNetNewsClient extends AbstractWebSource implements InstrumentSource {

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

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    /** {@code Sun, 02 Aug 2026 23:34:39 +0200}. */
    private static final DateTimeFormatter RSS_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** Generic words that must never carry a title match on their own. */
    private static final Set<String> NAME_STOP = Set.of(
            "der", "die", "das", "und", "the", "and", "inc", "incorporated", "corp",
            "corporation", "co", "company", "ag", "se", "kgaa", "gmbh", "plc", "ltd",
            "limited", "nv", "sa", "spa", "holding", "holdings", "group", "aktie",
            "aktien", "international");

    private final FinanzenNetResolver resolver;

    /**
     * Browser-first is the safe default in front of an Akamai wall, even though
     * the complete header set answers 200 on the direct leg today.
     */
    @Inject
    public FinanzenNetNewsClient(WebFetcher fetcher, FinanzenNetResolver resolver) {
        super(fetcher);
        this.resolver = resolver;
    }

    @Override
    public String sourceName() {
        return "finanzen-net";
    }

    /** German wire aggregate - German language, German press sphere. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("de", "DE");
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.BROWSER, FetchUtil.DIRECT};
    }

    /**
     * Additive key fan, exactly the doors the old client had:
     *
     * <ul>
     *   <li>SYMBOL - resolves the ticker to a slug and reads the instrument's
     *       own RSS feed: the clean per-instrument door, no name matching
     *       involved.</li>
     *   <li>ISIN - the strict resolver path, so a same-named twin can never
     *       answer.</li>
     *   <li>NAME - the instrument's own feed where the name resolves, merged
     *       with the title-precision-matched firehose pool. The pool leg
     *       matters because the instrument feed carries only pieces
     *       finanzen.net tagged to that paper, while a wire item naming the
     *       company can ride the general ticker.</li>
     * </ul>
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (limit <= 0) return List.of();
        Map<String, Article> merged = new LinkedHashMap<>();

        instrument.ticker().ifPresent(ticker ->
                resolver.resolveByTicker(ticker.value()).ifPresent(m ->
                        instrumentFeed(m.slug(), limit).forEach(it ->
                                merged.putIfAbsent(cleanLink(it.link()), it))));
        instrument.isin().ifPresent(isin ->
                resolver.resolveByIsin(isin.value()).ifPresent(m ->
                        instrumentFeed(m.slug(), limit).forEach(it ->
                                merged.putIfAbsent(cleanLink(it.link()), it))));

        String companyName = instrument.name();
        if (companyName != null && !companyName.isBlank()) {
            Set<String> words = significantWords(companyName);
            if (!words.isEmpty()) {
                resolver.resolveOne(companyName).ifPresent(m ->
                        instrumentFeed(m.slug(), limit).forEach(it ->
                                merged.putIfAbsent(cleanLink(it.link()), it)));
                pool().stream()
                        .filter(it -> titleMatches(haystack(it), words))
                        .forEach(it -> merged.putIfAbsent(cleanLink(it.link()), it));
            }
        }
        return merged.values().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    // --------------------------------------------------------------- parsers

    /** Package-private for tests. */
    static List<Article> parseFeed(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
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
                        out.add(new Article(
                                guid != null && !guid.isEmpty() ? guid : link,
                                unescape(title),
                                PUBLISHER,
                                link,
                                parseRssDate(pubDate),
                                List.of(),
                                null,
                                blankToNull(text(description)),
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

    // ------------------------------------------------------------- internals

    private List<Article> instrumentFeed(String slug, int limit) {
        return fetchFeed(String.format(FEED_INSTRUMENT, slug)).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /** The merged news+analyser pool, fetched live - cadence is the caller's job. */
    private List<Article> pool() {
        return mergeByLink(fetchFeed(FEED_NEWS), fetchFeed(FEED_ANALYSEN));
    }

    private List<Article> fetchFeed(String url) {
        String body = fetch(url);
        return body.isEmpty() ? List.of() : parseFeed(body);
    }

    private String fetch(String url) {
        try {
            WebResponse resp = get(url,
                    Map.of("Accept", "application/rss+xml, application/xml, text/xml"),
                    REQUEST_TIMEOUT);
            if (resp.status() == 200) return resp.body();
            LOG.debug("finanzen.net {} answered status {}", url, resp.status());
        } catch (Exception e) {
            LOG.debug("finanzen.net {} failed: {}", url, e.getMessage());
        }
        return "";
    }

    /** Dedupes by article URL, first occurrence wins. Package-private for tests. */
    static List<Article> mergeByLink(List<Article> first, List<Article> second) {
        Map<String, Article> byLink = new LinkedHashMap<>();
        for (Article it : first) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (Article it : second) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    private static String haystack(Article it) {
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

    // ---- the two HtmlTables primitives the feed parse needs, carried over ----

    /** Tags out, entities decoded, whitespace collapsed - for teaser descriptions. */
    static String text(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<br\\s*/?>", " ");
        s = s.replaceAll("<[^>]+>", " ");
        s = unescape(s);
        return s.replaceAll("\\s+", " ").trim();
    }

    /** The named/numeric entity set finanzen.net actually emits. */
    static String unescape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch != '&') { out.append(ch); i++; continue; }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 10) { out.append(ch); i++; continue; }
            String ent = s.substring(i + 1, semi);
            String rep = switch (ent) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos", "#39" -> "'";
                case "nbsp", "#160" -> " ";
                case "euro" -> "€";
                default -> null;
            };
            if (rep == null && ent.startsWith("#")) {
                try {
                    int cp = ent.startsWith("#x") || ent.startsWith("#X")
                            ? Integer.parseInt(ent.substring(2), 16)
                            : Integer.parseInt(ent.substring(1));
                    rep = new String(Character.toChars(cp));
                } catch (RuntimeException ignored) {
                    rep = null;
                }
            }
            if (rep == null) { out.append(ch); i++; continue; }
            out.append(rep);
            i = semi + 1;
        }
        return out.toString();
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
