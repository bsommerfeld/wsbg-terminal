package de.bsommerfeld.wsbg.terminal.web.impl.sources.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EDGAR full-text search ({@code efts.sec.gov/LATEST/search-index}, keyless,
 * live-probed 2026-08-11) - the SEC's own index over the TEXT of every filing
 * since 2001, not just over their headers.
 *
 * <p>What it adds to the eight-K leg beside it: the 8-K client answers "what
 * did this issuer file", this one answers "where is this said". A supplier
 * named in someone else's risk factors, a customer concentration disclosed in
 * a 10-K, a prospectus that prices a placement - all of it is text inside
 * documents nobody indexes by ticker. That is why this source is
 * NAME-addressed as well: the filing that matters most is often not the
 * subject's own. The additive fan asks the quoted name beside the quoted
 * ISIN - a US issuer prints its ISIN on prospectuses and on the notes it
 * issues, and a European issuer's ISIN appears in the filings of whoever
 * holds or underwrites it. Precise by construction.
 *
 * <p>A filing is a PRIMARY document - the issuer's own statement under
 * liability, not a report about it. Its sphere is named accordingly: the US
 * regulatory record is one gatekeeping, and a fact carried by a filing plus a
 * newspaper is genuinely carried twice.
 *
 * <p>Research material, never wire copy - what the old {@code dossierOnly()}
 * flag declared, the {@link InstrumentSource} contract kind now carries.
 */
@Singleton
public final class EdgarFullTextSource extends AbstractWebSource implements InstrumentSource {

    private static final Logger LOG = LoggerFactory.getLogger(EdgarFullTextSource.class);

    static final String SEARCH_URL = "https://efts.sec.gov/LATEST/search-index?q=%s";

    /**
     * The substantive forms. Without this the index answers with fund
     * PAPERWORK: a name that appears in one proxy-voting record appears in
     * ten thousand of them, and a measured probe returned an N-PX voting
     * table as its top hit while the issuer's own filings sat behind it (302
     * substantive hits against 10 000+ unfiltered, 2026-08-11). These are the
     * documents where a company is WRITTEN ABOUT under liability - annual and
     * quarterly reports, event filings, foreign-issuer reports, registration
     * and offering documents, proxy statements and the two blockholder
     * schedules. A form vocabulary, not an editorial choice of topics.
     */
    private static final String FORMS =
            "10-K,10-Q,8-K,20-F,40-F,6-K,S-1,S-4,F-1,424B3,424B4,424B5,DEF 14A,SC 13D,SC 13G";
    private static final String ARCHIVE_URL =
            "https://www.sec.gov/Archives/edgar/data/%s/%s/%s";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    @Inject
    public EdgarFullTextSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "edgar-fulltext";
    }

    /** The API carries no wall - direct first, the joker only as rescue. */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** The US regulatory record - a sphere of its own, and a primary one. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("en", "SEC");
    }

    /**
     * Additive key fan: the quoted NAME beside the quoted ISIN, de-duplicated
     * by filing id, capped at {@code limit}. The symbol is not what a
     * filing's text carries - it takes no leg.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (limit <= 0) return List.of();
        Map<String, Article> merged = new LinkedHashMap<>();
        String name = instrument.name();
        if (name != null && !name.isBlank()) {
            for (Article a : search("\"" + name.strip() + "\"", limit)) {
                merged.putIfAbsent(a.uuid(), a);
            }
        }
        instrument.isin().ifPresent(isin -> {
            for (Article a : search("\"" + isin.value() + "\"", limit)) {
                merged.putIfAbsent(a.uuid(), a);
            }
        });
        List<Article> out = new ArrayList<>(merged.values());
        return out.size() <= limit ? out : List.copyOf(out.subList(0, limit));
    }

    private List<Article> search(String query, int limit) {
        try {
            StringBuilder url = new StringBuilder(String.format(SEARCH_URL,
                    URLEncoder.encode(query, StandardCharsets.UTF_8)));
            url.append("&forms=").append(URLEncoder.encode(FORMS, StandardCharsets.UTF_8));
            // SEC fair-access: the descriptive User-Agent with contact is
            // MANDATORY on every SEC host (violations earn a ~10 min 403
            // block) - it stays explicit at the call site, never left to the
            // transport's default.
            WebResponse resp = get(url.toString(),
                    Map.of("User-Agent", EdgarClient.userAgent(), "Accept", "application/json"),
                    REQUEST_TIMEOUT);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[edgar-fts] status {} for {}",
                        resp == null ? "null" : resp.status(), query);
                return List.of();
            }
            List<Article> out = new ArrayList<>();
            for (JsonNode hit : JSON.readTree(resp.body()).path("hits").path("hits")) {
                if (out.size() >= limit) break;
                Article item = toItem(hit);
                if (item != null) out.add(item);
            }
            if (!out.isEmpty()) {
                LOG.info("[edgar-fts] {} → {} filing(s)", query, out.size());
            }
            return out;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[edgar-fts] {} failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * One search hit → an item. The TITLE is built by the house from the
     * form type and the filer, because the index carries no headline - a
     * filing has a form and a filer, and those two are what a reader needs
     * to know before opening it.
     */
    static Article toItem(JsonNode hit) {
        JsonNode src = hit.path("_source");
        String id = hit.path("_id").asText("");
        if (id.isEmpty()) return null;
        String form = src.path("root_forms").isArray() && src.path("root_forms").size() > 0
                ? src.path("root_forms").get(0).asText("")
                : src.path("file_type").asText("");
        String filer = src.path("display_names").isArray()
                && src.path("display_names").size() > 0
                ? src.path("display_names").get(0).asText("")
                : "";
        String filed = src.path("file_date").asText("");
        String title = (form.isBlank() ? "SEC-Filing" : form)
                + (filer.isBlank() ? "" : " · " + filer);
        String cik = src.path("ciks").isArray() && src.path("ciks").size() > 0
                ? src.path("ciks").get(0).asText("")
                : "";
        String url = documentUrl(id, cik);
        if (url == null) return null;
        return new Article(
                "edgar-fts-" + id,
                title,
                "SEC EDGAR",
                url,
                instantOf(filed),
                List.of());
    }

    /**
     * The search id is {@code <accession-with-dashes>:<document>}; the archive
     * path wants the accession WITHOUT dashes as the directory, under the CIK
     * of the FILING'S OWN filer. Not the accession's leading block - that is
     * the filing AGENT's id, and the measured path built from it answers 404
     * while the filer's own answers 200 (probed 2026-08-11).
     */
    static String documentUrl(String id, String cik) {
        if (cik == null || cik.isBlank()) return null;
        int colon = id.indexOf(':');
        String accession = (colon > 0 ? id.substring(0, colon) : id).replace("-", "");
        String document = colon > 0 ? id.substring(colon + 1) : "";
        if (document.isEmpty()) return null;
        long bare;
        try {
            bare = Long.parseLong(cik.strip());
        } catch (NumberFormatException e) {
            return null;
        }
        return String.format(ARCHIVE_URL, String.valueOf(bare), accession, document);
    }

    private static Instant instantOf(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate.strip()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
