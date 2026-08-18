package de.bsommerfeld.wsbg.terminal.price;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.web.facts.PriceRef;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.asx.AsxMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.cnbc.CnbcQuoteClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.euronext.EuronextClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.hkex.HkexClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.minkabu.MinkabuClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.nordic.NordicMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.nyse.NyseQuoteClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.six.SixMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.tmx.TmxMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.wienerboerse.WienerBoerseClient;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The instrument's OWN exchange, asked directly — the middle link of the price
 * chain between Lang &amp; Schwarz and Yahoo.
 *
 * <p>L&amp;S leads because the audience trades there in EUR, and Yahoo is the
 * safety net for whatever L&amp;S cannot resolve. Between the two sat a gap: a
 * Swiss, Australian, Canadian, Hong Kong or Nordic listing that L&amp;S does not
 * carry fell straight through to a Yahoo line for the same paper, when the home
 * venue publishes the authoritative quote itself. These clients were written and
 * tested for exactly that and were never wired to anything.
 *
 * <p><b>Routing is deterministic, never a guess.</b> The ISIN's first two letters
 * are its country of issue, so they decide the venue; only when no ISIN is stamped
 * does the Yahoo-style ticker suffix ({@code .SW}, {@code .AX}, {@code .TO}) stand
 * in. A ref that matches no venue returns empty and the chain moves on — this link
 * never guesses at a venue, because the wrong exchange answers with the right price
 * for the wrong paper.
 *
 * <p><b>Native currency, like the rest of the chain.</b> A CHF listing is returned
 * in CHF: the honest listing beats a converted EUR figure for a product the reader
 * cannot buy at that price. Conversion stays the caller's decision.
 *
 * <p>The timestamp is the READ time, not the last trade — these endpoints publish
 * no trade stamp. Session liveness is decided by {@link TradingWindowClock} in the
 * chain above, not by this number.
 */
@Singleton
public class HomeVenueQuotes {

    private final SixMarketClient six;
    private final AsxMarketClient asx;
    private final TmxMarketClient tmx;
    private final HkexClient hkex;
    private final NordicMarketClient nordic;
    private final NyseQuoteClient nyse;
    private final CnbcQuoteClient cnbc;
    private final WienerBoerseClient wiener;
    private final MinkabuClient minkabu;
    private final EuronextClient euronext;

    @Inject
    public HomeVenueQuotes(SixMarketClient six, AsxMarketClient asx, TmxMarketClient tmx,
            HkexClient hkex, NordicMarketClient nordic, NyseQuoteClient nyse,
            CnbcQuoteClient cnbc, WienerBoerseClient wiener, MinkabuClient minkabu,
            EuronextClient euronext) {
        this.wiener = wiener;
        this.minkabu = minkabu;
        this.euronext = euronext;
        this.six = six;
        this.asx = asx;
        this.tmx = tmx;
        this.hkex = hkex;
        this.nordic = nordic;
        this.nyse = nyse;
        this.cnbc = cnbc;
    }

    /**
     * The venues this link can answer for.
     *
     * <p>Two kinds, and the difference is not cosmetic. A QUOTE venue publishes the
     * running price; a HISTORY venue publishes only daily bars, so its snapshot is
     * a LAST CLOSE carrying its own daily series — stamped with the bar's date, which
     * is what makes the chain above mark it stale and the UI dim it. Reading a
     * history bar as a live quote would put yesterday's number on today's wire.
     */
    enum Venue {
        SIX, ASX, TMX, HKEX, NORDIC, NYSE,
        WIENER(true), MINKABU(true), EURONEXT(true);

        private final boolean historyOnly;

        Venue() {
            this(false);
        }

        Venue(boolean historyOnly) {
            this.historyOnly = historyOnly;
        }

        boolean isHistoryOnly() {
            return historyOnly;
        }
    }

