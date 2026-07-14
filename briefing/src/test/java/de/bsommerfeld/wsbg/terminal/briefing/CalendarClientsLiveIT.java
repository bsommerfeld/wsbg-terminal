package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Live probes of the four calendar sources — network, excluded from normal runs. */
@Tag("integration")
class CalendarClientsLiveIT {

    @Test
    void tradingViewAnswersEventsWithActuals() {
        List<TradingViewCalendarClient.TvEvent> events = new TradingViewCalendarClient()
                .events(Instant.now().minus(3, ChronoUnit.DAYS), Instant.now());
        System.out.println("[TV] events=" + events.size());
        assertFalse(events.isEmpty(), "TradingView answered nothing");
        assertTrue(events.stream().anyMatch(e -> e.actual() != null),
                "no actuals in a 3-day past window");
        events.stream().filter(e -> e.actual() != null).limit(3)
                .forEach(e -> System.out.println("[TV] " + e));
    }

    @Test
    void eqsAnswersIsinCarryingEvents() {
        List<EqsEventsClient.CorporateEvent> events = new EqsEventsClient().upcoming();
        System.out.println("[EQS] events=" + events.size());
        assertFalse(events.isEmpty(), "EQS answered nothing");
        assertTrue(events.stream().anyMatch(e -> e.isin() != null), "no ISINs");
        events.stream().limit(3).forEach(e -> System.out.println("[EQS] " + e));
    }

    @Test
    void earningsWhispersAnswersNextTradingDays() {
        // Weekends carry no reports — probe the next 5 days until one answers.
        for (int i = 1; i <= 5; i++) {
            var estimates = new EarningsWhispersClient().estimatesOn(LocalDate.now().plusDays(i));
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
        var meetings = new CentralBankCalendarClient().upcomingDecisions(LocalDate.now(), 2);
        meetings.forEach(m -> System.out.println("[CB] " + m));
        assertTrue(meetings.stream().anyMatch(m -> "EZB".equals(m.bank())), "no ECB dates");
        assertTrue(meetings.stream().anyMatch(m -> "Fed".equals(m.bank())), "no Fed dates");
    }

    @Test
    void wikipediaAnswersYesterdaysWorldLog() {
        var events = new WikipediaCurrentEventsClient().eventsOn(LocalDate.now().minusDays(1), 2);
        System.out.println("[WIKI] events=" + events.size());
        assertFalse(events.isEmpty(), "Wikipedia answered nothing for yesterday");
        events.forEach(e -> System.out.println("[WIKI] [" + e.category() + "] "
                + e.text() + " (" + e.source() + ")"));
    }
}
