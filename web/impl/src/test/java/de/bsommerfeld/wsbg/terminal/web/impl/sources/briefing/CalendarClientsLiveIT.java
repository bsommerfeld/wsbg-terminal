package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probes of the calendar sources — network, excluded from normal runs.
 * The TradingView and Wikipedia legs live with their L-Z port.
 */
@Tag("integration")
class CalendarClientsLiveIT {

    private static WebFetcher live() {
        return new HouseFetcher(Set.of(new DirectTransport()));
    }

    @Test
    void eqsAnswersIsinCarryingEvents() {
        List<EqsEventsClient.CorporateEvent> events = new EqsEventsClient(live()).upcoming();
        System.out.println("[EQS] events=" + events.size());
        assertFalse(events.isEmpty(), "EQS answered nothing");
        assertTrue(events.stream().anyMatch(e -> e.isin() != null), "no ISINs");
        events.stream().limit(3).forEach(e -> System.out.println("[EQS] " + e));
    }

    @Test
    void earningsWhispersAnswersNextTradingDays() {
        // Weekends carry no reports — probe the next 5 days until one answers.
        for (int i = 1; i <= 5; i++) {
            var estimates = new EarningsWhispersClient(live())
                    .estimatesOn(LocalDate.now().plusDays(i));
            if (!estimates.isEmpty()) {
                System.out.println("[EW] +" + i + "d estimates=" + estimates.size());
                estimates.stream().limit(3).forEach(e -> System.out.println("[EW] " + e));
                return;
            }
        }
        throw new AssertionError("EarningsWhispers answered nothing for 5 days ahead");
    }

    @Test
    void centralBanksAnswerBothLegs() {
        var meetings = new CentralBankCalendarClient(live())
                .upcomingDecisions(LocalDate.now(), 2);
        meetings.forEach(m -> System.out.println("[CB] " + m));
        assertTrue(meetings.stream().anyMatch(m -> "EZB".equals(m.bank())), "no ECB dates");
        assertTrue(meetings.stream().anyMatch(m -> "Fed".equals(m.bank())), "no Fed dates");
    }
}
