package de.bsommerfeld.wsbg.terminal.price;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.price.HomeVenueQuotes.Venue;
import de.bsommerfeld.wsbg.terminal.web.facts.PriceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The home-exchange link's two decisions, both made without a network: WHICH venue
 * owns a ref, and what a venue's partial quote becomes. Routing is the dangerous
 * half — the wrong exchange answers with the right price for the wrong paper — so
 * every country the table claims is pinned here, and so is every country it does
 * not claim.
 */
class HomeVenueQuotesTest {

    @Nested
    @DisplayName("which exchange owns the paper")
    class Routing {

        @Test
        void theIsinCountryDecides() {
            assertEquals(Venue.SIX, HomeVenueQuotes.venueForCountry("CH"));
            assertEquals(Venue.SIX, HomeVenueQuotes.venueForCountry("LI"));
            assertEquals(Venue.ASX, HomeVenueQuotes.venueForCountry("AU"));
            assertEquals(Venue.TMX, HomeVenueQuotes.venueForCountry("CA"));
            assertEquals(Venue.HKEX, HomeVenueQuotes.venueForCountry("HK"));
            assertEquals(Venue.NORDIC, HomeVenueQuotes.venueForCountry("SE"));
            assertEquals(Venue.NORDIC, HomeVenueQuotes.venueForCountry("DK"));
            assertEquals(Venue.NORDIC, HomeVenueQuotes.venueForCountry("FI"));
            assertEquals(Venue.NORDIC, HomeVenueQuotes.venueForCountry("IS"));
            assertEquals(Venue.NYSE, HomeVenueQuotes.venueForCountry("US"));
        }

        @Test
        void theHistoryVenuesRouteToo() {
            assertEquals(Venue.WIENER, HomeVenueQuotes.venueForCountry("AT"));
            assertEquals(Venue.MINKABU, HomeVenueQuotes.venueForCountry("JP"));
            assertEquals(Venue.EURONEXT, HomeVenueQuotes.venueForCountry("FR"));
            assertEquals(Venue.EURONEXT, HomeVenueQuotes.venueForCountry("NO"));
        }

        @Test
        void aHistoryVenueIsMarkedAsOne() {
            // The chain must never read a daily bar as a running quote.
            assertTrue(Venue.WIENER.isHistoryOnly());
            assertTrue(Venue.MINKABU.isHistoryOnly());
            assertTrue(Venue.EURONEXT.isHistoryOnly());
            assertFalse(Venue.SIX.isHistoryOnly());
            assertFalse(Venue.NYSE.isHistoryOnly());
        }

        @Test
        void theEuronextMarketFollowsTheCountryThenTheSuffix() {
            assertEquals("XPAR", HomeVenueQuotes.euronextMic(
                    new PriceRef("Air Liquide", "AI.PA", "FR0000120073"), "FR0000120073"));
            assertEquals("XAMS", HomeVenueQuotes.euronextMic(
                    new PriceRef("ASML", "ASML.AS", "NL0010273215"), "NL0010273215"));
            // No ISIN: the suffix decides.
            assertEquals("XBRU", HomeVenueQuotes.euronextMic(
                    new PriceRef("UCB", "UCB.BR"), null));
            assertNull(HomeVenueQuotes.euronextMic(new PriceRef("SAP", "SAP.DE"), null));
        }

        @Test
        void aCountryTheLinkDoesNotCoverStaysUnrouted() {
            // German, French and Dutch papers belong to L&S or Yahoo, not here.
            assertNull(HomeVenueQuotes.venueForCountry("DE"));
            assertNull(HomeVenueQuotes.venueForCountry(null));
            assertNull(HomeVenueQuotes.venueForCountry(""));
        }

        @Test
        void theIsinBeatsTheTickerSuffix() {
            // A Swiss issue quoted under an Australian-looking ticker is still Swiss.
            PriceRef ref = new PriceRef("Roche", "ROG.AX", "CH0012032048");
            assertEquals(Venue.SIX, HomeVenueQuotes.venueFor(ref));
        }

        @Test
        void theSuffixStandsInWhenNoIsinIsStamped() {
            assertEquals(Venue.ASX, HomeVenueQuotes.venueFor(new PriceRef("Rio Tinto", "RIO.AX")));
            assertEquals(Venue.TMX, HomeVenueQuotes.venueFor(new PriceRef("Shopify", "SHOP.TO")));
            assertEquals(Venue.SIX, HomeVenueQuotes.venueFor(new PriceRef("Nestlé", "NESN.SW")));
            assertEquals(Venue.NORDIC, HomeVenueQuotes.venueFor(new PriceRef("Volvo", "VOLV-B.ST")));
        }

