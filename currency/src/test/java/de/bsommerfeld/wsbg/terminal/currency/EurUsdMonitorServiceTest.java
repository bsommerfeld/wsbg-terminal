package de.bsommerfeld.wsbg.terminal.currency;

import de.bsommerfeld.wsbg.terminal.core.config.CurrencyConfig;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EurUsdMonitorServiceTest {

    @Test
    void usesPrimaryWhenAvailable() {
        EurUsdClient client = Mockito.mock(EurUsdClient.class);
        Mockito.when(client.fetchYahoo()).thenReturn(Optional.of(1.0876));

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        service.tick();

        Optional<EurUsdQuote> current = service.getCurrent();
        assertTrue(current.isPresent());
        assertEquals(1.0876, current.get().rate(), 1e-9);
        assertEquals(EurUsdQuote.Source.YAHOO, current.get().source());
        Mockito.verify(client, Mockito.never()).fetchFrankfurter();
    }

    @Test
    void fallsBackToFrankfurterWhenPrimaryFails() {
        EurUsdClient client = Mockito.mock(EurUsdClient.class);
        Mockito.when(client.fetchYahoo()).thenReturn(Optional.empty());
        Mockito.when(client.fetchFrankfurter()).thenReturn(Optional.of(1.0832));

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        service.tick();

        Optional<EurUsdQuote> current = service.getCurrent();
        assertTrue(current.isPresent());
        assertEquals(1.0832, current.get().rate(), 1e-9);
        assertEquals(EurUsdQuote.Source.FRANKFURTER, current.get().source());
    }

    @Test
    void keepsLastQuoteWhenBothSourcesFail() {
        EurUsdClient client = Mockito.mock(EurUsdClient.class);
        Mockito.when(client.fetchYahoo())
                .thenReturn(Optional.of(1.0876), Optional.empty());
        Mockito.when(client.fetchFrankfurter()).thenReturn(Optional.empty());

        EurUsdMonitorService service = newServiceWithoutAutoStart(client);
        service.tick(); // primary works
        service.tick(); // both fail

        Optional<EurUsdQuote> current = service.getCurrent();
        assertTrue(current.isPresent());
        assertEquals(1.0876, current.get().rate(), 1e-9);
    }

    @Test
    void directionUpdatesAcrossTicks() {
        EurUsdClient client = Mockito.mock(EurUsdClient.class);
        Mockito.when(client.fetchYahoo())
                .thenReturn(Optional.of(1.0876),
                        Optional.of(1.0900),
                        Optional.of(1.0850),
                        Optional.of(1.0850));

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
        EurUsdClient client = Mockito.mock(EurUsdClient.class);
        Mockito.when(client.fetchYahoo())
                .thenReturn(Optional.of(1.0876),
                        Optional.empty(),
                        Optional.of(1.0900));
        Mockito.when(client.fetchFrankfurter()).thenReturn(Optional.empty());

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
        EurUsdClient client = Mockito.mock(EurUsdClient.class);
        Mockito.when(client.fetchYahoo()).thenReturn(Optional.of(1.0876));

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

        EurUsdClient client = Mockito.mock(EurUsdClient.class);
        Mockito.when(client.fetchYahoo()).thenReturn(Optional.empty());
        Mockito.when(client.fetchFrankfurter()).thenReturn(Optional.empty());

        EurUsdMonitorService service = new EurUsdMonitorService(client, cfg);
        try {
            assertTrue(service.pollIntervalSeconds() >= 30);
        } finally {
            service.shutdown();
        }
    }

    /**
     * Builds a service that has scheduled a poll, then shuts the scheduler
     * down immediately so the test owns invocation timing via {@link
     * EurUsdMonitorService#tick()}. Without this the scheduler thread races
     * the test and clobbers mock-call counts.
     */
    private EurUsdMonitorService newServiceWithoutAutoStart(EurUsdClient client) {
        // Pin to a long interval so the scheduled tick doesn't fire during
        // the test window. We immediately call shutdown() to stop the
        // scheduler thread, then drive ticks manually.
        CurrencyConfig cfg = new CurrencyConfig();
        cfg.setPollIntervalSeconds(3600);

        EurUsdMonitorService service = new EurUsdMonitorService(client, cfg);
        service.shutdown();
        // Reset mock so the initial scheduled call (if it got off the
        // ground before shutdown) doesn't pollute later verifications.
        Mockito.clearInvocations(client);
        return service;
    }
}
