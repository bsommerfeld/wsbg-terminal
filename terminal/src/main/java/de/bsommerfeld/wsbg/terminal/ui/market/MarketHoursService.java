package de.bsommerfeld.wsbg.terminal.ui.market;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes the next-N-days schedule of trading sessions for a region,
 * with weekends and holidays skipped. Time-zone arithmetic uses each
 * region's own {@link java.time.ZoneId} so DST transitions are handled
 * by the JDK rather than hard-coded offsets.
 *
 * <p>
 * The schedule is generated server-side and shipped to the page on
 * connect; the page computes the live state and countdown locally to
 * avoid per-second network traffic.
 */
@Singleton
public final class MarketHoursService {

    private static final int LOOKAHEAD_DAYS = 14;

    private final HolidayProvider holidays;

    @Inject
    public MarketHoursService(HolidayProvider holidays) {
        this.holidays = holidays;
    }

    /** Returns a chronologically sorted list of sessions for the region. */
    public List<MarketSession> upcomingSessions(MarketRegion region) {
        LocalDate today = LocalDate.now(region.zone());
        List<MarketSession> out = new ArrayList<>();
        for (int i = 0; i < LOOKAHEAD_DAYS; i++) {
            LocalDate day = today.plusDays(i);
            if (isClosed(region, day)) continue;
            if (region.hasPre()) out.add(toSession(region, day, MarketSession.STATE_PRE,
                    region.preStart(), region.preEnd()));
            if (region.hasBreak()) {
                // Split main into morning / afternoon segments around the
                // midday recess — the gap falls through to "closed" on the
                // client, with a countdown to the afternoon reopen.
                out.add(toSession(region, day, MarketSession.STATE_MAIN,
                        region.mainStart(), region.breakStart()));
                out.add(toSession(region, day, MarketSession.STATE_MAIN,
                        region.breakEnd(), region.mainEnd()));
            } else {
                out.add(toSession(region, day, MarketSession.STATE_MAIN,
                        region.mainStart(), region.mainEnd()));
            }
            if (region.hasPost()) out.add(toSession(region, day, MarketSession.STATE_POST,
                    region.postStart(), region.postEnd()));
        }
        return out;
    }

    private boolean isClosed(MarketRegion region, LocalDate day) {
        DayOfWeek dow = day.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return true;
        return holidays.holidays(region.countryCode(), day.getYear()).contains(day);
    }

    private static MarketSession toSession(MarketRegion region, LocalDate day,
                                           String state, LocalTime start, LocalTime end) {
        long startMs = LocalDateTime.of(day, start)
                .atZone(region.zone())
                .toInstant()
                .toEpochMilli();
        long endMs = LocalDateTime.of(day, end)
                .atZone(region.zone())
                .toInstant()
                .toEpochMilli();
        return new MarketSession(state, startMs, endMs);
    }
}