        @Test
        void anUnknownOrAbsentSuffixStaysUnrouted() {
            assertNull(HomeVenueQuotes.venueFor(new PriceRef("SAP", "SAP.DE")));
            assertNull(HomeVenueQuotes.venueFor(new PriceRef("Nvidia", "NVDA")));
            assertNull(HomeVenueQuotes.venueFor(null));
        }

        @Test
        void anIsinFromAnUncoveredCountryFallsBackToTheSuffix() {
            // A German issue cross-listed on the ASX: the country routes nowhere, so
            // the suffix gets its turn rather than the ref being dropped.
            PriceRef ref = new PriceRef("Beispiel", "BSP.AX", "DE0001234567");
            assertEquals(Venue.ASX, HomeVenueQuotes.venueFor(ref));
        }
    }

    @Nested
    @DisplayName("the symbol the venue knows")
    class NativeSymbol {

        @Test
        void stripsTheYahooVenueSuffix() {
            assertEquals("RIO", HomeVenueQuotes.nativeSymbol("RIO.AX"));
            assertEquals("VOLV-B", HomeVenueQuotes.nativeSymbol("VOLV-B.ST"));
        }

        @Test
        void leavesASuffixlessTickerAlone() {
            assertEquals("NVDA", HomeVenueQuotes.nativeSymbol("NVDA"));
            assertEquals("NVDA", HomeVenueQuotes.nativeSymbol("  NVDA  "));
        }

        @Test
        void refusesNothing() {
            assertNull(HomeVenueQuotes.nativeSymbol(null));
            assertNull(HomeVenueQuotes.nativeSymbol("   "));
        }

        @Test
        void aTrailingDotIsNotASuffix() {
            assertEquals("ODD.", HomeVenueQuotes.nativeSymbol("ODD."));
        }
    }

    @Nested
    @DisplayName("what a partial quote becomes")
    class Assembly {

        @Test
        void derivesThePercentFromThePreviousClose() {
            MarketSnapshot s = HomeVenueQuotes.of("ABB", 110.0, 100.0, Double.NaN,
                    111.0, 109.0, 5_000L, "CHF", "SIX");
            assertEquals(10.0, s.dayChangePercent(), 1e-9);
            assertEquals(100.0, s.previousClose(), 1e-9);
        }

        @Test
        void derivesThePreviousCloseFromThePercent() {
            // HKEX publishes the move but not the close it moved from.
            MarketSnapshot s = HomeVenueQuotes.of("0700", 110.0, Double.NaN, 10.0,
                    111.0, 109.0, 5_000L, "HKD", "HKEX");
            assertEquals(100.0, s.previousClose(), 1e-9);
            assertEquals(10.0, s.dayChangePercent(), 1e-9);
        }

        @Test
        void reportsZeroRatherThanInventingAMove() {
            // ASX publishes a last price and a volume and nothing else — a fabricated
            // percent here would read on the wire as a day that never happened.
            MarketSnapshot s = HomeVenueQuotes.of("RIO", 120.0, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, 7_000L, "AUD", "ASX");
            assertEquals(120.0, s.price(), 1e-9);
            assertEquals(0.0, s.dayChangePercent(), 1e-9);
            assertEquals(0.0, s.previousClose(), 1e-9);
            assertEquals(7_000L, s.volume());
        }

        @Test
        void keepsTheNativeCurrencyAndNamesTheVenue() {
            MarketSnapshot s = HomeVenueQuotes.of("SHOP", 90.0, 88.0, Double.NaN,
                    91.0, 87.0, 1_000L, "CAD", "TMX", 120.0, 60.0);
            assertEquals("CAD", s.currency());
            assertEquals("TMX", s.exchangeName());
            assertEquals(120.0, s.fiftyTwoWeekHigh(), 1e-9);
            assertEquals(60.0, s.fiftyTwoWeekLow(), 1e-9);
        }

        @Test
        void aVenueSnapshotIsNeverMistakenForTheGermanOne() {
            // FallbackPriceSource forces an L&S snapshot stale off-session, and it
            // recognises one by its venue label carrying "l&s" or "lang". A home
            // exchange that happened to label itself that way would be dimmed all
            // through its own session, so no venue name here may contain either.
            for (String venue : new String[] {"SIX", "ASX", "TMX", "HKEX", "NYSE", "Heimatbörse"}) {
                MarketSnapshot s = HomeVenueQuotes.of("X", 95.0, 94.0, Double.NaN,
                        96.0, 93.0, 2_000L, "CHF", venue);
                String label = s.exchangeName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(label.contains("l&s") || label.contains("lang"),
                        "venue label would be read as the German venue: " + venue);
            }
        }
    }
}
