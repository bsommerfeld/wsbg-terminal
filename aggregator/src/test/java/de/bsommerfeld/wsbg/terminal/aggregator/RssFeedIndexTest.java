package de.bsommerfeld.wsbg.terminal.aggregator;

import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssFeedIndexTest {

    private static final Instant T0 = Instant.parse("2026-06-08T10:00:00Z");

    private static RawNewsItem withTickers(String uuid, Instant at, String... tickers) {
        return new RawNewsItem(uuid, "title " + uuid, "pub", "https://x/" + uuid, at, List.of(tickers));
    }

    private static RawNewsItem withIsin(String uuid, Instant at, String isin) {
        return new RawNewsItem(uuid, "title " + uuid, "finanznachrichten", "https://x/" + uuid,
                at, List.of(), isin, "summary", false);
    }

    @Test
    void ingestThenQueryBySymbol() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(withTickers("1", T0, "NVDA"));
        idx.ingest(withTickers("2", T0, "AAPL"));

        List<RawNewsItem> out = idx.newsFor("NVDA", 10);
        assertEquals(1, out.size());
        assertEquals("1", out.getFirst().uuid());
    }

    @Test
    void symbolLookupIsCaseInsensitive() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(withTickers("1", T0, "nvda"));
        assertEquals(1, idx.newsFor("NVDA", 10).size());
        assertEquals(1, idx.newsFor("nvda", 10).size());
    }

    @Test
    void queryByIsin() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(withIsin("1", T0, "DE000ENER6Y0"));
        assertEquals(1, idx.newsFor("DE000ENER6Y0", 10).size());
        assertTrue(idx.newsFor("NVDA", 10).isEmpty());
    }

    @Test
    void dedupsByUuidAcrossReIngest() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(withTickers("1", T0, "NVDA"));
        idx.ingest(withTickers("1", T0, "NVDA")); // feed re-emits the same item
        assertEquals(1, idx.newsFor("NVDA", 10).size());
    }

    @Test
    void anItemTaggedWithBothSymbolAndIsinIsReturnedOnce() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(new RawNewsItem("1", "t", "pub", "https://x/1", T0,
                List.of("NVDA"), "DE000ENER6Y0", "s", false));
        // querying the symbol returns it; it's a single item, not duplicated
        assertEquals(1, idx.newsFor("NVDA", 10).size());
        assertEquals(1, idx.newsFor("DE000ENER6Y0", 10).size());
    }

    @Test
    void ordersNewestFirstAndRespectsLimit() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(withTickers("old", Instant.parse("2026-06-01T00:00:00Z"), "NVDA"));
        idx.ingest(withTickers("new", Instant.parse("2026-06-08T00:00:00Z"), "NVDA"));
        idx.ingest(withTickers("mid", Instant.parse("2026-06-05T00:00:00Z"), "NVDA"));

        assertEquals(List.of("new", "mid"),
                idx.newsFor("NVDA", 2).stream().map(RawNewsItem::uuid).toList());
    }

    @Test
    void itemWithNoInstrumentKeyIsNotAddressable() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(new RawNewsItem("1", "t", "pub", "https://x/1", T0, List.of()));
        assertTrue(idx.newsFor("NVDA", 10).isEmpty());
    }

    @Test
    void bucketIsBoundedDroppingOldest() {
        RssFeedIndex idx = new RssFeedIndex();
        // ingest 100 items for NVDA with increasing timestamps; cap is 64
        for (int i = 0; i < 100; i++) {
            idx.ingest(withTickers("id" + i, T0.plusSeconds(i), "NVDA"));
        }
        List<RawNewsItem> out = idx.newsFor("NVDA", 1000);
        assertEquals(64, out.size(), "bucket must be capped");
        // newest (id99) kept, oldest (id0) evicted
        assertEquals("id99", out.getFirst().uuid());
        assertTrue(out.stream().noneMatch(it -> it.uuid().equals("id0")));
    }

    @Test
    void emptyOnBlankOrUnknown() {
        RssFeedIndex idx = new RssFeedIndex();
        idx.ingest(withTickers("1", T0, "NVDA"));
        assertTrue(idx.newsFor("  ", 10).isEmpty());
        assertTrue(idx.newsFor("TSLA", 10).isEmpty());
        assertTrue(idx.newsFor("NVDA", 0).isEmpty());
    }
}
