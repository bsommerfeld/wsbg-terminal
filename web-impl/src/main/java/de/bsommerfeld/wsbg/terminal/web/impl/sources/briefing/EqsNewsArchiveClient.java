package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
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
 * coverage the multi-year press leg needs. ISIN-addressed - without an ISIN it
 * stays silent (precision: never a same-named twin).
 *
 * <p>Records carry no article URL - the dated, categorized headline IS the
 * value ("[Ad-hoc] ..."); the item ships with a {@code null} link like the
 * finanznachrichten analyst leg.
 *
 * <p>Transport {@code DIRECT,BROWSER}: the endpoint carries no wall.
 */
@Singleton
public class EqsNewsArchiveClient extends AbstractWebSource
        implements InstrumentSource, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(EqsNewsArchiveClient.class);

    private static final String NEWS_URL =
            "https://www.eqs-news.com/wp-json/eqsnews/v1/news?lang=de&isin=%s&page=%d";
    /** 20 records/page; 40 pages = 800 disclosures ≈ a decade of a busy name. */
    private static final int MAX_PAGES = 40;
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final Duration requestTimeout = Duration.ofSeconds(12);

    @Inject
    public EqsNewsArchiveClient(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "eqs-archive";
    }

    @Override
    public FetchUtil[] mode() {
        return MODES;
    }

    /**
     * The disclosure record for one instrument, newest first. ISIN-addressed
     * ONLY: an instrument that resolved no ISIN gets an empty answer instead
     * of a guess. Pages are walked newest-first until {@code limit} is
     * reached, a page comes back empty, or the {@link #MAX_PAGES} depth cap
     * ends the walk.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (instrument == null || instrument.isin().isEmpty() || limit <= 0) return List.of();
        String key = instrument.isin().get().value().strip().toUpperCase(Locale.ROOT);
        try {
            List<Article> out = new ArrayList<>();
            for (int page = 1; page <= MAX_PAGES && out.size() < limit; page++) {
                WebResponse resp = get(
                        String.format(NEWS_URL, key, page),
                        Map.of("Accept", "application/json"),
                        requestTimeout);
                if (resp == null || resp.status() != 200 || resp.body() == null) break;
                JsonNode records = JSON.readTree(resp.body()).path("records");
                if (!records.isArray() || records.isEmpty()) break;
                for (JsonNode rec : records) {
                    Instant at = parseStamp(rec.path("dateUtc").asText(null),
                            rec.path("date").asText(null));
                    if (at == null) continue;
                    String headline = rec.path("headline").asText(null);
                    if (headline == null || headline.isBlank()) continue;
                    String category = rec.path("category").asText("");
                    String title = (category.isBlank() ? "" : "[" + category + "] ")
                            + headline.strip();
                    out.add(new Article(
                            "eqs-" + rec.path("id").asText(String.valueOf(title.hashCode())),
                            title, "EQS-News", null, at, List.of()));
                    if (out.size() >= limit) break;
                }
            }
            if (!out.isEmpty()) {
                LOG.info("[eqs-archive] {} → {} disclosure(s)", key, out.size());
            }
            return out;
        } catch (Exception e) {
            LOG.debug("[eqs-archive] fan for {} failed: {}", key, e.getMessage());
            return List.of();
        }
    }

    /**
     * The windowed archive fan - the old world's ONLY door into this source.
     * The same ISIN-addressed page walk, cut to the window: newest-first
     * pagination means a page entirely older than the window ends the walk,
     * because everything after it is older too.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        if (instrument == null || instrument.isin().isEmpty() || limit <= 0
                || fromDate == null || toDateExclusive == null) {
            return List.of();
        }
        String key = instrument.isin().get().value().strip().toUpperCase(Locale.ROOT);
        try {
            Instant from = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant to = toDateExclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
            List<Article> out = new ArrayList<>();
            for (int page = 1; page <= MAX_PAGES && out.size() < limit; page++) {
                WebResponse resp = get(
                        String.format(NEWS_URL, key, page),
                        Map.of("Accept", "application/json"),
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
                    out.add(new Article(
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
                        fromDate, toDateExclusive);
            }
            return out;
        } catch (Exception e) {
            LOG.debug("[eqs-archive] window for {} failed: {}", key, e.getMessage());
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
