package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bing NEWS' keyless RSS search ({@code bing.com/news/search?format=rss}) as
 * an independently indexed news shelf - the news vertical, a different door
 * than the plain web search RSS.
 *
 * <p>A query-addressed HTTP shelf, no state, no model seat. The class carries
 * no caller of its own and stands ready.
 */
final class BingNewsSearchClient {

    private static final Logger LOG = LoggerFactory.getLogger(BingNewsSearchClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebFetcher fetcher;
    private final String userAgent;

    BingNewsSearchClient(WebFetcher fetcher, String userAgent) {
        this.fetcher = fetcher;
        this.userAgent = userAgent;
    }

    /** The shelf's name, for logs and ledgers. */
    String name() {
        return "bing-news";
    }

    /** One query against the news RSS; anything unanswered stays empty. */
    List<RawNewsItem> search(String query, int limit) {
        try {
            WebResponse resp = fetcher.fetch(
                    "https://www.bing.com/news/search?format=rss&q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8),
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    TIMEOUT);
            if (resp == null || resp.status() != 200) return List.of();
            return parseRss(resp.body(), limit);
        } catch (Exception e) {
            LOG.debug("[BING-NEWS] research failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private static final Pattern RSS_ITEM = Pattern.compile("<item>(.*?)</item>",
            Pattern.DOTALL);
    private static final Pattern RSS_TITLE = Pattern.compile(
            "<title>(?:<!\\[CDATA\\[)?(.*?)(?:\\]\\]>)?</title>", Pattern.DOTALL);
    private static final Pattern RSS_LINK = Pattern.compile("<link>(.*?)</link>",
            Pattern.DOTALL);
    private static final Pattern RSS_DATE = Pattern.compile("<pubDate>(.*?)</pubDate>",
            Pattern.DOTALL);

    static List<RawNewsItem> parseRss(String xml, int limit) {
        List<RawNewsItem> out = new ArrayList<>();
        if (xml == null || xml.isBlank()) return out;
        Matcher item = RSS_ITEM.matcher(xml);
        while (item.find() && out.size() < limit) {
            String block = item.group(1);
            Matcher t = RSS_TITLE.matcher(block);
            Matcher l = RSS_LINK.matcher(block);
            if (!t.find() || !l.find()) continue;
            String titel = entwirre(t.group(1));
            String link = zielUrl(entwirre(l.group(1)));
            if (titel.isBlank() || link.isBlank()) continue;
            java.time.Instant wann = null;
            Matcher d = RSS_DATE.matcher(block);
            if (d.find()) {
                try {
                    wann = ZonedDateTime.parse(d.group(1).strip(),
                            DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                } catch (Exception ignored) {
                }
            }
            out.add(new RawNewsItem(link, titel, "", link, wann, List.of()));
        }
        return out;
    }

    private static final Pattern REDIRECT_URL = Pattern.compile(
            "[?&]url=([^&]+)", Pattern.CASE_INSENSITIVE);

    /**
     * The real article behind a search engine's click tracker. Bing's news RSS
     * hands out {@code bing.com/news/apiclick.aspx?...&url=<encoded target>}
     * links; the article fetcher never follows them, so every one of those
     * finds arrived as a card without readable text (measured: 81 of 196 empty
     * cards in one run). Unwrapped HERE, at the source, so the book, the
     * dedupe seam and the register all carry the publisher's own URL. A link
     * without a {@code url=} parameter is returned untouched.
     */
    static String zielUrl(String link) {
        if (link == null || link.isBlank()) return link;
        Matcher m = REDIRECT_URL.matcher(link);
        if (!m.find()) return link;
        try {
            String ziel = java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8)
                    .strip();
            String u = ziel.toLowerCase(Locale.ROOT);
            return u.startsWith("http://") || u.startsWith("https://") ? ziel : link;
        } catch (RuntimeException e) {
            return link;
        }
    }

    private static String entwirre(String s) {
        return s == null ? "" : s.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .strip();
    }
}
