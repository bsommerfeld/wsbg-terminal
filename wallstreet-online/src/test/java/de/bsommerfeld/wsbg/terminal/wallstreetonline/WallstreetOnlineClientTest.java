package de.bsommerfeld.wsbg.terminal.wallstreetonline;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the WSO search JSON into an instrument (ISIN/WKN). No network. */
class WallstreetOnlineClientTest {

    private final WallstreetOnlineClient client = new WallstreetOnlineClient();

    @Test
    void parsePicksTheMostTradedStockNotADerivative() {
        // Real shape: a Mini-Future/Knock-Out carries the company name too and must be
        // rejected (class != stock/etf/fund); the actual stock (highest popularity) wins.
        String body = """
            {"status":1,"result":[
              {"name":"Nvidia Corporation Mini Future Long (CITI)","isin":"DE000CT9VEN6","wkn":"CT9VEN","class":"derivative","popularity":500},
              {"name":"NVIDIA","isin":"US67066G1040","wkn":"918422","class":"stock","popularity":711361},
              {"name":"NVIDIA CDR","isin":"CA67080A1093","wkn":"A3DDVC","class":"stock","popularity":189}]}
            """;
        Optional<WsoInstrument> w = client.parse(body, "NVIDIA Corporation");
        assertTrue(w.isPresent());
        assertEquals("US67066G1040", w.get().isin(), "the stock, not the Mini Future");
        assertEquals("918422", w.get().wkn());
    }

    @Test
    void parseSkipsResultsWithoutAValidIsin() {
        String body = """
            {"status":1,"result":[
              {"name":"NVIDIA Optionsschein","isin":"","wkn":"X","class":"derivative","popularity":9},
              {"name":"NVIDIA","isin":"US67066G1040","wkn":"918422","class":"stock","popularity":700}]}
            """;
        assertEquals("US67066G1040", client.parse(body, "NVIDIA").get().isin());
    }

    @Test
    void parseRejectsATotalNameMismatch() {
        // A relevance hit whose name shares nothing with the query must not poison the anchor.
        String body = """
            {"status":1,"result":[{"name":"Allianz SE","isin":"DE0008404005","wkn":"840400","class":"stock","popularity":99}]}
            """;
        assertFalse(client.parse(body, "Rheinmetall").isPresent());
    }

    @Test
    void parseEmptyOrJunkYieldsNothing() {
        assertFalse(client.parse("{\"status\":1,\"result\":[]}", "X").isPresent());
        assertFalse(client.parse("not json", "X").isPresent());
    }

    @Test
    void persistedIsinIsServedWithoutTouchingTheNetwork(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("wso-isin.jsonl");
        Files.writeString(f, "{\"q\":\"microsoft\",\"isin\":\"US5949181045\",\"wkn\":\"870747\",\"name\":\"Microsoft\"}\n",
                StandardCharsets.UTF_8);
        WebFetcher boom = new WebFetcher() {
            public WebResponse fetch(String url, Map<String, String> h, Duration t) {
                throw new AssertionError("network hit despite a persisted ISIN");
            }
            public String name() { return "boom"; }
        };
        WallstreetOnlineClient c = new WallstreetOnlineClient(boom, f);
        Optional<WsoInstrument> w = c.resolve("Microsoft");
        assertTrue(w.isPresent());
        assertEquals("US5949181045", w.get().isin(), "served from the on-disk ISIN memory");
    }
}
