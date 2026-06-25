package de.bsommerfeld.wsbg.terminal.langschwarz;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the L&S search + chart JSON into an instrument and an EUR snapshot. No network. */
class LangSchwarzClientTest {

    private final LangSchwarzClient client = new LangSchwarzClient();

    @Test
    void searchPicksTheEquityHitAndItsIsin() {
        String body = """
            [{"id":43763,"displayname":"NVIDIA CORP. DL-,001","isin":"US67066G1040","wkn":918422,
              "instrumentId":43763,"categorySymbol":"STK","categoryName":"Aktie"},
             {"id":4222135,"displayname":"NVIDIA CDR","isin":"CA67080A1093","instrumentId":4222135,
              "categorySymbol":"STK"}]
            """;
        Optional<LsInstrument> inst = client.parseSearch(body);
        assertTrue(inst.isPresent());
        assertEquals(43763L, inst.get().instrumentId());
        assertEquals("US67066G1040", inst.get().isin());
    }

    @Test
    void searchEmptyArrayYieldsNothing() {
        assertFalse(client.parseSearch("[]").isPresent());
        assertFalse(client.parseSearch("not json").isPresent());
    }

    @Test
    void chartBuildsEurSnapshotWithSparkAndDayMove() {
        String body = """
            {"series":{"intraday":{"name":"Wert","data":[
               [1782284580000,2.03],[1782300000000,2.05],[1782342000000,2.04]]}},
             "info":{"plotlines":[{"value":2.07,"label":"Vortag"}],"isin":"DE000A1J5RX9"}}
            """;
        Optional<MarketSnapshot> snap = client.parseChart(body, "DE000A1J5RX9");
        assertTrue(snap.isPresent());
        MarketSnapshot s = snap.get();
        assertEquals(2.04, s.price(), 1e-9, "last intraday price");
        assertEquals(2.07, s.previousClose(), 1e-9);
        assertEquals(-1.4493, s.dayChangePercent(), 1e-3, "(2.04-2.07)/2.07");
        assertEquals("EUR", s.currency());
        assertEquals("L&S", s.exchangeName());
        assertEquals(3, s.spark().size());
        assertEquals(2.05, s.dayHigh(), 1e-9);
        assertEquals(2.03, s.dayLow(), 1e-9);
    }

    @Test
    void chartToleratesAPlainNumberPlotline() {
        String body = """
            {"series":{"intraday":{"data":[[1,10.0],[2,11.0]]}},"info":{"plotlines":10.0}}
            """;
        Optional<MarketSnapshot> snap = client.parseChart(body, "X");
        assertTrue(snap.isPresent());
        assertEquals(10.0, snap.get().previousClose(), 1e-9);
        assertEquals(10.0, snap.get().dayChangePercent(), 1e-6, "(11-10)/10");
    }

    @Test
    void chartWithoutDataYieldsNothing() {
        assertFalse(client.parseChart("{\"series\":{\"intraday\":{\"data\":[]}}}", "X").isPresent());
    }

    @Test
    void cleanForSearchStripsLegalCruft() {
        assertEquals("Micron Technology", LangSchwarzClient.cleanForSearch("Micron Technology, Inc."));
        assertEquals("Wendy's", LangSchwarzClient.cleanForSearch("The Wendy's Company"));
        assertEquals("Novo Nordisk", LangSchwarzClient.cleanForSearch("Novo Nordisk A/S"));
        assertEquals("SK hynix", LangSchwarzClient.cleanForSearch("SK hynix Inc."));
    }

    @Test
    void queryCandidatesGoSimplestFirst() {
        // full Yahoo name fails on L&S, so we also try the first word ("Micron").
        assertEquals(List.of("Micron Technology", "Micron", "Micron Technology, Inc."),
                LangSchwarzClient.queryCandidates("Micron Technology, Inc."));
    }
}
