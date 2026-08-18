package de.bsommerfeld.wsbg.terminal.web.impl.sources.yahooconversations;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Only the board-id extraction is unit-tested — its fixture is a REAL quote
 * page excerpt (live 2026-07-16, escaped-JSON bootstrap form). The
 * conversation leg deliberately ships WITHOUT shape tests (user GO
 * 2026-07-16): its JSON shape cannot be pinned from outside the widget
 * handshake, so it gets re-pinned against the first live answer instead of
 * asserted against a guessed fixture. What IS pinned here beyond the old
 * world: the browser-off behaviour of the dedicated OpenWeb fetcher
 * (status-0 answers → empty source, never a break). Ported from the module
 * world.
 */
class YahooConversationsSourceTest {

    private static String fixture() {
        try (InputStream in = YahooConversationsSourceTest.class
                .getResourceAsStream("/yahoo-quote-snippet.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("fixture missing", e);
        }
    }

    private static ResolvedInstrument sap() {
        return new ResolvedInstrument(Optional.empty(), Ticker.parse("SAP.DE"), "SAP SE");
    }

    @Test
    void boardIdExtractsFromTheEscapedBootstrapJson() {
        assertEquals("finmb_24937", YahooConversationsSource.extractBoardId(fixture()),
                "the live page carries the id as ESCAPED JSON in a script block");
        assertEquals("finmb_1", YahooConversationsSource.extractBoardId(
                "{\"messageBoardId\":\"finmb_1\"}"), "the plain form matches too");
        assertNull(YahooConversationsSource.extractBoardId("<html>no board here</html>"));
        assertNull(YahooConversationsSource.extractBoardId(null));
    }

    @Test
    void conversationParserToleratesGarbage() {
        assertNull(YahooConversationsSource.parse("<html>wall</html>", "AAPL"));
        assertNull(YahooConversationsSource.parse("{\"unrelated\":true}", "AAPL"));
        assertNull(YahooConversationsSource.parse("", "AAPL"));
        assertNull(YahooConversationsSource.parse(null, "AAPL"));
        assertEquals(0, YahooConversationsSource.parse(
                "{\"conversation\":{\"comments\":[],\"users\":{}}}", "AAPL").size(),
                "an empty comment list is a VALID empty, not a miss");
    }

    @Test
    void withoutABrowserRuntimeTheSourceStaysEmptyInsteadOfBreaking() {
        // The terminal's @Named("openweb") binding answers status-0 responses
        // while the browser is off — exactly what WebResponse.failure() is.
        AtomicInteger fetches = new AtomicInteger();
        YahooConversationsSource source = new YahooConversationsSource(
                fetcherAnswering(url -> {
                    fetches.incrementAndGet();
                    return WebResponse.failure();
                }));

        assertTrue(source.newsFor(sap(), 10).isEmpty(), "empty, never a break");
        assertEquals(1, fetches.get(), "the quote-page step already answers status 0 — stop there");

        source.newsFor(sap(), 10);
        assertEquals(2, fetches.get(),
                "a transport failure is never cached as a missing board — the next call retries");
    }

    @Test
    void tickerIsTheOnlyAddressAndTheBoardStepRidesTheQuotePage() {
        AtomicInteger fetches = new AtomicInteger();
        YahooConversationsSource source = new YahooConversationsSource(
                fetcherAnswering(url -> {
                    fetches.incrementAndGet();
                    if (url.startsWith(YahooConversationsSource.QUOTE_URL_PREFIX)) {
                        assertEquals(YahooConversationsSource.QUOTE_URL_PREFIX + "SAP.DE/", url,
                                "the FULL venue symbol is the board key — SAP.DE has its own board");
                        return WebResponse.text(200, fixture(), Map.of());
                    }
                    assertTrue(url.startsWith(YahooConversationsSource.READ_URL_PREFIX
                                    + YahooConversationsSource.SPOT_ID + "&postId=finmb_24937"),
                            "the conversation is addressed by the extracted board id");
                    return WebResponse.failure(); // handshake shape is pinned live, not here
                }));

        assertTrue(source.newsFor(sap(), 10).isEmpty());
        assertEquals(2, fetches.get(), "quote page + conversation handshake");

        assertTrue(source.newsFor(ResolvedInstrument.ofName("SAP SE"), 10).isEmpty(),
                "without a ticker the source is empty");
        assertEquals(2, fetches.get(), "…and no request is spent on it");

        source.newsFor(sap(), 10);
        assertEquals(3, fetches.get(),
                "the board id is cached long — only the conversation step is paid again");
    }

    private static WebFetcher fetcherAnswering(
            java.util.function.Function<String, WebResponse> answer) {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                assertEquals(FetchUtil.BROWSER, modes[0],
                        "every leg rides the OpenWeb fetcher in BROWSER mode");
                return answer.apply(url);
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
                return false;
            }
        };
    }
}
