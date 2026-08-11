package de.bsommerfeld.wsbg.terminal.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * US directors' dealings: SEC Form 4, read from the issuer's own filings
 * ({@code data.sec.gov/submissions} → the ownership XML of each filing,
 * keyless, live-probed 2026-08-11).
 *
 * <p>The house had insider transactions for GERMANY only - BaFin's § 19 MAR
 * register - so for every US name the dossier's most direct signal about what
 * the people who know the company are doing with their own money was simply
 * absent. Form 4 is the same disclosure under a different regulator, and it is
 * filed within two business days.
 *
 * <p>It answers in the SAME shape as the BaFin leg ({@link InsiderDealings}),
 * on purpose: the register the deals come from differs, what a reader does
 * with them does not, and a second shape would mean a second rendering for the
 * same finding.
 *
 * <p>One request for the filing list plus one per filing read. The list is
 * long for a large issuer, so the read stops at the most recent filings - the
 * bound is stated in the log rather than hidden, because "the last fifteen"
 * and "all there were" are different findings.
 */
@Singleton
public class EdgarInsiderClient {

    private static final Logger LOG = LoggerFactory.getLogger(EdgarInsiderClient.class);

    private static final String SUBMISSIONS_URL_FMT =
            "https://data.sec.gov/submissions/CIK%010d.json";
    private static final String DOC_URL_FMT =
            "https://www.sec.gov/Archives/edgar/data/%d/%s/%s";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How many recent Form 4 filings are opened - one request each. */
    private static final int MAX_FILINGS = 15;

    /**
     * Form 4 transaction codes, as the SEC defines them - a protocol table,
     * the same class of thing as the 8-K item map beside it. Only the codes
     * that describe a MARKET transaction or a real transfer are named; an
     * unnamed code keeps its letter, which is honest and never invents a
     * reading the form does not carry. The German wording matches the BaFin
     * leg's, so both registers speak one vocabulary in the material.
     */
    private static final Map<String, String> CODES = Map.of(
            "P", "Kauf",
            "S", "Verkauf",
            "A", "Zuteilung",
            "M", "Optionsausübung",
            "F", "Steuereinbehalt",
            "G", "Schenkung",
            "C", "Wandlung",
            "D", "Rückgabe",
            "X", "Optionsausübung");

    private static final Pattern OWNER_NAME =
            Pattern.compile("<rptOwnerName>\\s*([^<]*?)\\s*</rptOwnerName>");
    private static final Pattern OFFICER_TITLE =
            Pattern.compile("<officerTitle>\\s*([^<]*?)\\s*</officerTitle>");
    private static final Pattern IS_DIRECTOR =
            Pattern.compile("<isDirector>\\s*(1|true)\\s*</isDirector>");
    private static final Pattern IS_TEN_PERCENT =
            Pattern.compile("<isTenPercentOwner>\\s*(1|true)\\s*</isTenPercentOwner>");
    private static final Pattern NON_DERIVATIVE = Pattern.compile(
            "<nonDerivativeTransaction>(.*?)</nonDerivativeTransaction>", Pattern.DOTALL);

    private final EdgarClient edgar;
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** Test/default: plain direct transport with its own CIK resolver. */
    public EdgarInsiderClient() {
        this(new EdgarClient(), new DirectWebFetcher());
    }

    @Inject
    public EdgarInsiderClient(EdgarClient edgar, @DirectFirst WebFetcher fetcher) {
        this.edgar = edgar;
        this.fetcher = fetcher;
    }

