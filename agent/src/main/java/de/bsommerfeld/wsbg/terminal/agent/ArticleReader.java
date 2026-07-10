package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort readable-body extractor for a news article permalink — the
 * {@code link} on any {@code RawNewsItem}, regardless of which {@code NewsSource}
 * emitted it (Yahoo, wallstreet-online, future legs). Source-neutral on purpose:
 * publisher HTML is heterogeneous everywhere, so extraction is heuristic (prefer
 * {@code <p>} text, drop script/style, unescape entities) and the result is "best
 * effort", never authoritative. Leftover boilerplate is fine — the digest model
 * call downstream ({@link NewsDigester}) is instructed to ignore it.
 *
 * <p>Stateless: caching (including failure caching) lives in {@link NewsDigester},
 * keyed by link, so fetch and digest share one cache entry per article.
 */
final class ArticleReader {

    private static final Logger LOG = LoggerFactory.getLogger(ArticleReader.class);

    /** Upper bound on extracted article body length — keeps the digest prompt sane. */
    static final int ARTICLE_MAX_CHARS = 6000;
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(12);
    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style|noscript|template|svg)[^>]*>.*?</\\1>");
    private static final Pattern PARAGRAPH = Pattern.compile("(?is)<p[^>]*>(.*?)</p>");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<[^>]+>");

    /**
     * The EU cookie-consent interstitial a cookie-less client gets instead of a
     * Yahoo-hosted article. Its readable text is long enough to pass the length
     * gate and identical for EVERY link, so it must be recognised as a shell here
     * (the 2026-07-09 live run digested it into 2-char junk for every article).
     * Infrastructure detection, not content filtering — like the derivative
     * poison filter on the WSO side.
     */
    private static final Pattern CONSENT_SHELL =
            Pattern.compile("(?i)consent\\.yahoo\\.com|guce\\.yahoo\\.com|guce\\.oath\\.com");

    private final WebFetcher webFetcher;
    private final String userAgent;

    ArticleReader(WebFetcher webFetcher, String userAgent) {
        this.webFetcher = webFetcher;
        this.userAgent = userAgent;
    }

    /**
     * Best-effort full-text fetch for an article URL. Follows publisher redirects
     * (e.g. {@code finance.yahoo.com/m/…}), strips boilerplate, and returns readable
     * body text capped at {@link #ARTICLE_MAX_CHARS}.
     *
     * @return the article text, or {@link Optional#empty()} on any failure
     *         (network, non-200, nothing readable extracted).
     */
    Optional<String> fetchArticleText(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", userAgent);
            headers.put("Accept", "text/html,application/xhtml+xml");
            WebResponse resp = webFetcher.fetch(url.trim(), headers, FETCH_TIMEOUT);
            if (resp.status() != 200) {
                LOG.debug("[NEWS] article fetch '{}' returned HTTP {}", url, resp.status());
                return Optional.empty();
            }
            if (CONSENT_SHELL.matcher(resp.body()).find()) {
                LOG.debug("[NEWS] article fetch '{}' hit the consent shell — no article", url);
                return Optional.empty();
            }
            String text = extractReadableText(resp.body());
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.debug("[NEWS] article fetch '{}' failed: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Heuristic readable-text extraction from an HTML page. Drops
     * script/style/svg blocks, prefers paragraph ({@code <p>}) text to skip
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
