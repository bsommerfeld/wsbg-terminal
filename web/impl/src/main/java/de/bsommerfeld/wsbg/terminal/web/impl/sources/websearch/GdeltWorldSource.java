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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GDELT DOC 2.0 asked of the world's OTHER languages - the same keyless
 * full-text index as {@link GdeltDocSource}, with the German pin taken off.
 *
 * <p>The pin was never what carried precision (the title filter is); it was
 * what kept the index to one language area. GDELT indexes 65+ languages, and
 * one {@code sourcelang} OR-clause reaches all of them in a SINGLE request -
 * which matters more here than anywhere else, because the host's rate gate
 * makes every extra request expensive.
 *
 * <p>The origin needs no declaration on this source: GDELT reports the
 * language and the source country OF EACH ARTICLE, so every item is stamped
 * with what the index itself says about it - the sphere as a measured field,
 * not as a house opinion. This is the one source in the net that can tell a
 * Chinese-language piece out of Taiwan from one out of the mainland without
 * anybody maintaining a map.
 *
 * <p>The rate limit is the host's ({@code hostCoolingDown()} skips outright
 * while GDELT rests - the old global 8-second gate is the fetcher's cooldown
 * economy now); the research-depth-not-wire semantic the old
 * {@code dossierOnly()} flag carried is now the contract kind itself. As an
 * {@link ArchiveSource} it carries the same reach narrowed to one window of
 * the multi-year history via {@code startdatetime}/{@code enddatetime}.
 */
@Singleton
public final class GdeltWorldSource extends AbstractWebSource
        implements InstrumentSource, SearchEngine, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(GdeltWorldSource.class);

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    /**
     * The languages asked for, as GDELT names them, in GROUPS of four.
     *
     * <p>The grouping is forced by the host: GDELT answers a query of sixteen
     * OR-clauses with "your query was too short or too long" (measured
     * 2026-08-11) - the API caps the query string, not the number of results.
     * Four clauses answer, so the fan is two requests rather than one, and
     * each pays the host's pace.
     *
     * <p>German and English stay out. The German leg is
     * {@link GdeltDocSource}'s, and English is the language every other source
     * in the net already speaks - inside a capped reply either would crowd out
     * precisely the coverage this source exists for.
     */
    private static final List<String> SPRACHGRUPPEN = List.of(
            "(sourcelang:chinese OR sourcelang:russian OR sourcelang:japanese "
                    + "OR sourcelang:korean)",
            "(sourcelang:spanish OR sourcelang:portuguese OR sourcelang:french "
                    + "OR sourcelang:arabic)");

    @Inject
    public GdeltWorldSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "gdelt-world";
    }

    /** The API carries no wall, only the rate gate - direct first. */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /**
     * The recent world press in every language but the ones already covered.
     * The name is the only key a press index can address - a ticker or ISIN
     * means nothing to it, so an instrument without a name answers empty.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        String name = instrument.name();
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        return ask("\"" + GdeltDocSource.cleanName(name) + "\"",
                GdeltDocSource.firstSignificantWord(name), limit);
    }

    /**
     * The same reach, narrowed to one window of the multi-year history. The
     * years are where the language areas diverge most. Windows entirely
     * before the index's start answer empty without a request.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        String name = instrument.name();
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        // GDELT's index starts January 2017 - earlier windows stay silent.
        if (!toDateExclusive.isAfter(GdeltDocSource.GDELT_EPOCH)) return List.of();
        return ask("\"" + GdeltDocSource.cleanName(name) + "\"",
                GdeltDocSource.firstSignificantWord(name), limit, fromDate, toDateExclusive);
    }

    /** The free research query, fanned across the language groups verbatim. */
    @Override
    public List<Article> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return ask(query.strip(), null, limit);
    }

    private List<Article> ask(String baseQuery, String nameWord, int limit) {
        return ask(baseQuery, nameWord, limit, null, null);
    }

    /**
     * One request per language group, merged in group order. A group that
     * fails costs that group.
     */
    private List<Article> ask(String baseQuery, String nameWord, int limit,
            LocalDate fromDate, LocalDate toDateExclusive) {
        List<Article> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String gruppe : SPRACHGRUPPEN) {
            for (Article item : askGroup(baseQuery, gruppe, nameWord, limit, fromDate,
                    toDateExclusive)) {
                if (seen.add(item.link() == null ? item.uuid() : item.link())) out.add(item);
            }
        }
        return out;
    }

    private List<Article> askGroup(String baseQuery, String sprachgruppe, String nameWord,
            int limit, LocalDate fromDate, LocalDate toDateExclusive) {
        String query = baseQuery + " " + sprachgruppe;
        try {
            // GDELT wants %20 spaces (never '+') - the '+' form answers a
            // plain-text error instead of JSON.
            String url = String.format(GdeltDocSource.DOC_URL,
                    URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"),
                    Math.min(limit * 4, 75));
            if (fromDate != null) {
                // GDELT wants YYYYMMDDHHMMSS (no T/Z - the ISO form answers a
                // plain-text error).
                url += String.format(GdeltDocSource.WINDOW,
                        fromDate.toString().replace("-", "") + "000000",
                        toDateExclusive.toString().replace("-", "") + "000000");
            }
            // Polite skip while the fetcher's GDELT cooldown runs - a resting
            // host is owed no request (replaces the old global 8 s gate).
            if (hostCoolingDown(url)) return List.of();
            WebResponse resp = get(url, Map.of("Accept", "application/json"), REQUEST_TIMEOUT);
            if (resp == null || resp.status() != 200 || resp.body() == null) return List.of();
            // GDELT answers its rate limit as HTTP 200 with a plain-text
            // sentence - without this tripwire the source would starve in
            // silence, which is exactly how a throttled leg goes unnoticed.
            if (!resp.body().stripLeading().startsWith("{")) {
                LOG.warn("[gdelt-world] answered a 200 that is not JSON - rate gate or "
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
                // The referent filter of the German leg, with the one exception
                // another script forces: a Han or Cyrillic headline writes the
                // company in its own characters, so a missing Latin name word
                // proves nothing there - and reading it as "off topic" would
                // silence exactly the coverage this source exists for. Where
                // the test cannot be applied, the quoted QUERY carried it.
                if (nameWord != null && hasLatinLetter(title)
                        && !title.toLowerCase(Locale.ROOT).contains(nameWord)) {
                    continue;
                }
                Instant at = GdeltDocSource.parseSeenDate(a.path("seendate").asText(null));
                out.add(new Article(
                        "gdelt-world-"
                                + Integer.toHexString(a.path("url").asText(title).hashCode()),
                        title.strip(), a.path("domain").asText("GDELT"),
                        a.path("url").asText(null), at, List.of(), null, null, false, null,
                        GdeltOrigin.of(a.path("language").asText(""),
                                a.path("sourcecountry").asText(""))));
            }
            if (!out.isEmpty()) {
                LOG.info("[gdelt-world] '{}' → {} article(s)", query, out.size());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.debug("[gdelt-world] '{}' failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    private static boolean hasLatinLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }
}
