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
 * The macro calendar with the numbers as NUMBERS (live-verified 2026-08-02):
 * {@code calendar-api.fxstreet.com/en/api/v1/eventDates/{fromUtc}/{toUtc}}.
 * 46 countries, typed actual/consensus/previous — and the one field no other
 * keyless calendar carries: {@code revised}, the prior print after its
 * revision. A revision is news in its own right; the house says "prior 85.6,
 * since revised to 85.7" instead of quietly contradicting itself a month later.
 *
 * <p>The 401 that reads like an auth wall is none: the endpoint answers 401
 * with an empty body when the query carries NO filter at all, and transiently
 * under burst. At least one {@code volatilities=} makes it 200, and a 401 is
 * retried once rather than treated as fatal (verified: three 401s in a row,
 * then 200 on the identical URLs after a pause).
 *
 * <p>{@code lastUpdated} is an epoch and moves when a value is corrected —
 * the natural change key. Per-event detail lives at {@code /eventDates/{id}}
 * (200, unauthenticated) and carries what the indicator measures, its issuing
 * body and {@code nextReleaseDate}.
 */
@Singleton
public class FxStreetCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(FxStreetCalendarClient.class);

    private static final String BASE = "https://calendar-api.fxstreet.com/en/api/v1/eventDates/";
    /** Without a filter the endpoint answers 401 — this one is mandatory, not cosmetic. */
    private static final String FILTER = "?volatilities=HIGH&volatilities=MEDIUM";
    private static final String REFERER = "https://www.fxstreet.com/";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One calendar entry. Values are {@code null} while unreleased;
     * {@code revised} is the PRIOR print's correction, not this one's.
     * {@code volatility} is FXStreet's rating ("HIGH" / "MEDIUM" / "LOW" /
     * "NONE"), {@code betterThanExpected} their own verdict (null when there
     * was no consensus to beat).
     */
    public record FxsEvent(String id, String name, String countryCode, String currencyCode,
            long whenEpochSeconds, String volatility, String unit,
            Double actual, Double consensus, Double previous, Double revised,
            Boolean betterThanExpected, boolean tentative, boolean speech, long lastUpdated) {

        /** Released, i.e. the print is in. */
        public boolean released() {
            return actual != null;
        }

        /** The prior print was corrected — news the raw actual does not carry. */
        public boolean priorRevised() {
            return revised != null && previous != null
                    && Math.abs(revised - previous) > 1e-9;
        }
    }

    /** What an indicator measures, who issues it, and when it comes round again. */
    public record FxsDetail(String description, String source, String urlSource,
            String nextReleaseDateIso) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public FxStreetCalendarClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam. */
    @Inject
    public FxStreetCalendarClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Every rated event in the window, chronological. Empty on any failure. */
    public List<FxsEvent> window(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) return List.of();
        String url = BASE + iso(from) + '/' + iso(to) + FILTER;
        String body = get(url);
        return body == null ? List.of() : parse(body);
    }

    /** Only the prints that already landed in the window. */
    public List<FxsEvent> outcomes(Instant from, Instant to) {
        List<FxsEvent> out = new ArrayList<>();
        for (FxsEvent e : window(from, to)) {
            if (e.released()) out.add(e);
        }
        return out;
    }

    /** What the indicator measures and who publishes it. {@code null} on any failure. */
    public FxsDetail describe(String eventDateId) {
        if (eventDateId == null || eventDateId.isBlank()) return null;
        String body = get(BASE + eventDateId);
        return body == null ? null : parseDetail(body);
    }

    /**
     * One retry on 401 — that status is the endpoint's burst signal here, not
     * a verdict about who is asking.
     */
    private String get(String url) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                WebResponse resp = fetcher.fetch(url,
                        Map.of("User-Agent", userAgent, "Accept", "application/json",
                                "Referer", REFERER),
                        requestTimeout);
                if (resp != null && resp.status() == 200) return resp.body();
                int status = resp == null ? -1 : resp.status();
                if (status != 401 || attempt == 1) {
                    LOG.debug("[FXStreet] {} answered status {}", url, status);
                    return null;
                }
                Thread.sleep(1500L);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                LOG.debug("[FXStreet] fetch {} failed: {}", url, e.getMessage());
                return null;
            }
        }
        return null;
    }

    /** Package-private for tests: calendar JSON → events, network-free, garbage-tolerant. */
    static List<FxsEvent> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<FxsEvent> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode n : root) {
                String name = text(n, "name");
                long when = epochOf(text(n, "dateUtc"));
                if (name.isEmpty() || when == 0L) continue;
                out.add(new FxsEvent(text(n, "id"), name, text(n, "countryCode"),
                        text(n, "currencyCode"), when, text(n, "volatility"), text(n, "unit"),
                        number(n, "actual"), number(n, "consensus"), number(n, "previous"),
                        number(n, "revised"), bool(n, "isBetterThanExpected"),
                        n.path("isTentative").asBoolean(false),
                        n.path("isSpeech").asBoolean(false),
                        n.path("lastUpdated").asLong(0L)));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** Package-private for tests: detail JSON → the explainer, garbage-tolerant. */
    static FxsDetail parseDetail(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode n = JSON.readTree(body);
            if (!n.isObject()) return null;
            // "whyMatters" is the editorial paragraph where it exists; most
            // events only carry the plain description.
            String why = text(n, "whyMatters");
            String description = why.isEmpty() ? text(n, "description") : why;
            String next = text(n, "nextReleaseDate");
            if (description.isEmpty() && next.isEmpty()) return null;
            return new FxsDetail(stripTags(description), text(n, "source"),
                    text(n, "urlSource"), next);
        } catch (Exception e) {
            return null;
        }
    }

    /** The descriptions carry anchor markup — the model wants the prose, not the HTML. */
    private static String stripTags(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s{2,}", " ").trim();
    }

    private static String iso(Instant when) {
        return when.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    private static long epochOf(String date) {
        if (date.isEmpty()) return 0L;
        try {
            return OffsetDateTime.parse(date).toEpochSecond();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Double number(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isNumber() ? null : v.asDouble();
    }

    private static Boolean bool(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() || !v.isBoolean() ? null : v.asBoolean();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