    /**
     * The quote from the ref's home exchange, or empty when no venue matches, the
     * venue has nothing, or the call fails. Never throws: a venue that is down must
     * cost the chain nothing but this attempt.
     */
    public Optional<MarketSnapshot> snapshot(PriceRef ref) {
        if (ref == null) return Optional.empty();
        Venue venue = venueFor(ref);
        if (venue == null) return Optional.empty();
        String isin = ref.hasIsin() ? ref.isin() : null;
        String symbol = nativeSymbol(ref.ticker());
        if (venue.isHistoryOnly()) return fromHistory(venue, ref, isin);
        try {
            return switch (venue) {
                case SIX -> isin == null ? Optional.empty()
                        : six.quote(isin).map(q -> of(q.symbol(), q.last(), q.previousClose(),
                                Double.NaN, q.dayHigh(), q.dayLow(), q.volume(), "CHF", "SIX"));
                case ASX -> symbol == null ? Optional.empty()
                        : asx.quote(symbol).map(q -> of(q.symbol(), q.priceLast(), Double.NaN,
                                Double.NaN, Double.NaN, Double.NaN, q.volume(), "AUD", "ASX"));
                case TMX -> symbol == null ? Optional.empty()
                        : tmx.quote(symbol).map(q -> of(q.symbol(), q.price(), q.previousClose(),
                                q.changePercent(), q.dayHigh(), q.dayLow(), q.volume(),
                                q.currency(), "TMX", q.yearHigh(), q.yearLow()));
                case HKEX -> symbol == null ? Optional.empty()
                        : hkex.quote(symbol).map(q -> of(q.symbol(), q.last(), Double.NaN,
                                q.changePercent(), q.dayHigh(), q.dayLow(), q.volume(),
                                q.currency(), "HKEX"));
                case NORDIC -> isin == null ? Optional.empty()
                        : nordic.quoteByIsin(isin).map(q -> of(q.symbol(), q.last(),
                                q.last() - q.netChange(), q.changePercent(), q.dayHigh(),
                                q.dayLow(), q.volume(), q.currency(), venueName(q.exchange()),
                                q.yearHigh(), q.yearLow()));
                case NYSE -> symbol == null ? Optional.empty()
                        : nyse.tape(symbol).map(t -> of(t.symbol(), t.last(), t.previousClose(),
                                t.changePercent(), t.dayHigh(), t.dayLow(), t.volume(),
                                "USD", venueName(t.exchange()), t.yearHigh(), t.yearLow()));
                // Named rather than swept into a default, so a NEW quote venue still
                // makes the compiler ask for its branch.
                case WIENER, MINKABU, EURONEXT -> Optional.empty(); // fromHistory, above
            };
        } catch (RuntimeException down) {
            return Optional.empty();
        }
    }

    /**
     * A broad international quote, for a ref whose home venue this link does not
     * cover — a second net beside Yahoo rather than a replacement for it, so the
     * chain has somewhere to go when Yahoo's symbol shape does not match.
     */
    public Optional<MarketSnapshot> broadQuote(String ticker) {
        String symbol = nativeSymbol(ticker);
        if (symbol == null) return Optional.empty();
        try {
            CnbcQuoteClient.Quote q = cnbc.quote(symbol);
            if (q == null || q.last() == null || q.last() <= 0) return Optional.empty();
            return Optional.of(of(q.symbol() == null ? symbol : q.symbol(), q.last(),
                    d(q.previousClose()), d(q.changePercent()), d(q.dayHigh()), d(q.dayLow()),
                    q.volume() == null ? -1L : q.volume().longValue(),
                    q.currency() == null ? "USD" : q.currency(),
                    venueName(q.exchange() == null ? "CNBC" : q.exchange())));
        } catch (RuntimeException down) {
            return Optional.empty();
        }
    }

