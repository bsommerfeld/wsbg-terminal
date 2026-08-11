package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;

/**
 * The EUWAX Sentiment - what GERMAN PRIVATE INVESTORS are doing with their own
 * money, measured rather than guessed.
 *
 * <p>Stuttgart is the retail venue: the index counts, over the day's executed
 * leverage-product orders, whether private investors bought bullish or bearish
 * paper. Positive means the crowd is positioned long, negative short, and the
 * scale runs roughly from -100 to +100. For a terminal built around a room of
 * private investors this is the one sentiment figure that is not an opinion
 * poll - it is money that has already moved.
 *
 * <p>Read through onvista, NOT through the exchange. The exchange's own pages
 * sit behind a JavaScript challenge, and a parser written against a page one
 * cannot see is a guess. The index is a listed instrument with an ISIN
 * ({@code DE000A1MAA56}), so it can be quoted through a door that is open -
 * same number, no wall, one request.
 */
@Singleton
public class EuwaxSentimentClient extends OnvistaApi {

    private static final Logger LOG = LoggerFactory.getLogger(EuwaxSentimentClient.class);

    /** The index as onvista carries it - identity of record and the address. */
    static final String ISIN = "DE000A1MAA56";
    static final String ENTITY_VALUE = "59198950";

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /**
     * One reading.
     *
     * @param stand     the index level - positive: private investors are net
     *                  positioned LONG, negative: net short
     * @param vortag    the previous level, or NaN
     * @param hoch1J    highest level over a year, or NaN - the scale a reader
     *                  needs to tell a big reading from a small one
     * @param tief1J    lowest level over a year, or NaN
     * @param gemessen  when the index last printed
     */
    public record Stimmung(double stand, double vortag, double hoch1J, double tief1J,
            Instant gemessen) {

        /** Points moved since the previous reading, or NaN. */
        public double veraenderung() {
            return Double.isNaN(stand) || Double.isNaN(vortag) ? Double.NaN : stand - vortag;
        }
    }

    private volatile Stimmung cached;
    private volatile long cachedAtMs;

    /** Rides the shared direct-first seam (the old {@code @DirectFirst} binding). */
    @Inject
    public EuwaxSentimentClient(WebFetcher fetcher) {
        super(fetcher);
    }

    /** The current reading, or {@code null} when the quote could not be read. */
    public Stimmung stimmung() {
        Stimmung hit = cached;
        if (hit != null && System.currentTimeMillis() - cachedAtMs < CACHE_TTL.toMillis()) {
            return hit;
        }
        JsonNode root = postJson("v1/instruments/quote_list",
                "{\"list\":[{\"entityType\":\"INDEX\",\"entityValue\":\"" + ENTITY_VALUE + "\"}]}");
        if (root == null) return null;
        for (JsonNode q : listOf(root, "list")) {
            double stand = num(q, "last");
            if (Double.isNaN(stand)) continue;
            Stimmung s = new Stimmung(stand, num(q, "previousLast"),
                    num(q, "highPrice1Year"), num(q, "lowPrice1Year"),
                    instant(q, "datetimeLast"));
            cached = s;
            cachedAtMs = System.currentTimeMillis();
            LOG.info("[euwax] sentiment {} (previous {}, year range {} … {})",
                    s.stand(), s.vortag(), s.tief1J(), s.hoch1J());
            return s;
        }
        LOG.debug("[euwax] the quote carried no level");
        return null;
    }
}
