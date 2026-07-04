package de.bsommerfeld.wsbg.terminal.price;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * EUR / points / commodity normalisation of a raw venue snapshot. Extracted from
 * {@link FallbackPriceSource}: all the currency relabelling is a self-contained
 * concern that only needs the live EUR/USD rate.
 */
final class EurNormalizer {

    private static final Logger LOG = LoggerFactory.getLogger(EurNormalizer.class);

    /** Currency marker for a stock index: priced in points, never FX-converted. */
    static final String POINTS = "PTS";

    /** Live EUR/USD source; null in tests/lab (then no conversion is fabricated). */
    private final EurUsdMonitorService fx;

    EurNormalizer(EurUsdMonitorService fx) {
        this.fx = fx;
    }

    /** Converts a USD snapshot to EUR via the live rate; leaves non-USD (or rate-less) untouched. */
    MarketSnapshot toEur(MarketSnapshot s) {
        // A stock index (^GDAXI, ^IXIC, …) is quoted in points, not a currency —
        // Yahoo labels it EUR/USD anyway, but converting 24 000 "USD" → EUR is
        // nonsense. Keep the number, relabel it as points, skip FX entirely.
        if (s != null && s.symbol() != null && s.symbol().startsWith("^")) {
            return POINTS.equals(s.currency()) ? s : withCurrency(s, POINTS);
        }
        // A commodity future (GC=F gold, CL=F oil, …) is quoted in its native unit
        // (USD/oz, USD/bbl — the universal benchmark). Don't FX-convert it: gold-in-EUR
        // isn't how the market reads it, and the room says „Gold", not „Gold in Euro".
        if (s != null && s.symbol() != null && s.symbol().endsWith("=F")) {
            return s;
        }
        double r = fx == null ? 0 : fx.getCurrent().map(q -> q.rate()).orElse(0.0);
        if (s != null && "USD".equalsIgnoreCase(s.currency())) {
            LOG.info("[FX] {} {} USD @ rate {} → {} EUR", s.symbol(),
                    String.format(Locale.ROOT, "%.2f", s.price()), r,
                    r > 0 ? String.format(Locale.ROOT, "%.2f", s.price() / r) : "n/a");
        }
        return convertToEur(s, r);
    }

    /**
     * Pure USD→EUR conversion at rate {@code r} (USD per EUR). Percentages stay
     * (currency-agnostic); price/levels/spark divide by the rate. Non-USD snapshots
     * and a non-positive rate pass through untouched. Package-private for testing.
     */
    static MarketSnapshot convertToEur(MarketSnapshot s, double r) {
        if (s == null || !"USD".equalsIgnoreCase(s.currency())) return s;
        if (!(r > 0)) return s; // no rate → keep USD rather than fail
        List<Double> spark = new ArrayList<>(s.spark().size());
        for (double p : s.spark()) spark.add(p / r);
        return new MarketSnapshot(s.symbol(), s.price() / r, div(s.previousClose(), r),
                s.dayChangePercent(), div(s.dayHigh(), r), div(s.dayLow(), r), s.volume(),
                div(s.fiftyTwoWeekHigh(), r), div(s.fiftyTwoWeekLow(), r), "EUR",
                s.exchangeName(), s.marketTimeEpochSeconds(), spark);
    }

    private static double div(double v, double r) {
        return Double.isFinite(v) ? v / r : v;
    }

    /** Returns a copy of {@code s} with the currency relabelled (numbers untouched). */
    static MarketSnapshot withCurrency(MarketSnapshot s, String currency) {
        if (s == null) return null;
        return new MarketSnapshot(s.symbol(), s.price(), s.previousClose(),
                s.dayChangePercent(), s.dayHigh(), s.dayLow(), s.volume(),
                s.fiftyTwoWeekHigh(), s.fiftyTwoWeekLow(), currency,
                s.exchangeName(), s.marketTimeEpochSeconds(), s.spark());
    }
}
