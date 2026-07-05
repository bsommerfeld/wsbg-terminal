package de.bsommerfeld.wsbg.terminal.yahoofinance;

import de.bsommerfeld.wsbg.terminal.core.util.HostReachability;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort readable-body extractor for a Yahoo news article link. A
 * self-contained, dormant capability — <b>NOT wired into the editorial
 * pipeline</b> (the resolver still hands the model only headline titles);
 * extracted out of {@code YahooFinanceClient} so that hot client stays lean.
 *
 * <p>Shares the client's {@link WebFetcher}, User-Agent, request timeout and
 * offline gate; keeps its own TTL cache of extracted bodies.
 */
final class YahooArticleReader {

    private static final Logger LOG = LoggerFactory.getLogger(YahooArticleReader.class);

    /** Upper bound on extracted article body length — keeps a prompt block sane. */
    private static final int ARTICLE_MAX_CHARS = 6000;
    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style|noscript|template|svg)[^>]*>.*?</\\1>");
    private static final Pattern PARAGRAPH = Pattern.compile("(?is)<p[^>]*>(.*?)</p>");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<[^>]+>");

    private final WebFetcher webFetcher;
    private final String userAgent;
    private final Duration requestTimeout;
    private final HostReachability online;
    private final long cacheTtlSeconds;

    private final TtlCache<String, String> articleCache;

    YahooArticleReader(WebFetcher webFetcher, String userAgent, Duration requestTimeout,
                       HostReachability online, long cacheTtlSeconds) {
        this.webFetcher = webFetcher;
        this.userAgent = userAgent;
        this.requestTimeout = requestTimeout;
        this.online = online;
        this.cacheTtlSeconds = cacheTtlSeconds;
        this.articleCache = new TtlCache<>(cacheTtlSeconds);
    }

    /**
     * Best-effort full-text fetch for a news article URL (the {@code link} on a
     * {@code RawNewsItem}). Follows the {@code finance.yahoo.com/m/…} redirect
     * to the publisher, strips boilerplate, and returns readable body text
     * capped at {@link #ARTICLE_MAX_CHARS}.
     *
     * <p><b>Standalone capability — NOT wired into the editorial pipeline.</b>
     * The resolver still hands the model only headline titles; this exists so
     * deeper article context can be switched on later without new plumbing.
     * Publisher HTML is heterogeneous, so extraction is heuristic (prefer
     * {@code <p>} text); treat a result as "best effort", never authoritative.
     *
     * @return the article text, or {@link Optional#empty()} on any failure
     *         (network, non-200, nothing readable extracted).
     */
    Optional<String> fetchArticleText(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        String key = url.trim();
        long now = Instant.now().getEpochSecond();
        TtlCache.Entry<String> cached = articleCache.getFresh(key, now);
        if (cached != null) {
            return Optional.ofNullable(cached.value());
        }
        if (!online.isReachable()) {
            LOG.debug("Offline — skipping Yahoo article fetch '{}'", key);
            return Optional.empty();
        }
        try {
            WebResponse resp = httpGet(key, "text/html,application/xhtml+xml");
            if (resp.status() != 200) {
                LOG.warn("Yahoo article fetch '{}' returned HTTP {}", key, resp.status());
                return Optional.empty();
            }
            String text = extractReadableText(resp.body());
            articleCache.put(key, text.isEmpty() ? null : text, now);
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.warn("Yahoo article fetch '{}' failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private WebResponse httpGet(String url, String accept) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", userAgent);
        headers.put("Accept", accept);
        return webFetcher.fetch(url, headers, requestTimeout);
    }

    void clearCache() {
        articleCache.clear();
    }

    /**
     * Heuristic readable-text extraction from an HTML page. Drops
     * script/style/svg blocks, prefers paragraph (<{@code p}>) text to skip
     * nav/menu boilerplate, unescapes the common entities, collapses
     * whitespace, and caps length. Pure function — unit-tested.
     */
    static String extractReadableText(String html) {
        if (html == null || html.isBlank()) return "";
        String cleaned = SCRIPT_STYLE.matcher(html).replaceAll(" ");

        // Paragraph text first: it skips headers, nav, captions and most chrome.
        StringBuilder paras = new StringBuilder();
        Matcher pm = PARAGRAPH.matcher(cleaned);
        while (pm.find()) {
            String t = stripTags(pm.group(1)).trim();
            if (t.length() >= 40) paras.append(t).append(' ');
        }

        String text = paras.length() >= 200 ? paras.toString() : stripTags(cleaned);
        text = unescapeEntities(text).replaceAll("\\s+", " ").trim();
        if (text.length() > ARTICLE_MAX_CHARS) {
            text = text.substring(0, ARTICLE_MAX_CHARS).trim() + "…";
        }
        return text;
    }

    private static String stripTags(String s) {
        return s == null ? "" : HTML_TAG.matcher(s).replaceAll(" ");
    }

    private static String unescapeEntities(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&hellip;", "…")
                .replace("&mdash;", "—").replace("&ndash;", "–")
                .replace("&rsquo;", "'").replace("&lsquo;", "'")
                .replace("&ldquo;", "\"").replace("&rdquo;", "\"");
    }
}
