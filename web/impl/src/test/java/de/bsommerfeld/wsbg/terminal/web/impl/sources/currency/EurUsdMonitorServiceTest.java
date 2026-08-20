package de.bsommerfeld.wsbg.terminal.web.impl.sources.currency;

import de.bsommerfeld.wsbg.terminal.core.config.CurrencyConfig;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EurUsdMonitorServiceTest {

    /** Minimal Yahoo picture: just the rate, no extras — what the old rate-only stub was. */
    private static EurUsdClient.YahooFx fx(double rate) {
        return new EurUsdClient.YahooFx(rate, null, null, null, null, null, List.of());
    }

    /** The fetcher behind the stub client — must never be reached (all fetches overridden). */
    private static WebFetcher noNet() {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                throw new UnsupportedOperationException("no network in monitor tests");
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

    /**
     * Hand-rolled stub (the module world used Mockito, which web/impl does not
     * carry): queued answers with the last one repeating, Mockito-style; the
     * lazy-cadence extras (ECB history, DXY, crosses) always answer empty.
     */
    private static final class StubClient extends EurUsdClient {
        private final ArrayDeque<Optional<YahooFx>> yahoo = new ArrayDeque<>();
        private final ArrayDeque<Optional<Double>> frankfurter = new ArrayDeque<>();
        int frankfurterCalls;

        StubClient() {
            super(noNet());
        }

        @SafeVarargs
        final StubClient yahooAnswers(Optional<YahooFx>... answers) {
            yahoo.addAll(List.of(answers));
            return this;
        }

        @SafeVarargs
        final StubClient frankfurterAnswers(Optional<Double>... answers) {
            frankfurter.addAll(List.of(answers));
            return this;
        }

        private static <T> Optional<T> next(ArrayDeque<Optional<T>> queue) {
            if (queue.isEmpty()) return Optional.empty();
            return queue.size() == 1 ? queue.peek() : queue.poll();
        }

        @Override
        public Optional<YahooFx> fetchYahooDetailed() {
            return next(yahoo);
        }

        @Override
        public Optional<Double> fetchFrankfurter() {
            frankfurterCalls++;
            return next(frankfurter);
        }

        @Override
        public Optional<EcbHistory> fetchEcbHistory() {
            return Optional.empty();
        }

        @Override
        public Optional<DxyQuote> fetchDxy() {
            return Optional.empty();
        }

        @Override
        public Optional<EcbCrosses> fetchEcbCrosses() {
            return Optional.empty();
        }
    }

    @Test
    void usesPrimaryWhenAvailable() {
        StubClient client = new StubClient().yahooAnswers(Optional.of(fx(1.0876)));

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        service.tick();

        Optional<EurUsdQuote> current = service.getCurrent();
        assertTrue(current.isPresent());
        assertEquals(1.0876, current.get().rate(), 1e-9);
        assertEquals(EurUsdQuote.Source.YAHOO, current.get().source());
        assertEquals(0, client.frankfurterCalls, "fallback never asked");
    }

    @Test
    void fallsBackToFrankfurterWhenPrimaryFails() {
        StubClient client = new StubClient()
                .yahooAnswers(Optional.empty())
                .frankfurterAnswers(Optional.of(1.0832));

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        service.tick();

        Optional<EurUsdQuote> current = service.getCurrent();
        assertTrue(current.isPresent());
        assertEquals(1.0832, current.get().rate(), 1e-9);
        assertEquals(EurUsdQuote.Source.FRANKFURTER, current.get().source());
    }

    @Test
    void keepsLastQuoteWhenBothSourcesFail() {
        StubClient client = new StubClient()
                .yahooAnswers(Optional.of(fx(1.0876)), Optional.empty())
                .frankfurterAnswers(Optional.empty());

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        service.tick(); // primary works
        service.tick(); // both fail

        Optional<EurUsdQuote> current = service.getCurrent();
        assertTrue(current.isPresent());
        assertEquals(1.0876, current.get().rate(), 1e-9);
    }

    @Test
    void directionUpdatesAcrossTicks() {
        StubClient client = new StubClient().yahooAnswers(
                Optional.of(fx(1.0876)),
                Optional.of(fx(1.0900)),
                Optional.of(fx(1.0850)),
                Optional.of(fx(1.0850)));

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);

        service.tick();
        assertEquals(EurUsdQuote.Direction.NEUTRAL, service.getCurrent().get().direction());

        service.tick();
        assertEquals(EurUsdQuote.Direction.UP, service.getCurrent().get().direction());

        service.tick();
        assertEquals(EurUsdQuote.Direction.DOWN, service.getCurrent().get().direction());

        service.tick();
        assertEquals(EurUsdQuote.Direction.NEUTRAL, service.getCurrent().get().direction());
    }

    @Test
    void notifiesListenersOnEverySuccessfulTick() {
        StubClient client = new StubClient()
                .yahooAnswers(Optional.of(fx(1.0876)),
                        Optional.empty(),
                        Optional.of(fx(1.0900)))
                .frankfurterAnswers(Optional.empty());

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        List<EurUsdQuote> received = new ArrayList<>();
        service.addListener(received::add);

        service.tick(); // primary succeeds
        service.tick(); // both fail — no notification
        service.tick(); // primary succeeds

        assertEquals(2, received.size());
        assertEquals(1.0876, received.get(0).rate(), 1e-9);
        assertEquals(1.0900, received.get(1).rate(), 1e-9);
        assertEquals(EurUsdQuote.Direction.UP, received.get(1).direction());
    }

    @Test
    void listenerExceptionDoesNotBreakOtherListeners() {
        StubClient client = new StubClient().yahooAnswers(Optional.of(fx(1.0876)));

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        List<EurUsdQuote> received = new ArrayList<>();
        service.addListener(q -> { throw new RuntimeException("boom"); });
        service.addListener(received::add);

        service.tick();

        assertEquals(1, received.size());
    }

    @Test
    void enforcesMinimumPollIntervalOfThirtySeconds() {
        CurrencyConfig cfg = new CurrencyConfig();
        cfg.setPollIntervalSeconds(0);

        StubClient client = new StubClient();

        EurUsdMonitorService service = new EurUsdMonitorService(client, cfg);
        try {
            assertTrue(service.pollIntervalSeconds() >= 30);
        } finally {
            service.shutdown();
        }
    }

    /**
     * Builds a service with the poll loop NOT started, so the test owns
     * invocation timing via {@link EurUsdMonitorService#tick()}. The production
     * service polls with a zero initial delay; a started scheduler would race
     * the test and consume its stubbed return values.
     */
    private EurUsdMonitorService newServiceWithoutAutoStart(EurUsdClient client) {
        return new EurUsdMonitorService(client, new CurrencyConfig(), false);
    }
}
