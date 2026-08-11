package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * arXiv's keyless Atom API as the atomic-substrate shelf - the layer under
 * the press: the method, the material, the physics a business case rests
 * on. A paper is an article like any other and walks through the normal
 * intake.
 *
 * <p>A query-addressed HTTP shelf, not a model lane: one fetch, one tolerant
 * Atom read, no state. The class carries no caller of its own - it is the
 * terminal's only access to {@code export.arxiv.org} and stands ready.
 */
final class ArxivSearchClient {

    private static final Logger LOG = LoggerFactory.getLogger(ArxivSearchClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final WebFetcher fetcher;

    ArxivSearchClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The shelf's name, for logs and ledgers. */
    String name() {
        return "arxiv";
    }

    /** One query against the Atom API; anything unanswered stays empty. */
    List<Article> search(String query, int limit) {
        try {
            WebResponse resp = fetcher.fetch(
                    "http://export.arxiv.org/api/query?search_query=all:"
                            + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&max_results=" + Math.max(1, Math.min(20, limit))
                            + "&sortBy=relevance",
                    Map.of("User-Agent", "wsbg-terminal/1.0",
                            "Accept", "application/atom+xml"),
                    TIMEOUT);
            if (resp == null || resp.status() != 200) return List.of();
            return parseAtomArxiv(resp.body(), limit);
        } catch (Exception e) {
            LOG.debug("[ARXIV] research failed for '{}': {}", query, e.getMessage());
            return List.of();
        }
    }

    private static final Pattern ATOM_ENTRY = Pattern.compile("<entry>(.*?)</entry>",
            Pattern.DOTALL);
    private static final Pattern ATOM_TITLE = Pattern.compile("<title[^>]*>(.*?)</title>",
            Pattern.DOTALL);
    private static final Pattern ATOM_ID = Pattern.compile("<id>(.*?)</id>",
            Pattern.DOTALL);
    private static final Pattern ATOM_ALT = Pattern.compile(
            "<link[^>]*rel=\"alternate\"[^>]*>", Pattern.DOTALL);
    private static final Pattern ATOM_HREF = Pattern.compile("href=\"([^\"]+)\"");
    private static final Pattern ATOM_SUMMARY = Pattern.compile(
            "<summary[^>]*>(.*?)</summary>", Pattern.DOTALL);
    private static final Pattern ATOM_PUBLISHED = Pattern.compile(
            "<published>(.*?)</published>", Pattern.DOTALL);

    /** Tolerant Atom read of the arXiv answer; anything unreadable is skipped. */
    static List<Article> parseAtomArxiv(String xml, int limit) {
        List<Article> out = new ArrayList<>();
        if (xml == null || xml.isBlank()) return out;
        Matcher entry = ATOM_ENTRY.matcher(xml);
        while (entry.find() && out.size() < limit) {
            String block = entry.group(1);
            Matcher t = ATOM_TITLE.matcher(block);
            if (!t.find()) continue;
            String titel = entwirre(t.group(1)).replaceAll("\\s+", " ");
            String link = "";
            Matcher alt = ATOM_ALT.matcher(block);
            if (alt.find()) {
                Matcher h = ATOM_HREF.matcher(alt.group());
                if (h.find()) link = entwirre(h.group(1));
            }
            if (link.isBlank()) {
                Matcher id = ATOM_ID.matcher(block);
                if (id.find()) link = entwirre(id.group(1));
            }
            if (titel.isBlank() || link.isBlank()) continue;
            String zusammenfassung = null;
            Matcher s = ATOM_SUMMARY.matcher(block);
            if (s.find()) {
                String z = entwirre(s.group(1)).replaceAll("\\s+", " ");
                if (!z.isBlank()) zusammenfassung = z.length() > 900
                        ? z.substring(0, 900) + "…" : z;
            }
            Instant wann = null;
            Matcher p = ATOM_PUBLISHED.matcher(block);
            if (p.find()) {
                try {
                    wann = Instant.parse(p.group(1).strip());
                } catch (Exception ignored) {
                }
            }
            out.add(new Article(link, titel, "arXiv", link, wann, List.of(),
                    null, zusammenfassung, false));
        }
        return out;
    }

    private static String entwirre(String s) {
        return s == null ? "" : s.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .strip();
    }
}
