package de.bsommerfeld.wsbg.terminal.web.impl.sources.finra;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walk back through the daily files, network-free. Since 2026-08-11 it
 * fetches a SHIFT of trading days side by side instead of one socket at a
 * time - the answer must be the one the day-by-day walk gave: the same rows,
 * oldest first, and the same place to stop.
 */
class FinraShortVolumeWalkTest {

    /** Serves a row for every trading day in {@code has}, a 404 otherwise. */
    private static WebFetcher serving(Set<LocalDate> has, Set<LocalDate> asked) {
        return new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                    FetchUtil... modes) {
                String day = url.substring(url.length() - 12, url.length() - 4);
                LocalDate date = LocalDate.of(Integer.parseInt(day.substring(0, 4)),
                        Integer.parseInt(day.substring(4, 6)),
                        Integer.parseInt(day.substring(6, 8)));
                asked.add(date);
                if (!has.contains(date)) return WebResponse.text(404, "", Map.of());
                return WebResponse.text(200,
                        "Date|Symbol|ShortVolume|ShortExemptVolume|TotalVolume|Market\n"
                                + day + "|GME|400|0|1000|Q\n", Map.of());
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

    private static Set<LocalDate> tradingDaysBack(LocalDate from, int count) {
        Set<LocalDate> days = ConcurrentHashMap.newKeySet();
        LocalDate d = from;
        while (days.size() < count) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.add(d);
            }
            d = d.minusDays(1);
        }
        return days;
    }

    @Test
    void theSessionsComeBackOldestFirstAndCapped() {
        LocalDate from = LocalDate.of(2026, 8, 10);
        Set<LocalDate> asked = ConcurrentHashMap.newKeySet();
        var client = new FinraShortVolumeClient(serving(tradingDaysBack(from, 60), asked));

        List<ShortVolumeDay> rows = client.recent("GME", from, 25);

        assertEquals(25, rows.size(), "exactly the asked-for session count");
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i).date().isAfter(rows.get(i - 1).date()), "oldest first");
        }
        assertEquals(from, rows.get(rows.size() - 1).date(), "the newest session is the start");
        assertEquals(0.4, rows.get(0).shortShare(), 1e-9);
    }

    @Test
    void weekendsCostNoRequest() {
        LocalDate from = LocalDate.of(2026, 8, 10); // a Monday
        Set<LocalDate> asked = ConcurrentHashMap.newKeySet();
        new FinraShortVolumeClient(serving(tradingDaysBack(from, 60), asked))
                .recent("GME", from, 10);

        for (LocalDate d : asked) {
            assertFalse(d.getDayOfWeek() == DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == DayOfWeek.SUNDAY, d + " was fetched");
        }
    }

    @Test
    void aSymbolThatIsNotOnTheFilesStopsAfterTheMissStreak() {
        // Ten straight trading days without a row is not a holiday streak. The
        // shift may overshoot - what must NOT happen is walking the archive's
        // full 400-day reach for a symbol that is simply not there.
        LocalDate from = LocalDate.of(2026, 8, 10);
        Set<LocalDate> asked = ConcurrentHashMap.newKeySet();
        var client = new FinraShortVolumeClient(serving(Set.of(), asked));

        assertTrue(client.recent("GME", from, 40).isEmpty());
        assertTrue(asked.size() <= 20,
                "stopped on the miss streak, not at the reach limit — asked " + asked.size());
    }

    @Test
    void aHolidayInTheMiddleDoesNotEndTheWalk() {
        LocalDate from = LocalDate.of(2026, 8, 10);
        Set<LocalDate> present = tradingDaysBack(from, 60);
        present.remove(LocalDate.of(2026, 8, 5)); // one closed session
        Set<LocalDate> asked = ConcurrentHashMap.newKeySet();

        List<ShortVolumeDay> rows =
                new FinraShortVolumeClient(serving(present, asked)).recent("GME", from, 30);

        assertEquals(30, rows.size(), "a single gap is skipped, the walk carries on");
        assertFalse(rows.stream().anyMatch(r -> r.date().equals(LocalDate.of(2026, 8, 5))));
    }
}
