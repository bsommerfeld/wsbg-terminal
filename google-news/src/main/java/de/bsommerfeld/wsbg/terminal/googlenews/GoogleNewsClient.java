package de.bsommerfeld.wsbg.terminal.googlenews;

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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * German financial-press news via Google News' keyless RSS search
 * ({@code news.google.com/rss/search?q=<query>&hl=de&gl=DE&ceid=DE:de}) — a
 * broad-coverage German news leg beside wallstreet-online: one query returns
 * up to ~100 fresh items across the whole German press (WELT, Börse Express,
 * FinanzNachrichten, WirtschaftsWoche, …), keyless (live-verified 2026-07-13).
 * Google captchas bare clients at volume, so this source rides the standard
 * browser-first chain — see the injected constructor.
 *
 * <p><b>Query shape (live-probed 2026-07-13): {@code "<name> Aktie"}.</b> The
 * plain company name returns general press — for Rheinmetall: demo coverage
 * (rbb24), politics (Telepolis, Spiegel) — while the {@code " Aktie"} suffix
 * pulls the finance desk of the same outlets (Kurs pieces, WELT/Börse
 * Express/FinanzNachrichten) and does NOT starve small caps ("Meta Wolf
 * Aktie" → EQS-DD filings, Insider-Trades, Bezugsrechte). The suffix is a
 * query bias only; title relevance is still matched against the NAME.
 *
 * <p>Google News is name-addressed — a ticker symbol means nothing to its
 * full-text search — so this source implements {@link #newsForName} and
 * leaves the symbol-addressed {@link #newsFor} a no-op, exactly like the
 * other German venues.
 *
 * <p>Precision over recall (the WsoNewsClient lesson): a search feed for a
 * generic-word name would flood the pool with unrelated hits, so only items
 * whose TITLE actually carries a significant word of the queried name are
 * returned. The {@code <link>} is Google's redirect URL — kept as-is (it
 * resolves to the article). {@code <source>} is the originating publisher;
 * a trailing {@code " - <publisher>"} Google appends to every title is
 * stripped when it matches that source.
 *
 * <p>Politeness: the parsed (uncapped, relevance-filtered) result is cached
 * per query for 5 minutes — the aggregator may fan the same name from
 * several places in a burst, and Google should see one request for it.
 */
@Singleton
public class GoogleNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleNewsClient.class);

    private static final String SEARCH_URL = "https://news.google.com/rss/search";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Per-query politeness cache: parsed, relevance-filtered, uncapped items. */
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    private record CachedResult(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public GoogleNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: {@code @DirectFirst} since 2026-07-14. The browser-first
     * mandate of 2026-07-13 assumed the joker clears Google's walls, but the
     * news.google.com anchor NEVER becomes session-ready (consent wall — the
     * pending SOCS-cookie fix), so the browser leg only ever cost 25 s/3 s
     * per query and fell through to direct anyway; the direct RSS path is
     * what actually delivers. The old captcha concern stays covered by a
     * tripwire instead: a 200 whose body is not RSS (consent/captcha page)
     * logs a WARN loud enough to decide the source's removal on evidence.
     */
    @Inject
    public GoogleNewsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "google-news";
    }

    /** Google News is name-addressed — a ticker symbol means nothing to its search. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        return search(cleanName(companyName) + " Aktie", companyName, limit);
    }

    /**
     * ISIN full-text search: regulatory disclosures (EQS ad-hocs, corporate
     * releases) print the ISIN verbatim in their body, so this finds the
     * disclosure-grade documents the name query drowns in daily price notes.
     * NO title-relevance filter — the ISIN query is precise by construction
     * (an ISIN never matches a wrong same-named twin), and disclosure titles
     * rarely repeat the ISIN. Always used ADDITIVELY beside symbol/name.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        if (isin == null || isin.isBlank() || limit <= 0) return List.of();
        return search(isin.strip().toUpperCase(Locale.ROOT), null, limit);
    }

    /**
     * Multi-year press HISTORY: the same search narrowed to a date window via
     * Google News' {@code after:}/{@code before:} query operators - the
     * long-term dossier needs the years, not just the weeks (user mandate
     * 2026-07-16). Same transport, cache and consent tripwire as every other
     * query; the window rides in the cache key.
     */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
            String fromIsoDate, String toIsoDateExclusive, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        String q = cleanName(companyName) + " Aktie after:" + fromIsoDate
                + " before:" + toIsoDateExclusive;
        return search(q, companyName, limit);
    }

    private List<RawNewsItem> search(String query, String relevanceName, int limit) {
        String cacheKey = query.toLowerCase(Locale.ROOT);
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = fetcher.fetch(
                    SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&hl=de&gl=DE&ceid=DE:de",
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    // Consent/captcha pages arrive as HTTP 200 — without this
                    // tripwire the source would starve silently.
                    LOG.warn("Google News answered a 200 that is not RSS for '{}' — "
                            + "consent/captcha wall on the direct client, source is starving", query);
                    return List.of();
                }
                List<RawNewsItem> items = parse(body, relevanceName);
                cache.put(cacheKey, new CachedResult(Instant.now(), items));
                return cap(items, limit);
            }
            LOG.debug("Google News search for '{}' answered status {}",
                    query, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Google News search failed for '{}': {}", query, e.getMessage());
        }
        return List.of();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * Cleaned query form of a canonical name: comma-cut ("Amazon.com, Inc." →
     * "Amazon.com") and trailing legal suffixes stripped ("NVIDIA Corporation" →
     * "NVIDIA") — a full legal name plus " Aktie" is a query no headline writer
     * ever phrases.
     */
    static String cleanName(String name) {
        String raw = name.trim();
        String cut = raw.contains(",") ? raw.substring(0, raw.indexOf(',')).trim() : raw;
        String[] words = cut.split("\\s+");
        int end = words.length;
        while (end > 1 && NAME_STOP.contains(
                words[end - 1].toLowerCase(Locale.ROOT).replaceAll("[^a-zäöüß0-9]", ""))) {
            end--;
        }
        String cleaned = String.join(" ", java.util.Arrays.copyOfRange(words, 0, end)).trim();
        return cleaned.isEmpty() ? raw : cleaned;
    }

    /**
     * Parses a Google News RSS reply into relevance-filtered items, uncapped
     * (the politeness cache stores the full list; the caller caps per request).
     * Garbage / HTML answers yield an empty list, never an exception.
     * Package-private for tests.
     */
    /** A Google 200 is only a result when it is actually RSS/XML — consent and captcha shells are HTML. */
    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.length() > 512) head = head.substring(0, 512);
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.startsWith("<rss");
    }

    static List<RawNewsItem> parse(String xml, String companyName) {
        if (xml == null || xml.isBlank()) return List.of();
        Set<String> words = significantWords(companyName);
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null, source = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = source = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = textOf(r);
                                case "link" -> link = textOf(r);
                                case "guid" -> guid = textOf(r);
                                case "pubDate" -> pubDate = textOf(r);
                                case "source" -> source = textOf(r);
                                default -> { /* description etc. — ignored */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(r.getLocalName())) {
                        inItem = false;
                        RawNewsItem item = toItem(title, link, guid, pubDate, source, words);
                        if (item != null) out.add(item);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Google News feed for '{}': {}", companyName, e.getMessage());
            return List.of();
        }
        return out;
    }

    /** One parsed {@code <item>} → a {@link RawNewsItem}, or null when incomplete/off-topic. */
    private static RawNewsItem toItem(String title, String link, String guid,
                                      String pubDate, String source, Set<String> nameWords) {
        if (title == null || title.isEmpty() || link == null || link.isEmpty()) return null;
        String publisher = source == null ? "" : source;
        // Google appends " - <publisher>" to every title; strip it when it matches.
        if (!publisher.isEmpty() && title.endsWith(" - " + publisher)) {
            title = title.substring(0, title.length() - publisher.length() - 3).trim();
        }
        if (!titleMatches(title, nameWords)) return null; // precision: the title must name the company
        return new RawNewsItem(
                guid != null && !guid.isEmpty() ? guid : link,
                title,
                publisher,
                link,
                parsePubDate(pubDate),
                List.of());
    }

    /**
     * RFC-1123 pubDate ("Mon, 13 Jul 2026 08:30:00 GMT") → {@link Instant};
     * unparseable → null. Lenient about the day-of-week token (strict RFC-1123
     * rejects a weekday that doesn't match the date — the date wins).
     */
    static Instant parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        String s = pubDate.trim();
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception strict) {
            try {
                int comma = s.indexOf(',');
                return ZonedDateTime.parse(comma >= 0 ? s.substring(comma + 1).trim() : s,
                        RFC_1123_NO_DOW).toInstant();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** RFC-1123 without the day-of-week prefix, English month names. */
    private static final DateTimeFormatter RFC_1123_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        // Empty = NO filter requested (the ISIN query is precise by
        // construction and disclosure titles rarely repeat the ISIN).
        if (nameWords.isEmpty()) return true;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        // No relevance name (the ISIN query) = no filter — precise by
        // construction; the null used to NPE inside normalize (live 2026-07-14).
        if (name == null || name.isBlank()) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Reads the element's text content, trimmed. Safe for text-only elements. */
    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
