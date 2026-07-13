package de.bsommerfeld.wsbg.terminal.bafin;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealingsSource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BaFin Directors'-Dealings client — manager transactions the issuer must
 * report (Art. 19 MAR), straight from the supervisor's own database: person,
 * position ("Vorstand", "Aufsichtsrat", "in enger Beziehung"), buy/sell,
 * average price, aggregated volume, trade and notification dates, venue.
 * Fully keyless, no cookies, no headers required (probed 2026-07-13).
 *
 * <p>Endpoint: {@code portal.mvp.bafin.de/database/DealingsInfo/sucheForm.do}
 * with {@code emittentIsin=<ISIN>&zeitraum=0} (all periods) plus the two export
 * switches — {@code 6578706f7274=1} (hex-"export") AND the DisplayTag table
 * param {@code d-4000784-e=1}; without the latter the endpoint answers the HTML
 * search page even for a covered issuer. With both, the answer is
 * {@code text/csv}: BOM, semicolon-separated, 13 unquoted columns, German
 * number format with a currency suffix ({@code "954,62 EUR"},
 * {@code "3.043.314,50 EUR"}), dates {@code dd.MM.yyyy}, venue occasionally
 * empty. An ISIN without reported deals — quiet German issuer or foreign name —
 * answers the SAME CSV with only the header row (probed with a US ISIN), which
 * per the seam is a present record with an empty deal list; only a non-CSV
 * (HTML) answer counts as unreadable.
 *
 * <p>Deals are kept newest-first (notification date, then trade date) and
 * capped at {@value #MAX_DEALS}. Results are cached per ISIN for one hour;
 * failures are NOT cached, so an outage heals itself.
 */
@Singleton
public class InsiderDealingsClient implements InsiderDealingsSource {

    private static final Logger LOG = LoggerFactory.getLogger(InsiderDealingsClient.class);

    private static final String URL_FMT = "https://portal.mvp.bafin.de/database/DealingsInfo/"
            + "sucheForm.do?emittentIsin=%s&zeitraum=0&d-4000784-e=1&emittentButton=Suche+Emittent"
            + "&emittentName=&meldepflichtigerName=&zeitraumVon=&zeitraumBis=&6578706f7274=1";
    private static final String HEADER_TOKEN = "Meldepflichtiger";
    private static final int MAX_DEALS = 20;
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);

    /** ISIN (UPPER) → last good read; served while younger than {@link #CACHE_TTL}. */
    private final Map<String, InsiderDealings> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public InsiderDealingsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: rides the shared {@link WebFetcher} chain. */
    @Inject
    public InsiderDealingsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public Optional<InsiderDealings> byIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        InsiderDealings cached = cache.get(key);
        long now = System.currentTimeMillis() / 1000;
        if (cached != null && now - cached.fetchedAtEpochSeconds() < CACHE_TTL.toSeconds()) {
            return Optional.of(cached);
        }
        try {
            WebResponse resp = fetcher.fetch(
                    String.format(URL_FMT, URLEncoder.encode(key, StandardCharsets.UTF_8)),
                    Map.of("User-Agent", userAgent, "Accept", "text/csv,text/plain"),
                    requestTimeout);
            if (resp.status() != 200) {
                LOG.warn("[BaFin] HTTP {} for {}", resp.status(), key);
                return Optional.empty();
            }
            Optional<List<InsiderDealings.InsiderDeal>> deals = parseDeals(resp.body());
            if (deals.isEmpty()) return Optional.empty(); // non-CSV answer — not cached
            InsiderDealings result = new InsiderDealings(key, deals.get(),
                    System.currentTimeMillis() / 1000);
            LOG.info("[BaFin] {} → {} reported deals{}", key, result.deals().size(),
                    result.deals().isEmpty() ? " (keine Insider-Meldungen)" : "");
            cache.put(key, result);
            return Optional.of(result);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[BaFin] dealings for {} failed: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parses the CSV export into deals — newest first, capped at
     * {@value #MAX_DEALS} — or empty for a non-CSV answer (HTML page, blank,
     * missing header). A header-only CSV parses to a present EMPTY list.
     * Package-private, network-free (pinned by tests).
     */
    Optional<List<InsiderDealings.InsiderDeal>> parseDeals(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        String text = body.charAt(0) == '\uFEFF' ? body.substring(1) : body;
        if (text.stripLeading().startsWith("<") || !text.contains(HEADER_TOKEN)) {
            LOG.warn("[BaFin] non-CSV answer (export switch broken / error page?)");
            return Optional.empty();
        }
        List<InsiderDealings.InsiderDeal> deals = new ArrayList<>();
        boolean pastHeader = false;
        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) continue;
            if (!pastHeader) { // first non-blank line is the header
                pastHeader = true;
                continue;
            }
            // 13 plain semicolon-separated columns, never quoted (probed) — but a
            // trailing empty venue must survive, hence the -1 limit.
            String[] f = line.split(";", -1);
            if (f.length < 13) continue;
            deals.add(new InsiderDealings.InsiderDeal(
                    blankToNull(f[3]), blankToNull(f[4]), blankToNull(f[5]), blankToNull(f[6]),
                    parseGermanNumber(f[7]), currencyOf(f[7]),
                    parseGermanNumber(f[8]),
                    toIsoDate(f[10]), toIsoDate(f[9]),
                    blankToNull(f[11])));
        }
        deals.sort(Comparator
                .comparing((InsiderDealings.InsiderDeal d) ->
                        d.notifiedDateIso() == null ? "" : d.notifiedDateIso())
                .thenComparing(d -> d.dealDateIso() == null ? "" : d.dealDateIso())
                .reversed());
        return Optional.of(deals.size() > MAX_DEALS ? List.copyOf(deals.subList(0, MAX_DEALS))
                : List.copyOf(deals));
    }

    /** German-formatted amount with currency suffix ({@code "3.043.314,50 EUR"}) → double, NaN unknown. */
    static double parseGermanNumber(String s) {
        if (s == null) return Double.NaN;
        String t = s.strip().replaceAll("[A-Za-z]+$", "").strip()
                .replaceAll("[\\s\\u00A0\\u202F]", "");
        if (t.isEmpty()) return Double.NaN;
        if (t.contains(",")) t = t.replace(".", "").replace(',', '.');
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** The trailing currency token of an amount field ({@code "954,62 EUR"} → {@code "EUR"}), null unknown. */
    static String currencyOf(String s) {
        if (s == null) return null;
        String t = s.strip();
        int space = t.lastIndexOf(' ');
        if (space < 0) return null;
        String cur = t.substring(space + 1);
        return cur.chars().allMatch(Character::isLetter) && !cur.isEmpty() ? cur : null;
    }

    /** {@code dd.MM.yyyy} → ISO {@code yyyy-MM-dd}, null on blank/garbage. */
    static String toIsoDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.strip(), GERMAN_DATE).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
