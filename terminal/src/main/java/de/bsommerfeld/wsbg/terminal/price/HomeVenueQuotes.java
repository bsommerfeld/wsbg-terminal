package de.bsommerfeld.wsbg.terminal.price;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.web.facts.PriceRef;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.asx.AsxMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.cnbc.CnbcQuoteClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.hkex.HkexClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.nordic.NordicMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.nyse.NyseQuoteClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.six.SixMarketClient;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.tmx.TmxMarketClient;
import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;

import java.time.Instant;
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

    @Inject
    public HomeVenueQuotes(SixMarketClient six, AsxMarketClient asx, TmxMarketClient tmx,
            HkexClient hkex, NordicMarketClient nordic, NyseQuoteClient nyse,
            CnbcQuoteClient cnbc) {
        this.six = six;
        this.asx = asx;
        this.tmx = tmx;
        this.hkex = hkex;
        this.nordic = nordic;
        this.nyse = nyse;
        this.cnbc = cnbc;
    }

    /** The venues this link can answer for, as the routing table reads them. */
    enum Venue { SIX, ASX, TMX, HKEX, NORDIC, NYSE }

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
