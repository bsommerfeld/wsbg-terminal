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
        Optional<LsInstrument> inst = client.parseSearch(body, "NVIDIA");
        assertTrue(inst.isPresent());
        assertEquals(43763L, inst.get().instrumentId());
        assertEquals("US67066G1040", inst.get().isin());
    }

    @Test
    void searchEmptyArrayYieldsNothing() {
        assertFalse(client.parseSearch("[]", "X").isPresent());
        assertFalse(client.parseSearch("not json", "X").isPresent());
    }

    @Test
    void searchRanksFullNameAndPicksThePlainTracker() {
        // Real-shaped "MSCI World" list: must reject MSCI Inc (the stock), MSCI USA
        // and the factor variant, and pick a plain World tracker.
        String body = """
            [{"instrumentId":1,"displayname":"MSCI INC. A DL-,01","isin":"US55354G1004","categorySymbol":"STK"},
             {"instrumentId":2,"displayname":"MSCI WORLD QUALITY FACTOR UCITS ETF","isin":"IE00BP3QZ601","categorySymbol":"ETF"},
             {"instrumentId":3,"displayname":"UBS MSCI WORLD","isin":"LU0340285161","categorySymbol":"ETF"},
             {"instrumentId":4,"displayname":"DK MSCI USA","isin":"DE000ETFL268","categorySymbol":"ETF"}]
            """;
        Optional<LsInstrument> inst = client.parseSearch(body, "MSCI World");
        assertTrue(inst.isPresent());
        assertEquals(3L, inst.get().instrumentId(), "plain UBS MSCI WORLD, not Inc/USA/factor");
        assertEquals("LU0340285161", inst.get().isin());
    }

    @Test
    void searchRejectsTheWrongSameNamedTwin() {
        // "Mullen Automotive" — L&S only carries the unrelated "Mullen Group" (Canada).
        // Coverage of {mullen, automotive} is 0.5 (only "mullen") → below the bar → empty,
        // so the chain falls through instead of pricing the wrong company.
        String body = """
            [{"instrumentId":741078,"displayname":"MULLEN GROUP LTD","isin":"CA6252841045","categorySymbol":"STK"}]
            """;
        assertFalse(client.parseSearch(body, "Mullen Automotive").isPresent());
    }

    @Test
    void parseByIsinPicksTheExactIsinMatch() {
        // L&S search by ISIN returns the one listing; pick by exact ISIN, not name coverage.
        String body = """
            [{"instrumentId":43763,"displayname":"NVIDIA CORP. DL-,001","isin":"US67066G1040","categorySymbol":"STK"}]
            """;
        var inst = client.parseByIsin(body, "US67066G1040");
        assertTrue(inst.isPresent());
        assertEquals(43763L, inst.get().instrumentId());
        assertFalse(client.parseByIsin(body, "DE0007164600").isPresent(), "no ISIN match → empty");
    }

    @Test
    void searchStripsLegalSuffixesSoMegacapsStillMatch() {
        // Yahoo hands "Microsoft Corporation" / "Eli Lilly and Company"; L&S abbreviates
        // ("MICROSOFT", "ELI LILLY"). The legal/connector words must be stripped from BOTH
        // sides, else coverage drops to 0.5 and the megacap is wrongly rejected (a real
        // regression caught in the live smoke test).
        String msft = """
            [{"instrumentId":1,"displayname":"MICROSOFT DL-,00000625","isin":"US5949181045","categorySymbol":"STK"},
             {"instrumentId":2,"displayname":"MICROSOFT CORP. CDR","isin":"CA59516M1041","categorySymbol":"STK"}]
            """;
        var m = client.parseSearch(msft, "Microsoft Corporation");
        assertTrue(m.isPresent());
        assertEquals("US5949181045", m.get().isin(), "home line, not the Canadian CDR");

        String lly = """
            [{"instrumentId":3,"displayname":"ELI LILLY","isin":"US5324571083","categorySymbol":"STK"}]
            """;
        var l = client.parseSearch(lly, "Eli Lilly and Company");
        assertTrue(l.isPresent());
        assertEquals("US5324571083", l.get().isin());
    }

    @Test
    void searchToleratesAbbreviatedNames() {
        // L&S abbreviates "Therapeutics" → "THERAP."; the prefix match must still cover it.
        String body = """
            [{"instrumentId":3169641,"displayname":"OUTLOOK THERAP. DL-,01","isin":"US69012T3059","categorySymbol":"STK"}]
            """;
        Optional<LsInstrument> inst = client.parseSearch(body, "Outlook Therapeutics");
        assertTrue(inst.isPresent());
        assertEquals("US69012T3059", inst.get().isin());
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
        assertEquals("Amazon", LangSchwarzClient.cleanForSearch("Amazon.com, Inc."), "drop the .com so L&S finds it");
    }

    @Test
    void searchResolvesDotcomNames() {
        // "Amazon.com, Inc." must reach L&S's "AMAZON.COM" listing.
        String body = """
            [{"instrumentId":42,"displayname":"AMAZON.COM INC.","isin":"US0231351067","categorySymbol":"STK"}]
            """;
        var inst = client.parseSearch(body, "Amazon.com, Inc.");
        assertTrue(inst.isPresent());
        assertEquals("US0231351067", inst.get().isin());
    }

    @Test
    void queryCandidatesGoSimplestFirst() {
        // full Yahoo name fails on L&S, so we also try the first word ("Micron").
        assertEquals(List.of("Micron Technology", "Micron", "Micron Technology, Inc."),
                LangSchwarzClient.queryCandidates("Micron Technology, Inc."));
    }
}
