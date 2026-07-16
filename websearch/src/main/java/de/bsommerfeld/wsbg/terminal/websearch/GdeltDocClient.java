package de.bsommerfeld.wsbg.terminal.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GDELT DOC 2.0 - the keyless world-press full-text index (articles from
 * January 2017 on, German small caps included; probed 2026-07-16). Answers
 * ONLY the windowed archive fan ({@link NewsSource#newsForNameWindow}): the
 * multi-year press-history leg's breadth source beside Google News (press
 * colour) and the EQS archive (disclosures).
 *
 * <p>THE RATE LIMIT IS SERIOUS: GDELT wants one request every ~5 seconds and
 * a short burst earned a multi-minute IP block during probing. Every request
 * passes a global 8-second gate, strictly sequential - the archive sweep is a
 * handful of yearly windows, so the wait is bounded and cheap.
 */
@Singleton
public class GdeltDocClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(GdeltDocClient.class);

    private static final String DOC_URL = "https://api.gdeltproject.org/api/v2/doc/doc"
            + "?query=%s&mode=artlist&format=json&maxrecords=%d"
            + "&startdatetime=%s&enddatetime=%s";
    private static final DateTimeFormatter GDELT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One global gate for the whole JVM - GDELT blocks bursty IPs for minutes. */
    private static final Object GATE = new Object();
    private static final long MIN_INTERVAL_MS = 8_000;
    private static long lastRequestMs = 0;

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);

    /** Test/default: plain direct transport (the API carries no wall, only the rate gate). */
    public GdeltDocClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public GdeltDocClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "gdelt";
    }

    /** Archive-only source: the live-news fans stay unanswered. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
            String fromIsoDate, String toIsoDateExclusive, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        // GDELT's index starts January 2017 - earlier windows stay silent.
        if (toIsoDateExclusive.compareTo("2017-01-01") <= 0) return List.of();
        try {
            // Quoted phrase + German sources: the probe measured ~50% referent
            // noise on the bare name; the language pin plus the title filter
            // below carry precision (the ISIN means nothing to a press index).
            String query = "\"" + cleanName(companyName) + "\" sourcelang:german";
            // GDELT wants YYYYMMDDHHMMSS (no T/Z - live-probed: the ISO form
            // answers a plain-text error) and %20 spaces (never '+').
            String url = String.format(DOC_URL,
                    URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"),
                    Math.min(limit * 4, 75),
                    fromIsoDate.replace("-", "") + "000000",
                    toIsoDateExclusive.replace("-", "") + "000000");
            WebResponse resp;
            synchronized (GATE) {
                long wait = lastRequestMs + MIN_INTERVAL_MS - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                try {
                    resp = fetcher.fetch(url, Map.of("User-Agent", userAgent,
                            "Accept", "application/json"), requestTimeout);
                } finally {
                    lastRequestMs = System.currentTimeMillis();
                }
            }
            if (resp == null || resp.status() != 200 || resp.body() == null) return List.of();
            JsonNode articles = JSON.readTree(resp.body()).path("articles");
            if (!articles.isArray()) return List.of();
            String nameWord = firstSignificantWord(companyName);
            List<RawNewsItem> out = new ArrayList<>();
            for (JsonNode a : articles) {
                if (out.size() >= limit) break;
                String title = a.path("title").asText(null);
                if (title == null || title.isBlank()) continue;
                // Referent filter: the company's leading name word must appear
                // in the TITLE - GDELT full-text matches drag in body-only
                // mentions that are bycatch for a per-name history.
                if (nameWord != null
                        && !title.toLowerCase(Locale.ROOT).contains(nameWord)) {
                    continue;
                }
                Instant at = parseSeenDate(a.path("seendate").asText(null));
                out.add(new RawNewsItem(
                        "gdelt-" + Integer.toHexString(a.path("url").asText(title).hashCode()),
                        title.strip(), a.path("domain").asText("GDELT"),
                        a.path("url").asText(null), at, List.of()));
            }
            if (!out.isEmpty()) {
                LOG.info("[gdelt] '{}' {}..{} → {} article(s)", companyName,
                        fromIsoDate, toIsoDateExclusive, out.size());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.debug("[gdelt] window for '{}' failed: {}", companyName, e.getMessage());
            return List.of();
        }
    }

    /** "Mutares SE & Co. KGaA" → "Mutares" (legal-form tails mean nothing to the press). */
    static String cleanName(String companyName) {
        String[] words = companyName.strip().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            String lower = w.toLowerCase(Locale.ROOT).replaceAll("[.,&]+", "");
            if (out.length() > 0 && (lower.matches(
                    "se|ag|kgaa|gmbh|co|inc|corp|plc|nv|sa|oyj|ab|as|spa|holding[s]?")
                    || lower.isEmpty())) {
                break;
            }
            if (out.length() > 0) out.append(' ');
            out.append(w);
        }
        return out.length() == 0 ? companyName.strip() : out.toString();
    }

    private static String firstSignificantWord(String companyName) {
        String cleaned = cleanName(companyName).toLowerCase(Locale.ROOT);
        String first = cleaned.split("\\s+")[0].replaceAll("[^a-z0-9äöüß-]", "");
        return first.length() >= 3 ? first : null;
    }

    private static Instant parseSeenDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.strip(), GDELT_STAMP).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