    /**
     * A last-close snapshot from a venue that publishes only daily bars. The newest
     * bar is the price, the one before it the previous close, and the whole series
     * becomes {@code dailyCloses} — which is the multi-day context the brief reads
     * ({@code changeOverTradingDays}) and which, outside Lang &amp; Schwarz, no
     * venue in the chain has ever filled.
     */
    private Optional<MarketSnapshot> fromHistory(Venue venue, PriceRef ref, String isin) {
        try {
            List<Double> closes;
            List<double[]> lastBar = new ArrayList<>();
            String currency;
            LocalDate day;
            switch (venue) {
                case WIENER -> {
                    if (isin == null) return Optional.empty();
                    var h = wiener.history(isin, LocalDate.now(ZoneOffset.UTC).minusMonths(3));
                    if (h.isEmpty() || h.get().bars().isEmpty()) return Optional.empty();
                    var bars = h.get().bars();
                    closes = bars.stream().map(b -> b.close()).toList();
                    var last = bars.get(bars.size() - 1);
                    lastBar.add(new double[] {last.high(), last.low(), last.volumeShares()});
                    day = last.date();
                    currency = "EUR";
                }
                case MINKABU -> {
                    // The RIC IS the Tokyo ticker, venue suffix included (6758.T), so
                    // this is the one venue that must NOT have its suffix stripped.
                    String ric = ref.hasTicker() ? ref.ticker().trim() : null;
                    if (ric == null) return Optional.empty();
                    var h = minkabu.history(ric, 120);
                    if (h.isEmpty() || h.get().bars().isEmpty()) return Optional.empty();
                    var bars = h.get().bars();
                    closes = bars.stream().map(b -> b.close()).toList();
                    var last = bars.get(bars.size() - 1);
                    lastBar.add(new double[] {last.high(), last.low(), last.volume()});
                    day = last.date();
                    currency = "JPY";
                }
                case EURONEXT -> {
                    String mic = euronextMic(ref, isin);
                    if (isin == null || mic == null) return Optional.empty();
                    var h = euronext.history(isin, mic, "1M");
                    if (h.isEmpty() || h.get().bars().isEmpty()) return Optional.empty();
                    var bars = h.get().bars();
                    closes = bars.stream().map(b -> b.close()).toList();
                    var last = bars.get(bars.size() - 1);
                    lastBar.add(new double[] {last.high(), last.low(), last.volume()});
                    day = last.date();
                    currency = "EUR";
                }
                default -> {
                    return Optional.empty();
                }
            }
            List<Double> series = closes.stream().filter(Double::isFinite).toList();
            if (series.isEmpty()) return Optional.empty();
            double price = series.get(series.size() - 1);
            double prev = series.size() > 1 ? series.get(series.size() - 2) : Double.NaN;
            double[] bar = lastBar.get(0);
            return Optional.of(new MarketSnapshot(
                    ref.hasTicker() ? ref.ticker() : isin,
                    price,
                    finite(prev) ? prev : 0.0,
                    finite(prev) && prev > 0 ? (price - prev) / prev * 100.0 : 0.0,
                    finite(bar[0]) ? bar[0] : 0.0,
                    finite(bar[1]) ? bar[1] : 0.0,
                    bar[2] > 0 ? (long) bar[2] : -1L,
                    0.0, 0.0,
                    currency,
                    venueLabel(venue),
                    day.atStartOfDay(ZoneOffset.UTC).toEpochSecond(),
                    List.of(),
                    series));
        } catch (RuntimeException down) {
            return Optional.empty();
        }
    }

    /** The Euronext market the paper trades on, from its ISIN country or its ticker suffix. */
    static String euronextMic(PriceRef ref, String isin) {
        String country = isin == null ? null
                : Isin.parse(isin).map(Isin::country).orElse(null);
        String byCountry = country == null ? null : switch (country.toUpperCase(Locale.ROOT)) {
            case "FR" -> "XPAR";
            case "NL" -> "XAMS";
            case "BE" -> "XBRU";
            case "PT" -> "XLIS";
            case "IE" -> "XMSM";
            case "NO" -> "XOSL";
            default -> null;
        };
        if (byCountry != null) return byCountry;
        String ticker = ref != null && ref.hasTicker() ? ref.ticker() : null;
        int dot = ticker == null ? -1 : ticker.lastIndexOf('.');
        if (dot < 0 || dot == ticker.length() - 1) return null;
        return switch (ticker.substring(dot + 1).toUpperCase(Locale.ROOT)) {
            case "PA" -> "XPAR";
            case "AS" -> "XAMS";
            case "BR" -> "XBRU";
            case "LS" -> "XLIS";
            case "IR" -> "XMSM";
            case "OL" -> "XOSL";
            default -> null;
        };
    }

    /** The venue label the chain logs and the UI shows. */
    static String venueLabel(Venue venue) {
        return switch (venue) {
            case WIENER -> "Wiener Börse";
            case MINKABU -> "Tokyo";
            case EURONEXT -> "Euronext";
            case NORDIC -> "Nasdaq Nordic";
            default -> venue.name();
        };
    }

    // ------------------------------------------------------------------ routing

