package de.bsommerfeld.wsbg.terminal.web.impl.sources.edgar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The issuer's OWN reported figures, from the SEC's XBRL company-facts API
 * ({@code data.sec.gov/api/xbrl/companyfacts/CIK<10>.json}, keyless,
 * live-probed 2026-08-11).
 *
 * <p>This closes the hole under the dossier's fundamental section. That
 * section asks for earnings, margin, balance sheet and segments AS A COURSE
 * WITH A DIRECTION - and until now the only material it had was press prose:
 * a figure survived into the report if some article happened to quote it. A
 * number a journalist chose to mention is not a series, and two articles from
 * different years are not a trend. Here the numbers come from the filing
 * itself, dated, with their unit, in the issuer's own taxonomy - the one
 * source about a company's earnings that cannot be a paraphrase of another.
 *
 * <p>ONE request per subject: company-facts answers with the complete history
 * of every tag at once (~4 MB for a large issuer), which is cheaper than
 * asking per concept and lets the house pick its figures without a second
 * round trip. The tag list below is a PROTOCOL vocabulary - the names US-GAAP
 * gives these quantities - not an editorial selection of what matters.
 *
 * <p>Per period the LAST filed value wins: a figure restated by a later filing
 * is the figure that stands, and comparatives repeated in a newer document
 * must never overwrite the newer number with an older one.
 */
@Singleton
public class EdgarFactsClient {

    private static final Logger LOG = LoggerFactory.getLogger(EdgarFactsClient.class);

    static final String FACTS_URL_FMT =
            "https://data.sec.gov/api/xbrl/companyfacts/CIK%010d.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000L;

    /**
     * The quantities the house reads, in the order a reader meets them:
     * what came in, what it cost, what stayed, what the company stands on.
     * Several US-GAAP tags can carry the same quantity (an issuer reporting
     * under the newer revenue standard tags revenue differently); the FIRST
     * tag that answers for a period wins, so the alternatives are fallbacks,
     * never additional rows.
     */
    private static final List<Kennzahl> KENNZAHLEN = List.of(
            new Kennzahl("umsatz", List.of(
                    "RevenueFromContractWithCustomerExcludingAssessedTax",
                    "RevenueFromContractWithCustomerIncludingAssessedTax",
                    "Revenues", "SalesRevenueNet")),
            new Kennzahl("bruttoergebnis", List.of("GrossProfit")),
            new Kennzahl("betriebsergebnis", List.of("OperatingIncomeLoss")),
            new Kennzahl("nettoergebnis", List.of("NetIncomeLoss")),
            new Kennzahl("ergebnis_je_aktie", List.of("EarningsPerShareDiluted",
                    "EarningsPerShareBasic")),
            new Kennzahl("forschung", List.of("ResearchAndDevelopmentExpense")),
            new Kennzahl("operativer_cashflow", List.of(
                    "NetCashProvidedByUsedInOperatingActivities",
                    "NetCashProvidedByUsedInOperatingActivitiesContinuingOperations")),
            new Kennzahl("investitionen", List.of(
                    "PaymentsToAcquirePropertyPlantAndEquipment")),
            new Kennzahl("bilanzsumme", List.of("Assets")),
            new Kennzahl("verbindlichkeiten", List.of("Liabilities")),
            new Kennzahl("eigenkapital", List.of("StockholdersEquity")),
            new Kennzahl("liquide_mittel", List.of(
                    "CashAndCashEquivalentsAtCarryingValue")),
            new Kennzahl("langfristige_schulden", List.of("LongTermDebtNoncurrent",
                    "LongTermDebt")));

    /** One house quantity and the US-GAAP tags that may carry it, best first. */
    private record Kennzahl(String name, List<String> tags) {}

    /**
     * One reported figure: the house name of the quantity, the fiscal period
     * it belongs to, the value with its unit, and the filing that reported it.
     *
     * @param kennzahl house name ({@code "umsatz"})
     * @param tag      the US-GAAP tag the value actually came from
     * @param fy       fiscal year as the filing states it
     * @param fp       fiscal period ({@code "FY"}, {@code "Q1"} …)
     * @param ende     end of the reported period
     * @param wert     the reported value
     * @param einheit  unit as the filing states it ({@code "USD"}, {@code "USD/shares"})
     * @param form     the form that reported it ({@code "10-K"}, {@code "10-Q"})
     * @param datiert  the filing date - the same period restated later is a
     *                 later filing, which is how the newest value is found
     */
    public record Kennzahlwert(String kennzahl, String tag, int fy, String fp,
            LocalDate ende, double wert, String einheit, String form, LocalDate datiert) {}