    /**
     * The reported Form 4 transactions of {@code ticker}, newest first, or
     * {@code null} when the issuer has no CIK (not US-listed) or the register
     * could not be read. An issuer with a CIK and no Form 4 in the recent
     * filings answers with an EMPTY deal list - "nobody reported anything" is
     * a finding, not a miss.
     *
     * @param isin carried through onto the record so both insider legs are
     *             keyed the same way; the SEC itself files by CIK
     */
    public InsiderDealings insiderDeals(String ticker, String isin) {
        Long cik = edgar.cikFor(ticker);
        if (cik == null) return null;
        try {
            WebResponse resp = fetcher.fetch(String.format(Locale.ROOT, SUBMISSIONS_URL_FMT, cik),
                    Map.of("User-Agent", EdgarClient.userAgent(), "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.warn("[edgar-insider] HTTP {} for {} (cik {})",
                        resp == null ? "null" : resp.status(), ticker, cik);
                return null;
            }
            JsonNode recent = JSON.readTree(resp.body()).path("filings").path("recent");
            JsonNode forms = recent.path("form");
            if (!forms.isArray()) return null;
            List<InsiderDealings.InsiderDeal> deals = new ArrayList<>();
            int opened = 0;
            int seen = 0;
            for (int i = 0; i < forms.size() && opened < MAX_FILINGS; i++) {
                if (!"4".equals(forms.path(i).asText())) continue;
                seen++;
                String accession = recent.path("accessionNumber").path(i).asText("")
                        .replace("-", "");
                String document = stripRenderer(
                        recent.path("primaryDocument").path(i).asText(""));
                if (accession.isEmpty() || document.isEmpty()) continue;
                opened++;
                String filed = recent.path("filingDate").path(i).asText(null);
                deals.addAll(readFiling(cik, accession, document, filed));
            }
            LOG.info("[edgar-insider] {} → {} transaction(s) from {} of {} Form-4 filing(s)",
                    ticker, deals.size(), opened, seen);
            return new InsiderDealings(isin == null ? "" : isin.toUpperCase(Locale.ROOT),
                    List.copyOf(deals), Instant.now().getEpochSecond());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[edgar-insider] {} failed: {}", ticker, e.getMessage());
            return null;
        }
    }

    private List<InsiderDealings.InsiderDeal> readFiling(long cik, String accession,
            String document, String filed) {
        try {
            WebResponse resp = fetcher.fetch(
                    String.format(Locale.ROOT, DOC_URL_FMT, cik, accession, document),
                    Map.of("User-Agent", EdgarClient.userAgent(), "Accept", "application/xml"),
                    requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) return List.of();
            return parse(resp.body(), filed);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /**
     * One ownership document → its non-derivative transactions. Derivative
     * legs (option grants and their exercises) are left out: they are the
     * mechanics of a pay package, not a decision to hold or to sell, and they
     * would outnumber the open-market trades that carry the signal.
     * Package-private for tests.
     */
    static List<InsiderDealings.InsiderDeal> parse(String xml, String filed) {
        String person = group(OWNER_NAME, xml);
        String role = role(xml);
        List<InsiderDealings.InsiderDeal> out = new ArrayList<>();
        Matcher blocks = NON_DERIVATIVE.matcher(xml);
        while (blocks.find()) {
            String block = blocks.group(1);
            String code = value(block, "transactionCode");
            double shares = number(value(block, "transactionShares"));
            double price = number(value(block, "transactionPricePerShare"));
            String date = value(block, "transactionDate");
            if (date == null || date.isBlank()) continue;
            String art = code == null || code.isBlank() ? ""
                    : CODES.getOrDefault(code, code);
            // The disposed flag tells buy from sell where the code does not.
            String disposed = value(block, "transactionAcquiredDisposedCode");
            if ("Zuteilung".equals(art) && "D".equals(disposed)) art = "Rückgabe";
            double volume = Double.isNaN(shares) || Double.isNaN(price)
                    ? Double.NaN : shares * price;
            out.add(new InsiderDealings.InsiderDeal(
                    person == null ? "" : person, role,
                    value(block, "securityTitle") == null ? "Aktie"
                            : value(block, "securityTitle"),
                    art, price, "USD", volume, date, filed, "SEC Form 4"));
        }
        return out;
    }

    /** The relationship the filer ticked - the reader weighs a CEO's sale differently. */
    private static String role(String xml) {
        String title = group(OFFICER_TITLE, xml);
        if (title != null && !title.isBlank()) return title;
        if (IS_DIRECTOR.matcher(xml).find()) return "Director";
        if (IS_TEN_PERCENT.matcher(xml).find()) return "10%-Eigner";
        return "";
    }

    /**
     * The value of {@code <tag><value>…</value></tag>} - Form 4 wraps every
     * reported quantity in a value element beside its optional footnote, and a
     * quantity that carries only a footnote has no value to read.
     */
    static String value(String block, String tag) {
        Matcher m = Pattern.compile("<" + tag + ">\\s*(?:<value>\\s*([^<]*?)\\s*</value>)?",
                Pattern.DOTALL).matcher(block);
        return m.find() ? m.group(1) : null;
    }

    private static String group(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static double number(String raw) {
        if (raw == null || raw.isBlank()) return Double.NaN;
        try {
            return Double.parseDouble(raw.strip());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * The submissions index names the RENDERED document
     * ({@code xslF345X06/form4.xml}); the raw XML sits at the same name
     * without the stylesheet directory in front of it.
     */
    static String stripRenderer(String primaryDocument) {
        int slash = primaryDocument.lastIndexOf('/');
        return slash < 0 ? primaryDocument : primaryDocument.substring(slash + 1);
    }
}
