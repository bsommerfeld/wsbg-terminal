package de.bsommerfeld.wsbg.terminal.bundesanzeiger;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterest;
import de.bsommerfeld.wsbg.terminal.core.price.ShortInterestSource;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Bundesanzeiger Leerverkaufsregister client — the visible short-interest floor
 * for German issuers: every net short position &ge; 0.5 % of shares outstanding
 * must be disclosed WITH the position holder's name (§ 33 WpHG / EU SSR). The
 * register is one public CSV (~40 KB, ~476 rows; probed 2026-07-13), fetched
 * whole and joined by ISIN ONLY — the issuer-name column is inconsistent across
 * disclosures, the ISIN is not.
 *
 * <p>The CSV sits behind a Wicket session: GET {@code /pub/de/nlp?4} answers 302
 * and sets the {@code jsessionid} cookie, then GET the {@code ...~resource~link}
 * URL with that cookie answers {@code text/csv} — {@code "Positionsinhaber",
 * "Emittent","ISIN","Position","Datum"}, quoted fields (holder names carry
 * commas), German decimal comma in the position, BOM up front.
 *
 * <p><b>Transport (2026-07-14 joker-first mandate):</b> the injected shared
 * {@link WebFetcher} chain leads — the
 * hidden browser holds the Wicket session cookie natively, so the same
 * two-request flow works through its anchored tab. Because the seam itself is
 * stateless, the plain-HTTP leg of that chain cannot carry the cookie between
 * the two requests; the own stateful JDK {@link HttpClient} with a
 * {@link CookieManager} therefore stays as the working DIRECT fallback whenever
 * the seam attempt yields no readable CSV (it still sends a browser User-Agent
 * via {@link BrowserUserAgent} like the sibling venue modules).
 *
 * <p>The whole register is fetched at most once per hour (it updates daily) and
 * served from the parsed in-memory table; a failed or non-CSV fetch answers
 * empty and does NOT refresh the cache stamp, so the next call retries. A read
 * register with zero rows for an ISIN answers a PRESENT record with an empty
 * position list — "niemand meldepflichtig short" is a finding, not a miss (see
 * {@link ShortInterestSource}).
 */
@Singleton
public class ShortInterestClient implements ShortInterestSource {

    private static final Logger LOG = LoggerFactory.getLogger(ShortInterestClient.class);

    private static final String ENTRY_URL = "https://www.bundesanzeiger.de/pub/de/nlp?4";
    private static final String CSV_URL =
            "https://www.bundesanzeiger.de/pub/de/nlp?0--top~csv~form~panel-form-csv~resource~link";
    private static final String HEADER_TOKEN = "Positionsinhaber";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final HttpClient http;
    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** The whole parsed register (ISIN UPPER → rows) + the second it was read. */
    private record Register(Map<String, List<ShortInterest.ShortPosition>> byIsin,
                            long fetchedAtEpochSeconds) {}

    private final Object refreshLock = new Object();
    private volatile Register register;

    /** Cookie-flow-only variant for tests/CLI (no embedded browser available). */
    public ShortInterestClient() {
        this(null);
    }