    /**
     * The venue that owns this ref. The ISIN country decides; the ticker suffix is
     * consulted only when no ISIN is stamped, because a suffix is a Yahoo
     * convention while an ISIN country is the issue itself.
     */
    static Venue venueFor(PriceRef ref) {
        if (ref == null) return null;
        if (ref.hasIsin()) {
            Venue byCountry = venueForCountry(Isin.parse(ref.isin())
                    .map(Isin::country).orElse(null));
            if (byCountry != null) return byCountry;
        }
        return venueForSuffix(ref.hasTicker() ? ref.ticker() : null);
    }

    /** ISIN country of issue → home venue; null for a country this link does not cover. */
    static Venue venueForCountry(String country) {
        if (country == null) return null;
        return switch (country.toUpperCase(Locale.ROOT)) {
            case "CH", "LI" -> Venue.SIX;
            case "AU" -> Venue.ASX;
            case "CA" -> Venue.TMX;
            case "HK" -> Venue.HKEX;
            case "SE", "DK", "FI", "IS" -> Venue.NORDIC;
            case "US" -> Venue.NYSE;
            case "AT" -> Venue.WIENER;
            case "JP" -> Venue.MINKABU;
            case "FR", "NL", "BE", "PT", "IE", "NO" -> Venue.EURONEXT;
            default -> null;
        };
    }

    /** Yahoo-style venue suffix → home venue, for refs the desk left unstamped. */
    static Venue venueForSuffix(String ticker) {
        if (ticker == null) return null;
        int dot = ticker.lastIndexOf('.');
        if (dot < 0 || dot == ticker.length() - 1) return null;
        return switch (ticker.substring(dot + 1).toUpperCase(Locale.ROOT)) {
            case "SW" -> Venue.SIX;
            case "AX" -> Venue.ASX;
            case "TO", "V" -> Venue.TMX;
            case "HK" -> Venue.HKEX;
            case "ST", "CO", "HE", "IC" -> Venue.NORDIC;
            case "VI" -> Venue.WIENER;
            case "T" -> Venue.MINKABU;
            case "PA", "AS", "BR", "LS", "IR", "OL" -> Venue.EURONEXT;
            default -> null;
        };
    }

    /**
     * The symbol the home venue knows, which is the ticker without the Yahoo venue
     * suffix ({@code RIO.AX} is {@code RIO} on the ASX). A suffix-less ticker is
     * already native.
     */
    static String nativeSymbol(String ticker) {
        if (ticker == null || ticker.isBlank()) return null;
        String t = ticker.trim();
        int dot = t.lastIndexOf('.');
        if (dot <= 0 || dot == t.length() - 1) return t;
        return t.substring(0, dot);
    }

    // ------------------------------------------------------------------ assembly

    static MarketSnapshot of(String symbol, double price, double previousClose,
            double changePercent, double dayHigh, double dayLow, long volume,
            String currency, String venue) {
        return of(symbol, price, previousClose, changePercent, dayHigh, dayLow, volume,
                currency, venue, Double.NaN, Double.NaN);
    }

    /**
     * One snapshot from whatever the venue published. Percent and previous close are
     * two views of the same fact, so a venue that gives one gets the other derived;
     * a venue that gives neither reports zero rather than a fabricated move.
     */
    static MarketSnapshot of(String symbol, double price, double previousClose,
            double changePercent, double dayHigh, double dayLow, long volume,
            String currency, String venue, double yearHigh, double yearLow) {
        double close = previousClose;
        double pct = changePercent;
        if (!finite(close) && finite(pct) && pct != -100.0 && price > 0) {
            close = price / (1.0 + pct / 100.0);
        }
        if (!finite(pct) && finite(close) && close > 0) {
            pct = (price - close) / close * 100.0;
        }
        return new MarketSnapshot(
                symbol,
                price,
                finite(close) ? close : 0.0,
                finite(pct) ? pct : 0.0,
                finite(dayHigh) ? dayHigh : 0.0,
                finite(dayLow) ? dayLow : 0.0,
                volume,
                finite(yearHigh) ? yearHigh : 0.0,
                finite(yearLow) ? yearLow : 0.0,
                currency,
                venue,
                Instant.now().getEpochSecond(),
                List.of());
    }

    private static boolean finite(double d) {
        return Double.isFinite(d) && d != 0.0;
    }

    private static double d(Double boxed) {
        return boxed == null ? Double.NaN : boxed;
    }

    /** The venue label as the chain logs and the UI show it; blank falls back to the code. */
    private static String venueName(String raw) {
        return raw == null || raw.isBlank() ? "Heimatbörse" : raw.trim();
    }
}
