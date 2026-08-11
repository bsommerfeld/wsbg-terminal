package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Eurex OPEN INTEREST - what is actually positioned, per product and per
 * expiry (keyless, live-probed 2026-08-11).
 *
 * <p>The attention-wave reading has an axis for the option book, and the house
 * has never been able to fill it: it could see how much was TRADED and never
 * how much was HELD. The difference is the whole signal - turnover with
 * falling open interest is positions being closed, the same turnover with
 * rising open interest is fresh money taking a side.
 *
 * <p>The endpoint describes itself: every answer carries the product code and
 * the UNDERLYING's ISIN in its meta block, so a catalogued entry can be
 * verified against what the exchange says it is instead of trusting a list.
 *
 * <p>The catalog holds the INDEX and RATE products - the market-wide
 * positioning the regime reading asks for. Single-stock options are reachable
 * through the same call and are not catalogued: their product ids sit sparsely
 * across a wide space, and finding one issuer's id means scanning that space,
 * which is a job for a cached index rather than for a dossier's collect phase.
 * The mechanism is here ({@link Statistik#underlyingIsin()}); the index is not.
 */
@Singleton
public class EurexOpenInterestClient {

    private static final Logger LOG = LoggerFactory.getLogger(EurexOpenInterestClient.class);

    private static final String URL = "https://www.eurex.com/api/v1/overallstatistics/%d";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    /** One catalogued product: the exchange's id, its code, and the house's name. */
    public record Produkt(int id, String code, String titel) {}

    /**
     * The index and rate option books, verified live 2026-08-11 (the product
     * code in each answer's meta block is asserted against this entry).
     */
    public static final List<Produkt> KATALOG = List.of(
            new Produkt(70044, "ODAX", "DAX-Optionen"),
            new Produkt(70050, "OGBL", "Bund-Optionen (10 Jahre)"),
            new Produkt(70042, "OGBM", "Bobl-Optionen (5 Jahre)"),
            new Produkt(70040, "OGBS", "Schatz-Optionen (2 Jahre)"),
            new Produkt(70060, "OBTP", "BTP-Optionen (Italien)"),
            new Produkt(70062, "OATX", "ATX-Optionen (Österreich)"));

    /** One expiry's book. {@code art} is the exchange's contract type letter. */
    public record Laufzeit(String datum, double callVolumen, double callOpenInterest,
            double putVolumen, double putOpenInterest, String putCallRatio, String art) {}

    /**
     * One product's book.
     *
     * @param code           the exchange's product code, read from its answer
     * @param underlyingIsin the ISIN of what the contracts are written on
     * @param basiskurs      the underlying's closing price, or NaN
     * @param volumen        contracts traded
     * @param openInterest   contracts HELD - the half that was missing
     * @param putCallRatio   as the exchange states it, verbatim
     * @param laufzeiten     per-expiry rows, nearest first
     */
    public record Statistik(String code, String underlyingIsin, double basiskurs,
            double volumen, double openInterest, String putCallRatio,
            List<Laufzeit> laufzeiten) {

        public boolean isEmpty() {
            return openInterest <= 0 && volumen <= 0;
        }
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private record CacheEntry(Statistik statistik, long atMs) {}

    private final Map<Integer, CacheEntry> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public EurexOpenInterestClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public EurexOpenInterestClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** One product's book, or {@code null} when the exchange did not answer. */
    public Statistik statistik(int productId) {
        CacheEntry hit = cache.get(productId);
        if (hit != null && System.currentTimeMillis() - hit.atMs() < CACHE_TTL_MS) {
            return hit.statistik();
        }
        try {
            WebResponse resp = fetcher.fetch(String.format(Locale.ROOT, URL, productId),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200 || resp.body() == null) return null;
            JsonNode root = JSON.readTree(resp.body());
            if (root.has("error")) {
                LOG.debug("[eurex] product {} unknown to the exchange", productId);
                return null;
            }
            JsonNode meta = root.path("meta");
            JsonNode header = root.path("header");
            List<Laufzeit> laufzeiten = new ArrayList<>();
            for (JsonNode row : root.path("dataRows")) {
                laufzeiten.add(new Laufzeit(row.path("date").asText(""),
                        row.path("callVolume").asDouble(Double.NaN),
                        row.path("callOpenInterest").asDouble(Double.NaN),
                        row.path("putVolume").asDouble(Double.NaN),
                        row.path("putOpenInterest").asDouble(Double.NaN),
                        row.path("putCallRatio").asText(""),
                        row.path("contractType").asText("")));
            }
            Statistik s = new Statistik(meta.path("productCode").asText(""),
                    meta.path("isin").asText(""),
                    header.path("underlyingClosingPrice").asDouble(Double.NaN),
                    header.path("volume").asDouble(Double.NaN),
                    header.path("openInterest").asDouble(Double.NaN),
                    header.path("putCallRatio").asText(""),
                    List.copyOf(laufzeiten));
            cache.put(productId, new CacheEntry(s, System.currentTimeMillis()));
            return s;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[eurex] product {} failed: {}", productId, e.getMessage());
            return null;
        }
    }

    /**
     * The catalogued books, in catalog order. A product whose answer carries a
     * DIFFERENT code than the catalog claims is dropped and logged loudly - an
     * id that silently moved would otherwise put another market's positioning
     * under a familiar name.
     */
    public Map<String, Statistik> katalog() {
        Map<String, Statistik> out = new LinkedHashMap<>();
        for (Produkt p : KATALOG) {
            Statistik s = statistik(p.id());
            if (s == null) continue;
            if (!s.code().isBlank() && !s.code().equalsIgnoreCase(p.code())) {
                LOG.warn("[eurex] product id {} now answers as {}, catalogued as {} - dropped",
                        p.id(), s.code(), p.code());
                continue;
            }
            if (s.isEmpty()) continue;
            out.put(p.titel(), s);
        }
        if (!out.isEmpty()) {
            LOG.info("[eurex] {} of {} catalogued option books read", out.size(),
                    KATALOG.size());
        }
        return out;
    }
}
