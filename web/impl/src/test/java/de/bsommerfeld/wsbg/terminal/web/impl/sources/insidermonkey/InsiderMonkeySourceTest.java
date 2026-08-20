package de.bsommerfeld.wsbg.terminal.web.impl.sources.insidermonkey;

import de.bsommerfeld.wsbg.terminal.web.facts.HedgeFundPopularity;
import de.bsommerfeld.wsbg.terminal.web.facts.HedgeFundPopularity.InsiderRow;
import de.bsommerfeld.wsbg.terminal.web.facts.HedgeFundPopularity.QuarterPoint;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.FakeFetchers;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the Insider Monkey page shape against a trimmed live fixture
 * (OTLK, cik 1649989, captured 2026-07-14): the inline period-keyed JSON
 * arrays, their period-JOIN (never an index zip), the insider tables under
 * their section headers, the US symbol gate and the SEC CIK-map parse.
 */
class InsiderMonkeySourceTest {

    private static final String SEC_JSON = """
            {"0":{"cik_str":1045810,"ticker":"NVDA","title":"NVIDIA CORP"},
             "1":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."},
             "2":{"cik_str":1649989,"ticker":"OTLK","title":"Outlook Therapeutics, Inc."},
             "3":{"cik_str":99999,"ticker":"AAPL","title":"Duplicate wins never"}}
            """;

