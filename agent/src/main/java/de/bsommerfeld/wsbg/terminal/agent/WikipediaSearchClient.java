package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wikipedia's keyless REST search as the encyclopaedia shelf - a source for
 * what did NOT stand in this week's press: business history, substrate
 * knowledge, the atomic side paths. The find's page then walks through the
 * normal intake like any article.
 *
 * <p>This is the SEARCH endpoint ({@code /w/rest.php/v1/search/page}), a
 * different door than the current-events feed and than Wikidata. The class
 * carries no caller of its own and stands ready.
 */
final class WikipediaSearchClient {

    private static final Logger LOG = LoggerFactory.getLogger(WikipediaSearchClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebFetcher fetcher;
    private final String host;

    /** @param de German Wikipedia when true, the English one otherwise. */
    WikipediaSearchClient(WebFetcher fetcher, boolean de) {
        this.fetcher = fetcher;
        this.host = de ? "de.wikipedia.org" : "en.wikipedia.org";
    }

    /** The shelf's name, for logs and ledgers. */
    String name() {
        return "wikipedia";
    }

    /** The language edition this instance asks. */
    String host() {
        return host;
    }

    /** One query against the REST search; anything unanswered stays empty. */
    List<Article> search(String query, int limit) {
        try {
            WebResponse resp = fetcher.fetch(
                    "https://" + host + "/w/rest.php/v1/search/page?limit="
                            + Math.min(5, limit) + "&q="
                            + URLEncoder.encode(query, StandardCharsets.UTF_8),
                    Map.of("User-Agent", "wsbg-terminal/1.0",
                            "Accept", "application/json"),
                    TIMEOUT);
            if (resp == null || resp.status() != 200) return List.of();
            List<Article> out = new ArrayList<>();
            Matcher m = WIKI_PAGE.matcher(resp.body());
            while (m.find() && out.size() < limit) {
                String key = m.group(1);
                String title = m.group(2).replace("\\\"", "\"");
                String url = "https://" + host + "/wiki/" + key;
                out.add(new Article(url, title, "Wikipedia", url,
                        null, List.of()));
            }
            return out;
        } catch (Exception e) {
            LOG.debug("[WIKIPEDIA] research failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private static final Pattern WIKI_PAGE = Pattern.compile(
            "\\{\\s*\"id\"[^{}]*?\"key\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"title\"\\s*:\\s*"
                    + "\"((?:[^\"\\\\]|\\\\.)+)\"");
}
