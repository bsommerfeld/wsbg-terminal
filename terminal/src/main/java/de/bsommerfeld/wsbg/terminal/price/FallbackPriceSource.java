package de.bsommerfeld.wsbg.terminal.price;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.PriceRef;
import de.bsommerfeld.wsbg.terminal.core.price.PriceSource;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import de.bsommerfeld.wsbg.terminal.langschwarz.LangSchwarzClient;
import de.bsommerfeld.wsbg.terminal.langschwarz.LsInstrument;
import de.bsommerfeld.wsbg.terminal.nasdaq.NasdaqClient;
import de.bsommerfeld.wsbg.terminal.tradegate.TradegateClient;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The production price-source chain (see {@link PriceSource}). Tries providers in
 * preference order and returns the <b>first fresh</b> snapshot; if none is fresh
 * (deep overnight, all venues closed) it returns the freshest stale one so the UI
 * still shows a last price (dimmed). Everything is normalised to EUR.
 *
 * <ol>
 *   <li><b>Lang &amp; Schwarz Tradecenter</b> — by name, EUR, with sparkline + after-hours.
 *       Also yields the ISIN the Tradegate step needs.</li>
 *   <li><b>Tradegate</b> — by ISIN, EUR, daytime fallback.</li>
 *   <li><b>NASDAQ.com</b> — by ticker, USD→EUR, covers the US after-hours window.</li>
 *   <li><b>Yahoo</b> — by ticker, USD→EUR, last-resort (Yahoo stays the news source elsewhere).</li>
 * </ol>
 *
 * All four ride the shared browser-joker {@code WebFetcher}. Any provider that throws
 * or returns empty is simply skipped.
 */
