package de.bsommerfeld.wsbg.terminal.price;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** USD→EUR normalisation of foreign-venue snapshots in the price chain. */
class FallbackPriceSourceTest {

    private static MarketSnapshot usd(double price, double pct, List<Double> spark) {
        return new MarketSnapshot("NVDA", price, 100.0, pct, 110.0, 90.0, 1000,
                200.0, 50.0, "USD", "NASDAQ", 1782342000L, spark);
    }

    @Test
    void convertsUsdPriceAndLevelsToEurKeepingThePercent() {
        MarketSnapshot eur = FallbackPriceSource.convertToEur(usd(110.0, 3.5, List.of(100.0, 110.0)), 1.10);
        assertEquals("EUR", eur.currency());
        assertEquals(100.0, eur.price(), 1e-9, "110 USD / 1.10 = 100 EUR");
        assertEquals(3.5, eur.dayChangePercent(), 1e-9, "percentage is currency-agnostic");
        assertEquals(List.of(90.9091, 100.0), eur.spark().stream().map(p -> Math.round(p * 1e4) / 1e4).toList());
        assertEquals("NASDAQ", eur.exchangeName(), "venue label preserved");
    }

    @Test
    void leavesEurSnapshotsUntouched() {
        MarketSnapshot ls = new MarketSnapshot("X", 50.0, 49.0, 2.0, 51.0, 48.0, -1,
                Double.NaN, Double.NaN, "EUR", "L&S", 1782342000L, List.of());
        assertSame(ls, FallbackPriceSource.convertToEur(ls, 1.10), "already EUR — pass through");
    }

    @Test
    void keepsUsdWhenNoRateAvailable() {
        MarketSnapshot s = usd(110.0, 1.0, List.of());
        assertSame(s, FallbackPriceSource.convertToEur(s, 0.0), "no rate → don't fabricate EUR");
    }

    @Test
    void indexSymbolIsLabelledPointsAndNeverFxConverted() {
        // ^GDAXI at 24013 "USD" must stay 24013, relabelled as points — NOT
        // divided by the EUR/USD rate into a nonsensical ~21000 "EUR".
        MarketSnapshot dax = new MarketSnapshot("^GDAXI", 24013.0, 23900.0, 0.47,
                24100.0, 23850.0, -1, 24500.0, 18000.0, "USD", "XETRA", 1782342000L, List.of());
        // fx==null path: toEur must short-circuit on the ^ symbol before any rate use.
        MarketSnapshot pts = new FallbackPriceSource(null, null, null, null, null, null, null).toEur(dax);
        assertEquals("PTS", pts.currency(), "index quoted in points, not a currency");
        assertEquals(24013.0, pts.price(), 1e-9, "index level untouched (no FX division)");
    }

    @Test
    void withCurrencyRelabelsButKeepsNumbers() {
        MarketSnapshot s = usd(110.0, 3.5, List.of(100.0, 110.0));
        MarketSnapshot pts = FallbackPriceSource.withCurrency(s, "PTS");
        assertEquals("PTS", pts.currency());
        assertEquals(110.0, pts.price(), 1e-9);
        assertEquals(List.of(100.0, 110.0), pts.spark());
        assertEquals("NASDAQ", pts.exchangeName());
    }

    // ---- time-window source selection (CET). 2026-06: 22=Mon … 26=Fri, 27=Sat, 28=Sun ----

    private static java.time.ZonedDateTime berlin(int day, int hour, int min) {
        return java.time.ZonedDateTime.of(2026, 6, day, hour, min, 0, 0,
                java.time.ZoneId.of("Europe/Berlin"));
    }

    @Test
    void windowSelection() {
        var LS = FallbackPriceSource.PriceWindow.LS;
        var AH = FallbackPriceSource.PriceWindow.US_AFTERHOURS;
        var GAP = FallbackPriceSource.PriceWindow.GAP;
        assertEquals(LS, FallbackPriceSource.windowAt(berlin(24, 10, 0)), "Wed 10:00 → L&S");
        assertEquals(LS, FallbackPriceSource.windowAt(berlin(24, 7, 30)), "Wed 07:30 → L&S (open)");
        assertEquals(AH, FallbackPriceSource.windowAt(berlin(24, 23, 30)), "Wed 23:30 → US after-hours");
        assertEquals(AH, FallbackPriceSource.windowAt(berlin(24, 1, 0)), "Wed 01:00 (prev Tue) → after-hours");
        assertEquals(AH, FallbackPriceSource.windowAt(berlin(27, 1, 0)), "Sat 01:00 (prev Fri) → after-hours");
        assertEquals(GAP, FallbackPriceSource.windowAt(berlin(24, 5, 0)), "Wed 05:00 → gap");
        assertEquals(GAP, FallbackPriceSource.windowAt(berlin(22, 1, 0)), "Mon 01:00 (prev Sun) → gap");
        assertEquals(LS, FallbackPriceSource.windowAt(berlin(27, 11, 0)), "Sat 11:00 → L&S weekend slot");
        assertEquals(GAP, FallbackPriceSource.windowAt(berlin(27, 15, 0)), "Sat 15:00 → gap");
        assertEquals(LS, FallbackPriceSource.windowAt(berlin(28, 18, 0)), "Sun 18:00 → L&S weekend slot");
    }
}