    /** An issuer's reported figures - the entity name as the SEC carries it. */
    public record Kennzahlen(String firma, List<Kennzahlwert> werte) {

        public static final Kennzahlen LEER = new Kennzahlen("", List.of());

        public boolean isEmpty() {
            return werte.isEmpty();
        }
    }

    private final EdgarClient edgar;
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(45);

    private record CacheEntry(Kennzahlen facts, long atMs) {}

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Inject
    public EdgarFactsClient(EdgarClient edgar, WebFetcher fetcher) {
        this.edgar = edgar;
        this.fetcher = fetcher;
    }

    /**
     * Every reported figure of {@code ticker}, newest period first. Empty for
     * a non-US-shaped symbol, an issuer without a CIK, or a network failure
     * (the last is never cached).
     */
    public Kennzahlen kennzahlen(String ticker) {
        if (ticker == null || ticker.isBlank()) return Kennzahlen.LEER;
        String key = ticker.trim().toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CacheEntry hit = cache.get(key);
        if (hit != null && now - hit.atMs() < CACHE_TTL_MS) return hit.facts();

        Long cik = edgar.cikFor(key);
        if (cik == null) {
            cache.put(key, new CacheEntry(Kennzahlen.LEER, now));
            return Kennzahlen.LEER;
        }
        try {
            // SEC fair-access: the descriptive UA with contact is mandatory.
            WebResponse resp = fetcher.fetch(String.format(Locale.ROOT, FACTS_URL_FMT, cik),
                    Map.of("User-Agent", EdgarClient.userAgent(), "Accept", "application/json"),
                    requestTimeout, EdgarClient.MODE);
            if (resp == null || resp.status() != 200 || resp.body() == null) {
                LOG.warn("[edgar-facts] HTTP {} for {} (cik {})",
                        resp == null ? "null" : resp.status(), key, cik);
                return Kennzahlen.LEER;
            }
            Kennzahlen facts = parse(resp.body());
            cache.put(key, new CacheEntry(facts, now));
            LOG.info("[edgar-facts] {} → {} reported figure(s) (cik {})",
                    key, facts.werte().size(), cik);
            return facts;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[edgar-facts] {} failed: {}", key, e.getMessage());
            return Kennzahlen.LEER;
        }
    }

    /** Parses a company-facts document into the house's figures. Package-private for tests. */
    static Kennzahlen parse(String body) throws Exception {
        JsonNode root = JSON.readTree(body);
        String firma = root.path("entityName").asText("");
        JsonNode gaap = root.path("facts").path("us-gaap");
        List<Kennzahlwert> out = new ArrayList<>();
        for (Kennzahl kennzahl : KENNZAHLEN) {
            // Keyed by period, so a later filing of the SAME period replaces
            // the earlier one and a fallback tag never doubles a period the
            // preferred tag already answered.
            Map<String, Kennzahlwert> byPeriod = new LinkedHashMap<>();
            for (String tag : kennzahl.tags()) {
                JsonNode units = gaap.path(tag).path("units");
                if (units.isMissingNode()) continue;
                var fields = units.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> unit = fields.next();
                    for (JsonNode row : unit.getValue()) {
                        String fp = row.path("fp").asText("");
                        int fy = row.path("fy").asInt(0);
                        String form = row.path("form").asText("");
                        if (fp.isEmpty() || fy == 0) continue;
                        // Only the primary annual and quarterly reports - an
                        // 8-K exhibit repeating a figure is the same number
                        // under a second roof, which is not a second reading.
                        if (!form.startsWith("10-K") && !form.startsWith("10-Q")) continue;
                        String period = fy + "|" + fp;
                        LocalDate filed = date(row.path("filed").asText(null));
                        Kennzahlwert seated = byPeriod.get(period);
                        if (seated != null && seated.datiert() != null && filed != null
                                && !filed.isAfter(seated.datiert())) {
                            continue;
                        }
                        byPeriod.put(period, new Kennzahlwert(kennzahl.name(), tag, fy, fp,
                                date(row.path("end").asText(null)), row.path("val").asDouble(),
                                unit.getKey(), form, filed));
                    }
                }
            }
            out.addAll(byPeriod.values());
        }
        out.sort((a, b) -> {
            int byEnd = compareDates(b.ende(), a.ende());
            return byEnd != 0 ? byEnd : a.kennzahl().compareTo(b.kennzahl());
        });
        return new Kennzahlen(firma, List.copyOf(out));
    }

    private static int compareDates(LocalDate a, LocalDate b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.strip());
        } catch (Exception e) {
            return null;
        }
    }
}
