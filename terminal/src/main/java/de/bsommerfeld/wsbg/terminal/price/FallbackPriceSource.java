package de.bsommerfeld.wsbg.terminal.price;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.core.price.PriceRef;
import de.bsommerfeld.wsbg.terminal.core.price.PriceSource;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import de.bsommerfeld.wsbg.terminal.langschwarz.LangSchwarzClient;
import de.bsommerfeld.wsbg.terminal.langschwarz.LsInstrument;
import de.bsommerfeld.wsbg.terminal.nasdaq.NasdaqClient;
import de.bsommerfeld.wsbg.terminal.deutscheboerse.DeutscheBoerseClient;
import de.bsommerfeld.wsbg.terminal.wallstreetonline.WallstreetOnlineClient;
import de.bsommerfeld.wsbg.terminal.wallstreetonline.WsoInstrument;
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
 *       Also yields the ISIN the Deutsche Börse step needs. Leads at every hour
 *       (the audience's actual venue); off-session its quote is marked stale.</li>
 *   <li><b>Deutsche Börse</b> (Xetra/Frankfurt) — by ISIN, EUR, official day-move,
 *       honest timestamp. Replaces the old Tradegate fallback.</li>
 *   <li><b>NASDAQ.com</b> — by ticker, USD→EUR, US fallback.</li>
 *   <li><b>Yahoo</b> — by ticker, USD→EUR, last-resort (Yahoo stays the news source elsewhere).</li>
 * </ol>
 *
 * The first two are the EUR venues the DAX-room audience trades; Yahoo/NASDAQ are
 * the fallback for what they can't resolve (US-only names, crypto, indices). All
 * ride the shared browser-joker {@code WebFetcher}; any provider that throws or
 * returns empty is simply skipped.
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
    private final DeutscheBoerseClient db;
    private final WallstreetOnlineClient wso;
    private final NasdaqClient nasdaq;
    private final YahooFinanceClient yahoo;
    private final EurUsdMonitorService fx;
    /** Read fresh each call so the Settings toggle takes effect live; null in tests/lab. */
    private final GlobalConfig config;

    @Inject
    public FallbackPriceSource(LangSchwarzClient ls, DeutscheBoerseClient db,
            WallstreetOnlineClient wso, NasdaqClient nasdaq, YahooFinanceClient yahoo,
            EurUsdMonitorService fx, GlobalConfig config) {
        this.ls = ls;
        this.db = db;
        this.wso = wso;
        this.nasdaq = nasdaq;
        this.yahoo = yahoo;
        this.fx = fx;
        this.config = config;
    }

    @Override
    public String name() {
        return "chain[L&S (EUR) | (US opt-in fallback) Yahoo]";
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
        // The target audience trades EUR on L&S (Trade Republic's venue) + Deutsche Börse, so
        // those German EQUITY venues LEAD at every hour — including overnight, where they
        // return a last EUR close (marked stale below). This is what fixes a US-ETF (SOXX)
        // being priced from its $590 US listing instead of the EUR product. They stay out
        // only for non-equities — crypto (BTC-USD), index (^IXIC), FX (=X) — which would
        // mis-match a same-named German share ("Bitcoin" → "Bitcoin Group SE"); those go
        // to Yahoo. The L&S resolver itself now rejects wrong-twin name hits (min coverage).
        boolean germanVenueEligible = isEquityTicker(ref.ticker());
        // US price fallback (Yahoo/NASDAQ) for an equity/ETF the EUR venues couldn't
        // resolve is OPT-IN (default off) — a US-listing price converted to EUR is the
        // wrong product for this audience. A NON-equity (index ^…, crypto -USD, FX =X)
        // has no EU listing, so Yahoo is its legit/only source and is ALWAYS allowed.
        // The US opt-in setting was removed (2026-06-30): equities are L&S-only. Yahoo stays
        // ONLY for non-equities (index points ^…, crypto -USD, FX =X) that have no EU listing.
        boolean usAllowed = !germanVenueEligible;
        // For an EQUITY that fell through to the US fallback we keep the NATIVE US price
        // ($, the honest US listing — not a misleading EUR conversion of the wrong product).
        // A non-equity normalises through toEur (index → points, crypto → EUR).
        boolean convertUs = !germanVenueEligible;
        final String[] isin = { ref.hasIsin() ? ref.isin() : null };

        // wallstreet-online resolves the German listing's ISIN from a STRUCTURED search —
        // a more reliable anchor than L&S's fuzzy name-pick. It (a) feeds the official
        // Deutsche Börse quote ISIN-exactly and (b) cross-checks L&S: a name hit whose ISIN
        // disagrees with this anchor matched a wrong twin and is dropped. L&S name-resolution
        // stays the fallback when WSO has no hit.
        if (isin[0] == null && germanVenueEligible && ref.hasName()) {
            isin[0] = wso.resolve(ref.name(), ref.hasTicker() ? ref.ticker() : null)
                    .map(WsoInstrument::isin).orElse(null);
        }
        final String anchorIsin = isin[0];

        // Ordered attempts. Prices are taken from L&S ONLY (the audience's EUR venue, and it
        // carries the sparkline) — "weniger ist mehr" (2026-06-30). Deutsche Börse / NASDAQ
        // stay BUILT (isolated modules) but are NOT in the active chain; Yahoo remains the
        // opt-in US fallback for what L&S can't price (and the always-on source for index
        // points / crypto, which have no L&S listing).
        List<Supplier<Optional<MarketSnapshot>>> attempts = new ArrayList<>(3);
        attempts.add(() -> {
            if (!germanVenueEligible) return Optional.empty();
            // Prefer the WSO anchor ISIN — L&S search resolves an ISIN EXACTLY (no name fuzz,
            // no wrong-twin risk). Fall back to the name (with the country cross-check) only
            // when there is no anchor or L&S doesn't list that ISIN.
            LsInstrument inst = anchorIsin != null
                    ? safe(() -> ls.resolveByIsin(anchorIsin)).orElse(null) : null;
            if (inst == null && ref.hasName()) {
                LsInstrument byName = safe(() -> ls.resolveInstrument(ref.name())).orElse(null);
                if (byName != null) {
                    String lsIsin = blankToNull(byName.isin());
                    // A name hit on a different ISIN COUNTRY than the anchor is a wrong twin
                    // abroad ("Mullen Automotive" US vs L&S's "Mullen Group" CA) → drop it.
                    if (anchorIsin != null && lsIsin != null && lsIsin.length() >= 2
                            && !anchorIsin.regionMatches(true, 0, lsIsin, 0, 2)) {
                        LOG.debug("[L&S] dropped '{}' → {} (country ≠ WSO anchor {})", ref.name(), lsIsin, anchorIsin);
                    } else {
                        inst = byName;
                    }
                }
            }
            if (inst == null) return Optional.empty();
            if (isin[0] == null) isin[0] = blankToNull(inst.isin());
            LsInstrument fi = inst;
            return safe(() -> ls.fetchSnapshot(fi));
        });
        // US fallback — Yahoo only (opt-in for equities; always on for index points / crypto).
        attempts.add(() -> {
            if (!usAllowed || !ref.hasTicker()) return Optional.empty();
            MarketSnapshot s = yahooSnapshotRaw(ref.ticker());
            return s == null ? Optional.empty() : Optional.of(convertUs ? toEur(s) : s);
        });

        MarketSnapshot freshestStale = null;
        for (Supplier<Optional<MarketSnapshot>> attempt : attempts) {
            MarketSnapshot s = attempt.get().orElse(null);
            if (s == null) continue;
            // Dimming is for the dead-of-night GAP only, NOT during active trading hours: a
            // quote counts as live if it's genuinely fresh OR we're simply not in the GAP
            // window (the audience doesn't want a grayed price while the German exchange is
            // open — e.g. a US index showing its last close at 14:00 CET should read live,
            // not closed). EXCEPTION: a German venue (L&S) off its own session reports a
            // fresh-looking stamp on a last close, so it's only live inside the L&S window.
            boolean live = (isFresh(s) || window != PriceWindow.GAP)
                    && !(isGermanVenue(s) && !lsSession);
            if (live) return win(ref, s, true);
            freshestStale = fresher(freshestStale, s);
        }
        if (freshestStale != null) return win(ref, freshestStale, false);
        return Optional.empty();
    }

    /**
     * True for an L&amp;S snapshot — the one venue that reports a fresh-looking
     * timestamp even when closed, so off-session it must be forced stale. Deutsche
     * Börse and Yahoo/NASDAQ carry an honest last-trade timestamp and need no override.
     */
    private static boolean isGermanVenue(MarketSnapshot s) {
        String x = s == null ? null : s.exchangeName();
        if (x == null) return false;
        String t = x.toLowerCase(Locale.ROOT);
        return t.contains("l&s") || t.contains("lang");
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

    /** Raw Yahoo snapshot (native currency, NOT converted) — the caller decides whether to toEur. */
    private MarketSnapshot yahooSnapshotRaw(String ticker) {
        try {
            var map = yahoo.fetchCharts(List.of(ticker));
            MarketSnapshot s = map.get(ticker.toUpperCase(Locale.ROOT));
            if (s == null) s = map.get(ticker);
            return s;
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

    /** Currency marker for a stock index: priced in points, never FX-converted. */
    static final String POINTS = "PTS";

    /** Converts a USD snapshot to EUR via the live rate; leaves non-USD (or rate-less) untouched. */
    MarketSnapshot toEur(MarketSnapshot s) {
        // A stock index (^GDAXI, ^IXIC, …) is quoted in points, not a currency —
        // Yahoo labels it EUR/USD anyway, but converting 24 000 "USD" → EUR is
        // nonsense. Keep the number, relabel it as points, skip FX entirely.
        if (s != null && s.symbol() != null && s.symbol().startsWith("^")) {
            return POINTS.equals(s.currency()) ? s : withCurrency(s, POINTS);
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
