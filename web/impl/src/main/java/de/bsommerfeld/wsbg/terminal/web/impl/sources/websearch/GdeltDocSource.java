package de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * GDELT DOC 2.0 - the keyless world-press full-text index (articles from
 * January 2017 on, German small caps included; probed 2026-07-16), asked with
 * the German pin on ({@code sourcelang:german}): the German-press breadth leg
 * beside Google News (press colour).
 *
 * <p>Both doors of the new world: as an {@link InstrumentSource} it asks the
 * quoted company name (the probe measured ~50% referent noise on the bare
 * name; the language pin plus the title filter carry precision - an ISIN or
 * ticker means nothing to a press index, so the name is the only key this
 * backend can address). As a {@link SearchEngine} it carries the free research
 * query with the same German pin - the pin is this source's identity, its
 * world twin ({@link GdeltWorldSource}) covers the other languages. And as an
 * {@link ArchiveSource} it narrows the same name query to a date window via
 * {@code startdatetime}/{@code enddatetime} - the windowed archive fan the old
 * world addressed this backend with exclusively: the multi-year press-history
 * leg's breadth source beside Google News (press colour) and the EQS archive
 * (disclosures).
 *
 * <p>THE RATE LIMIT IS SERIOUS: GDELT wants one request every ~5 seconds and
 * a short burst earned a multi-minute IP block during probing. The old world's
 * global 8-second gate is gone - the house fetcher owns the host's cooldown
 * economy - and this source stays polite by skipping outright while
 * {@code hostCoolingDown()} says the host is resting: a request that would
 * only be told to wait is never sent.
 */
@Singleton
public final class GdeltDocSource extends AbstractWebSource
        implements InstrumentSource, SearchEngine, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(GdeltDocSource.class);

    static final String DOC_URL = "https://api.gdeltproject.org/api/v2/doc/doc"
            + "?query=%s&mode=artlist&format=json&maxrecords=%d";
    static final String WINDOW = "&startdatetime=%s&enddatetime=%s";
    /** GDELT's index starts January 2017 - earlier windows stay silent. */
    static final LocalDate GDELT_EPOCH = LocalDate.of(2017, 1, 1);
    static final DateTimeFormatter GDELT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    @Inject
    public GdeltDocSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "gdelt";
    }

    /** The API carries no wall, only the rate gate - direct first. */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /**
     * The German press on one company, quoted-name query, title-filtered.
     * Name is the only key GDELT can address - a missing name answers empty.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        String name = instrument.name();
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        // Quoted phrase + German sources: the probe measured ~50% referent
        // noise on the bare name; the language pin plus the title filter
        // below carry precision (the ISIN means nothing to a press index).
        return ask("\"" + cleanName(name) + "\" sourcelang:german",
                firstSignificantWord(name), limit);
    }

    /**
     * The same quoted-name query, narrowed to one window of the multi-year
     * history - the archive fan the old world addressed this backend with
     * exclusively. Windows entirely before the index's start answer empty
     * without a request.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        String name = instrument.name();
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        // GDELT's index starts January 2017 - earlier windows stay silent.
        if (!toDateExclusive.isAfter(GDELT_EPOCH)) return List.of();
        return ask("\"" + cleanName(name) + "\" sourcelang:german",
                firstSignificantWord(name), limit, fromDate, toDateExclusive);
    }

    /** The free research query, carried with this source's German pin. */
    @Override
    public List<Article> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return ask(query.strip() + " sourcelang:german", null, limit);
    }

    private List<Article> ask(String query, String nameWord, int limit) {
        return ask(query, nameWord, limit, null, null);
    }

    private List<Article> ask(String query, String nameWord, int limit,
            LocalDate fromDate, LocalDate toDateExclusive) {
        try {
            // GDELT wants %20 spaces (never '+') - live-probed: '+' answers a
            // plain-text error page instead of JSON.
            String url = String.format(DOC_URL,
                    URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"),
                    Math.min(limit * 4, 75));
            if (fromDate != null) {
                // GDELT wants YYYYMMDDHHMMSS (no T/Z - live-probed: the ISO
                // form answers a plain-text error).
                url += String.format(WINDOW,
                        fromDate.toString().replace("-", "") + "000000",
                        toDateExclusive.toString().replace("-", "") + "000000");
            }
            // A host that is resting is owed no request: while the fetcher's
            // cooldown for GDELT runs, asking would only burn the collect
            // phase's time (measured 2026-08-11: 39 of 83 gated requests slept
            // to be told there was nothing to wait for).
            if (hostCoolingDown(url)) return List.of();
            WebResponse resp = get(url, Map.of("Accept", "application/json"), REQUEST_TIMEOUT);
            if (resp == null || resp.status() != 200 || resp.body() == null) return List.of();
            // GDELT answers its rate limit as HTTP 200 with a plain-text
            // sentence - without this tripwire the source would starve in
            // silence, which is exactly how a throttled leg goes unnoticed.
            if (!resp.body().stripLeading().startsWith("{")) {
                LOG.warn("[gdelt] answered a 200 that is not JSON - rate gate or "
                        + "query rejected: {}",
                        resp.body().strip().substring(0, Math.min(120, resp.body().strip().length())));
                return List.of();
            }
            JsonNode articles = JSON.readTree(resp.body()).path("articles");
            if (!articles.isArray()) return List.of();
            List<Article> out = new ArrayList<>();
            for (JsonNode a : articles) {
                if (out.size() >= limit) break;
                String title = a.path("title").asText(null);
                if (title == null || title.isBlank()) continue;
                // Referent filter: the company's leading name word must appear
                // in the TITLE - GDELT full-text matches drag in body-only
                // mentions that are bycatch for a per-name fan.
                if (nameWord != null
                        && !title.toLowerCase(Locale.ROOT).contains(nameWord)) {
                    continue;
                }
                Instant at = parseSeenDate(a.path("seendate").asText(null));
                out.add(new Article(
                        "gdelt-" + Integer.toHexString(a.path("url").asText(title).hashCode()),
                        title.strip(), a.path("domain").asText("GDELT"),
                        a.path("url").asText(null), at, List.of()));
            }
            if (!out.isEmpty()) {
                LOG.info("[gdelt] '{}' → {} article(s)", query, out.size());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.debug("[gdelt] '{}' failed: {}", query, e.getMessage());
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

    static String firstSignificantWord(String companyName) {
        String cleaned = cleanName(companyName).toLowerCase(Locale.ROOT);
        String first = cleaned.split("\\s+")[0].replaceAll("[^a-z0-9äöüß-]", "");
        return first.length() >= 3 ? first : null;
    }

    static Instant parseSeenDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.strip(), GDELT_STAMP).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }
}
