package de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The news leg of Yahoo's {@code v1/finance/search} reply, ported from the
 * module world's client test. The old client's own 429 circuit-breaker tests
 * fell away with the breaker — the house fetcher owns the cooldown now, and
 * the polite-skip path is covered here via {@code hostCoolingDown}.
 */
class YahooFinanceSourceTest {

    private static final String SEARCH_BODY = """
            {
              "quotes": [
                {
                  "exchange": "NMS",
                  "shortname": "NVIDIA Corporation",
                  "longname": "NVIDIA Corporation",
                  "quoteType": "EQUITY",
                  "symbol": "NVDA",
                  "exchDisp": "NASDAQ",
                  "regularMarketPrice": 145.30,
                  "regularMarketPercentChange": -1.23
                }
              ],
              "news": [
                {
                  "uuid": "abc-123",
                  "title": "NVDA Drops 10% on Huawei AI News",
                  "publisher": "Stocktwits",
                  "link": "https://finance.yahoo.com/m/abc-123/nvda.html",
                  "providerPublishTime": 1779941029,
                  "type": "STORY",
                  "relatedTickers": ["NVDA", "AMD"]
                }
              ]
            }
            """;

    @Test
    void parsesNewsFromSearchResponse() {
        List<Article> news = YahooFinanceSource.parseNews(SEARCH_BODY);
        assertEquals(1, news.size());
        Article n = news.get(0);
        assertEquals("abc-123", n.uuid());
        assertEquals("NVDA Drops 10% on Huawei AI News", n.title());
        assertEquals("Stocktwits", n.publisher());
        assertEquals(List.of("NVDA", "AMD"), n.relatedTickers());
        assertNotNull(n.publishedAt());
    }

    @Test
    void skipsNewsWithoutTitle() {
        String body = """
                {"quotes": [], "news": [{"uuid":"x","publisher":"y"}]}
                """;
        assertTrue(YahooFinanceSource.parseNews(body).isEmpty());
    }

    @Test
    void returnsEmptyResultOnMalformedJson() {
        assertTrue(YahooFinanceSource.parseNews("not json at all").isEmpty());
    }

    @Test
    void symbolFanRidesTheDeclaredModeAndCacheAnswersBursts() {
        AtomicInteger fetches = new AtomicInteger();
        YahooFinanceSource source = new YahooFinanceSource(fake(fetches, new AtomicBoolean(false)));
        ResolvedInstrument nvda = new ResolvedInstrument(
                Optional.empty(), Ticker.parse("NVDA"), "NVIDIA Corporation");

        List<Article> items = source.newsFor(nvda, 5);
        assertEquals(1, items.size());
        assertEquals(1, fetches.get());

        source.newsFor(nvda, 5);
        assertEquals(1, fetches.get(), "a burst on the same symbol is served from the cache");
    }

    @Test
    void coolingHostIsSkippedWithoutARequest() {
        AtomicInteger fetches = new AtomicInteger();
        YahooFinanceSource source = new YahooFinanceSource(fake(fetches, new AtomicBoolean(true)));
        assertTrue(source.search("Nvidia", 5).isEmpty(),
                "a resting Yahoo answers empty without a socket");
        assertEquals(0, fetches.get());
    }

    private static WebFetcher fake(AtomicInteger fetches, AtomicBoolean cooling) {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                fetches.incrementAndGet();
                assertEquals(FetchUtil.BROWSER, modes[0], "declared mode order rides the fetch");
                return WebResponse.text(200, SEARCH_BODY, Map.of());
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return cooling.get();
            }
        };
    }
}
