package de.bsommerfeld.wsbg.terminal.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.source.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
 * subject's own.
 *
 * <p>A filing is a PRIMARY document - the issuer's own statement under
 * liability, not a report about it. Its sphere is named accordingly: the US
 * regulatory record is one gatekeeping, and a fact carried by a filing plus a
 * newspaper is genuinely carried twice.
 *
 * <p>DOSSIER-ONLY: filings are research material. The wire wants the day's
 * catalyst in a sentence, and a 10-K is not that.
 */
@Singleton
public class EdgarFullTextClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(EdgarFullTextClient.class);

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

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** Test/default: plain direct transport. */
    public EdgarFullTextClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public EdgarFullTextClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "edgar-fulltext";
    }

    @Override
    public boolean dossierOnly() {
        return true;
    }

    /** The US regulatory record - a sphere of its own, and a primary one. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("en", "SEC");
    }

    /** The symbol is not what a filing's text carries - the NAME is. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        return search("\"" + companyName.strip() + "\"", null, null, limit);
    }

    /**
     * The ISIN inside filing TEXT: a US issuer prints it on prospectuses and
     * on the notes it issues, and a European issuer's ISIN appears in the
     * filings of whoever holds or underwrites it. Precise by construction.
     */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        if (isin == null || isin.isBlank() || limit <= 0) return List.of();
        return search("\"" + isin.strip().toUpperCase(Locale.ROOT) + "\"", null, null, limit);
    }

    /** The same index, narrowed to one window of the multi-year history. */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
            String fromIsoDate, String toIsoDateExclusive, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        // Full-text coverage starts in 2001; earlier windows stay silent.
        if (toIsoDateExclusive.compareTo("2001-01-01") <= 0) return List.of();
        return search("\"" + companyName.strip() + "\"", fromIsoDate,
                dayBefore(toIsoDateExclusive), limit);
    }

    private List<RawNewsItem> search(String query, String from, String to, int limit) {
        try {
            StringBuilder url = new StringBuilder(String.format(SEARCH_URL,
                    URLEncoder.encode(query, StandardCharsets.UTF_8)));
            url.append("&forms=").append(URLEncoder.encode(FORMS, StandardCharsets.UTF_8));
            if (from != null) url.append("&dateRange=custom&startdt=").append(from);
            if (to != null) url.append("&enddt=").append(to);
            WebResponse resp = fetcher.fetch(url.toString(),
                    Map.of("User-Agent", EdgarClient.userAgent(), "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.debug("[edgar-fts] status {} for {}",
                        resp == null ? "null" : resp.status(), query);
                return List.of();
            }
            List<RawNewsItem> out = new ArrayList<>();
            for (JsonNode hit : JSON.readTree(resp.body()).path("hits").path("hits")) {
                if (out.size() >= limit) break;
                RawNewsItem item = toItem(hit);
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
    static RawNewsItem toItem(JsonNode hit) {
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
        return new RawNewsItem(
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

    /** EDGAR's {@code enddt} is INCLUSIVE - the house's window end is not. */
    private static String dayBefore(String isoDateExclusive) {
        try {
            return LocalDate.parse(isoDateExclusive).minusDays(1).toString();
        } catch (Exception e) {
            return isoDateExclusive;
        }
    }
}
