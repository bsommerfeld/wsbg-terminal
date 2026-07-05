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
import de.bsommerfeld.wsbg.terminal.price.TradingWindowClock.PriceWindow;
import de.bsommerfeld.wsbg.terminal.wallstreetonline.WallstreetOnlineClient;
import de.bsommerfeld.wsbg.terminal.wallstreetonline.WsoInstrument;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooFinanceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The production price-source chain (see {@link PriceSource}). Tries providers in
 * preference order and returns the <b>first fresh</b> snapshot; if none is fresh
 * (deep overnight, all venues closed) it returns the freshest stale one so the UI
 * still shows a last price (dimmed). Everything is normalised to EUR.
 *
 * <ol>
 *   <li><b>Lang &amp; Schwarz Tradecenter</b> — by name (or by the ISIN
 *       wallstreet-online resolves when the name search misses), EUR, with
 *       sparkline + after-hours. Leads at every hour (the audience's actual
 *       venue); off-session its quote is marked stale.</li>
 *   <li><b>Yahoo</b> — by ticker, USD→EUR, the safety net for what L&amp;S can't
 *       resolve (US-only names, crypto, indices). Yahoo stays the news source elsewhere.</li>
 * </ol>
 *
 * All ride the shared browser-joker {@code WebFetcher}; any provider that throws
 * or returns empty is simply skipped. The trading-window clock, EUR normalisation
 * and the TTL cache are factored into {@link TradingWindowClock},
 * {@link EurNormalizer} and {@link SnapshotCache} respectively; this class owns the
 * resolve-attempt orchestration.
 */
@Singleton
public class FallbackPriceSource implements PriceSource {

    private static final Logger LOG = LoggerFactory.getLogger(FallbackPriceSource.class);

    /** A snapshot whose quote is younger than this counts as fresh/live. */
    private static final long FRESH_SECONDS = 30 * 60;

    private final LangSchwarzClient ls;
    private final WallstreetOnlineClient wso;
    private final YahooFinanceClient yahoo;
    private final EurNormalizer eur;
    private final SnapshotCache cache = new SnapshotCache();
    /** Read fresh each call so the Settings toggle takes effect live; null in tests/lab. */
    private final GlobalConfig config;