    @Inject
    public ShortInterestClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public Optional<ShortInterest> byIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String key = isin.trim().toUpperCase(Locale.ROOT);
        Register reg = freshRegister();
        if (reg == null) return Optional.empty();
        ShortInterest si = fromRegister(key, reg.byIsin(), reg.fetchedAtEpochSeconds());
        LOG.info("[Bundesanzeiger] {} → {} disclosed positions, total {}%",
                key, si.positions().size(),
                String.format(Locale.ROOT, "%.2f", si.totalDisclosedPercent()));
        return Optional.of(si);
    }

    /** The cached register while fresh, else a refetch; null when unreadable. */
    private Register freshRegister() {
        Register reg = register;
        long now = System.currentTimeMillis() / 1000;
        if (reg != null && now - reg.fetchedAtEpochSeconds() < CACHE_TTL.toSeconds()) return reg;
        synchronized (refreshLock) {
            reg = register;
            now = System.currentTimeMillis() / 1000;
            if (reg != null && now - reg.fetchedAtEpochSeconds() < CACHE_TTL.toSeconds()) return reg;
            Register fetched = fetchRegister();
            if (fetched != null) register = fetched;
            return fetched;
        }
    }

    /** Seam (browser joker) attempt first, then the own stateful cookie flow. */
    private Register fetchRegister() {
        Register viaSeam = fetchViaSeam();
        return viaSeam != null ? viaSeam : fetchViaCookieFlow();
    }

    /**
     * The same two-request flow over the shared {@link WebFetcher} chain. On the
     * chain's browser leg the hidden tab keeps the Wicket session cookie between
     * the two requests; on its stateless plain-HTTP leg the CSV request arrives
     * without the session and answers HTML, which {@link #parseRegister} rejects
     * — the caller then falls back to the stateful JDK cookie flow.
     */
    private Register fetchViaSeam() {
        WebFetcher f = fetcher;
        if (f == null) return null;
        try {
            f.fetch(ENTRY_URL,
                    Map.of("User-Agent", userAgent, "Accept", "text/html,application/xhtml+xml"),
                    requestTimeout);
            WebResponse csv = f.fetch(CSV_URL,
                    Map.of("User-Agent", userAgent, "Accept", "text/csv,text/plain"),
                    requestTimeout);
            if (csv.status() != 200) {
                LOG.info("[Bundesanzeiger] seam answered HTTP {} for the register CSV — trying cookie flow",
                        csv.status());
                return null;
            }
            Optional<Map<String, List<ShortInterest.ShortPosition>>> table = parseRegister(csv.body());
            if (table.isEmpty()) return null;
            long now = System.currentTimeMillis() / 1000;
            LOG.info("[Bundesanzeiger] register read via {}: {} issuers, {} positions",
                    f.name(), table.get().size(),
                    table.get().values().stream().mapToInt(List::size).sum());
            return new Register(table.get(), now);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.info("[Bundesanzeiger] seam register fetch failed ({}) — trying cookie flow",
                    e.getMessage());
            return null;
        }
    }

    /** The two-request cookie flow: entry page for the session, then the CSV. */
    private Register fetchViaCookieFlow() {
        try {
            // Step 1: establishes the Wicket jsessionid in the cookie jar; the
            // 302 target (the search page) is irrelevant, only the cookie counts.
            http.send(get(ENTRY_URL, "text/html,application/xhtml+xml"),
                    HttpResponse.BodyHandlers.discarding());
            HttpResponse<String> csv = http.send(get(CSV_URL, "text/csv,text/plain"),
                    HttpResponse.BodyHandlers.ofString());
            if (csv.statusCode() != 200) {
                LOG.warn("[Bundesanzeiger] HTTP {} for the register CSV", csv.statusCode());
                return null;
            }
            Optional<Map<String, List<ShortInterest.ShortPosition>>> table = parseRegister(csv.body());
            if (table.isEmpty()) return null;
            long now = System.currentTimeMillis() / 1000;
            LOG.info("[Bundesanzeiger] register read: {} issuers, {} positions",
                    table.get().size(), table.get().values().stream().mapToInt(List::size).sum());
            return new Register(table.get(), now);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("[Bundesanzeiger] register fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private HttpRequest get(String url, String accept) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", accept)
                .GET()
                .build();
    }

    /**
     * Parses the register CSV into ISIN → rows, or empty for a non-CSV answer
     * (HTML error page, blank, missing header). Rows with an unparseable
     * position are skipped. Package-private, network-free (pinned by tests).
     */
    Optional<Map<String, List<ShortInterest.ShortPosition>>> parseRegister(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        String text = body.charAt(0) == '\uFEFF' ? body.substring(1) : body;
        if (text.stripLeading().startsWith("<") || !text.contains(HEADER_TOKEN)) {
            LOG.warn("[Bundesanzeiger] non-CSV register answer (bot wall / error page?)");
            return Optional.empty();
        }
        Map<String, List<ShortInterest.ShortPosition>> table = new HashMap<>();
        boolean pastHeader = false;
        for (String line : text.split("\r?\n")) {
            if (line.isBlank()) continue;
            if (!pastHeader) { // first non-blank line is the header
                pastHeader = true;
                continue;
            }
            List<String> f = splitQuoted(line);
            if (f.size() < 5) continue;
            String isin = f.get(2).trim().toUpperCase(Locale.ROOT);
            double percent = parseGermanNumber(f.get(3));
            if (isin.isEmpty() || !Double.isFinite(percent)) continue;
            table.computeIfAbsent(isin, k -> new ArrayList<>())
                    .add(new ShortInterest.ShortPosition(f.get(0).trim(), percent, f.get(4).trim()));
        }
        return Optional.of(table);
    }

    /**
     * Joins one ISIN against the parsed register: positions largest first,
     * their sum as the visible short-interest floor. Zero rows still answer a
     * present record (the register WAS read). Package-private, network-free.
     */
    ShortInterest fromRegister(String isin, Map<String, List<ShortInterest.ShortPosition>> table,
            long fetchedAtEpochSeconds) {
        List<ShortInterest.ShortPosition> rows = table.getOrDefault(isin, List.of());
        List<ShortInterest.ShortPosition> sorted = rows.stream()
                .sorted(Comparator.comparingDouble(ShortInterest.ShortPosition::percent).reversed())
                .toList();
        double total = rows.stream().mapToDouble(ShortInterest.ShortPosition::percent).sum();
        return new ShortInterest(isin, total, sorted, fetchedAtEpochSeconds);
    }

    /** One CSV line into fields — quoted values, {@code ""} escapes, commas inside quotes. */
    static List<String> splitQuoted(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    /** German decimal comma ({@code "0,60"}) → double, NaN on garbage. */
    static double parseGermanNumber(String s) {
        if (s == null) return Double.NaN;
        String t = s.strip().replaceAll("[\\s\\u00A0\\u202F]", "");
        if (t.isEmpty()) return Double.NaN;
        if (t.contains(",")) t = t.replace(".", "").replace(',', '.');
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
