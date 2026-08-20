package de.bsommerfeld.wsbg.terminal.web.impl.sources.handelsblatt;

import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport.FakeWebFetcher;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests against a live-captured market payload (probed 2026-08-02). */
class HandelsblattMarketClientTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = HandelsblattMarketClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesMasterDataSnapshotAndPerformanceLadder() throws Exception {
        Optional<HandelsblattInstrument> parsed =
                HandelsblattMarketClient.parse(fixture("hb-isin-siemens.json"));

        assertTrue(parsed.isPresent());
        HandelsblattInstrument i = parsed.get();
        assertEquals("DE0007236101", i.isin());
        assertEquals("723610", i.wkn());
        assertEquals("SIE", i.ticker());
        assertEquals("EUR", i.currency());
        assertEquals("Aktien", i.classification());
        assertEquals("Siemens AG", i.companyName());
        assertEquals("Industrial Machinery", i.sector());
        assertEquals("Deutschland", i.country());
        assertEquals(283.45, i.last());
        assertEquals(4.613397305775973, i.oneWeekPercent());
        assertEquals(110.0874592351023, i.fiveYearPercent());
        assertEquals(10.9002, i.eps());
        assertEquals(5.35, i.dividend());
        assertEquals(5.35, i.dividendYield());
    }

    @Test
    void repairsThePublishersDoubleEncodedText() throws Exception {
        HandelsblattInstrument i =
                HandelsblattMarketClient.parse(fixture("hb-isin-siemens.json")).orElseThrow();

        assertTrue(i.description().contains("ansässiges"),
                "the endpoint double-encodes UTF-8; the client repairs it");
        assertEquals("gegründet", HandelsblattMarketClient.repairEncoding("gegrÃ¼ndet"));
        assertEquals("clean text", HandelsblattMarketClient.repairEncoding("clean text"));
        assertNull(HandelsblattMarketClient.repairEncoding(null));
    }

    @Test
    void absentFieldsStayNullAndEmptyPayloadsYieldNothing() throws Exception {
        HandelsblattInstrument i =
                HandelsblattMarketClient.parse(fixture("hb-isin-siemens.json")).orElseThrow();

        assertNotNull(i.marketplace(), "the venue is present in this payload");

        HandelsblattInstrument bare = HandelsblattMarketClient.parse(
                "[{\"reference\":{\"instrument\":{\"isin\":\"DE0007236101\"}}}]").orElseThrow();
        assertEquals("DE0007236101", bare.isin());
        assertNull(bare.last(), "a missing snapshot stays null, it is not invented");
        assertNull(bare.oneYearPercent());
        assertNull(bare.sector());

        assertNull(HandelsblattMarketClient.parse("[]").orElse(null));
        assertNull(HandelsblattMarketClient.parse("[{\"reference\":{}}]").orElse(null),
                "no ISIN, no instrument");
    }

    @Test
    void garbagePayloadsYieldEmpty() {
        assertTrue(HandelsblattMarketClient.parse("<html>error</html>").isEmpty());
        assertTrue(HandelsblattMarketClient.parse("").isEmpty());
        assertTrue(HandelsblattMarketClient.parse(null).isEmpty());
    }

    @Test
    void malformedIsinsNeverReachTheNetwork() {
        FakeWebFetcher fetcher = new FakeWebFetcher();
        HandelsblattMarketClient client = new HandelsblattMarketClient(fetcher);

        assertTrue(client.byIsin(null).isEmpty());
        assertTrue(client.byIsin("SIE").isEmpty());
        assertTrue(client.byIsin("DE00072361").isEmpty());
        assertEquals(0, fetcher.total());
    }

    @Test
    void wellFormedIsinIsFetchedAndParsed() throws Exception {
        FakeWebFetcher fetcher = new FakeWebFetcher()
                .on("market.www.handelsblatt.com", fixture("hb-isin-siemens.json"));
        HandelsblattMarketClient client = new HandelsblattMarketClient(fetcher);

        Optional<HandelsblattInstrument> i = client.byIsin("de0007236101");

        assertTrue(i.isPresent());
        assertEquals(1, fetcher.total());
        assertTrue(fetcher.calls.get(0)
                .endsWith("market.www.handelsblatt.com/api/isin/DE0007236101"));
    }
}