    @Inject
    public FallbackPriceSource(LangSchwarzClient ls, WallstreetOnlineClient wso,
            YahooFinanceClient yahoo, EurUsdMonitorService fx, GlobalConfig config) {
        this.ls = ls;
        this.wso = wso;
        this.yahoo = yahoo;
        this.eur = new EurNormalizer(fx);
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
        Optional<MarketSnapshot> cached = cache.get(key);
        if (cached.isPresent()) return cached;

        // Source preference is decided by the CET clock window, NOT by the (unreliable)
        // per-venue timestamp: L&S reports a "fresh"-looking stamp even when it's closed.
        // Even in the 02:00–07:30 GAP (all venues closed) we do NOT bail: the
        // ticker source (Yahoo) still returns a last close, and the freshest-stale
        // fallback below hands it back marked stale — so a unit first seen cold during
        // the gap still shows a (dimmed) last price instead of no number at all.
        // The target audience trades EUR on L&S (Trade Republic's venue), so that German
        // EQUITY venue LEADS at every hour — including overnight, where it returns a last
        // EUR close (marked stale below). This is what fixes a US-ETF (SOXX) being priced
        // from its $590 US listing instead of the EUR product. It stays out only for
        // non-equities — crypto (BTC-USD), index (^IXIC), FX (=X) — which would mis-match
        // a same-named German share ("Bitcoin" → "Bitcoin Group SE"); those go to Yahoo.
        boolean germanVenueEligible = isEquityTicker(ref.ticker());
        // Yahoo is a SAFETY NET, not a competitor: the attempts run L&S FIRST, so for an
        // equity Yahoo only ever fires when L&S genuinely found nothing — the "are you SURE
        // there's no price?" check (2026-06-30). It rescues real stocks L&S simply doesn't
        // list (Abivax FR0012333284, foreign small-caps) instead of leaving them price-less.
        // Non-equities (index ^…, crypto -USD, FX =X) have no EU listing, so Yahoo is their
        // legit/only source anyway. So Yahoo is always allowed; L&S-first ordering keeps the
        // EUR venue primary for everything it can resolve.
        boolean usAllowed = true;
        // For an EQUITY that fell through to the Yahoo net we keep the NATIVE price
        // ($, the honest listing — not a misleading EUR conversion of the wrong product).
        // A non-equity normalises through toEur (index → points, crypto → EUR, commodity → USD).
        boolean convertUs = !germanVenueEligible;
        final String[] isin = { ref.hasIsin() ? ref.isin() : null };

        // L&S BY NAME FIRST (2026-06-30): the audience's EUR venue resolves ~80% of subjects
        // straight from a name search (and carries the sparkline) — NO WSO call for the common
        // case. Only when the name search MISSES do we pay wallstreet-online for a structured
        // ISIN anchor and retry L&S by that exact ISIN. So WSO is the FALLBACK, not the per-equity
        // primary (it stays cached either way). Prices are L&S-only ("weniger ist mehr"); Yahoo
        // remains the opt-in US fallback + the always-on source for index points / crypto.
        List<Supplier<Optional<MarketSnapshot>>> attempts = new ArrayList<>(2);
        attempts.add(() -> {
            if (!germanVenueEligible) return Optional.empty();
            // 1) L&S by name — the cheap 80% common path, no WSO.
            LsInstrument inst = ref.hasName()
                    ? safe(() -> ls.resolveInstrument(ref.name())).orElse(null) : null;
            // 2) Name missed → wallstreet-online's structured ISIN, then L&S by that exact ISIN.
            if (inst == null) {
                String wsoIsin = isin[0] != null ? isin[0]
                        : (ref.hasName()
                            ? safe(() -> wso.resolve(ref.name(), ref.hasTicker() ? ref.ticker() : null))
                                    .map(WsoInstrument::isin).orElse(null)
                            : null);
                if (wsoIsin != null) {
                    if (isin[0] == null) isin[0] = wsoIsin;
                    inst = safe(() -> ls.resolveByIsin(wsoIsin)).orElse(null);
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
            return s == null ? Optional.empty() : Optional.of(convertUs ? eur.toEur(s) : s);
        });

        MarketSnapshot freshestStale = null;
        for (Supplier<Optional<MarketSnapshot>> attempt : attempts) {
            MarketSnapshot s = attempt.get().orElse(null);
            if (s == null) continue;
            if (isLive(s)) return win(ref, s, true);
            freshestStale = fresher(freshestStale, s);
        }
        if (freshestStale != null) return win(ref, freshestStale, false);
        return Optional.empty();
    }

    /**
     * GAP-aware DISPLAY liveness — the single source of truth for both source selection here
     * AND the UI's dim flag ({@code HeadlineJson} sends {@code priceStale = !isLive(s)}; the
     * page must NOT re-derive staleness from the raw timestamp, or a US/index quote on its last
     * close reads "closed" all through German trading hours). A quote is live (not dimmed) when
     * it's genuinely fresh OR we're simply not in the dead-of-night GAP — EXCEPT an L&S quote
     * off its own session (it reports a fresh-looking stamp on a last close, so force it stale).
     * Computed against the live CET clock, so the flag updates as the day moves through windows.
     */
    public static boolean isLive(MarketSnapshot s) {
        if (s == null) return false;
        PriceWindow window = TradingWindowClock.now();
        boolean lsSession = window == PriceWindow.LS;
        return (isFresh(s) || window != PriceWindow.GAP) && !(isGermanVenue(s) && !lsSession);
    }

    /**
     * True for an L&amp;S snapshot — the one venue that reports a fresh-looking
     * timestamp even when closed, so off-session it must be forced stale. Yahoo/NASDAQ
     * carry an honest last-trade timestamp and need no override.
     */
    private static boolean isGermanVenue(MarketSnapshot s) {
        String x = s == null ? null : s.exchangeName();
        if (x == null) return false;
        String t = x.toLowerCase(Locale.ROOT);
        return t.contains("l&s") || t.contains("lang");
    }

    /** Logs which source won (price + currency + venue + freshness), caches it, and returns it. */
    private Optional<MarketSnapshot> win(PriceRef ref, MarketSnapshot s, boolean live) {
        cache.put(cacheKey(ref), s);
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

    /** A plain equity symbol — not a crypto pair (BTC-USD), an index (^IXIC) or FX (EURUSD=X). */
    private static boolean isEquityTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) return false;
        String t = ticker.trim().toUpperCase(Locale.ROOT);
        return !t.startsWith("^") && !t.endsWith("-USD") && !t.endsWith("-EUR") && t.indexOf('=') < 0;
    }

    /** True when the quote is recent enough to be a live price (vs an old close). */
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
