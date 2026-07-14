package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * EQS-News' corporate-events register (live-verified 2026-07-13, keyless):
 * upcoming German AGM / earnings / blackout dates straight from the issuers'
 * EQS/DGAP disclosure pipeline, ISIN on every record — joinable against the
 * cage's snapshots exactly like the FN ad-hocs. The HTML calendar page is a
 * 404-status app shell; the WordPress JSON API underneath is the real surface.
 * Quirk pinned by probe: {@code eventType} must be the literal {@code future}
 * ({@code upcoming} is silently ignored). Records with
 * {@code exactDateUnknown=1} are dropped — a date-less event can't sit on a
 * docket.
 */
@Singleton
public class EqsEventsClient {

    private static final Logger LOG = LoggerFactory.getLogger(EqsEventsClient.class);

    private static final String EVENTS_URL =
            "https://www.eqs-news.com/wp-json/eqsnews/v1/events?lang=de&eventType=future&page=";
    private static final int PAGES = 3; // 20 records/page — 60 events cover the near docket
    private static final long CACHE_TTL_MS = 30 * 60 * 1000;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One upcoming corporate event; {@code headline} is EQS's German event label. */
    public record CorporateEvent(Instant startDate, String isin, String companyName,
            String headline) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile List<CorporateEvent> cache = List.of();
    private volatile long cachedAtMs;

    /** Test/default: plain direct transport. */
    public EqsEventsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public EqsEventsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Upcoming corporate events (first pages, chronological as EQS ships them). Empty on failure. */
    public List<CorporateEvent> upcoming() {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache;
        List<CorporateEvent> out = new ArrayList<>();
        for (int page = 1; page <= PAGES; page++) {
            List<CorporateEvent> events = fetchPage(page);
            if (events.isEmpty()) break;
            out.addAll(events);
        }
        if (!out.isEmpty()) {
            cache = List.copyOf(out);
            cachedAtMs = now;
        }
        return out;
    }

    private List<CorporateEvent> fetchPage(int page) {
        try {
            WebResponse resp = fetcher.fetch(EVENTS_URL + page,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[EQS] events page {} answered status {}", page,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[EQS] events page {} failed: {}", page, e.getMessage());
        }
        return List.of();
    }

    /**
     * Package-private for tests: reply JSON → events, network-free. Shape pinned
     * by probe: {@code {"status":200,"records":[{startDate,isin,companyName,
     * headline,exactDateUnknown,…}]}}; {@code startDate} is ISO with offset.
     */
    static List<CorporateEvent> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<CorporateEvent> out = new ArrayList<>();
        try {
            JsonNode records = JSON.readTree(body).path("records");
            if (!records.isArray()) return List.of();
            for (JsonNode r : records) {
                if (r.path("exactDateUnknown").asInt(0) != 0) continue;
                String headline = r.path("headline").asText("");
                String date = r.path("startDate").asText("");
                if (headline.isEmpty() || date.isEmpty()) continue;
                Instant when;
                try {
                    when = OffsetDateTime.parse(date).toInstant();
                } catch (Exception ex) {
                    continue;
                }
                out.add(new CorporateEvent(when, blankToNull(r.path("isin").asText("")),
                        r.path("companyName").asText(""), headline));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
