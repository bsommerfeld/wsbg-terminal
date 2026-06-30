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
    void parsePicksTheDominantlyPopularStockOverASameNamedTwin() {
        // SpaceX: the room writes the long name, but the real stock's official short name is
        // "SpaceX" (zero word-overlap → fails coverage), while an obscure Canadian twin matches
        // the words. Popularity (992371 vs 201) must override coverage and pick the real one.
        String body = """
            {"status":1,"result":[
              {"name":"SpaceX","isin":"US84615Q1031","wkn":"A42D4F","class":"stock","popularity":992371},
              {"name":"Space Exploration Technologies Corporation","isin":"CA8459291081","wkn":"A42DG1","class":"stock","popularity":201},
              {"name":"Space Exploration Technologies CEDEARS","isin":"AR0922353138","wkn":"X","class":"stock","popularity":6}]}
            """;
        var w = client.parse(body, "Space Exploration Technologies Corp.");
        assertTrue(w.isPresent());
        assertEquals("US84615Q1031", w.get().isin(), "the dominant 'SpaceX', not the CA twin");
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
