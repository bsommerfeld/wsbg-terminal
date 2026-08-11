package de.bsommerfeld.wsbg.terminal.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a Google-News RSS redirect link ({@code news.google.com/rss/articles/…})
 * to the publisher's own article URL.
 *
 * <p>WHY THIS EXISTS: the RSS {@code <link>} is not an HTTP redirect - it is a
 * JS shell that only a fully interactive browser session ever leaves, and the
 * CEF transport documents news.google.com as a permanently never-ready origin.
 * Measured on the 2026-08-10 SAP run: 0 of 112 such links ever produced an
 * article text; 83 of the 91 unreadable admitted cards were exactly these
 * links. Resolving the link BEFORE the fetch turns that cohort back into
 * ordinary publisher pages the existing reader already handles.
 *
 * <p>HOW: the same two keyless calls the shell itself performs - GET the shell
 * page (it carries a signature and timestamp for the article id), then POST the
 * id+signature to the public {@code batchexecute} endpoint, whose reply names
 * the publisher URL. No API key, no third-party service, no curated mapping.
 *
 * <p>Failure is never an error here: any miss returns {@link Optional#empty()}
 * and the caller proceeds with the original link exactly as before. Results
 * (including misses) are cached per session - the shell is stable within a run.
 *
 * <p>Own {@link HttpClient} on purpose (the ImageFetcher precedent): the shared
 * WebFetcher seam cannot POST, and routing the shell GET through CEF would hit
 * the very never-ready origin this class exists to avoid.
 */
final class GoogleNewsUrlResolver {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleNewsUrlResolver.class);

    private static final Pattern ARTICLE_LINK = Pattern.compile(
            "^https?://news\\.google\\.com/rss/articles/([^?/]+)");
    private static final Pattern SIGNATURE = Pattern.compile("data-n-a-sg=\"([^\"]+)\"");
    private static final Pattern TIMESTAMP = Pattern.compile("data-n-a-ts=\"([^\"]+)\"");
    /** The publisher URL inside the batchexecute reply (JSON-escaped or plain). */
    private static final Pattern PUBLISHER_URL = Pattern.compile(
            "https?:(?:\\\\/\\\\/|//)(?!news\\.google\\.com)[^\\\\\"\\s]+");

    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final String ENDPOINT =
            "https://news.google.com/_/DotsSplashUi/data/batchexecute";

    private final HttpClient http;
    private final String userAgent;
    /** link → resolved URL, or "" for a remembered miss. */
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    GoogleNewsUrlResolver(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "Mozilla/5.0" : userAgent;
        this.http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** True when the link is a Google-News RSS redirect the fetch layer cannot read. */
    static boolean istRedirect(String url) {
        return url != null && ARTICLE_LINK.matcher(url.trim()).find();
    }

    /**
     * The publisher URL behind the redirect, or empty on any miss. Never
     * throws; a miss is cached so the run pays for each link at most once.
     */
    Optional<String> resolve(String url) {
        if (url == null) return Optional.empty();
        String trimmed = url.trim();
        Matcher m = ARTICLE_LINK.matcher(trimmed);
        if (!m.find()) return Optional.empty();
        String cached = cache.get(trimmed);
        if (cached != null) {
            return cached.isEmpty() ? Optional.empty() : Optional.of(cached);
        }
        String resolved = "";
        try {
            resolved = resolveNow(trimmed, m.group(1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty(); // do not cache an interrupt as a miss
        } catch (Exception e) {
            LOG.debug("[GN-RESOLVE] '{}' failed: {}", trimmed, e.getMessage());
        }
        cache.put(trimmed, resolved);
        if (!resolved.isEmpty()) {
            LOG.debug("[GN-RESOLVE] {} -> {}", trimmed, resolved);
            return Optional.of(resolved);
        }
        return Optional.empty();
    }

    private String resolveNow(String url, String articleId)
            throws java.io.IOException, InterruptedException {
        // 1) The shell page carries the signature/timestamp for this article id.
        HttpRequest shellReq = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .GET().build();
        HttpResponse<String> shell = http.send(shellReq,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (shell.statusCode() != 200) return "";
        Matcher sg = SIGNATURE.matcher(shell.body());
        Matcher ts = TIMESTAMP.matcher(shell.body());
        if (!sg.find() || !ts.find()) return "";

        // 2) batchexecute answers with the publisher URL for id+signature.
        String inner = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,1,1,"
                + "\"US:en\",null,1,null,null,null,null,null,1],\"X\",\"X\",1,[1,1,1],"
                + "1,1,null,0,0,null,0],\"" + articleId + "\"," + ts.group(1) + ",\""
                + sg.group(1) + "\"]";
        String payload = "[[[\"Fbv4je\",\"" + inner.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\",null,\"generic\"]]]";
        String form = "f.req=" + URLEncoder.encode(payload, StandardCharsets.UTF_8);
        HttpRequest postReq = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(postReq,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) return "";
        Matcher pub = PUBLISHER_URL.matcher(resp.body());
        if (!pub.find()) return "";
        return pub.group().replace("\\/", "/");
    }
}
