package de.bsommerfeld.wsbg.terminal.boersede;

import de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.AnalystCall;
import de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.CompanyDate;
import de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.FundamentalYear;
import de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.InsiderDeal;
import de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.IpoEntry;
import de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient.StockSplit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixture-driven tests for the boerse.de market desk (fixtures curled 2026-08-02). */
class BoerseDeMarketClientTest {

    // -------------------------------------------------------------- insider trades

    @Test
    @DisplayName("insider trades: name, role, direction, volume and the linked ISIN")
    void parsesInsiderDeals() {
        List<InsiderDeal> deals =
                BoerseDeMarketClient.parseInsiderDeals(Fixtures.load("insider-trades.html"));

        assertEquals(5, deals.size());
        InsiderDeal first = deals.get(0);
        assertEquals(LocalDate.of(2026, 7, 31), first.date());
        assertEquals("Heidelberg Materials", first.company());
        assertEquals("DE0006047004", first.isin(), "the issuer link carries the ISIN");
        assertEquals("Aldach, René", first.person());
        assertEquals("Kauf", first.tradeType());
        assertEquals("Vorstand", first.role());
        assertEquals(39950.0, first.volume());
        assertEquals("EUR", first.currency());
        assertTrue(first.isBuy());
    }

    @Test
    @DisplayName("insider trades: German thousands separators are read, not truncated")
    void parsesGermanVolumes() {
        List<InsiderDeal> deals =
                BoerseDeMarketClient.parseInsiderDeals(Fixtures.load("insider-trades.html"));

        InsiderDeal adidas = deals.stream()
                .filter(d -> d.company().equals("Adidas")).findFirst().orElseThrow();
        assertEquals(508569.0, adidas.volume(), "508.569 EUR is half a million, not 508");
        assertEquals("Gulden, Bjørn", adidas.person());
        assertEquals("DE000A1EWWW0", adidas.isin());
    }

    // ------------------------------------------------------------ company calendar

    @Test
    @DisplayName("company dates: date, issuer with ISIN, and the German event label")
    void parsesCompanyDates() {
        List<CompanyDate> dates =
                BoerseDeMarketClient.parseCompanyDates(Fixtures.load("unternehmenstermine.html"));

        assertEquals(5, dates.size());
        assertEquals(LocalDate.of(2026, 8, 5), dates.get(0).date());
        assertEquals("ASML Holding", dates.get(0).company());
        assertEquals("NL0010273215", dates.get(0).isin());
        assertEquals("ASML Dividendenzahlung", dates.get(0).event());

        CompanyDate siemens = dates.get(1);
        assertEquals("Siemens Q3-Zahlen", siemens.event());
        assertEquals(LocalDate.of(2026, 8, 6), siemens.date());
    }

    // -------------------------------------------------------------- analyst calls

    @Test
    @DisplayName("analyst calls: house and rating are split off the dpa-AFX headline")
    void parsesAnalystCalls() {
        List<AnalystCall> calls =
                BoerseDeMarketClient.parseAnalystCalls(Fixtures.load("empfehlungen-page1.html"));

        AnalystCall jpm = calls.stream()
                .filter(c -> c.house().equals("JPMORGAN")).findFirst().orElseThrow();
        assertEquals("hold", jpm.rating());
        assertEquals(LocalDate.of(2026, 7, 27), jpm.date());
        assertTrue(jpm.link().endsWith("/38481547"));
        assertEquals("JPMORGAN: SAP \"hold\"", jpm.headline());

        assertTrue(calls.stream().anyMatch(c -> c.house().equals("GOLDMAN SACHS")
                && "buy".equals(c.rating())));
    }

    @Test
    @DisplayName("analyst calls: the lead teaser's date hides as a run-together yyyyMMdd")
    void readsLeadTeaserDate() {
        List<AnalystCall> calls =
                BoerseDeMarketClient.parseAnalystCalls(Fixtures.load("empfehlungen-page1.html"));

        AnalystCall lead = calls.get(0);
        assertEquals("JEFFERIES", lead.house());
        assertEquals("buy", lead.rating());
        assertEquals(LocalDate.of(2026, 7, 28), lead.date(),
                "the lead has no date cell - '20260728' at the head of the teaser is it");
    }

    @Test
    @DisplayName("analyst calls: walks rel=next and stops when the next page is gone")
    void analystCallsPaginate() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("/empfehlungen/x/DE0007164600", "empfehlungen-page1.html");
        BoerseDeMarketClient client = new BoerseDeMarketClient(net);

        List<AnalystCall> calls = client.analystCalls("de0007164600", 100);

