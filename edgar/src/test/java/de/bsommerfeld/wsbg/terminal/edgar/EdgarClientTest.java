package de.bsommerfeld.wsbg.terminal.edgar;

import de.bsommerfeld.wsbg.terminal.edgar.EdgarClient.EdgarEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EdgarClientTest {

    // ---- parseCikMap ----

    @Test
    void parseCikMapReadsEntriesAndKeepsFirstDuplicate() throws Exception {
        String body = """
                {"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."},
                 "1":{"cik_str":1649989,"ticker":"OTLK","title":"Outlook Therapeutics, Inc."},
                 "2":{"cik_str":999999,"ticker":"AAPL","title":"Duplicate wins never"},
                 "3":{"cik_str":-1,"ticker":"BAD","title":"invalid cik"},
                 "4":{"cik_str":123,"ticker":"","title":"empty ticker"}}
                """;
        Map<String, Long> map = EdgarClient.parseCikMap(body);
        assertEquals(2, map.size());
        assertEquals(320193L, map.get("AAPL"));
        assertEquals(1649989L, map.get("OTLK"));
    }

    // ---- parseEightK ----

    private static final String SUBMISSIONS_FIXTURE = """
            {"cik":320193,"name":"Test Corp","filings":{"recent":{
              "accessionNumber":["a-6","a-5","a-4","a-3","a-2","a-1"],
              "form":["8-K","8-K","8-K/A","10-Q","8-K","8-K"],
              "filingDate":["2026-07-01","2026-05-12","2026-05-01","2026-04-20","2004-01-05","2026-02-02"],
              "items":["2.06,9.01","1.01,5.02","2.06,9.01","","5","7.01,8.01"]
            }}}
            """;

    @Test
    void parseEightKMapsClassifiedItemsOnly() throws Exception {
        List<EdgarEvent> events = EdgarClient.parseEightK(SUBMISSIONS_FIXTURE, "AAPL");

        // 2.06,9.01 → exactly ONE event (9.01 deliberately unmapped);
        // 1.01,5.02 → TWO events; 8-K/A, 10-Q, legacy "5" and 7.01/8.01 skipped.
        assertEquals(3, events.size());

        // Oldest first.
        assertEquals(LocalDate.of(2026, 5, 12), events.get(0).date());
        assertEquals("VERTRAG", events.get(0).eventClass());
        assertEquals("1.01,5.02", events.get(0).items());
        assertEquals("AAPL", events.get(0).ticker());

        assertEquals(LocalDate.of(2026, 5, 12), events.get(1).date());
        assertEquals("FUEHRUNGSWECHSEL", events.get(1).eventClass());

        assertEquals(LocalDate.of(2026, 7, 1), events.get(2).date());
        assertEquals("IMPAIRMENT", events.get(2).eventClass());
        assertEquals("2.06,9.01", events.get(2).items());
    }

    @Test
    void parseEightKSkipsUnparseableDates() throws Exception {
        String body = """
                {"filings":{"recent":{
                  "form":["8-K"],
                  "filingDate":["not-a-date"],
                  "items":["2.06"]
                }}}
                """;
        assertTrue(EdgarClient.parseEightK(body, "X").isEmpty());
    }

    @Test
    void parseEightKHandlesMissingRecentBlock() throws Exception {
        assertTrue(EdgarClient.parseEightK("{}", "X").isEmpty());
    }

    // ---- US-shape gate (must return empty WITHOUT any network) ----

    @Test
    void nonUsShapesReturnEmptyWithoutNetwork() {
        EdgarClient client = new EdgarClient(new de.bsommerfeld.wsbg.terminal.source.net.WebFetcher() {
            @Override
            public String name() {
                return "assert-no-network";
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                    String url, Map<String, String> headers, java.time.Duration timeout) {
                throw new AssertionError("network must not be touched: " + url);
            }
        });
        assertTrue(client.eightKEvents("RHM.DE").isEmpty());
        assertTrue(client.eightKEvents("^GDAXI").isEmpty());
        assertTrue(client.eightKEvents("BTC-USD").isEmpty());
        assertTrue(client.eightKEvents(null).isEmpty());
        assertTrue(client.eightKEvents("  ").isEmpty());
    }
}