    private static String fixture() {
        try (InputStream in = InsiderMonkeySourceTest.class
                .getResourceAsStream("/company-page-otlk.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- curve ----

    @Test
    void parsesQuarterCurveWithLabelPriceAndOngoingFlag() {
        List<QuarterPoint> q = InsiderMonkeySource.parseQuarters(fixture());
        assertEquals(6, q.size());

        QuarterPoint first = q.get(0);
        assertEquals("2018-12-31", first.filingPeriodIso());
        assertEquals("Q4 2018", first.quarterLabel());
        assertEquals(9, first.funds());
        assertEquals(30845, first.totalShares()); // 30845.14375 rounded
        assertEquals(9, first.newPositions());
        assertEquals(122.97, first.quarterEndPriceUsd(), 1e-9);
        assertFalse(first.ongoing());

        QuarterPoint last = q.get(5);
        assertEquals("2026-03-31", last.filingPeriodIso());
        assertEquals("Q1 2026", last.quarterLabel());
        assertEquals(5, last.funds());
        assertEquals(0.43, last.quarterEndPriceUsd(), 1e-9);
        assertTrue(last.ongoing());
    }

    @Test
    void joinsArraysByFilingPeriodNotByIndex() {
        List<QuarterPoint> q = InsiderMonkeySource.parseQuarters(fixture());
        // nClosedPositions starts a quarter LATE (2019-03-31) — an index zip
        // would book 2018 Q4's closed count as 4; the period join keeps it 0.
        assertEquals(0, q.get(0).closedPositions());
        assertEquals(4, q.get(1).closedPositions()); // 2019-03-31
        assertEquals(4, q.get(2).closedPositions()); // 2019-06-30
        // shares array covers only the first three periods.
        assertEquals(58847, q.get(1).totalShares()); // 58846.6 rounded
        assertEquals(49049, q.get(2).totalShares());
    }

    @Test
    void periodsBeyondTheShorterArraysDegradeToUnknownOrZero() {
        List<QuarterPoint> q = InsiderMonkeySource.parseQuarters(fixture());
        QuarterPoint uncovered = q.get(3); // 2019-09-30: only in nPositions
        assertEquals(5, uncovered.funds());
        assertEquals(-1, uncovered.totalShares());   // unknown
        assertEquals(0, uncovered.newPositions());   // omitted-zero convention
        assertEquals(0, uncovered.closedPositions());
    }

    @Test
    void cutJsonArrayBracketMatchesAndMissesCleanly() {
        String cut = InsiderMonkeySource.cutJsonArray(fixture(), "\"nPositions\":");
        assertTrue(cut.startsWith("[{"));
        assertTrue(cut.endsWith("}]"));
        assertNull(InsiderMonkeySource.cutJsonArray(fixture(), "\"noSuchKey\":"));
        assertNull(InsiderMonkeySource.cutJsonArray("\"broken\":[{\"a\":1}", "\"broken\":"));
    }

    // ---- insider rows ----

    @Test
    void parsesInsiderTablesUnderTheirSectionHeaders() {
        List<InsiderRow> rows = InsiderMonkeySource.parseInsiderRows(fixture());
        assertEquals(5, rows.size());

        InsiderRow newest = rows.get(0); // merged and sorted newest first
        assertEquals("2026-06-02", newest.dateIso());
        assertEquals("Haddadin", newest.insider());
        assertEquals("Purchases", newest.transaction());
        assertEquals(29_000, newest.shares());
        assertEquals(0.83, newest.priceUsd(), 1e-9);
        assertEquals(24_145.40, newest.totalValueUsd(), 1e-9);
        assertEquals(66_167, newest.remainingShares());

        InsiderRow big = rows.get(1);
        assertEquals("2026-05-28", big.dateIso());
        assertEquals(8_539_709, big.shares());
        assertEquals(4_999_999.62, big.totalValueUsd(), 1e-9);

        InsiderRow sale = rows.get(3); // sales table row
        assertEquals("2023-04-20", sale.dateIso());
        assertEquals("Dagnon", sale.insider());
        assertEquals("Sales", sale.transaction());
        assertEquals(520_000, sale.shares());
        assertEquals(653_058, sale.remainingShares());
    }

    @Test
    void capsInsiderRows() {
        StringBuilder b = new StringBuilder("<span class=\"left\">Insider Trading: Purchases</span><table>");
        for (int i = 1; i <= 14; i++) {
            b.append("<tr class=\" even \"><td><a title=\"Click to See Insider Details\">P").append(i)
                    .append("</a></td><td><span>$1.00</span></td><td><span>10</span></td>")
                    .append("<td><span>$10.00</span></td><td><span>100</span></td>")
                    .append("<td><span>2026-01-").append(String.format("%02d", i))
                    .append("</span></td></tr>");
        }
        b.append("</table>");
        Optional<HedgeFundPopularity> p = InsiderMonkeySource.parsePage("XX", 1, b.toString());
        assertEquals(InsiderMonkeySource.MAX_INSIDER_ROWS, p.orElseThrow().recentInsiderRows().size());
        assertEquals("2026-01-14", p.orElseThrow().recentInsiderRows().get(0).dateIso());
    }

    @Test
    void pageWithoutCurveOrRowsIsEmpty() {
        assertTrue(InsiderMonkeySource.parsePage("XX", 1, "<html><body>nothing</body></html>").isEmpty());
    }

    // ---- SEC CIK map ----

    @Test
    void parsesCikMapFirstEntryWins() throws Exception {
        Map<String, Long> map = InsiderMonkeySource.parseCikMap(SEC_JSON);
        assertEquals(3, map.size());
        assertEquals(320193L, map.get("AAPL")); // duplicate ticker: first wins
        assertEquals(1649989L, map.get("OTLK"));
        assertEquals(1045810L, map.get("NVDA"));
    }

    // ---- US gate + end-to-end against stubbed transport ----

    @Test
    void nonUsShapedSymbolsReturnEmptyWithZeroNetwork() {
        InsiderMonkeySource client = new InsiderMonkeySource(
                FakeFetchers.fetcher((url, headers, timeout) -> fail("network call for a gated symbol: " + url)));
        for (String s : List.of("RHM.DE", "005930.KS", "^GDAXI", "BTC-USD", "CC=F", "TOOLONG", "BRK.AB", "", "  ")) {
            assertTrue(client.popularityFor(s).isEmpty(), s);
        }
    }

    @Test
    void resolvesCikFetchesSlugIgnoredUrlAndCachesTheResult() {
        AtomicInteger secFetches = new AtomicInteger();
        AtomicInteger pageFetches = new AtomicInteger();
        WebFetcher stub = FakeFetchers.fetcher((url, headers, timeout) -> {
            if (url.equals(InsiderMonkeySource.SEC_TICKERS_URL)) {
                secFetches.incrementAndGet();
                assertTrue(headers.get("User-Agent").contains("contact:"), "SEC needs a contact UA");
                return WebResponse.text(200, SEC_JSON, Map.of());
            }
            pageFetches.incrementAndGet();
            assertEquals("https://www.insidermonkey.com/insider-trading/company/x/1649989/", url);
            return WebResponse.text(200, fixture(), Map.of());
        });
        InsiderMonkeySource client = new InsiderMonkeySource(stub);

        HedgeFundPopularity p = client.popularityFor("otlk").orElseThrow(); // case-insensitive
        assertEquals("OTLK", p.symbol());
        assertEquals(1649989L, p.cik());
        assertEquals(6, p.quarters().size());
        assertEquals(5, p.recentInsiderRows().size());

        client.popularityFor("OTLK"); // second call rides the 1 h cache
        assertEquals(1, secFetches.get());
        assertEquals(1, pageFetches.get());

        // Unknown-but-US-shaped ticker: structural miss, no page fetch.
        assertTrue(client.popularityFor("ZZZQ").isEmpty());
        assertEquals(1, pageFetches.get());
    }

    @Test
    void networkFailureIsNotCachedStructuralMissIs() {
        AtomicInteger pageFetches = new AtomicInteger();
        int[] pageStatus = {503};
        WebFetcher stub = FakeFetchers.fetcher((url, headers, timeout) -> {
            if (url.equals(InsiderMonkeySource.SEC_TICKERS_URL)) {
                return WebResponse.text(200, SEC_JSON, Map.of());
            }
            pageFetches.incrementAndGet();
            return WebResponse.text(pageStatus[0], pageStatus[0] == 200 ? fixture() : "", Map.of());
        });
        InsiderMonkeySource client = new InsiderMonkeySource(stub);

        assertTrue(client.popularityFor("OTLK").isEmpty()); // 503 — not cached
        pageStatus[0] = 200;
        assertTrue(client.popularityFor("OTLK").isPresent()); // retried, heals
        assertEquals(2, pageFetches.get());
    }

    @Test
    void secFetchFailureIsAMissAndUncached() {
        AtomicInteger secFetches = new AtomicInteger();
        boolean[] secUp = {false};
        WebFetcher stub = FakeFetchers.fetcher((url, headers, timeout) -> {
            if (url.equals(InsiderMonkeySource.SEC_TICKERS_URL)) {
                secFetches.incrementAndGet();
                return secUp[0] ? WebResponse.text(200, SEC_JSON, Map.of())
                        : WebResponse.failure();
            }
            return WebResponse.text(200, fixture(), Map.of());
        });
        InsiderMonkeySource client = new InsiderMonkeySource(stub);

        assertTrue(client.popularityFor("OTLK").isEmpty()); // map down → miss
        secUp[0] = true;
        assertTrue(client.popularityFor("OTLK").isPresent()); // retried lazily, heals
        assertEquals(2, secFetches.get());
    }
}