@Singleton
public class FallbackPriceSource implements PriceSource {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackPriceSource.class);

    /** A snapshot whose quote is younger than this counts as fresh/live. */
    private static final long FRESH_SECONDS = 30 * 60;

    /**
     * Resolved snapshots (EUR price + day-move + spark) are reused this long. The same
     * ticker is priced once per subject AND re-priced across many clusters within one
     * editorial tick, and each miss is up to TWO slow browser fetches (info + chart) per
     * source — that serial I/O was the dominant per-tick blocker. Mirrors the NASDAQ news
     * cache; off-hours the underlying value doesn't move, in-session 2 min is well inside
     * the venues' own refresh cadence. Keyed by ticker (or name).
     */
    private static final long CACHE_TTL_SECONDS = 120;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** A cached resolved snapshot with the epoch-second it was stored. */
    private record Cached(MarketSnapshot snapshot, long storedAt) {}

    private final LangSchwarzClient ls;
    private final TradegateClient tradegate;
    private final NasdaqClient nasdaq;
    private final YahooFinanceClient yahoo;
    private final EurUsdMonitorService fx;

    @Inject
    public FallbackPriceSource(LangSchwarzClient ls, TradegateClient tradegate,
            NasdaqClient nasdaq, YahooFinanceClient yahoo, EurUsdMonitorService fx) {
        this.ls = ls;
        this.tradegate = tradegate;
        this.nasdaq = nasdaq;
        this.yahoo = yahoo;
        this.fx = fx;
    }

    @Override
    public String name() {
        return "chain[time-windowed: L&S | NASDAQ | Yahoo | Tradegate]";
    }

    @Override
    public Optional<MarketSnapshot> snapshot(PriceRef ref) {
        if (ref == null) return Optional.empty();

        // Reuse a recently resolved snapshot before touching any venue — this is the
        // big per-tick saving (the same ticker is otherwise re-fetched for every cluster
        // that names it, each a multi-second browser round-trip).
        String key = cacheKey(ref);
        if (key != null) {
            Cached hit = cache.get(key);
            if (hit != null && Instant.now().getEpochSecond() - hit.storedAt() < CACHE_TTL_SECONDS) {
                return Optional.of(hit.snapshot());
            }
        }

        // Source preference is decided by the CET clock window, NOT by the (unreliable)
        // per-venue timestamp: L&S reports a "fresh"-looking stamp even when it's closed.
        PriceWindow window = windowAt(ZonedDateTime.now(BERLIN));
        // Even in the 02:00–07:30 GAP (all venues closed) we do NOT bail: the
        // ticker sources (NASDAQ/Yahoo) still return a last close, and the
        // freshest-stale fallback below hands it back marked stale — so a unit
        // first seen cold during the gap still shows a (dimmed) last price instead
        // of no number at all. German venues stay out of the gap (lsSession=false),
        // so the gap only costs the two ticker calls, not the L&S name lookup.
        boolean lsSession = window == PriceWindow.LS;
        // L&S + Tradegate are German EQUITY venues: only in their session, and never a
        // crypto (BTC-USD), index (^IXIC) or FX pair (it mis-matches a same-named German
        // share, e.g. "Bitcoin" → "Bitcoin Group SE"). Those go to NASDAQ/Yahoo.
        boolean germanVenueEligible = lsSession && isEquityTicker(ref.ticker());
        final String[] isin = { ref.hasIsin() ? ref.isin() : null };

        // Ordered attempts for this window. In the L&S session L&S leads (real-time EUR
        // + sparkline) and Tradegate is the absolute last resort; in the US after-hours
        // window (23:00–02:00) the German venues are closed, so NASDAQ leads.
        List<Supplier<Optional<MarketSnapshot>>> attempts = new ArrayList<>(4);
        if (lsSession) {
            attempts.add(() -> {
                if (!ref.hasName() || !germanVenueEligible) return Optional.empty();
                Optional<LsInstrument> inst = safe(() -> ls.resolveInstrument(ref.name()));
                if (inst.isEmpty()) return Optional.empty();
                if (isin[0] == null) isin[0] = blankToNull(inst.get().isin()); // hand the ISIN to Tradegate
                return safe(() -> ls.fetchSnapshot(inst.get()));
            });
        }
        attempts.add(() -> ref.hasTicker()
                ? safe(() -> nasdaq.fetchSnapshot(ref.ticker())).map(this::toEur) : Optional.empty());
        attempts.add(() -> ref.hasTicker()
                ? Optional.ofNullable(yahooSnapshot(ref.ticker())) : Optional.empty());
        if (lsSession) {
            attempts.add(() -> (isin[0] != null && germanVenueEligible)
                    ? safe(() -> tradegate.fetchSnapshot(isin[0])) : Optional.empty());
        }

        MarketSnapshot freshestStale = null;
        for (Supplier<Optional<MarketSnapshot>> attempt : attempts) {
            MarketSnapshot s = attempt.get().orElse(null);
            if (s == null) continue;
            if (isFresh(s)) return win(ref, s, true);
            freshestStale = fresher(freshestStale, s);
        }
        if (freshestStale != null) return win(ref, freshestStale, false);
        return Optional.empty();
    }

    /** Which trading window the CET clock is in — drives which price source leads. */
    enum PriceWindow { LS, US_AFTERHOURS, GAP }

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    /**
     * The active window at {@code berlin} (CET/CEST):
     * <ul>
     *   <li><b>LS</b> — L&amp;S Tradecenter open: Mon–Fri 07:30–23:00, plus the weekend
     *       slots Sat 10:00–13:00 and Sun 17:00–19:00.</li>
     *   <li><b>US_AFTERHOURS</b> — L&amp;S closed but US post-market live: weekday
     *       23:00–24:00 and 00:00–02:00 of the morning after a weekday (Tue–Sat).</li>
     *   <li><b>GAP</b> — 02:00–07:30 and idle weekend hours: nothing trades.</li>
     * </ul>
     * Package-private + parameterised for testing.
     */
    static PriceWindow windowAt(ZonedDateTime berlin) {
        DayOfWeek day = berlin.getDayOfWeek();
        LocalTime t = berlin.toLocalTime();
        boolean weekday = day.getValue() <= 5; // Mon(1)–Fri(5)
        if (day == DayOfWeek.SATURDAY && inRange(t, LocalTime.of(10, 0), LocalTime.of(13, 0))) return PriceWindow.LS;
        if (day == DayOfWeek.SUNDAY && inRange(t, LocalTime.of(17, 0), LocalTime.of(19, 0))) return PriceWindow.LS;
        if (weekday && inRange(t, LocalTime.of(7, 30), LocalTime.of(23, 0))) return PriceWindow.LS;
        if (weekday && !t.isBefore(LocalTime.of(23, 0))) return PriceWindow.US_AFTERHOURS; // 23:00–24:00
        if (t.isBefore(LocalTime.of(2, 0)) && berlin.minusDays(1).getDayOfWeek().getValue() <= 5) {
            return PriceWindow.US_AFTERHOURS; // 00:00–02:00 after a weekday (incl. Fri→Sat)
        }
        return PriceWindow.GAP;
    }

    private static boolean inRange(LocalTime t, LocalTime from, LocalTime toExclusive) {
        return !t.isBefore(from) && t.isBefore(toExclusive);
    }

    /** Logs which source won (price + currency + venue + freshness), caches it, and returns it. */
    private Optional<MarketSnapshot> win(PriceRef ref, MarketSnapshot s, boolean live) {
        String key = cacheKey(ref);
        if (key != null) cache.put(key, new Cached(s, Instant.now().getEpochSecond()));
        LOG.info("[PRICE] {} → {} {} via {} ({})",
                ref.hasTicker() ? ref.ticker() : ref.name(),
                String.format(Locale.ROOT, "%.2f", s.price()),
                s.currency() == null ? "?" : s.currency(),
                s.exchangeName() == null ? "?" : s.exchangeName(),
                live ? "live" : "stale/last-close");
        return Optional.of(s);
    }

    /** Cache identity: the ticker (upper) when present, else the normalised name; null if neither. */
    private static String cacheKey(PriceRef ref) {
        if (ref.hasTicker()) return ref.ticker().toUpperCase(Locale.ROOT);
        if (ref.hasName()) return "name:" + ref.name().trim().toLowerCase(Locale.ROOT);
        return null;
    }

    private MarketSnapshot yahooSnapshot(String ticker) {
        try {
            var map = yahoo.fetchCharts(List.of(ticker));
            MarketSnapshot s = map.get(ticker.toUpperCase(Locale.ROOT));
            if (s == null) s = map.get(ticker);
            return s == null ? null : toEur(s);
        } catch (Exception e) {
            LOG.debug("Yahoo snapshot failed for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    /** True when the quote is recent enough to be a live price (vs an old close). */
    /** A plain equity symbol — not a crypto pair (BTC-USD), an index (^IXIC) or FX (EURUSD=X). */
    private static boolean isEquityTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) return false;
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        return !t.startsWith("^") && !t.endsWith("-USD") && !t.endsWith("-EUR") && t.indexOf('=') < 0;
    }

    private static boolean isFresh(MarketSnapshot s) {
        if (s == null || !s.hasPrice() || s.marketTimeEpochSeconds() <= 0) return false;
        // Tolerate clock/timezone skew: a venue's last-tick timestamp can read a minute
        // into the "future" (L&S does). Fresh = within FRESH_SECONDS in EITHER direction —
        // requiring age >= 0 wrongly rejected every L&S quote, so NASDAQ/Tradegate kept winning.
        long age = Instant.now().getEpochSecond() - s.marketTimeEpochSeconds();
        return Math.abs(age) <= FRESH_SECONDS;
    }

    private static MarketSnapshot fresher(MarketSnapshot a, MarketSnapshot b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.marketTimeEpochSeconds() > a.marketTimeEpochSeconds() ? b : a;
    }

    /** Converts a USD snapshot to EUR via the live rate; leaves non-USD (or rate-less) untouched. */
    MarketSnapshot toEur(MarketSnapshot s) {
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static <T> Optional<T> safe(Supplier<Optional<T>> call) {
        try {
            return call.get();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