        assertFalse(calls.isEmpty());
        assertEquals(List.of(
                        "https://www.boerse.de/empfehlungen/x/DE0007164600",
                        "https://www.boerse.de/empfehlungen/SAP-Aktie/DE0007164600_seite,2,anzahl,20"),
                net.requested,
                "page 1 via /x/<ISIN>, then the page's own rel=next");
        assertTrue(client.analystCalls(null, 10).isEmpty());
        assertTrue(client.analystCalls("DE0007164600", 0).isEmpty());
    }

    @Test
    @DisplayName("analyst calls: a headline that is not a recommendation is dropped")
    void dropsNonRecommendationHeadlines() {
        assertNull(BoerseDeMarketClient.call("BOTSI-Advisor Abstufung von Rang 245",
                "https://www.boerse.de/nachrichten/x/1", LocalDate.now()));
        assertNull(BoerseDeMarketClient.call("JPMORGAN: SAP \"hold\"", "  ", LocalDate.now()));
        assertNull(BoerseDeMarketClient.call(null, "https://x", LocalDate.now()));

        AnalystCall noQuotes = BoerseDeMarketClient.call("DZ BANK: SAP Kursziel gesenkt",
                "https://www.boerse.de/nachrichten/x/2", LocalDate.of(2026, 7, 1));
        assertNotNull(noQuotes, "a colon headline without quotes still names the house");
        assertEquals("DZ BANK", noQuotes.house());
        assertNull(noQuotes.rating());
    }

    // --------------------------------------------------------------- fundamentals

    @Test
    @DisplayName("fundamentals: every Geschäftsjahr table folds into one map per year")
    void parsesFundamentals() {
        List<FundamentalYear> years =
                BoerseDeMarketClient.parseFundamentals(Fixtures.load("fundamental-analyse.html"));

        assertEquals(8, years.size(), "eight fiscal-year columns, oldest first");
        assertEquals(2019, years.get(0).year());
        assertEquals(2026, years.get(7).year());

        FundamentalYear y2025 = years.get(6);
        assertEquals(36800.0, y2025.get("Umsatz"), "German thousands separator, in millions");
        assertEquals(7161.0, y2025.get("Jahresüberschuss"));
        assertEquals(50106.0, y2025.get("Anlagevermögen"),
                "the &shy; inside the label must not survive into the key");
        assertEquals(5.83, y2025.get("Gewinn je Aktie (unverwässert)"));
        assertNotNull(y2025.get("KGV (Kurs-Gewinn-Verhältnis)"),
                "all six tables fold into the same per-year map, ratios included");
        assertNotNull(y2025.get("Eigenkapitalrendite"));
        assertNotNull(y2025.get("Personal am Jahresende"));
    }

    @Test
    @DisplayName("fundamentals: an absent figure is absent, never a fabricated zero")
    void fundamentalsSkipMissingFigures() {
        List<FundamentalYear> years =
                BoerseDeMarketClient.parseFundamentals(Fixtures.load("fundamental-analyse.html"));

        FundamentalYear running = years.get(7);
        assertEquals(2026, running.year());
        assertNull(running.get("Umsatz"),
                "the running year reports '-' for every P&L line - no fabricated zero");
        assertNull(running.get("Eigenkapital"));
        assertNotNull(running.get("Anzahl der Aktien"),
                "share count and market cap are the only always-current rows");
        assertNull(years.get(0).get("Gibt Es Nicht"));
        assertTrue(BoerseDeMarketClient.parseFundamentals("<table><tr><td>x</td></tr></table>")
                .isEmpty(), "a table without a Geschäftsjahr header is not a fundamental table");
    }

    @Test
    @DisplayName("fundamentals: fetched by ISIN, blank input costs no request")
    void fundamentalsFetchByIsin() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("/fundamental-analyse/x/DE0007164600", "fundamental-analyse.html");
        BoerseDeMarketClient client = new BoerseDeMarketClient(net);

        assertEquals(8, client.fundamentals("DE0007164600").size());
        assertTrue(client.fundamentals("  ").isEmpty());
        assertTrue(client.fundamentals(null).isEmpty());
        assertEquals(1, net.requested.size());
        assertTrue(client.fundamentals("DE000UNKNOWN0").isEmpty(), "410 → empty, not an exception");
    }

    // --------------------------------------------------------------- IPOs, splits

    @Test
    @DisplayName("IPOs: listing date, issue price, current price and performance")
    void parsesIpos() {
        List<IpoEntry> ipos = BoerseDeMarketClient.parseIpos(Fixtures.load("ipos.html"));

        assertEquals(4, ipos.size());
        IpoEntry spacex = ipos.get(0);
        assertEquals("SpaceX", spacex.company());
        assertEquals("US84615Q1031", spacex.isin());
        assertEquals("A42D4F", spacex.wkn());
        assertEquals(LocalDate.of(2026, 6, 12), spacex.firstQuote());
        assertEquals(116.71, spacex.issuePrice());
        assertEquals(94.00, spacex.currentPrice());
        assertEquals(-19.46, spacex.performancePercent(), "a negative German percentage");
    }

    @Test
    @DisplayName("splits: ratio and effective date survive the unclosed <tr> placeholders")
    void parsesStockSplits() {
        List<StockSplit> splits =
                BoerseDeMarketClient.parseStockSplits(Fixtures.load("aktiensplits.html"));

        assertEquals(4, splits.size(), "the stray empty <tr> rows must not become entries");
        assertEquals("Agrob VZ", splits.get(0).company());
        assertEquals("DE0005019038", splits.get(0).isin());
        assertEquals("1-7", splits.get(0).ratio());
        assertEquals(LocalDate.of(2026, 7, 29), splits.get(0).date());
        assertTrue(splits.stream().anyMatch(s -> s.company().equals("StoneX Group")));
    }

    // ---------------------------------------------------------------- global legs

    @Test
    @DisplayName("global lists: each surface is one fetch and honours its limit")
    void globalListsFetchAndCut() {
        Fixtures.FakeFetcher net = new Fixtures.FakeFetcher()
                .on("/insider-trades/", "insider-trades.html")
                .on("/unternehmenstermine/", "unternehmenstermine.html")
                .on("/ipos/", "ipos.html")
                .on("/aktiensplits/", "aktiensplits.html");
        BoerseDeMarketClient client = new BoerseDeMarketClient(net);

        assertEquals(2, client.insiderDeals(2).size());
        assertEquals(5, client.companyDates(99).size());
        assertEquals(3, client.ipos(3).size());
        assertEquals(4, client.stockSplits(10).size());
        assertEquals(4, net.requested.size());

        assertTrue(client.insiderDeals(0).isEmpty());
        assertTrue(client.stockSplits(-1).isEmpty());
    }

    @Test
    @DisplayName("every parser answers an empty list on garbage instead of throwing")
    void allParsersTolerateGarbage() {
        String junk = "<html><body><table><tr><td>?</td></tr></table></body></html>";
        for (String input : new String[]{null, "", "not html at all", junk}) {
            assertTrue(BoerseDeMarketClient.parseInsiderDeals(input).isEmpty());
            assertTrue(BoerseDeMarketClient.parseCompanyDates(input).isEmpty());
            assertTrue(BoerseDeMarketClient.parseAnalystCalls(input).isEmpty());
            assertTrue(BoerseDeMarketClient.parseFundamentals(input).isEmpty());
            assertTrue(BoerseDeMarketClient.parseIpos(input).isEmpty());
            assertTrue(BoerseDeMarketClient.parseStockSplits(input).isEmpty());
        }
    }

    @Test
    @DisplayName("German number and date primitives read the site's own notation")
    void primitivesReadGermanNotation() {
        assertEquals(39950.0, BoerseDeHtml.parseGermanNumber("39.950 EUR"));
        assertEquals(2.70, BoerseDeHtml.parseGermanNumber("2,70"));
        assertEquals(-19.46, BoerseDeHtml.parseGermanNumber("-19,46%"));
        assertNull(BoerseDeHtml.parseGermanNumber("-"));
        assertNull(BoerseDeHtml.parseGermanNumber("n.v."));
        assertNull(BoerseDeHtml.parseGermanNumber(null));

        assertEquals(LocalDate.of(2026, 7, 31), BoerseDeHtml.parseGermanDate("31.07.26"));
        assertEquals(LocalDate.of(2026, 7, 31), BoerseDeHtml.parseGermanDate("31.07.2026"));
        assertNull(BoerseDeHtml.parseGermanDate("irgendwann"));

        assertEquals("Umlaufvermögen", BoerseDeHtml.text("<td>Umlauf&shy;verm&ouml;gen</td>"));
        assertEquals("DE0006047004", BoerseDeHtml.instrumentIsin(
                "<a href=\"https://www.boerse.de/aktien/Heidelberg-Aktie/DE0006047004\">x</a>"));
        assertNull(BoerseDeHtml.instrumentIsin("<a href=\"/nachrichten/x/1\">x</a>"));
    }
}
