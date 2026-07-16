package de.bsommerfeld.wsbg.terminal.briefing;

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

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The EQS disclosure ARCHIVE: {@code eqs-news.com/wp-json/eqsnews/v1/news}
 * answers keyless JSON, ISIN-filtered, paginated 20/page - the DGAP legacy
 * reaches back beyond 2018 even for small caps (probed 2026-07-16: Mutares =
 * 654 records, page 30 at 2018-05). The deepest keyless first-party-adjacent
 * history of the German disclosure universe, and exactly the pennystock
 * coverage the multi-year press leg needs. Answers ONLY the windowed archive
 * fan ({@link NewsSource#newsForNameWindow}); ISIN-addressed - without an
 * ISIN it stays silent (precision: never a same-named twin).
 *
 * <p>Records carry no article URL - the dated, categorized headline IS the
 * value ("[Ad-hoc] ..."); the item ships with a {@code null} link like the
 * finanznachrichten analyst leg.
 */
@Singleton
public class EqsNewsArchiveClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(EqsNewsArchiveClient.class);

    private static final String NEWS_URL =
            "https://www.eqs-news.com/wp-json/eqsnews/v1/news?lang=de&isin=%s&page=%d";
    /** 20 records/page; 40 pages = 800 disclosures ≈ a decade of a busy name. */
    private static final int MAX_PAGES = 40;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport (the endpoint carries no wall). */
    public EqsNewsArchiveClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public EqsNewsArchiveClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "eqs-archive";
    }

    /** Archive-only source: the live-news fans stay unanswered. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
            String fromIsoDate, String toIsoDateExclusive, int limit) {
        if (isin == null || isin.isBlank() || limit <= 0) return List.of();
        try {
            Instant from = LocalDate.parse(fromIsoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = LocalDate.parse(toIsoDateExclusive)
                    .atStartOfDay(ZoneOffset.UTC).toInstant();
            List<RawNewsItem> out = new ArrayList<>();
            String key = isin.strip().toUpperCase(Locale.ROOT);
            for (int page = 1; page <= MAX_PAGES && out.size() < limit; page++) {
                WebResponse resp = fetcher.fetch(
                        String.format(NEWS_URL, key, page),
                        Map.of("User-Agent", userAgent, "Accept", "application/json"),
                        requestTimeout);
                if (resp == null || resp.status() != 200 || resp.body() == null) break;
                JsonNode records = JSON.readTree(resp.body()).path("records");
                if (!records.isArray() || records.isEmpty()) break;
                boolean pageOlderThanWindow = true;
                for (JsonNode rec : records) {
                    Instant at = parseStamp(rec.path("dateUtc").asText(null),
                            rec.path("date").asText(null));
                    if (at == null) continue;
                    if (!at.isBefore(from)) pageOlderThanWindow = false;
                    if (at.isBefore(from) || !at.isBefore(to)) continue;
                    String headline = rec.path("headline").asText(null);
                    if (headline == null || headline.isBlank()) continue;
                    String category = rec.path("category").asText("");
                    String title = (category.isBlank() ? "" : "[" + category + "] ")
                            + headline.strip();
                    out.add(new RawNewsItem(
                            "eqs-" + rec.path("id").asText(String.valueOf(title.hashCode())),
                            title, "EQS-News", null, at, List.of()));
                    if (out.size() >= limit) break;
                }
                // Newest-first pagination: a page entirely older than the
                // window means everything after it is older too.
                if (pageOlderThanWindow) break;
            }
            if (!out.isEmpty()) {
                LOG.info("[eqs-archive] {} → {} disclosure(s) in {}..{}", key, out.size(),
                        fromIsoDate, toIsoDateExclusive);
            }
            return out;
        } catch (Exception e) {
            LOG.debug("[eqs-archive] window for {} failed: {}", isin, e.getMessage());
            return List.of();
        }
    }

    private static Instant parseStamp(String utc, String local) {
        for (String raw : new String[]{utc, local}) {
            if (raw == null || raw.isBlank()) continue;
            try {
                return LocalDateTime.parse(raw.strip(), STAMP).toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
                // fall through to the next candidate
            }
        }
        return null;
    }
}
