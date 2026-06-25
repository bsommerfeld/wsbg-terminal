package de.bsommerfeld.wsbg.terminal.nasdaq;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the NASDAQ.com articlebysymbol news rows into RawNewsItems. No network. */
class NasdaqNewsClientTest {

    private final NasdaqNewsClient client = new NasdaqNewsClient();

    @Test
    void parsesRowsAndAbsolutisesRelativeUrls() {
        String body = """
            {"data":{"rows":[
               {"title":"NVIDIA hits new high","url":"/articles/nvidia-x","publisher":"Reuters","created":"Jun 24, 2026"},
               {"title":"Chip demand surges","url":"https://www.nasdaq.com/articles/chips","publisher":"Zacks","created":""}],
             "totalrecords":2}}
            """;
        List<RawNewsItem> news = client.parse(body, "NVDA");
        assertEquals(2, news.size());
        assertEquals("NVIDIA hits new high", news.get(0).title());
        assertEquals("https://www.nasdaq.com/articles/nvidia-x", news.get(0).link(), "relative url absolutised");
        assertEquals(List.of("NVDA"), news.get(0).relatedTickers());
        assertEquals("Reuters", news.get(0).publisher());
        assertTrue(news.get(0).publishedAt() != null, "created date parsed");
        assertTrue(news.get(1).publishedAt() == null, "blank created → null");
    }

    @Test
    void emptyRowsAndJunkYieldNothing() {
        assertTrue(client.parse("{\"data\":{\"rows\":[]}}", "NVDA").isEmpty());
        assertTrue(client.parse("not json", "NVDA").isEmpty());
    }

    @Test
    void skipsNonUsSymbols() {
        assertTrue(client.newsFor("RHM.DE", 5).isEmpty());
        assertTrue(client.newsFor("^IXIC", 5).isEmpty());
        assertTrue(client.newsFor("BTC-USD", 5).isEmpty());
    }
}
