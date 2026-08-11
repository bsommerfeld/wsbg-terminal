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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GDELT DOC 2.0 asked of the world's OTHER languages - the same keyless
 * full-text index as {@link GdeltDocClient}, with the German pin taken off.
 *
 * <p>The pin was never what carried precision (the title filter is); it was
 * what kept the index to one language area. GDELT indexes 65+ languages, and
 * one {@code sourcelang} OR-clause reaches all of them in a SINGLE request -
 * which matters more here than anywhere else, because the API's rate gate
 * makes every extra request eight seconds of the collect phase.
 *
 * <p>The origin needs no declaration on this source: GDELT reports the
 * language and the source country OF EACH ARTICLE, so every item is stamped
 * with what the index itself says about it - the sphere as a measured field,
 * not as a house opinion. This is the one source in the net that can tell a
 * Chinese-language piece out of Taiwan from one out of the mainland without
 * anybody maintaining a map.
 *
 * <p>DOSSIER-ONLY, like its German twin: an eight-second gate has no place on
 * the live wire.
 */
@Singleton
public class GdeltWorldClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(GdeltWorldClient.class);

    private static final String DOC_URL = "https://api.gdeltproject.org/api/v2/doc/doc"
            + "?query=%s&mode=artlist&format=json&maxrecords=%d";
    private static final String WINDOW = "&startdatetime=%s&enddatetime=%s";
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The languages asked for, as GDELT names them, in GROUPS of four.
     *
     * <p>The grouping is forced by the host: GDELT answers a query of sixteen
     * OR-clauses with "your query was too short or too long" (measured
     * 2026-08-11) - the API caps the query string, not the number of results.
     * Four clauses answer, so the fan is two requests rather than one, and
     * each pays the eight-second gate.
     *
     * <p>German and English stay out. The German leg is
     * {@link GdeltDocClient}'s, and English is the language every other source
     * in the net already speaks - inside a capped reply either would crowd out
     * precisely the coverage this source exists for.
     */
    private static final List<String> SPRACHGRUPPEN = List.of(
            "(sourcelang:chinese OR sourcelang:russian OR sourcelang:japanese "
                    + "OR sourcelang:korean)",
            "(sourcelang:spanish OR sourcelang:portuguese OR sourcelang:french "
                    + "OR sourcelang:arabic)");

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);

    /** Test/default: plain direct transport (the API carries no wall, only the rate gate). */
    public GdeltWorldClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public GdeltWorldClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "gdelt-world";
    }

    /** Eight seconds per request is research depth, never wire material. */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    /** A ticker symbol means nothing to a press index. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** The recent world press in every language but the ones already covered. */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        return ask(companyName, limit, null, null);
    }

    /** The same reach, narrowed to one window of the multi-year history. */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
            String fromIsoDate, String toIsoDateExclusive, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        // GDELT's index starts January 2017 - earlier windows stay silent.
        if (toIsoDateExclusive.compareTo("2017-01-01") <= 0) return List.of();
        return ask(companyName, limit, fromIsoDate, toIsoDateExclusive);
    }

    /**
     * One request per language group, strictly sequential through the host's
     * gate, merged in group order. A group that fails costs that group.
     */
    private List<RawNewsItem> ask(String companyName, int limit, String fromIsoDate,
            String toIsoDateExclusive) {
        List<RawNewsItem> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String gruppe : SPRACHGRUPPEN) {
            for (RawNewsItem item : askGroup(companyName, gruppe, limit, fromIsoDate,
                    toIsoDateExclusive)) {
                if (seen.add(item.link() == null ? item.uuid() : item.link())) out.add(item);
            }
        }
        return out;
    }

    private List<RawNewsItem> askGroup(String companyName, String sprachgruppe, int limit,
            String fromIsoDate, String toIsoDateExclusive) {
        try {
            String query = "\"" + GdeltDocClient.cleanName(companyName) + "\" " + sprachgruppe;
            String url = String.format(DOC_URL,
                    URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20"),
                    Math.min(limit * 4, 75));
            if (fromIsoDate != null) {
                // GDELT wants YYYYMMDDHHMMSS (no T/Z - the ISO form answers a
                // plain-text error) and %20 spaces (never '+').
                url += String.format(WINDOW, fromIsoDate.replace("-", "") + "000000",
                        toIsoDateExclusive.replace("-", "") + "000000");
            }
            String call = url;
            WebResponse resp = GdeltGate.through(call, () -> fetcher.fetch(call,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout));
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
            String nameWord = GdeltDocClient.firstSignificantWord(companyName);
            List<RawNewsItem> out = new ArrayList<>();
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
                Instant at = GdeltDocClient.parseSeenDate(a.path("seendate").asText(null));
                out.add(new RawNewsItem(
                        "gdelt-world-"
                                + Integer.toHexString(a.path("url").asText(title).hashCode()),
                        title.strip(), a.path("domain").asText("GDELT"),
                        a.path("url").asText(null), at, List.of(), null, null, false, null,
                        GdeltOrigin.of(a.path("language").asText(""),
                                a.path("sourcecountry").asText(""))));
            }
            if (!out.isEmpty()) {
                LOG.info("[gdelt-world] '{}' {} → {} article(s)", companyName,
                        fromIsoDate == null ? "recent" : fromIsoDate + ".." + toIsoDateExclusive,
                        out.size());
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.debug("[gdelt-world] '{}' failed: {}", companyName, e.getMessage());
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
